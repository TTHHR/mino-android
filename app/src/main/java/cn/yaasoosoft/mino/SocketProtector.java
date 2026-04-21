package cn.yaasoosoft.mino;

import java.net.Socket;

public interface SocketProtector {
    boolean protect(Socket socket);
}
