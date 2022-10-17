package com.yaasoosoft.anyvpn;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import com.yaasoosoft.anyvpn.presenter.Settings;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mainActivity;
    Button start;
    Button stop;
    CheckBox xxor,logCheck,dnsCheck,appCheck;
    EditText minoUrl,xxorText,logText,dnsText;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainActivity=this;
         start=findViewById(R.id.startbutton);
         stop=findViewById(R.id.stopbutton);
         minoUrl=findViewById(R.id.minoUrl);
         xxor=findViewById(R.id.xxorCheckBox);
         xxorText=findViewById(R.id.xxorText);
         logText=findViewById(R.id.logText);
         logCheck=findViewById(R.id.logCheckBox);
         dnsText=findViewById(R.id.dnsText);
         dnsCheck=findViewById(R.id.dnsCheckBox);
         appCheck=findViewById(R.id.appCheckBox);
        Intent service=new Intent(this,AmService.class);
        ActivityResultLauncher<Intent> requestActivity = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK) {
                    startService(service);
            }
        });

        start.setOnClickListener(v->{
            if(!checkStart())
            {
                return;
            }
            Intent i=VpnService.prepare(this);
            if(i!=null) {
                requestActivity.launch(i);
            }
            else
            {
                startService(service);
            }
        });
        stop.setOnClickListener(v->{
            stopService(service);
           // Toast.makeText(this,"请手动前往系统设置断开VPN",Toast.LENGTH_LONG).show();
            System.exit(0);
        });
        xxor.setOnClickListener(v->{
            Settings.getInstance().setXxor(xxor.isChecked());
            if(xxor.isChecked()) {
                xxorText.setVisibility(View.VISIBLE);
                String xork = xxorText.getText().toString().isEmpty() ? xxorText.getText().toString() : "mino";
                Settings.getInstance().setXxorKey(xork);
            }
            else
            {
                xxorText.setVisibility(View.GONE);
            }
        });
        logCheck.setOnClickListener(v->{
            if(logCheck.isChecked())
            {
                logText.setVisibility(View.VISIBLE);
            }
            else
            {
                logText.setVisibility(View.GONE);
            }
        });
        dnsCheck.setOnClickListener(v->{
            Settings.getInstance().setUseDns(dnsCheck.isChecked());
            if(dnsCheck.isChecked())
            {
                dnsText.setVisibility(View.VISIBLE);
            }
            else
            {
                dnsText.setVisibility(View.GONE);
            }
        });
        appCheck.setOnClickListener(v->{
            Settings.getInstance().setAppCheck(appCheck.isChecked());
            if(appCheck.isChecked())
            {
               showApps();
            }
            else
            {

            }
        });
        Settings.getInstance().pushApps(getPackageName());//自身需要代理
    }
    boolean checkStart()
    {
        String url=minoUrl.getText().toString();

        String urlStarts="mino://";
        try{
        if(!url.startsWith(urlStarts))
        {
            Toast.makeText(this,"请填写mino url",Toast.LENGTH_LONG).show();
            return false;
        }
        url=url.substring(urlStarts.length());
        String host;
        if(url.contains("@"))
        {
            String un=url.substring(0,url.indexOf(":"));//用户名
            url=url.substring(un.length());
            String ps=url.substring(1,url.indexOf("@"));//密码
            url=url.substring(ps.length());
            host = url.substring(2, url.lastIndexOf(":"));
            url=url.substring(url.lastIndexOf(":")+1);
            Settings.getInstance().setPassword(ps);
            Settings.getInstance().setUsername(un);
            Settings.getInstance().setUserAuth(true);
        }
        else {
             host = url.substring(0, url.lastIndexOf(":"));
             url=url.substring(url.lastIndexOf(":")+1);
            Settings.getInstance().setUserAuth(false);
        }

            String portStr=url;
            int port=Integer.parseInt(portStr);
            Settings.getInstance().setRemoteProxyAddress(host);
            Settings.getInstance().setRemoteProxyPort(port);
            String xork=xxorText.getText().toString().isEmpty()?xxorText.getText().toString():"mino";
            Settings.getInstance().setXxorKey(xork);
            String dns = dnsText.getText().toString().isEmpty() ? dnsText.getText().toString() : "180.76.76.76";
            Settings.getInstance().setDns(dns);
        }
        catch (Exception e)
        {
            Log.e("url",e.toString());
            Toast.makeText(this,"mino url不正确",Toast.LENGTH_LONG).show();
            return false;
        }

        return true;
    }
    public void addLog(String text)
    {
        if(logCheck.isChecked())
        runOnUiThread(()->{
            logText.append("\n");
            logText.append(text);
        });
    }
    private void showApps()
    {
        List<ApplicationInfo> allApps = getPackageManager().getInstalledApplications(0);
        int length=0;
        String[] packageNames=new String[allApps.size()];
        String[] appNames=new String[allApps.size()];
        boolean[] itemChoose=new boolean[packageNames.length];
        for (int i = 0; i < allApps.size(); i++) {
            if((allApps.get(i).flags&ApplicationInfo.FLAG_SYSTEM)!=0)//系统应用，跳过
            {
                continue;
            }
            String packageName=allApps.get(i).packageName;
            if(packageName.equals(getPackageName()))//自身APP，跳过
            {
                continue;
            }
            String appName=allApps.get(i).loadLabel(getPackageManager()).toString();
            packageNames[length]=packageName;
            appNames[length]=appName;
            length++;
        }



        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        dialog.setIcon(R.mipmap.ic_launcher);
        dialog.setTitle("请选择允许通过的APP");
        dialog.setMultiChoiceItems(Arrays.copyOf(appNames,length), Arrays.copyOf(itemChoose,length), (dialogInterface, i, b) -> {
            if(b)
            {
                Settings.getInstance().pushApps(packageNames[i]);
            }
        });
        dialog.show();
    }
}