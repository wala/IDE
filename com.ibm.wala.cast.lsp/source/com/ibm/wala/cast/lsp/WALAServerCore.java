package com.ibm.wala.cast.lsp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.commons.io.input.TeeInputStream;
import org.apache.commons.io.output.TeeOutputStream;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.WillSaveTextDocumentParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.cast.tree.impl.AbstractSourcePosition;
import com.ibm.wala.classLoader.Module;
import com.ibm.wala.util.CancelRuntimeException;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Pair;

public class WALAServerCore implements LanguageClientAware, LanguageServer {
	protected final boolean logging;
	protected LanguageClient client;
	protected final Map<String,String> savedNames = HashMapFactory.make();
	protected final Map<String,List<CodeLens>> codeLenses = HashMapFactory.make();
	protected final Map<String,NavigableMap<Position,String>> hovers = HashMapFactory.make();
	protected String[] rootUris;
	
	public interface WALAServerAnalysis {
		
		String source();
		
		void analyze(
			Collection<Module> sources,
			Consumer<AnalysisError> callback);
	}
	
	protected final Map<String, Map<String,Module>> languageSources = HashMapFactory.make();
	protected final Map<String, Set<WALAServerAnalysis>> languageAnalyses = HashMapFactory.make();

	public WALAServerCore(boolean logging) {
		this.logging = logging;
	}
	
	public WALAServerCore() {
		this(false);
	}
	
	public void addAnalysis(String language, WALAServerAnalysis analysis) {
		if (! languageAnalyses.containsKey(language)) {
			languageAnalyses.put(language, HashSetFactory.make());
		}

		languageAnalyses.get(language).add(analysis);
	}
	
	protected boolean addSource(String language, String url, Module file) {
		if (! languageSources.containsKey(language)) {
			languageSources.put(language, HashMapFactory.make());
		}

		return languageSources.get(language).put(url, file) != file;
	}

	protected static URI getPositionUri(Position pos) {
		URL url = pos.getURL();
		try {
			URI uri = url.toURI();
			try {
				if(uri.getScheme().equalsIgnoreCase("file")) {
					uri = Paths.get(uri).toUri();
				}
			} catch (Exception e) {
				
			}
			return uri;
		} catch(URISyntaxException e) {
			System.err.println("Error converting URL " + url + " to a URI:" + e.getMessage());
			return null;
		}
	}

	protected org.eclipse.lsp4j.Position positionFromWALA(Supplier<Integer> line, Supplier<Integer> column) {
		org.eclipse.lsp4j.Position codeStart = new org.eclipse.lsp4j.Position();
		codeStart.setLine(line.get()-1);
		codeStart.setCharacter(column.get());
		return codeStart;
	}

	protected Location locationFromWALA(Position walaCodePosition) {
		Location codeLocation = new Location();
		codeLocation.setUri(Util.unmangleUri(getPositionUri(walaCodePosition).toString()));
		Range codeRange = new Range();
		codeRange.setStart(positionFromWALA(walaCodePosition::getFirstLine, walaCodePosition::getFirstCol));
		codeRange.setEnd(positionFromWALA(walaCodePosition::getLastLine, walaCodePosition::getLastCol));
		codeLocation.setRange(codeRange);
		return codeLocation;
	}

	@Override
	public void connect(LanguageClient client) {
		this.client = client;
	}
	
	public void analyze(String language) {
		Collection<Module> sources = languageSources.get(language).values();
		if (languageAnalyses.containsKey(language) && languageSources.containsKey(language)) {
			Map<String, List<Diagnostic>> diags = HashMapFactory.make();
			languageAnalyses.get(language).forEach((analysis) -> {
				analysis.analyze(sources, (error) -> {
					switch (error.kind()) {
					case CodeLens:
						processCodeLens(analysis, error);
						break;
					case Diagnostic:
						processDiagnostic(diags, analysis, error);
						break;
					case Hover:
						processHover(analysis, error);
						break;
					}
				});
			});
			
			publishDiagnostics(diags);
		}
	}

	private void publishDiagnostics(Map<String, List<Diagnostic>> diags) {
		for (Map.Entry<String, List<Diagnostic>> d : diags.entrySet()) {
			PublishDiagnosticsParams pdp = new PublishDiagnosticsParams();
			if (d.getValue() != null && !d.getValue().isEmpty()) {
				pdp.setUri(Util.unmangleUri(d.getKey()).replace("//", "///"));//the devil "/"
				pdp.setDiagnostics(d.getValue());
				client.publishDiagnostics(pdp);
			}
		}
	}

	private void processHover(WALAServerAnalysis analysis, AnalysisError error) {
		String uri = Util.unmangleUri(getPositionUri(error.position()).toString());
		if (! hovers.containsKey(uri)) {
			hovers.put(uri, new TreeMap<>());
		}
		
		hovers.get(uri).put(error.position(), error.toString(false));
		
		System.err.println("hover at " + uri + ": " + error.toString(false));
	}
	
	private void processCodeLens(WALAServerAnalysis analysis, AnalysisError error) {
		Range where = locationFromWALA(error.position()).getRange();
		CodeLens cl = new CodeLens();
		boolean isRepair = error.repair() != null;
		final String command = isRepair? "repair": "dummy";
		final String title = error.toString();
		Command cmd = new Command(title, command);
		cmd.setArguments(isRepair? Arrays.asList(error.repair()): Arrays.asList(""));
		cl.setCommand(cmd);
		cl.setRange(where);
		
		String uri = Util.unmangleUri(getPositionUri(error.position()).toString());
		if (! codeLenses.containsKey(uri)) {
			codeLenses.put(uri, new LinkedList<>());
		}
		codeLenses.get(uri).add(cl);
	}
	
	private void processDiagnostic(Map<String, List<Diagnostic>> diags, WALAServerAnalysis analysis,
			AnalysisError error) {
		Diagnostic d = new Diagnostic();
		d.setMessage(error.toString(false));
		Position pos = error.position();
		d.setRange(locationFromWALA(pos).getRange());
		d.setSource(analysis.source());
		d.setSeverity(error.severity());
		if (error.related() != null && error.related().iterator().hasNext()) {
			Set<DiagnosticRelatedInformation> relList = HashSetFactory.make();
			for (Pair<Position, String> related : error.related()) {
				DiagnosticRelatedInformation di = new DiagnosticRelatedInformation();
				di.setLocation(locationFromWALA(related.fst));
				di.setMessage(related.snd);
				relList.add(di);
			}
			d.setRelatedInformation(new LinkedList<>(relList));
		}
		String uri = Util.unmangleUri(getPositionUri(error.position()).toString());
		if (savedNames.containsKey(uri)) {
			uri = savedNames.get(uri);
		}
		if (!diags.containsKey(uri)) {
			diags.put(uri, new LinkedList<>());
		}
		diags.get(uri).add(d);
	}

	@Override
	public void initialized(InitializedParams params) {
		if (logging) {
			MessageParams msg = new MessageParams();
			msg.setMessage(params.toString());
			msg.setType(MessageType.Log);
			client.logMessage(msg);
		}
	}

	@Override
	public CompletableFuture<Object> shutdown() {
		ShowMessageRequestParams msg = new ShowMessageRequestParams();
		msg.setMessage("really quit?");
		msg.setType(MessageType.Info);
		List<MessageActionItem> options = new LinkedList<>();
		MessageActionItem yes = new MessageActionItem();
		yes.setTitle("yes");
		options.add(yes);
		MessageActionItem no = new MessageActionItem();
		no.setTitle("no");
		options.add(no);
		msg.setActions(options);
		return client.showMessageRequest(msg).thenApply((response) -> {
			if (response.equals(yes)) {
				exit();
			}
			
			return CompletableFuture.completedFuture(response);
		});
	}

	@Override
	public void exit() {
		System.exit(0);
	}

	@Override
	public WorkspaceService getWorkspaceService() {
		// TODO Auto-generated method stub
		return new WorkspaceService() {

			@Override
			public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
				// TODO Auto-generated method stub

			}

			@Override
			public void didChangeConfiguration(DidChangeConfigurationParams params) {
				// TODO Auto-generated method stub

			}
		};
	}

	protected Module makeModule(DidOpenTextDocumentParams params) {
		TextDocumentItem doc = params.getTextDocument();
		String uri = Util.mangleUri(doc.getUri());
		return new LSPStringModule(uri, doc.getText());
	}

	protected class WALATextDocumentService implements TextDocumentService {
		
		public WALATextDocumentService() {
			
		}
		
		@Override
		public void didOpen(DidOpenTextDocumentParams params) {
			TextDocumentItem doc = params.getTextDocument();
			String language = doc.getLanguageId();
			String uri = Util.mangleUri(doc.getUri());
			if (addSource(language, uri, makeModule(params))) {
				analyze(language);
			}
		}
		
		@Override
		public void didChange(DidChangeTextDocumentParams params) {
			String uri = Util.mangleUri(params.getTextDocument().getUri());
			clearDiagnostics(uri);
		}

		private void clearDiagnostics(String uri) {
			PublishDiagnosticsParams diagnostics = new PublishDiagnosticsParams();
			diagnostics.setUri(Util.unmangleUri(uri));

			client.publishDiagnostics(diagnostics);
		}

		@Override
		public void didClose(DidCloseTextDocumentParams params) {
			String uri = Util.mangleUri(params.getTextDocument().getUri());
			for(Entry<String, Map<String, Module>> sl : languageSources.entrySet()) {
				if (sl.getValue().containsKey(uri)) {
					sl.getValue().remove(uri);
					if (! sl.getValue().isEmpty()) {
						analyze(sl.getKey());
					} else {
						clearDiagnostics(uri);
					}
				}
			}
		}

		@Override
		public void didSave(DidSaveTextDocumentParams params) {
			String uri = Util.mangleUri(params.getTextDocument().getUri());
			for(Entry<String, Map<String, Module>> sl : languageSources.entrySet()) {
				if (sl.getValue().containsKey(uri)) {
					analyze(sl.getKey());
				}
			}
		}

		@Override
		public void willSave(WillSaveTextDocumentParams params) {
			String uri = Util.mangleUri(params.getTextDocument().getUri());
			for(Entry<String, Map<String, Module>> sl : languageSources.entrySet()) {
				if (sl.getValue().containsKey(uri)) {
					analyze(sl.getKey());
				}
			}
		}

		@Override
		public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
			return CompletableFuture.supplyAsync(() -> {
				String document = Util.mangleUri(params.getTextDocument().getUri());
				if (codeLenses.containsKey(document)) {
					return codeLenses.get(document);
				} else {
					return Collections.emptyList();
				}
			});
		}

		@Override
		public CompletableFuture<Hover> hover(TextDocumentPositionParams position) {
			Hover reply = new Hover();
			reply.setContents(Either.forLeft(Collections.singletonList(Either.forLeft(""))));
			try {
				String uri = Util.mangleUri(position.getTextDocument().getUri());
				URL url = new URI(uri).toURL();
				if (hovers.containsKey(uri)) {
					Position lookupPos = lookupPos(position.getPosition(), url);
					Position loc = getNearest(hovers.get(uri), lookupPos);
					String message = hovers.get(uri).get(loc);
					reply.setContents(Collections.singletonList(Either.forLeft(message)));
					reply.setRange(locationFromWALA(loc).getRange());
				}
				return CompletableFuture.completedFuture(reply);
			} catch (MalformedURLException | URISyntaxException e) {
				assert false : e;
				return CompletableFuture.completedFuture(reply);
			}
		}
	}
	
	@Override
	public TextDocumentService getTextDocumentService() {
		return new WALATextDocumentService(); 
	}


	static InputStream logStream(InputStream is, String logFileName) {
		File log;
		try {
			log = File.createTempFile(logFileName, ".txt");
			return new TeeInputStream(is, new FileOutputStream(log));
		} catch (IOException e) {
			return is;
		}
	}

	static OutputStream logStream(OutputStream os, String logFileName) {
		File log;
		try {
			log = File.createTempFile(logFileName, ".txt");
			return new TeeOutputStream(os, new FileOutputStream(log));
		} catch (IOException e) {
			return os;
		}
	}

	protected Integer serverPort;

	public Integer getServerPort() {
		return serverPort;
	}


	public void launchOnServerPort(int port, boolean runAsDaemon) throws IOException {
		@SuppressWarnings("resource")
		ServerSocket ss = new ServerSocket(port);
		serverPort = ss.getLocalPort();
		Thread st = new Thread() {
			@Override
			public void run() {
				try {
					while (true) {
						try {
							Socket conn = ss.accept();
							Launcher<LanguageClient> launcher = 
									LSPLauncher.createServerLauncher(WALAServerCore.this, 
											logStream(conn.getInputStream(), "walaLspIn"),
											logStream(conn.getOutputStream(), "walaLspOut"));
							connect(launcher.getRemoteProxy());
							launcher.startListening();
						} catch (IOException e) {
							if (ss.isClosed()) {
								break;
							}
						}
					}
				} catch (CancelRuntimeException e) {
					System.err.println(e);
				}
			}
		};
		if (runAsDaemon) {
			st.setDaemon(true);
		}
		st.start();
	}

	public void launchOnStdio() throws IOException {
		launchOnStream(System.in, System.out);
	}

	public void launchOnStream(InputStream in, OutputStream out) throws IOException {
		Launcher<LanguageClient> launcher = 
			LSPLauncher.createServerLauncher(this, logStream(in, "wala.lsp.in"), logStream(new PrintStream(out, true), "wala.lsp.out"), true, new PrintWriter(System.err));
		connect(launcher.getRemoteProxy());
		launcher.startListening();
	}

	public void launchOnClientPort(String hostname, int port) throws IOException {
		@SuppressWarnings("resource")
		final Socket conn = new Socket(hostname, port);
		Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(this, logStream(conn.getInputStream(), "wala.lsp.in"), logStream(conn.getOutputStream(), "wala.lsp.out"));
		connect(launcher.getRemoteProxy());
		launcher.startListening();
	}

	@Override
	public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
		Set<String> roots = HashSetFactory.make();
		if (params.getRootUri() != null) {
			roots.add(params.getRootUri());
		}
		if (params.getWorkspaceFolders() != null) {
			params.getWorkspaceFolders().forEach((folder) -> {
				roots.add(folder.getName());
			});
		}
		rootUris = roots.toArray(new String[ roots.size() ]);
		
		final ServerCapabilities caps = new ServerCapabilities();
		caps.setHoverProvider(true);
		caps.setTextDocumentSync(TextDocumentSyncKind.Full);
		CodeLensOptions cl = new CodeLensOptions();
		cl.setResolveProvider(true);
		caps.setCodeLensProvider(cl);
		caps.setDocumentSymbolProvider(true);
		caps.setDefinitionProvider(true);
		caps.setReferencesProvider(true);
		ExecuteCommandOptions exec = new ExecuteCommandOptions();
		exec.setCommands(new LinkedList<String>());
		caps.setExecuteCommandProvider(exec);
		caps.setCodeActionProvider(true);
		InitializeResult v = new InitializeResult(caps);
		return CompletableFuture.completedFuture(v);
	}

	protected Position lookupPos(org.eclipse.lsp4j.Position pos, URL url) {
		return new AbstractSourcePosition() {
	
			@Override
			public int getFirstLine() {
				// LSP is 0-based, but parsers mostly 1-based
				return pos.getLine() + 1;
			}
	
			@Override
			public int getLastLine() {
				// LSP is 0-based, but parsers mostly 1-based
				return pos.getLine() + 1;
			}
	
			@Override
			public int getFirstCol() {
				return pos.getCharacter();
			}
	
			@Override
			public int getLastCol() {
				return pos.getCharacter();
			}
	
			@Override
			public int getFirstOffset() {
				return -1;
			}
	
			@Override
			public int getLastOffset() {
				return -1;
			}
	
			@Override
			public URL getURL() {
				return url;
			}
	
			@Override
			public Reader getReader() throws IOException {
				return new InputStreamReader(url.openConnection().getInputStream());
			}
		};
	}

	private boolean within(Position a, Position b) {
		return (a.getFirstLine() < b.getFirstLine() ||
				(a.getFirstLine() == b.getFirstLine() &&
				a.getFirstCol() <= b.getFirstCol())) 
				&&
				(a.getLastLine() > b.getLastLine() ||
						(a.getLastLine() == b.getLastLine() &&
						a.getLastCol() >= b.getLastCol()));
	}

	protected Position getNearest(NavigableMap<Position, ?> scriptPositions, Position pos) {
		Entry<Position, ?> entry = scriptPositions.floorEntry(pos);
		if (entry == null) {
			return null;
		}
	
		Entry<Position, ?> prev = entry;
		while (prev != null && within(entry.getKey(), prev.getKey()) && prev.getKey().getLastCol() >= pos.getFirstCol()) {
			entry = prev;
			prev = scriptPositions.lowerEntry(entry.getKey());
		}
	
		Entry<Position, ?> next = entry;
		while (next != null && within(next.getKey(), pos) && next.getKey().getLastCol() <= entry.getKey().getLastCol()) {
			entry = next;
			next = scriptPositions.higherEntry(entry.getKey());
		}
	
		return entry.getKey();		
	}

}
