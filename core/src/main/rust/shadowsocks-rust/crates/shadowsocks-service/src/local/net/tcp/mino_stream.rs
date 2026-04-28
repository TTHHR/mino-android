use std::{
    io::{self, IoSlice},
    pin::Pin,
    task::{self, Poll},
};

use rand::random;
use shadowsocks::{
    net::{ConnectOpts, TcpStream},
    relay::socks5::Address,
};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};

use crate::{
    config::{MinoEncoder, MinoOutboundConfig},
    local::context::ServiceContext,
    net::MonProxyStream,
};

const MINO_VERSION: u8 = 0x02;
const XOR_MAGIC: u8 = b'X';
const XOR_VERSION: u8 = 0x01;

pub struct MinoClientStream {
    stream: MonProxyStream<TcpStream>,
    xor_key: Option<Vec<u8>>,
    read_pos: usize,
    write_pos: usize,
}

impl MinoClientStream {
    pub async fn connect(
        context: &ServiceContext,
        server: &crate::local::loadbalancing::ServerIdent,
        addr: Address,
        config: &MinoOutboundConfig,
        connect_opts: &ConnectOpts,
    ) -> io::Result<MinoClientStream> {
        let stream = TcpStream::connect_server_with_opts(
            context.context_ref(),
            server.server_config().tcp_external_addr(),
            connect_opts,
        )
        .await?;
        let mut stream = MonProxyStream::from_stream(stream, context.flow_stat());
        let xor_key = match config.encoder {
            MinoEncoder::None => None,
            MinoEncoder::Xor => {
                let key = random::<[u8; 4]>();
                let len = config.xor_mod.min(key.len()).max(1);
                let key = key[..len].to_vec();
                let mut header = Vec::with_capacity(2 + key.len());
                header.push(XOR_MAGIC);
                header.push(XOR_VERSION);
                header.extend_from_slice(&key);
                stream.write_all(&header).await?;
                Some(key)
            }
        };

        let mut mino = MinoClientStream {
            stream,
            xor_key,
            read_pos: 0,
            write_pos: 0,
        };
        let request = mino_request(&addr, &config.username, &config.password)?;
        mino.write_all(&request).await?;
        mino.flush().await?;
        let mut length = [0u8; 1];
        mino.read_exact(&mut length).await?;
        if length[0] != 0 {
            let mut message = vec![0u8; length[0] as usize];
            mino.read_exact(&mut message).await?;
            return Err(io::Error::new(
                io::ErrorKind::Other,
                format!("mino upstream failed: {}", String::from_utf8_lossy(&message)),
            ));
        }
        Ok(mino)
    }

    pub fn local_addr(&self) -> io::Result<std::net::SocketAddr> {
        self.stream.get_ref().local_addr()
    }

    pub fn set_nodelay(&self, nodelay: bool) -> io::Result<()> {
        self.stream.get_ref().set_nodelay(nodelay)
    }
}

fn mino_request(addr: &Address, username: &str, password: &str) -> io::Result<Vec<u8>> {
    let mut flags = 0u8;
    if !username.is_empty() {
        flags |= 1;
    }
    let mut buf = Vec::with_capacity(Address::max_serialized_len() + username.len() + password.len() + 4);
    buf.push(MINO_VERSION);
    buf.push(flags);
    match addr {
        Address::SocketAddress(socket_addr) => {
            match socket_addr.ip() {
                std::net::IpAddr::V4(ip) => buf.extend_from_slice(&ip.octets()),
                std::net::IpAddr::V6(ip) => {
                    flags |= 1 << 5;
                    buf[1] = flags;
                    buf.extend_from_slice(&ip.octets());
                }
            }
            buf.extend_from_slice(&socket_addr.port().to_be_bytes());
        }
        Address::DomainNameAddress(host, port) => {
            let host = host.as_bytes();
            if host.len() > u8::MAX as usize {
                return Err(io::Error::new(io::ErrorKind::InvalidInput, "mino host is too long"));
            }
            flags |= 1 << 6;
            buf[1] = flags;
            buf.push(host.len() as u8);
            buf.extend_from_slice(host);
            buf.extend_from_slice(&port.to_be_bytes());
        }
    }
    if !username.is_empty() {
        if username.len() > u8::MAX as usize || password.len() > u8::MAX as usize {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "mino auth is too long"));
        }
        buf.push(username.len() as u8);
        buf.push(password.len() as u8);
        buf.extend_from_slice(username.as_bytes());
        buf.extend_from_slice(password.as_bytes());
    }
    Ok(buf)
}

impl AsyncRead for MinoClientStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut task::Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let before = buf.filled().len();
        let poll = Pin::new(&mut self.stream).poll_read(cx, buf);
        if let Poll::Ready(Ok(())) = poll {
            if let Some(key) = self.xor_key.clone() {
                let data = &mut buf.filled_mut()[before..];
                for byte in data {
                    *byte ^= key[self.read_pos % key.len()];
                    self.read_pos += 1;
                }
            }
        }
        poll
    }
}

impl AsyncWrite for MinoClientStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut task::Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        if let Some(key) = self.xor_key.clone() {
            let mut encrypted = Vec::with_capacity(buf.len());
            for (idx, byte) in buf.iter().enumerate() {
                encrypted.push(*byte ^ key[(self.write_pos + idx) % key.len()]);
            }
            match Pin::new(&mut self.stream).poll_write(cx, &encrypted) {
                Poll::Ready(Ok(n)) => {
                    self.write_pos += n;
                    Poll::Ready(Ok(n))
                }
                other => other,
            }
        } else {
            Pin::new(&mut self.stream).poll_write(cx, buf)
        }
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut task::Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.stream).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut task::Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.stream).poll_shutdown(cx)
    }

    fn poll_write_vectored(
        self: Pin<&mut Self>,
        cx: &mut task::Context<'_>,
        bufs: &[IoSlice<'_>],
    ) -> Poll<io::Result<usize>> {
        for buf in bufs {
            if !buf.is_empty() {
                return self.poll_write(cx, buf);
            }
        }
        self.poll_write(cx, &[])
    }
}
