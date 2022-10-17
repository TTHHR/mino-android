package com.dxkite.mino.presenter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class Settings {
    private int localTcpServerPort =1080;
    private String localIpAddress="10.0.0.2";
    private int remoteProxyPort =28648;
    private String remoteProxyAddress="47.105.213.150";

    private String localNet="127.*;192.*;10.*;172.*";
    private String xxorKey="mino";

    private String username;
    private String password;

    private List<String>localNetList=null;
    private boolean skipLocalNet=false;
    private boolean userAuth=false;
    private boolean xxor;
    private String dns="180.76.76.76";
    private boolean useDns;
    private boolean appCheck;
    private HashSet<String> appPackages=new HashSet<>();
    private Settings(){};
    private static Settings settings=new Settings();
    public static Settings getInstance()
    {
        return settings;
    }
    public int getLocalTcpServerPort()
    {
        return localTcpServerPort;
    }

    public String getLocalIpAddress() {
        return localIpAddress;
    }

    public void setLocalTcpServerPort(int localTcpServerPort) {
        this.localTcpServerPort = localTcpServerPort;
    }

    public void setLocalIpAddress(String localIpAddress) {
        this.localIpAddress = localIpAddress;
    }

    public int getRemoteProxyPort() {
        return remoteProxyPort;
    }

    public void setRemoteProxyPort(int remoteProxyPort) {
        this.remoteProxyPort = remoteProxyPort;
    }

    public String getRemoteProxyAddress() {
        return remoteProxyAddress;
    }

    public void setRemoteProxyAddress(String remoteProxyAddress) {
        this.remoteProxyAddress = remoteProxyAddress;
    }

    public List<String> getLocalNetList() {
        if(localNetList==null)
        {
            localNetList=new ArrayList<>();
            String[] nets=localNet.replaceAll("\\*","").split(";");
            localNetList.addAll(Arrays.asList(nets));
        }
        return localNetList;
    }
    

    public static void setSettings(Settings settings) {
        Settings.settings = settings;
    }

    public boolean isSkipLocalNet() {
        return skipLocalNet;
    }

    public void setSkipLocalNet(boolean skipLocalNet) {
        this.skipLocalNet = skipLocalNet;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isUserAuth() {
        return userAuth;
    }

    public void setUserAuth(boolean userAuth) {
        this.userAuth = userAuth;
    }

    public void setXxor(boolean xxor) {
        this.xxor = xxor;
    }

    public boolean getXxor() {
        return xxor;
    }

    public String getXxorKey() {
        return xxorKey;
    }

    public void setXxorKey(String xork) {
        xxorKey=xork;
    }

    public void setDns(String dns) {
        this.dns=dns;
    }

    public String getDns() {
        return dns;
    }

    public boolean getUseDns() {
        return useDns;
    }

    public void setUseDns(boolean checked) {
        useDns=checked;
    }

    public void setAppCheck(boolean checked) {
        appCheck=checked;
    }

    public boolean getAppCheck() {
        return appCheck;
    }

    public void pushApps(String item) {
        if(item!=null)
        appPackages.add(item);
    }

    public HashSet<String> getApps() {
        return appPackages;
    }
}
