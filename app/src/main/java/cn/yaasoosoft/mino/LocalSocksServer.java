package cn.yaasoosoft.mino;

import android.util.Log;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class LocalSocksServer {
    private static final String TAG = "LocalSocksServer";

    private final MinoConfig.UpstreamEndpoint endpoint;
    private final SocketProtector protector;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;

    public LocalSocksServer(MinoConfig.UpstreamEndpoint endpoint, SocketProtector protector) {
        this.endpoint = endpoint;
        this.protector = protector;
    }

    public int start() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        running.set(true);
        executor.execute(this::acceptLoop);
        return serverSocket.getLocalPort();
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        executor.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                executor.execute(() -> handleClient(client));
            } catch (SocketException e) {
                if (running.get()) {
                    Log.e(TAG, "accept error", e);
                }
                return;
            } catch (IOException e) {
                Log.e(TAG, "accept error", e);
            }
        }
    }

    private void handleClient(Socket client) {
        Socket remote = null;
        try {
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            negotiate(input, output);
            TargetAddress target = readTarget(input, output);
            if (target == null) {
                return;
            }
            remote = UpstreamSocketFactory.connect(endpoint, target.host, target.port, protector);
            sendSuccess(output);
            Socket finalRemote = remote;
            executor.execute(() -> relay(client, finalRemote));
            relay(finalRemote, client);
        } catch (Exception e) {
            try {
                sendFailure(client, 0x01);
            } catch (IOException ignored) {
            }
            Log.e(TAG, "proxy error", e);
        } finally {
            closeQuietly(remote);
            closeQuietly(client);
        }
    }

    private void negotiate(InputStream input, OutputStream output) throws IOException {
        int version = input.read();
        int methodCount = input.read();
        if (version != 0x05 || methodCount < 0) {
            throw new EOFException("invalid socks negotiation");
        }
        byte[] methods = new byte[methodCount];
        readFully(input, methods);
        output.write(new byte[]{0x05, 0x00});
        output.flush();
    }

    private TargetAddress readTarget(InputStream input, OutputStream output) throws IOException {
        byte[] head = new byte[4];
        readFully(input, head);
        if (head[0] != 0x05) {
            throw new IOException("SOCKS 版本错误");
        }

        int cmd = head[1] & 0xFF;
        if (cmd == 0x03) {
            discardAddress(input, head[3] & 0xFF);
            output.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0});
            output.flush();
            return null;
        }
        if (cmd != 0x01) {
            sendCommandUnsupported(output);
            throw new IOException("不支持的 SOCKS CMD: " + cmd);
        }

        String host;
        int atyp = head[3] & 0xFF;
        if (atyp == 0x01) {
            byte[] addr = new byte[4];
            readFully(input, addr);
            host = (addr[0] & 0xFF) + "." + (addr[1] & 0xFF) + "." + (addr[2] & 0xFF) + "." + (addr[3] & 0xFF);
        } else if (atyp == 0x03) {
            int length = input.read();
            if (length < 0) {
                throw new EOFException("域名长度缺失");
            }
            byte[] addr = new byte[length];
            readFully(input, addr);
            host = new String(addr, StandardCharsets.UTF_8);
        } else if (atyp == 0x04) {
            byte[] addr = new byte[16];
            readFully(input, addr);
            host = InetAddress.getByAddress(addr).getHostAddress();
        } else {
            throw new IOException("地址类型不支持");
        }
        byte[] portBytes = new byte[2];
        readFully(input, portBytes);
        int port = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);
        return new TargetAddress(host, port);
    }

    private void discardAddress(InputStream input, int atyp) throws IOException {
        if (atyp == 0x01) {
            readFully(input, new byte[4]);
        } else if (atyp == 0x03) {
            int length = input.read();
            if (length < 0) {
                throw new EOFException("域名长度缺失");
            }
            readFully(input, new byte[length]);
        } else if (atyp == 0x04) {
            readFully(input, new byte[16]);
        } else {
            throw new IOException("地址类型不支持: " + atyp);
        }
        readFully(input, new byte[2]);
    }

    private void sendSuccess(OutputStream output) throws IOException {
        output.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        output.flush();
    }

    private void sendCommandUnsupported(OutputStream output) throws IOException {
        output.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        output.flush();
    }

    private void sendFailure(Socket client, int code) throws IOException {
        if (client == null || client.isClosed()) {
            return;
        }
        OutputStream output = client.getOutputStream();
        output.write(new byte[]{0x05, (byte) code, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        output.flush();
    }

    private void relay(Socket inSocket, Socket outSocket) {
        byte[] buffer = new byte[8192];
        try {
            InputStream input = inSocket.getInputStream();
            OutputStream output = outSocket.getOutputStream();
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(inSocket);
            closeQuietly(outSocket);
        }
    }

    private static void readFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                throw new EOFException("连接提前结束");
            }
            offset += read;
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static final class TargetAddress {
        final String host;
        final int port;

        TargetAddress(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
