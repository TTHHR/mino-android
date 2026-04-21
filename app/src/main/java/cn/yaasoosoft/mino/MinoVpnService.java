package cn.yaasoosoft.mino;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.net.Socket;

import engine.Engine;
import engine.Key;

public class MinoVpnService extends VpnService implements SocketProtector {
    public static final String ACTION_START = "cn.yaasoosoft.mino.action.START";
    public static final String ACTION_STOP = "cn.yaasoosoft.mino.action.STOP";
    public static final String ACTION_STATE_CHANGED = "cn.yaasoosoft.mino.action.STATE_CHANGED";
    public static final String EXTRA_CONFIG_PATH = "config_path";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_ACTIVE_CONFIG = "active_config";
    public static final String EXTRA_STATUS_TEXT = "status_text";

    private static final String TAG = "MinoVpnService";
    private static final String CHANNEL_ID = "mino_vpn";
    private static final int NOTIFICATION_ID = 1001;

    private static volatile boolean running;
    private static volatile String activeConfigName = "";

    private ParcelFileDescriptor vpnInterface;
    private LocalSocksServer localSocksServer;

    public static boolean isRunning() {
        return running;
    }

    public static String getActiveConfigName() {
        return activeConfigName;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            String configPath = intent.getStringExtra(EXTRA_CONFIG_PATH);
            if (configPath == null || configPath.isEmpty()) {
                publishState("未启动");
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                startForeground(NOTIFICATION_ID, buildNotification("VPN 已启动"));
                startVpn(configPath);
            } catch (Exception e) {
                Log.e(TAG, "start vpn failed", e);
                publishState("启动失败: " + e.getMessage());
                stopVpn();
                stopSelf();
            }
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void startVpn(String configPath) throws IOException {
        stopVpn();

        ConfigRepository repository = new ConfigRepository(this);
        File configFile = new File(configPath);
        MinoConfig config = repository.readConfig(configFile);
        MinoConfig.UpstreamEndpoint endpoint = config.parseEndpoint();

        localSocksServer = new LocalSocksServer(endpoint, this);
        int localPort = localSocksServer.start();

        Builder builder = new Builder()
                .setSession("Mino")
                .setMtu(1500)
                .addAddress("10.8.0.2", 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .setBlocking(false);
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "failed to exclude self from vpn", e);
        }

        vpnInterface = builder.establish();
        if (vpnInterface == null) {
            throw new IOException("VPN 接口创建失败");
        }

        Key key = new Key();
        key.setMark(0);
        key.setMTU(1500);
        key.setDevice("fd://" + vpnInterface.getFd());
        key.setInterface("");
        key.setLogLevel("error");
        key.setProxy("socks5://127.0.0.1:" + localPort);
        key.setRestAPI("");
        key.setTCPSendBufferSize("");
        key.setTCPReceiveBufferSize("");
        key.setTCPModerateReceiveBuffer(false);
        Engine.insert(key);
        Engine.start();

        running = true;
        activeConfigName = configFile.getName();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification("当前配置: " + activeConfigName));
        }
        publishState("运行中");
    }

    private void stopVpn() {
        running = false;
        activeConfigName = "";
        if (localSocksServer != null) {
            localSocksServer.stop();
            localSocksServer = null;
        }
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException ignored) {
            }
            vpnInterface = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        publishState("未启动");
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public boolean protect(Socket socket) {
        return super.protect(socket);
    }

    private Notification buildNotification(String text) {
        createNotificationChannel();
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent stopIntent = PendingIntent.getService(
                this,
                1,
                new Intent(this, MinoVpnService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Mino VPN")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .addAction(0, "停止", stopIntent)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Mino VPN",
                NotificationManager.IMPORTANCE_LOW
        );
        manager.createNotificationChannel(channel);
    }

    private void publishState(String statusText) {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_RUNNING, running);
        intent.putExtra(EXTRA_ACTIVE_CONFIG, activeConfigName);
        intent.putExtra(EXTRA_STATUS_TEXT, statusText);
        sendBroadcast(intent);
    }
}
