package com.yaasoosoft.anyvpn.presenter;

import static java.lang.Short.MAX_VALUE;

import android.util.Log;

import com.yaasoosoft.anyvpn.entity.*;
import com.yaasoosoft.anyvpn.utils.Resource;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

public  class VpnRunnable implements Runnable
    {
        private static final String TAG = VpnRunnable.class.getSimpleName();
        private static int LOCAL_IP;
        private static int DNS_IP;

        private FileDescriptor vpnFileDescriptor;
        private byte[] m_Packet;
        private IPHeader m_IPHeader;
        private TCPHeader m_TCPHeader;
        private UDPHeader m_UDPHeader;
        private ByteBuffer m_DNSBuffer;
        private DnsProxy m_DnsProxy;
        FileChannel vpnInput =null;
        FileChannel vpnOutput =null;

        public static VpnRunnable Instance;

        public VpnRunnable(FileDescriptor vpnFileDescriptor)
        {
            this.vpnFileDescriptor = vpnFileDescriptor;
            m_Packet = new byte[MAX_VALUE];
            m_IPHeader = new IPHeader(m_Packet, 0);
            m_TCPHeader=new TCPHeader(m_Packet, 20);
            m_UDPHeader=new UDPHeader(m_Packet, 20);
            m_DNSBuffer=((ByteBuffer)ByteBuffer.wrap(m_Packet).position(28)).slice();
            IPAddress ipAddress=new IPAddress(Settings.getInstance().getLocalIpAddress(),32);
            LOCAL_IP = CommonMethods.ipStringToInt(ipAddress.Address);
            IPAddress dipAddress=new IPAddress(Settings.getInstance().getDns(),32);
            DNS_IP = CommonMethods.ipStringToInt(dipAddress.Address);
            try {
                m_DnsProxy = new DnsProxy();
                m_DnsProxy.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
            Instance=this;
        }

        @Override
        public void run()
        {
            Log.i(TAG, "Started");

             vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();
             vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();
            try
            {
                ByteBuffer bufferToNetwork = ByteBuffer.wrap(m_Packet);
                boolean hasdata=false;
                while (!Thread.interrupted())
                {
                    int size=0;
                    while ((size = vpnInput.read(bufferToNetwork)) > 0 ) {
                        onIPPacketReceived(m_IPHeader, size);
                        bufferToNetwork.clear();
                        hasdata=true;
                    }
                    if(!hasdata) {
                        Thread.sleep(100);
                        hasdata=false;
                    }
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "Stopping");
            }
            finally
            {
                Resource.closeResources(vpnInput, vpnOutput);
            }
        }
        void onIPPacketReceived(IPHeader ipHeader, int size) throws IOException {
            byte proto=ipHeader.getProtocol();
            switch (proto) {
                case IPHeader.TCP:
                    TCPHeader tcpHeader =m_TCPHeader;
                    tcpHeader.m_Offset=ipHeader.getHeaderLength();
                    if (ipHeader.getSourceIP() == LOCAL_IP) {
                        if (tcpHeader.getSourcePort() == Settings.getInstance().getLocalTcpServerPort()) {// 收到本地TCP服务器数据
                            NatSession session =NatSessionManager.getSession(tcpHeader.getDestinationPort());
                            if (session != null) {
                                ipHeader.setSourceIP(ipHeader.getDestinationIP());
                                tcpHeader.setSourcePort(session.RemotePort);
                                ipHeader.setDestinationIP(LOCAL_IP);

                                CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                                vpnOutput.write(ByteBuffer.wrap(ipHeader.m_Data,ipHeader.m_Offset, size));
//                                m_ReceivedBytes+=size;
                            }else {
                                System.out.printf("NoSession: %s %s\n", ipHeader.toString(),tcpHeader.toString());
                            }
                        } else {

                            // 添加端口映射
                            int portKey=tcpHeader.getSourcePort();
                            NatSession session=NatSessionManager.getSession(portKey);
                            if(session==null||session.RemoteIP!=ipHeader.getDestinationIP()||session.RemotePort!=tcpHeader.getDestinationPort()){
                                session=NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), tcpHeader.getDestinationPort());
                            }

                            session.LastNanoTime=System.nanoTime();
                            session.PacketSent++;//注意顺序

                            int tcpDataSize=ipHeader.getDataLength()-tcpHeader.getHeaderLength();
                            if(session.PacketSent==2&&tcpDataSize==0){
                                return;//丢弃tcp握手的第二个ACK报文。因为客户端发数据的时候也会带上ACK，这样可以在服务器Accept之前分析出HOST信息。
                            }

                            //分析数据，找到host
//                            if(session.BytesSent==0&&tcpDataSize>10){
//                                int dataOffset=tcpHeader.m_Offset+tcpHeader.getHeaderLength();
//                                String host=HttpHostHeaderParser.parseHost(tcpHeader.m_Data, dataOffset, tcpDataSize);
//                                if(host!=null){
//                                    session.RemoteHost=host;
//                                }
//                            }

                            // 转发给本地TCP服务器
                            ipHeader.setSourceIP(ipHeader.getDestinationIP());
                            ipHeader.setDestinationIP(LOCAL_IP);
                            tcpHeader.setDestinationPort((short)Settings.getInstance().getLocalTcpServerPort());

                            CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                            vpnOutput.write(ByteBuffer.wrap(ipHeader.m_Data, ipHeader.m_Offset, size));
                            session.BytesSent+=tcpDataSize;//注意顺序
//                            m_SentBytes+=size;
                        }
                    }
                    break;
                case IPHeader.UDP:
                    // 转发DNS数据包：
                    UDPHeader udpHeader =m_UDPHeader;
                    udpHeader.m_Offset=ipHeader.getHeaderLength();
                    if (ipHeader.getSourceIP() == LOCAL_IP && udpHeader.getDestinationPort() == 53) {
                        Log.i(TAG,"get dns");
                        m_DNSBuffer.clear();
                        m_DNSBuffer.limit(ipHeader.getDataLength() - 8);
                        DnsPacket dnsPacket=DnsPacket.FromBytes(m_DNSBuffer);
                        if(dnsPacket!=null&&dnsPacket.Header.QuestionCount>0){
                            m_DnsProxy.onDnsRequestReceived(ipHeader, udpHeader, dnsPacket);
                        }
                    }
                    break;
            }
        }
        public void sendUDPPacket(IPHeader ipHeader, UDPHeader udpHeader) {
            try {
                CommonMethods.ComputeUDPChecksum(ipHeader, udpHeader);
                this.vpnOutput.write(ByteBuffer.wrap(ipHeader.m_Data, ipHeader.m_Offset, ipHeader.getTotalLength()));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
