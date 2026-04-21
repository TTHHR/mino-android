package cn.yaasoosoft.mino;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;

public class MinoConfig {
    public String username = "";
    public String password = "";
    public String address = ":1080";
    public String upstream = "";

    public static MinoConfig load(InputStream inputStream) throws IOException {
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(inputStream);
        if (!(loaded instanceof Map)) {
            throw new IOException("YAML 格式无效");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) loaded;
        MinoConfig config = new MinoConfig();
        config.username = readString(data, "username");
        config.password = readString(data, "password");
        String address = readString(data, "address");
        if (!address.isEmpty()) {
            config.address = address;
        }
        config.upstream = readString(data, "upstream");
        if (config.upstream.isEmpty()) {
            throw new IOException("缺少 upstream 配置");
        }
        return config;
    }

    private static String readString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    public UpstreamEndpoint parseEndpoint() throws IOException {
        try {
            URI uri = new URI(upstream);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isEmpty()) {
                throw new IOException("upstream 缺少 host");
            }
            if (port <= 0) {
                port = defaultPort(scheme);
            }
            if (port <= 0) {
                throw new IOException("upstream 缺少端口");
            }

            String user = username;
            String pass = password;
            if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) {
                String[] pair = uri.getUserInfo().split(":", 2);
                user = pair[0];
                pass = pair.length > 1 ? pair[1] : "";
            }
            return new UpstreamEndpoint(scheme, host, port, user, pass);
        } catch (URISyntaxException e) {
            throw new IOException("upstream 格式错误: " + e.getMessage(), e);
        }
    }

    private int defaultPort(String scheme) {
        switch (scheme) {
            case "socks5":
                return 1080;
            case "http":
                return 80;
            case "https":
                return 443;
            default:
                return -1;
        }
    }

    public String summary(String fileName) {
        return "文件: " + fileName + "\n"
                + "上游: " + upstream + "\n"
                + "本地监听: " + address + "\n"
                + "用户名: " + (username.isEmpty() ? "(空)" : username);
    }

    public static final class UpstreamEndpoint {
        public final String scheme;
        public final String host;
        public final int port;
        public final String username;
        public final String password;

        public UpstreamEndpoint(String scheme, String host, int port, String username, String password) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
        }
    }
}
