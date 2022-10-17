package com.yaasoosoft.anyvpn.presenter.mino;

import android.util.Log;

import com.google.common.primitives.Longs;
import com.yaasoosoft.anyvpn.MainActivity;
import com.yaasoosoft.anyvpn.presenter.DnsProxy;
import com.yaasoosoft.anyvpn.presenter.Settings;
import com.yaasoosoft.anyvpn.presenter.Tunnel;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class MinoTunnel extends Tunnel {

    private boolean m_TunnelEstablished;
    private byte[] sessionKey=null;
    private int dataEncryptIndex=0;//当前加密字节数
    private int dataDecryptIndex=0;//当前加密字节数
    public MinoTunnel(InetSocketAddress serverAddress, Selector selector) throws Exception{

        super(serverAddress,selector);
    }

    public MinoTunnel(SocketChannel innerChannel, Selector selector) {
        super(innerChannel, selector);
        // TODO Auto-generated constructor stub
    }

    protected void packHead(ByteBuffer buffer)
    {

        buffer.put((byte) 0x02);//version
        if (Settings.getInstance().isUserAuth()) {
            buffer.put((byte) 0x41);//用户验证
        } else {
            buffer.put((byte) 0x40);//无验证
        }

        String doMain= DnsProxy.getHostByIp(m_DestAddress.getHostName());//根据虚假IP拿到host信息，交给服务器去请求
        if(doMain==null)
        {
            doMain=m_DestAddress.getHostName();
        }
        Log.i("Mino","domain "+doMain);
        byte[] domainBytes = doMain.getBytes();
        buffer.put((byte) domainBytes.length);//domain length;
        buffer.put(domainBytes);
        buffer.putShort((short) m_DestAddress.getPort());
        if (Settings.getInstance().isUserAuth()) {
            byte[] user = Settings.getInstance().getUsername().getBytes();
            buffer.put((byte) user.length);// length;
            byte[] pass = Settings.getInstance().getPassword().getBytes();
            buffer.put((byte) pass.length);// length;
            buffer.put(user);
            buffer.put(pass);
        }

    }

    @Override
    protected void onConnected(ByteBuffer buffer) throws Exception {

        buffer.clear();

        if(Settings.getInstance().getXxor())//需要加密，加密需要握手
        {
            woshou(buffer,Settings.getInstance().getXxorKey());
        }
        int start=buffer.position();
        packHead(buffer);

        if(Settings.getInstance().getXxor())
        {
            buffer.flip();
            encrypt(buffer,start);
        }
        buffer.flip();//切换读模式
        //Log.e("HEAD:",bytesToHexString(buffer.array(),buffer.limit()));
        //MainActivity.mainActivity.addLog("HEAD:"+bytesToHexString(buffer.array(),buffer.limit()));
        if(this.write(buffer,true)){//发送连接请求到代理服务器
            this.beginReceive();//开始接收代理服务器响应数据
        }
        else
        {
            Log.e("write","fail");
        }
    }

    @Override
    protected boolean isTunnelEstablished() {
        return m_TunnelEstablished;
    }

    @Override
    protected void beforeSend(ByteBuffer buffer) throws Exception {
        Log.i("Tunnel","send");
        if(Settings.getInstance().getXxor()) {
            encrypt(buffer);
        }
    }

    @Override
    protected void afterReceived(ByteBuffer buffer) throws Exception {
        Log.i("Tunnel","rec");
        if(Settings.getInstance().getXxor())
        {
            decrypt(buffer);
        }
        if(!m_TunnelEstablished){
            //收到代理服务器响应数据
            //分析响应并判断是否连接成功
            byte errorLength= buffer.get();
            if(errorLength==0)
            {
                Log.i("Tunnel","通过服务器校验");
                MainActivity.mainActivity.addLog("通过服务器校验");
                buffer.limit(buffer.position());
            }else {
                MainActivity.mainActivity.addLog("未通过服务器校验");
                Log.e("Tunnel","未通过服务器校验");
                throw new Exception(String.format("Proxy server responsed an error: %s",new String(buffer.array(),1,errorLength)));
            }

            m_TunnelEstablished=true;
            super.onTunnelEstablished();
        }
    }

    @Override
    protected void onDispose() {
        Log.e("Tunnel","关闭连接:"+m_DestAddress.getHostString());
        MainActivity.mainActivity.addLog("关闭连接:"+m_DestAddress.getHostName());
    }
    protected byte[] xor(byte[] buf,  byte[] key)  {
        int len=key.length;
        for (int i = 0; i < buf.length; i++) {
            buf[i] = (byte) (buf[i] ^ key[i%len]);
        }
        return buf;
    }
    protected void woshou(ByteBuffer data,String key)
    {
        data.clear();
        // 1. 获取 8 位的随机数据 rdm
        String rdm=RandomStringUtils.randomAlphanumeric(8);
        // 2. headerKey = key xor rdm
        byte[] headerKey = xor(key.getBytes(), rdm.getBytes());
        // 3. 随机生成 0~255 长度的 padding
        int paddingSize=RandomUtils.nextInt(0,255);
        byte[] padding=RandomStringUtils.randomAlphanumeric(paddingSize).getBytes();
        // 4. 获取当前时间戳（毫秒级别） int64 timestamp
        byte[] timeBuf=Longs.toByteArray(System.currentTimeMillis());
// 5. 生成 header =
//       "XXOR"
//      + byte(version=1)
//      + byte(encodingtype=1)
//      + byte(enable-mac=1)
        ByteBuffer headerBuffer=ByteBuffer.allocate(8);
        headerBuffer.put("XXOR".getBytes());
        headerBuffer.put((byte)1);//version
        headerBuffer.put((byte)paddingSize);
        headerBuffer.put((byte)1);//EncodeType
        headerBuffer.put((byte)1);//EnableMac

        // 6. encodingHeader = header xor headerKey
        byte[] heads=xor(headerBuffer.array(),headerKey);

        // 7. checkHeader = rdm + encodingHeader + padding + timestamp
        ByteBuffer checkHeader=ByteBuffer.allocate(rdm.length()+heads.length+paddingSize+timeBuf.length);
        checkHeader.put(rdm.getBytes());
        checkHeader.put(heads);
        checkHeader.put(padding);
        checkHeader.put(timeBuf);

        // 8. realHeader = checkHeader + sha1(checkHeader)
        byte [] sum=DigestUtils.sha1(checkHeader.array());
        data.put(checkHeader.array());
        data.put(sum);
        // 9. sessionKey = padding xor headerKey
        sessionKey = xor(padding, headerKey);
        // 后续数据使用 sessionKey xor

    }

    protected void encrypt(ByteBuffer data, int startIndex)
    {
        //需要ByteBuffer在读模式
        if(sessionKey==null)
            return;
        byte[] buffBytes=new byte[data.limit()];
        for (int i = 0; i < buffBytes.length; i++) {
            buffBytes[i]=data.get();
        }
        //写模式
        data.clear();
        for (int i = 0; i <buffBytes.length; i++) {
            if(i<startIndex)
                data.put(buffBytes[i]);
            else {
                data.put((byte) (buffBytes[i] ^ sessionKey[dataEncryptIndex % sessionKey.length]));
                dataEncryptIndex++;
            }
        }

    }
    protected void encrypt(ByteBuffer data)
    {
        //需要ByteBuffer在读模式
        if(sessionKey==null)
            return;
        byte[] buffBytes=data.array();
        int start=data.position();
        int end=data.limit();
        for (int i = start; i <end; i++) {
            buffBytes[start+i]= (byte) (buffBytes[start+i]^sessionKey[dataEncryptIndex%sessionKey.length]);
            dataEncryptIndex++;
        }

    }
    protected void decrypt(ByteBuffer data)
    {
        //需要ByteBuffer在读模式
        if(sessionKey==null)
            return;
        byte[] buffBytes=data.array();
        int start=data.position();
        int end=data.limit();
        for (int i = start; i <end; i++) {
            buffBytes[start+i]= (byte) (buffBytes[start+i]^sessionKey[dataDecryptIndex%sessionKey.length]);
            dataDecryptIndex++;
        }
    }
}
