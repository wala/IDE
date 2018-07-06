package com.ibm.wala.cast.lsp.tomcat;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.jsonrpc.JsonRpcException;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.MessageIssueException;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.eclipse.lsp4j.jsonrpc.json.JsonRpcMethod;
import org.eclipse.lsp4j.jsonrpc.json.MessageConstants;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.eclipse.lsp4j.jsonrpc.services.GenericEndpoint;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;

import com.ibm.wala.cast.lsp.WALAServer;
import com.ibm.wala.cast.python.ml.driver.PythonDriver;
import com.ibm.wala.util.collections.HashMapFactory;

@ServerEndpoint("/websocket")
public class WalaWebSocketServer {
	private final Map<Session,Endpoint> endpoints = HashMapFactory.make();

	private static MessageJsonHandler jsonHandler;
	
	static {
		Map<String, JsonRpcMethod> supportedMethods = new LinkedHashMap<>();
		supportedMethods.putAll(ServiceEndpoints.getSupportedMethods(LanguageClient.class));
		supportedMethods.putAll(ServiceEndpoints.getSupportedMethods(WALAServer.class));
		
		jsonHandler = new MessageJsonHandler(supportedMethods);
	}
	
	@OnOpen
    public void onOpen(Session session) throws IOException{
        System.out.println("Open Connection " + session + " ...");

        LanguageServer server = new WALAServer(PythonDriver.python);
		Endpoint local = new GenericEndpoint(Collections.singleton(server));
		endpoints.put(session, local);

		MessageConsumer out = new MessageConsumer() {
			@Override
			public void consume(Message arg0) throws MessageIssueException, JsonRpcException {
				System.err.println("sending message: " + arg0);
				try {
					session.getBasicRemote().sendText(arg0.toString());
				} catch (IOException e) {
					throw new Error(e);
				}
			}
		};
		
		if (server instanceof LanguageClientAware) {
			RemoteEndpoint remote = new RemoteEndpoint(out, local);
			((LanguageClientAware)server).connect(ServiceEndpoints.toServiceObject(remote, LanguageClient.class));
		}
    }

    @OnClose
    public void onClose(Session session) throws IOException{
        System.out.println("Close Connection " + session + " ...");
        endpoints.remove(session);
        session.close();
    }

    @OnMessage
    public String onMessage(String message, Session session) throws InterruptedException, ExecutionException{
        Message m = jsonHandler.parseMessage(message);
        Endpoint localEndpoint = endpoints.get(session);
        
        if (m instanceof RequestMessage) {
            System.err.println("Request from the client: " + m);
        	RequestMessage requestMessage = (RequestMessage)m;
        	CompletableFuture<?> result = localEndpoint.request(requestMessage.getMethod(), requestMessage.getParams());
        	Object r = result.join();
        	ResponseMessage responseMessage = new ResponseMessage();
        	responseMessage.setRawId(requestMessage.getRawId());
        	responseMessage.setJsonrpc(MessageConstants.JSONRPC_VERSION);
        	responseMessage.setResult(r);
        	System.err.println("reply to client " + responseMessage);
        	return responseMessage.toString();
        
        } else if (m instanceof NotificationMessage) {
            System.err.println("Notification from the client: " + m);
        	NotificationMessage notifyMessage = (NotificationMessage)m;
        	localEndpoint.notify(notifyMessage.getMethod(), notifyMessage.getParams());
        	return null;
     
        } else {
        	assert false : "message " + m + " not understood";
        	return null;
        }
    }

    @OnError
    public void onError(Throwable e, Session session){
        e.printStackTrace();
    }

}
