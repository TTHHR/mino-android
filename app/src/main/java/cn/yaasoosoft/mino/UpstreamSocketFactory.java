package cn.yaasoosoft.mino;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocketFactory;

public final class UpstreamSocketFactory {
    private static final String TAG = "UpstreamSocketFactory";
    private static final byte MINO_VERSION = 0x02;

    private UpstreamSocketFactory() {
    }

    public static Socket connect(MinoConfig.UpstreamEndpoint endpoint,
                                 String targetHost,
                                 int targetPort,
                                 SocketProtector protector) throws IOException {
        switch (endpoint.scheme) {
            case "mino":
                return connectMino(endpoint, targetHost, targetPort, protector);
            case "socks5":
                return connectSocks5(endpoint, targetHost, targetPort, protector);
            case "http":
            case "https":
                return connectHttpProxy(endpoint, targetHost, targetPort, protector);
            default:
                throw new IOException("不支持的 upstream 协议: " + endpoint.scheme);
        }
    }

    private static Socket connectMino(MinoConfig.UpstreamEndpoint endpoint,
                                      String targetHost,
                                      int targetPort,
                                      SocketProtector protector) throws IOException {
        Socket socket = openSocket(endpoint, protector);
        writeMinoRequest(socket.getOutputStream(), endpoint, targetHost, targetPort);
        readMinoResponse(socket.getInputStream());
        return socket;
    }

    private static Socket connectSocks5(MinoConfig.UpstreamEndpoint endpoint,
                                        String targetHost,
                                        int targetPort,
                                        SocketProtector protector) throws IOException {
        Socket socket = openSocket(endpoint, protector);
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();

        boolean withAuth = !endpoint.username.isEmpty();
        output.write(new byte[]{0x05, 0x01, withAuth ? (byte) 0x02 : 0x00});
        output.flush();

        byte[] methodReply = readFully(input, 2);
        if (methodReply[0] != 0x05 || methodReply[1] == (byte) 0xFF) {
            throw new IOException("SOCKS5 上游握手失败");
        }

        if (methodReply[1] == 0x02) {
            byte[] user = endpoint.username.getBytes(StandardCharsets.UTF_8);
            byte[] pass = endpoint.password.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream auth = new ByteArrayOutputStream();
            auth.write(0x01);
            auth.write(user.length);
            auth.write(user);
            auth.write(pass.length);
            auth.write(pass);
            output.write(auth.toByteArray());
            output.flush();
            byte[] authReply = readFully(input, 2);
            if (authReply[1] != 0x00) {
                throw new IOException("SOCKS5 上游认证失败");
            }
        }

        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(new byte[]{0x05, 0x01, 0x00, 0x03});
        byte[] host = targetHost.getBytes(StandardCharsets.UTF_8);
        request.write(host.length);
        request.write(host);
        request.write((targetPort >> 8) & 0xFF);
        request.write(targetPort & 0xFF);
        output.write(request.toByteArray());
        output.flush();

        byte[] head = readFully(input, 4);
        if (head[1] != 0x00) {
            throw new IOException("SOCKS5 上游连接失败, code=" + (head[1] & 0xFF));
        }
        int atyp = head[3] & 0xFF;
        int skip;
        if (atyp == 0x01) {
            skip = 4;
        } else if (atyp == 0x04) {
            skip = 16;
        } else if (atyp == 0x03) {
            skip = readFully(input, 1)[0] & 0xFF;
        } else {
            throw new IOException("SOCKS5 上游返回了未知地址类型");
        }
        readFully(input, skip + 2);
        return socket;
    }

    private static Socket connectHttpProxy(MinoConfig.UpstreamEndpoint endpoint,
                                           String targetHost,
                                           int targetPort,
                                           SocketProtector protector) throws IOException {
        Socket socket = openSocket(endpoint, protector);
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();

        String target = targetHost + ":" + targetPort;
        StringBuilder request = new StringBuilder()
                .append("CONNECT ").append(target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(target).append("\r\n")
                .append("Proxy-Connection: Keep-Alive\r\n");
        if (!endpoint.username.isEmpty()) {
            String credentials = endpoint.username + ":" + endpoint.password;
            request.append("Proxy-Authorization: Basic ")
                    .append(Base64.encodeToString(credentials.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP))
                    .append("\r\n");
        }
        request.append("\r\n");
        output.write(request.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();

        String response = readHttpHeader(input);
        if (!response.startsWith("HTTP/1.1 200") && !response.startsWith("HTTP/1.0 200")) {
            throw new IOException("HTTP 上游连接失败: " + response.split("\r\n", 2)[0]);
        }
        return socket;
    }

    private static Socket openSocket(MinoConfig.UpstreamEndpoint endpoint,
                                     SocketProtector protector) throws IOException {
        Socket socket;
        if ("https".equals(endpoint.scheme)) {
            socket = SSLSocketFactory.getDefault().createSocket();
        } else {
            socket = new Socket();
        }
        boolean protectedOk = protector.protect(socket);
        Log.d(TAG, "protect " + endpoint.scheme + "://" + endpoint.host + ":" + endpoint.port + " => " + protectedOk);
        socket.connect(new InetSocketAddress(endpoint.host, endpoint.port), 10000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static void writeMinoRequest(OutputStream output,
                                         MinoConfig.UpstreamEndpoint endpoint,
                                         String targetHost,
                                         int targetPort) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int flags = 0;
        byte[] hostBytes = targetHost.getBytes(StandardCharsets.UTF_8);
        if (!endpoint.username.isEmpty()) {
            flags |= 1;
        }
        flags |= 1 << 6;

        buffer.write(MINO_VERSION);
        buffer.write(flags);
        buffer.write(hostBytes.length);
        buffer.write(hostBytes);
        buffer.write((targetPort >> 8) & 0xFF);
        buffer.write(targetPort & 0xFF);

        if (!endpoint.username.isEmpty()) {
            byte[] user = endpoint.username.getBytes(StandardCharsets.UTF_8);
            byte[] pass = endpoint.password.getBytes(StandardCharsets.UTF_8);
            buffer.write(user.length);
            buffer.write(pass.length);
            buffer.write(user);
            buffer.write(pass);
        }
        output.write(buffer.toByteArray());
        output.flush();
    }

    private static void readMinoResponse(InputStream input) throws IOException {
        int messageLength = input.read();
        if (messageLength < 0) {
            throw new EOFException("mino 上游未返回响应");
        }
        if (messageLength == 0) {
            return;
        }
        byte[] message = readFully(input, messageLength);
        throw new IOException("mino 上游连接失败: " + new String(message, StandardCharsets.UTF_8));
    }

    private static String readHttpHeader(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = new byte[]{'\r', '\n', '\r', '\n'};
        while (matched < end.length) {
            int next = input.read();
            if (next < 0) {
                throw new EOFException("HTTP 上游响应提前结束");
            }
            output.write(next);
            if (next == end[matched]) {
                matched++;
            } else {
                matched = next == end[0] ? 1 : 0;
            }
            if (output.size() > 8192) {
                throw new IOException("HTTP 上游响应头过大");
            }
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new EOFException("数据读取不完整");
            }
            offset += read;
        }
        return buffer;
    }
}
