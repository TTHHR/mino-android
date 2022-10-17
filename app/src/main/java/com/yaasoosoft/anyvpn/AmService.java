package com.yaasoosoft.anyvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;

import android.net.VpnService;
import android.os.Build;

import android.os.ParcelFileDescriptor;
import android.util.Log;


import com.yaasoosoft.anyvpn.presenter.Settings;
import com.yaasoosoft.anyvpn.presenter.VpnRunnable;
import com.yaasoosoft.anyvpn.service.LocalTcpServer;
import com.yaasoosoft.anyvpn.utils.Resource;

import java.nio.channels.Selector;

import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AmService extends VpnService {
    private static final String ACTION_DISCONNECT = "STOP";
    private boolean isRunning;
    private static final String VPN_ROUTE = "0.0.0.0"; // Intercept everything
    private ParcelFileDescriptor vpnInterface = null;
    private String TAG="AmVPN";

    //IO
    private Selector udpSelector;
    private Selector tcpSelector;

    //线程池
    private ExecutorService executorService;
    public static AmService Instance;
    public AmService() {
        Instance=this;
    }
    private void createNotificationChannel() {
        Notification.Builder builder = new Notification.Builder(this.getApplicationContext()); //获取一个Notification构造器
        Intent nfIntent = new Intent(this, MainActivity.class); //点击后跳转的界面，可以设置跳转数据

        builder.setContentIntent(PendingIntent.getActivity(this, 0, nfIntent, PendingIntent.FLAG_IMMUTABLE)) // 设置PendingIntent
                .setLargeIcon(BitmapFactory.decodeResource(this.getResources(), R.mipmap.ic_launcher)) // 设置下拉列表中的图标(大图标)
                .setContentTitle("VPN") // 设置下拉列表里的标题
                .setSmallIcon(R.mipmap.logo_sm) // 设置状态栏内的小图标
                .setContentText("正在使用VPN") // 设置上下文内容
                .setWhen(System.currentTimeMillis()); // 设置该通知发生的时间

        /*以下是对Android 8.0的适配*/
        //普通notification适配
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId("notification_id");
        }
        //前台服务notification适配
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel("notification_id", "notification_name", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }

        Notification notification = builder.build(); // 获取构建好的Notification
        notification.defaults = Notification.DEFAULT_SOUND; //设置为默认的声音
        startForeground(110, notification);

    }
    @Override
    public void onCreate() {
        super.onCreate();
        if(isRunning)
            return;
        isRunning = true;
        createNotificationChannel();
        setupVPN();
        if(vpnInterface!=null)
        try
        {
            udpSelector = Selector.open();
            tcpSelector = Selector.open();

            executorService = Executors.newFixedThreadPool(2);
            executorService.submit(new LocalTcpServer(Settings.getInstance().getLocalTcpServerPort()));
//            executorService.submit(new UDPInput(networkToDeviceQueue, udpSelector));
//            executorService.submit(new UDPOutput(deviceToNetworkUDPQueue, udpSelector, this));
//            executorService.submit(new TCPInput(networkToDeviceQueue, tcpSelector));
//            executorService.submit(new TCPOutput(deviceToNetworkTCPQueue, networkToDeviceQueue, tcpSelector, this));
            executorService.submit(new VpnRunnable(vpnInterface.getFileDescriptor()));
           // LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_VPN_STATE).putExtra("running", true));
            Log.i(TAG, "Started");
        }
        catch (Exception e)
        {
            // TODO: Here and elsewhere, we should explicitly notify the user of any errors
            // and suggest that they stop the service, since we can't do it ourselves
            Log.e(TAG, "Error starting service", e);
            release();
        }
    }
    @Override
    public void onDestroy() {
        isRunning = false;
        Log.e(TAG,"exit service");
        executorService.shutdownNow();
        stopForeground(true);
        release();
        super.onDestroy();
    }

    private void setupVPN() {
        if (vpnInterface == null)
        {
            Builder builder = new Builder();
            builder.addAddress(Settings.getInstance().getLocalIpAddress(), 32);
            builder.addRoute(VPN_ROUTE, 0);
            builder.setSession(getString(R.string.app_name));
            //if(Settings.getInstance().getUseDns())
            {
                Log.i("AmService","add dns"+Settings.getInstance().getDns());
                builder.addDnsServer(Settings.getInstance().getDns());
            }
            if(Settings.getInstance().getAppCheck())
            {
                HashSet<String> apps=Settings.getInstance().getApps();
                for (String s:apps
                     ) {
                    try {
                        builder.addAllowedApplication(s);
                    } catch (PackageManager.NameNotFoundException e) {
                        e.printStackTrace();
                    }
                }

            }
            //builder.setHttpProxy(ProxyInfo.buildDirectProxy("",1234));
            vpnInterface = builder.establish();
        }
    }
    private void release()
    {
        Resource.closeResources(udpSelector, tcpSelector, vpnInterface);
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null)
        Log.e(TAG,"rec cmd "+intent.getAction());
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            //disconnect();
            stopSelf();
            return START_NOT_STICKY;
        } else {
            //connect();
            return START_STICKY;
        }
    }

}