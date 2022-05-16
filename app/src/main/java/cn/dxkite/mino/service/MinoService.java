package cn.dxkite.mino.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import cn.dxkite.mino.R;
import cn.dxkite.mino.view.MainActivity;

public class MinoService extends Service {
    private static final String TAG = MinoService.class.getSimpleName();
    private boolean running=false;
    private Thread minoThread=null;
    private String minoPath=null;
    private String minoConfigFilePath="/data/data/cn.dxkite.mino/mino.yml";
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    private void createNotificationChannel() {
        Notification.Builder builder = new Notification.Builder(this.getApplicationContext()); //获取一个Notification构造器
        Intent nfIntent = new Intent(this, MainActivity.class); //点击后跳转的界面，可以设置跳转数据

        builder.setContentIntent(PendingIntent.getActivity(this, 0, nfIntent, PendingIntent.FLAG_IMMUTABLE)) // 设置PendingIntent
                .setLargeIcon(BitmapFactory.decodeResource(this.getResources(), R.mipmap.ic_launcher)) // 设置下拉列表中的图标(大图标)
                .setContentTitle("mino") // 设置下拉列表里的标题
                .setSmallIcon(R.mipmap.ic_launcher) // 设置状态栏内的小图标
                .setContentText("notification for keep mino running") // 设置上下文内容
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
        notification.defaults = Notification.VISIBILITY_PUBLIC;
        startForeground(110, notification);

    }

    @Override
    public void onDestroy() {
        Log.e(TAG,"service exit");
        if(minoThread!=null)
            minoThread.interrupt();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        minoPath = getApplicationInfo().nativeLibraryDir+"/libmino.so";
        if(minoThread==null) {
            minoThread=new Thread(new minoRunn());
            createNotificationChannel();
        }
        running=hasRun();
        if(running)
        {
            Uri uri = Uri.parse("http://127.0.0.1:1080");

            Intent web = new Intent(Intent.ACTION_VIEW,uri);
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(web);
        }
        else
        {
            minoThread.start();
        }
        return super.onStartCommand(intent, flags, startId);
    }
    private class minoRunn implements Runnable{

        @Override
        public void run() {
            running=true;

            List<String> cmds=new ArrayList<>();
            cmds.clear();
            cmds.add(minoPath);
            cmds.add("-conf");
            cmds.add(minoConfigFilePath);
            Process minoProcess = runShell(cmds);
            while (running&&!Thread.interrupted())
            {
                    if (!hasRun()) {
                        minoProcess=runShell(cmds);
                    }
                try{
                    Thread.sleep(500);
                }catch (Exception e)
                {
                    running=false;
                }
            }
            minoProcess.destroy();
            running=false;

        }
    }
    private Process runShell(List<String> command)
    {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        File file=new File(getFilesDir().getPath());
        if(!file.exists())
            file.mkdir();
        processBuilder.directory(file.getAbsoluteFile());

        processBuilder.environment().put("HOME", file.getAbsolutePath());
        file=new File(getCacheDir().getPath());
        if(!file.exists())
            file.mkdir();
        processBuilder.environment().put("TMPDIR", file.getAbsolutePath());

        Process shellProcess=null;
        try {
            shellProcess=processBuilder.start();
        }catch (Exception e)
        {
            Log.e(TAG,e.toString());
        }
        return shellProcess;
    }
    private boolean hasRun()
    {
        List<String>cmds=new ArrayList<>();
        cmds.add("ps");
        cmds.add("-A");
        Process process =runShell(cmds);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));){
            String s;
            while ((s = in.readLine()) != null) {
                s = s.toLowerCase();
                if (s.contains("libmino.so")) {
                    return true;
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }
}
