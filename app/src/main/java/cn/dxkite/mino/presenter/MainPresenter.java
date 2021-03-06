package cn.dxkite.mino.presenter;


import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.ProxyInfo;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import cn.dxkite.mino.entity.MinoConfig;
import cn.dxkite.mino.exception.MinoException;
import cn.dxkite.mino.service.MinoService;
import cn.dxkite.mino.view.inter.MainInterface;

public class MainPresenter {
    private String TAG=MainPresenter.class.getSimpleName();
    private  MainInterface mainInterface;
    private MinoConfig minoConfig=null;
    private String minoConfigFilePath="/data/data/cn.dxkite.mino/mino.yml";
    private String minoPath=null;
    private MainPresenter(){};

    public MainPresenter(MainInterface mainInterface)
    {
        this.mainInterface=mainInterface;
    }

    public void loadMinoConfig(Uri uri) throws MinoException
    {
        if(uri!=null)
        {
            try (InputStream in = ((Context)mainInterface).getContentResolver().openInputStream(uri);
                 BufferedReader r = new BufferedReader(new InputStreamReader(in));){
                StringBuilder total = new StringBuilder();
                for (String line; (line = r.readLine()) != null; ) {
                    total.append(line).append('\n');
                }

                String content = total.toString();
                Yaml yaml = new Yaml();

                minoConfig=yaml.loadAs(content,MinoConfig.class);
            }catch (Exception e) {
                Log.e(TAG,"read uri "+e);
                throw new MinoException("File type error");
            }
        }
        else
        {
            File file=new File(minoConfigFilePath);
            if(file.exists())
            {
                try (InputStream in = new FileInputStream(file);
                     BufferedReader r = new BufferedReader(new InputStreamReader(in))){
                    StringBuilder total = new StringBuilder();
                    for (String line; (line = r.readLine()) != null; ) {
                        total.append(line).append('\n');
                    }

                    String content = total.toString();
                    Yaml yaml = new Yaml();
                    minoConfig=yaml.load(content);
                }catch (Exception e) {
                    Log.e(TAG,"read default "+e);
                    file.delete();
                }
            }
            else
                minoConfig=new MinoConfig();
        }
        saveMinoConfig(minoConfigFilePath);
    }

    private void saveMinoConfig(String path)
    {
        if(minoConfig!=null)
        {
            File file=new File(path);
            if(!file.exists())
            {
                try{
                    file.createNewFile();
                }
                catch(Exception e)
                {
                    
                }
            }
            try(FileWriter fw=new FileWriter(path))
            {
                Yaml yaml = new Yaml();
                yaml.dump(minoConfig,fw);
            }catch (Exception e)
            {
                Log.e(TAG,"config file has error "+e);
            }
        }
    }

    private boolean checkMino() throws MinoException
    {
        minoPath = ((Context)mainInterface).getApplicationInfo().nativeLibraryDir+"/libmino.so";
        File minoFile=new File(minoPath);
        if(!minoFile.exists())
        {
            Log.e(TAG,"mino not exist");
            throw new MinoException("mino not exist");
        }
         if(minoConfig==null)
            loadMinoConfig(null);
        File minoConfigFile=new File(minoConfigFilePath);
        if(!minoConfigFile.exists())
            throw new MinoException("mino config file not exist");
        return true;
    }
    private Process runShell(List<String> command)
    {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        File file=new File(((Context)mainInterface).getFilesDir().getPath());
        if(!file.exists())
            file.mkdir();
        processBuilder.directory(file.getAbsoluteFile());

        processBuilder.environment().put("HOME", file.getAbsolutePath());
        file=new File(((Context)mainInterface).getCacheDir().getPath());
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
    public void stop()
    {
        ((Context)mainInterface).stopService(new Intent((Context) mainInterface, MinoService.class));
        saveMinoConfig(minoConfigFilePath);
    }

    public void start() {


        try{
            if(checkMino())
            {
                ((Context)mainInterface).startService(new Intent((Context) mainInterface, MinoService.class));
            }
        }catch (Exception e)
        {
            mainInterface.showError(e.getMessage());
        }

    }

    public MinoConfig getMinoConfig() {
        if(minoConfig==null)
            try {
                loadMinoConfig(null);
            }catch (Exception e)
            {
                mainInterface.showError(e.getMessage());
            }
        return minoConfig;
    }
}
