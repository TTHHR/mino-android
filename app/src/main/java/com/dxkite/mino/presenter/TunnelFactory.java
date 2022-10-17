package com.dxkite.mino.presenter;

import android.util.Log;

import com.dxkite.mino.entity.RawTunnel;
import com.dxkite.mino.presenter.mino.MinoTunnel;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;


public class TunnelFactory {
	
	public static Tunnel wrap(SocketChannel channel,Selector selector){
		return new RawTunnel(channel, selector);
	}
 
	public static Tunnel createTunnelByConfig(InetSocketAddress destAddress,Selector selector) throws Exception {
		Log.e("Tunnel","addr "+destAddress.getHostString()+" "+destAddress.getHostName());
//		if(Settings.getInstance().isSkipLocalNet())
//		{
//			String host=destAddress.getHostName();
//			List<String>nets=Settings.getInstance().getLocalNetList();
//			for (String local:nets
//				 ) {
//				if(host.startsWith(local))
//				{
//					//返回无代理tunnel
//					return new RawTunnel(destAddress, selector);
//				}
//			}
//		}

			InetAddress address = InetAddress.getByName(Settings.getInstance().getRemoteProxyAddress());
			InetSocketAddress server = new InetSocketAddress(address, Settings.getInstance().getRemoteProxyPort());
			return new MinoTunnel(server, selector);
	}

}
