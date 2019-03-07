package com.ibm.wala.cast.lsp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

public class ClientDriver implements LanguageClient {
	protected final LanguageServer server;
	
	public static void main(String[] args) throws IOException {
		ClientDriver client = new ClientDriver();
		client.start();
		client.server.shutdown();
	}
	
	public ClientDriver() throws IOException {
		PipedInputStream serverIn = new PipedInputStream();
		PipedOutputStream serverOut = new PipedOutputStream();
		
		PipedInputStream clientIn = new PipedInputStream(serverOut);
		PipedOutputStream clientOut = new PipedOutputStream(serverIn);

		WALAServerCore server = createServer();
		server.launchOnStream(serverIn, serverOut);
		
		this.server = server;
		server.connect(this);
		
		Launcher<LanguageServer> l = LSPLauncher.createClientLauncher(this, clientIn, clientOut);
		l.startListening();
	}

	protected WALAServerCore createServer() {
		WALAServerCore server = new WALAServerCore(true);
		return server;
	}

	protected void start() {
		initialize();
	}
	
	private void initialize() {
		InitializeParams x = new InitializeParams();
		ClientCapabilities c = new ClientCapabilities();
		TextDocumentClientCapabilities tc = new TextDocumentClientCapabilities();
		PublishDiagnosticsCapabilities pc = new PublishDiagnosticsCapabilities();
		pc.setRelatedInformation(true);
		tc.setPublishDiagnostics(pc);
		c.setTextDocument(tc);
		x.setCapabilities(c);
		CompletableFuture<InitializeResult> y = server.initialize(x);
		y.thenAccept((InitializeResult xx) -> { 
			InitializedParams z = new InitializedParams();
			server.initialized(z);
		});
	}

	@Override
	public void telemetryEvent(Object object) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
		System.err.println("issues: " + diagnostics);
	}

	@Override
	public void showMessage(MessageParams msg) {
		System.err.println(msg.getType() + ": " + msg.getMessage());
	}

	@Override
	public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams msg) {
		System.err.println(msg.getType() + ": " + msg.getMessage());
		List<MessageActionItem> actions = msg.getActions();
		int i = 0;
		System.err.println("Choose from the following options:");
		for(MessageActionItem act : actions) {
			System.err.println(i++ + ": " + act.getTitle());
		}
		
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		try {
			int choice = Integer.parseInt(reader.readLine());
			return CompletableFuture.completedFuture(actions.get(choice));
		} catch (NumberFormatException | IOException e) {
			assert false : e;
			return CompletableFuture.completedFuture(actions.get(0));
		}
	}

	@Override
	public void logMessage(MessageParams msg) {
		System.err.println(msg.getType() + ": " + msg.getMessage());
	}

}
