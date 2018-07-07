package com.ibm.wala.cast.lsp.tomcat;

import javax.websocket.server.ServerEndpoint;

import com.ibm.wala.cast.lsp.WALAServer;
import com.ibm.wala.cast.python.ml.driver.PythonDriver;

@ServerEndpoint("/websocket")
public class WalaWebSocketServer extends LSPWebSocketServer<WALAServer> {

	public WalaWebSocketServer() {
		super(() -> { return new WALAServer(PythonDriver.python); }, WALAServer.class);
	}

}
