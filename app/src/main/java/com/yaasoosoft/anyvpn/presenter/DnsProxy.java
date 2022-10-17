package com.yaasoosoft.anyvpn.presenter;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

import android.util.Log;
import android.util.SparseArray;

import com.yaasoosoft.anyvpn.AmService;
import com.yaasoosoft.anyvpn.entity.CommonMethods;
import com.yaasoosoft.anyvpn.entity.DnsPacket;
import com.yaasoosoft.anyvpn.entity.IPHeader;

import com.yaasoosoft.anyvpn.entity.Question;
import com.yaasoosoft.anyvpn.entity.ResourcePointer;
import com.yaasoosoft.anyvpn.entity.UDPHeader;


public class DnsProxy implements Runnable {
	public final static int FAKE_NETWORK_IP=CommonMethods.ipStringToInt("10.231.0.0");
	private class QueryState
	{
		public short ClientQueryID;
		public long QueryNanoTime;
		public int ClientIP;
		public short ClientPort;
		public int RemoteIP;
		public short RemotePort;
	}
	
	public boolean Stopped;
	private static final ConcurrentHashMap<Integer,String> IPDomainMaps= new ConcurrentHashMap<Integer,String>();
	private static final ConcurrentHashMap<String,Integer> DomainIPMaps= new ConcurrentHashMap<String,Integer>();
	private DatagramSocket m_Client;
	private Thread m_ReceivedThread;
	private short m_QueryID;
	private SparseArray<QueryState> m_QueryArray;

	public static String getHostByIp(String ip)
	{
		int ipAddr=CommonMethods.ipStringToInt(ip);

		return IPDomainMaps.get(ipAddr);
	}


	public DnsProxy() throws IOException {
		m_QueryArray = new SparseArray<>();
		m_Client = new DatagramSocket(0);
	}
	
	public void start(){
		m_ReceivedThread = new Thread(this);
		m_ReceivedThread.setName("DnsProxyThread");
		m_ReceivedThread.start();
	}
	
	public void stop(){
		Stopped=true;
		if(	m_Client!=null){
			m_Client.close();
			m_Client=null;
		}
	}

	@Override
	public void run() {
		try {
			byte[] RECEIVE_BUFFER = new byte[2000];
			IPHeader ipHeader=new IPHeader(RECEIVE_BUFFER, 0);
			ipHeader.Default();
			UDPHeader udpHeader=new UDPHeader(RECEIVE_BUFFER, 20);
			
			ByteBuffer dnsBuffer=ByteBuffer.wrap(RECEIVE_BUFFER);
			dnsBuffer.position(28);
			dnsBuffer=dnsBuffer.slice();

			DatagramPacket packet = new DatagramPacket(RECEIVE_BUFFER,28, RECEIVE_BUFFER.length-28);
	    
			while (m_Client!=null&&!m_Client.isClosed()){
				
				packet.setLength(RECEIVE_BUFFER.length-28);
				m_Client.receive(packet);
				
				dnsBuffer.clear();
				dnsBuffer.limit(packet.getLength());
				try {
					DnsPacket dnsPacket=DnsPacket.FromBytes(dnsBuffer);
					if(dnsPacket!=null){
						OnDnsResponseReceived(ipHeader,udpHeader,dnsPacket);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally{
			System.out.println("DnsResolver Thread Exited.");
			this.stop();
		}
	}

	private void tamperDnsResponse(byte[] rawPacket,DnsPacket dnsPacket,int newIP){
		Question question=dnsPacket.Questions[0];

		dnsPacket.Header.setResourceCount((short)1);
		dnsPacket.Header.setAResourceCount((short)0);
		dnsPacket.Header.setEResourceCount((short)0);

		ResourcePointer rPointer=new ResourcePointer(rawPacket, question.Offset()+question.Length());
		rPointer.setDomain((short)0xC00C);
		rPointer.setType(question.Type);
		rPointer.setClass(question.Class);
		rPointer.setTTL(32);
		rPointer.setDataLength((short)4);
		rPointer.setIP(newIP);

		dnsPacket.Size=12+question.Length()+16;
	}
	private void dnsPollution(byte[] rawPacket,DnsPacket dnsPacket){
		if(dnsPacket.Header.QuestionCount>0){
			Question question=dnsPacket.Questions[0];
			if(question.Type==1){
				int fakeIP=getOrCreateFakeIP(question.Domain);
					tamperDnsResponse(rawPacket,dnsPacket,fakeIP);
			}
		}
	}
	
	private void OnDnsResponseReceived(IPHeader ipHeader,UDPHeader udpHeader,DnsPacket dnsPacket) {
		QueryState state =null;
		synchronized (m_QueryArray) {
			state=m_QueryArray.get(dnsPacket.Header.ID);
			if(state!=null){
				m_QueryArray.remove(dnsPacket.Header.ID);
			}
		}
		
		if (state != null) {
			//DNS污染，将请求时的虚假IP发送给DNS
			dnsPollution(udpHeader.m_Data,dnsPacket);

			dnsPacket.Header.setID(state.ClientQueryID);
			ipHeader.setSourceIP(state.RemoteIP);
			ipHeader.setDestinationIP(state.ClientIP);
			ipHeader.setProtocol(IPHeader.UDP);
			ipHeader.setTotalLength(20+8+dnsPacket.Size);
			udpHeader.setSourcePort(state.RemotePort);
			udpHeader.setDestinationPort(state.ClientPort);
			udpHeader.setTotalLength(8+dnsPacket.Size);

			VpnRunnable.Instance.sendUDPPacket(ipHeader, udpHeader);
		}
	}

	private int getOrCreateFakeIP(String domainString){
		Integer fakeIP=DomainIPMaps.get(domainString);
		if(fakeIP==null){
			int hashIP=domainString.hashCode();
			do{
				fakeIP=FAKE_NETWORK_IP | (hashIP&0x0000FFFF);
				hashIP++;
			}while(IPDomainMaps.containsKey(fakeIP));

			DomainIPMaps.put(domainString,fakeIP);
			IPDomainMaps.put(fakeIP, domainString);
		}
		return fakeIP;
	}

	
	public void onDnsRequestReceived(IPHeader ipHeader,UDPHeader udpHeader,DnsPacket dnsPacket){
		if(true){
			Question question=dnsPacket.Questions[0];
			Integer fakeIP=DomainIPMaps.get(question.Domain);
			if(fakeIP==null)
			{
				getOrCreateFakeIP(question.Domain);//根据域名获取虚假IP
			}

		    //转发DNS
			QueryState state = new QueryState();
			state.ClientQueryID =dnsPacket.Header.ID;
			state.QueryNanoTime = System.nanoTime();
			state.ClientIP = ipHeader.getSourceIP();
			state.ClientPort = udpHeader.getSourcePort();
			state.RemoteIP = ipHeader.getDestinationIP();
			state.RemotePort = udpHeader.getDestinationPort();

			// 转换QueryID
			m_QueryID++;// 增加ID
			dnsPacket.Header.setID(m_QueryID);
			
			synchronized (m_QueryArray) {
				m_QueryArray.put(m_QueryID, state);// 关联数据
			}
			
			InetSocketAddress remoteAddress = new InetSocketAddress(CommonMethods.ipIntToInet4Address(state.RemoteIP ), state.RemotePort);
			DatagramPacket packet = new DatagramPacket(udpHeader.m_Data, udpHeader.m_Offset+8, dnsPacket.Size);
			packet.setSocketAddress(remoteAddress);

			try {
				if(AmService.Instance.protect(m_Client)){
					Log.i("DNS","send req");
					m_Client.send(packet);
				}else {
					System.err.println("VPN protect udp socket failed.");
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
}
