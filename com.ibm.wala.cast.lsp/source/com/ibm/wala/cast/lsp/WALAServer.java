/******************************************************************************
 * Copyright (c) 2018 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *****************************************************************************/
package com.ibm.wala.cast.lsp;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.IntSupplier;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import com.ibm.wala.analysis.pointers.HeapGraph;
import com.ibm.wala.cast.ir.ssa.AstIRFactory.AstIR;
import com.ibm.wala.cast.ir.ssa.SSAConversion.CopyPropagationRecord;
import com.ibm.wala.cast.ir.ssa.SSAConversion.SSAInformation;
import com.ibm.wala.cast.loader.AstMethod;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Module;
import com.ibm.wala.classLoader.SourceURLModule;
import com.ibm.wala.client.AbstractAnalysisEngine;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.HeapModel;
import com.ibm.wala.ipa.callgraph.propagation.InstanceFieldKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PropagationCallGraphBuilder;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.CancelRuntimeException;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;

public class WALAServer implements LanguageClientAware, LanguageServer {
	private LanguageClient client;
	private final Map<URL, NavigableMap<Position,PointerKey>> values = HashMapFactory.make();
	private final Map<URL, NavigableMap<Position,int[]>> instructions = HashMapFactory.make();

	private final Function<String, AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?>> languages;
	
	private final Map<String, Set<Module>> languageSources = HashMapFactory.make();
	
	private final Set<Function<PointerKey,String>> valueAnalyses = HashSetFactory.make();
	private final Set<Function<int[],String>> instructionAnalyses = HashSetFactory.make();
	
	public boolean addSource(String language, Module file) {
		if (! languageSources.containsKey(language)) {
			languageSources.put(language, HashSetFactory.make());
		}
		
		return languageSources.get(language).add(file);
	}
	
	// The information the client sent to use when it called initialize
	private InitializeParams initializeParams = null;

	private TextDocumentClientCapabilities getTextCapabilities() {
		if(initializeParams == null) {
			// really, this should be a warning/error
			return null;
		}
		final ClientCapabilities caps = initializeParams.getCapabilities();
		if(caps == null) {
			return null;
		}
		return caps.getTextDocument();
	}

	// See MarkupKind for the allowed return values
	public String getHoverFormatRequested() {
		final TextDocumentClientCapabilities tcaps = getTextCapabilities();
		if(tcaps == null) {
			return MarkupKind.PLAINTEXT;
		}
		final HoverCapabilities hcaps = tcaps.getHover();
		if(hcaps == null) {
			return MarkupKind.PLAINTEXT;
		}
		final List<String> formats = hcaps.getContentFormat();
		if(formats == null || formats.isEmpty()) {
			return MarkupKind.PLAINTEXT;
		}
		return formats.get(0);
	}

	private <X> NavigableMap<Position, X> ensureUrlEntry(URL url, Map<URL, NavigableMap<Position, X>> map) {
		if (! map.containsKey(url)) {
			NavigableMap<Position, X> v = new TreeMap<Position,X>(new Comparator<Position>() {
				private int check(int v1, int v2, IntSupplier otherwise) {
					if (v1 != v2) {
						return v1 - v2;
					} else {
						return otherwise==null? 0: otherwise.getAsInt();
					}
				}
				
				@Override
				public int compare(Position o1, Position o2) {
					return 
						check(o1.getFirstLine(), o2.getFirstLine(),
							() -> { return check(o1.getFirstCol(), o2.getFirstCol(),
								() -> { return check(o1.getLastLine(), o2.getLastLine(),
									() -> { return check(o1.getLastCol(), o2.getLastCol(), null); }); }); });
				} 
			});
			
			map.put(url, v);
			return v;
		} else {
			return map.get(url);
		}
	}
	
	public void add(Position p, PointerKey v) {
		URL url = p.getURL();
		ensureUrlEntry(url, values).put(p, v);
	}

	public void add(Position p, int[] v) {
		URL url = p.getURL();
		ensureUrlEntry(url, instructions).put(p, v);
	}

	private String traverse(HeapGraph<InstanceKey> H, Function<PointerKey,String> analysis, PointerKey ptr) {
		String mine = analysis.apply(ptr);
		if (mine != null && !"?".equals(mine)) {
			return mine;
		} else if (H.containsNode(ptr)) {
			String all = "";
			for(Iterator<?> Is = H.getSuccNodes(ptr); Is.hasNext(); ) {
				for(Iterator<?> fields = H.getSuccNodes(Is.next()); fields.hasNext(); ) {
					Object f = fields.next();
					if (f instanceof InstanceFieldKey) {
						InstanceFieldKey field = (InstanceFieldKey) f;
						String sub = traverse(H, analysis, field);
						if (sub != null && !"?".equals(sub)) {
							String data = field.getField().getName() + ": " + sub + " ";
							if (! all.contains(data)) {
								all += data;
							}
						}
					}
				}
			}
			return "".equals(all)? null: all;
		} else {
			return null;
		}
	};

	public void addValueAnalysis(HeapGraph<InstanceKey> H, Function<PointerKey,String> analysis) {
		System.err.println(H);
		valueAnalyses.add((PointerKey key) -> {
			return traverse(H, analysis, key);
		});
	}

	public void addInstructionAnalysis(Function<int[],String> analysis) {
		instructionAnalyses.add(analysis);
	}

	public PointerKey getValue(Position p) {
		NavigableMap<Position, PointerKey> m = values.get(p.getURL());
		if (m.containsKey(p)) {
			return m.get(p);
		} else {
			return m.lowerEntry(p).getValue();
		}
	}
	
	public void analyze(String language) {
		try {
			AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?> engine = languages.apply(language);

			engine.setModuleFiles(languageSources.get(language));
			PropagationCallGraphBuilder cgBuilder = (PropagationCallGraphBuilder) engine.defaultCallGraphBuilder();

			CallGraph CG = cgBuilder.getCallGraph();
			HeapModel H = cgBuilder.getPointerAnalysis().getHeapModel();

			CG.iterator().forEachRemaining((CGNode n) -> { 
				IMethod M = n.getMethod();
				if (M instanceof AstMethod) {
					AstIR ir = (AstIR)n.getIR();
					ir.iterateAllInstructions().forEachRemaining((SSAInstruction inst) -> {
						if (inst.iindex != -1) {
							Position pos = ((AstMethod)M).debugInfo().getInstructionPosition(inst.iindex);
							if (pos != null) {
								add(pos, new int[] {CG.getNumber(n), inst.iindex});
							}
							if (inst.hasDef()) {
								if (pos != null) {
									PointerKey v = H.getPointerKeyForLocal(n, inst.getDef());
									add(pos, v);
								}
							}
							for(int i = 0; i < inst.getNumberOfUses(); i++) {
								Position p = ((AstMethod)M).debugInfo().getOperandPosition(inst.iindex, i);
								if (p != null) {
									PointerKey v = H.getPointerKeyForLocal(n, inst.getUse(i));
									add(p, v);
								}
							}
						}
					});

					SSAInformation info = ir.getLocalMap();
					Map<Object, CopyPropagationRecord> copyHistory = info.getCopyHistory();
					copyHistory.values().forEach((CopyPropagationRecord rec) -> {
						int instIndex = rec.getInstructionIndex();
						Position pos = ((AstMethod)M).debugInfo().getInstructionPosition(instIndex);
						if (pos != null) {
							PointerKey v = H.getPointerKeyForLocal(n, rec.getRhs());
							add(pos, v);
						}
					});
				}
			});

			engine.performAnalysis(cgBuilder);

		} catch (IOException | IllegalArgumentException | CancelException e) {

		}
	}
	
	public WALAServer(Function<WALAServer, Function<String, AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?>>> languages) {
		this(languages, null);
	}

	public WALAServer(Function<WALAServer, Function<String, AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?>>> languages, Integer port) {
		this.languages = languages.apply(this);
		this.serverPort = port;
	}

	@Override
	public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
		if(this.initializeParams != null) {
			// initialize should only be called once
			client.logMessage(new MessageParams(MessageType.Error, "initialize called multiple times."));
		}
		this.initializeParams = params;
		System.err.println("client sent " + params);
		final ServerCapabilities caps = new ServerCapabilities();
		caps.setHoverProvider(true);
		caps.setTextDocumentSync(TextDocumentSyncKind.Full);
		InitializeResult v = new InitializeResult(caps);
		System.err.println("server responding with " + v);
		return CompletableFuture.completedFuture(v);
	}

	public void initialized(InitializedParams params) {
		System.err.println("client sent " + params);
		MessageParams m = new MessageParams();
		m.setMessage("Welcome to WALA");
		client.showMessage(m);
	}
	
	@Override
	public CompletableFuture<Object> shutdown() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void exit() {

	}

	private Position lookupPos(org.eclipse.lsp4j.Position pos, URL url) {
		return new Position() {

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
			public int compareTo(Object o) {
				assert false;
				return 0;
			}

			@Override
			public URL getURL() {
				return url;
			}

			@Override
			public Reader getReader() throws IOException {
				return new InputStreamReader(url.openConnection().getInputStream());
			}
			
			public String toString() {
				return url + ":" + getFirstLine() + "," + getFirstCol();
			}
		};
	}

	@Override
	public TextDocumentService getTextDocumentService() {
		return new TextDocumentService() {

			@Override
			public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
					CompletionParams position) {
				return null;
			}	

			@Override
			public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public CompletableFuture<Hover> hover(TextDocumentPositionParams position) { 
				return CompletableFuture.supplyAsync(() -> {
					try {
						Position lookupPos = lookupPos(position.getPosition(), new URI(position.getTextDocument().getUri()).toURL());
						String msg = positionToString(lookupPos);
						final String hoverMarkupKind = getHoverFormatRequested();
						final boolean hoverKind = MarkupKind.MARKDOWN.equals(hoverMarkupKind);
						Hover reply = new Hover();
						if(hoverKind) {
							MarkupContent md = new MarkupContent();
							md.setKind("markdown");
							md.setValue(msg);
							reply.setContents(md);
						} else {
							reply.setContents(Collections.singletonList(Either.forLeft(msg)));
						}
							return reply;
					} catch (MalformedURLException | URISyntaxException e) {
						assert false : e.toString();
						return null;
					}
				});
			}

			@Override
			public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public CompletableFuture<List<? extends Location>> definition(TextDocumentPositionParams position) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(
					TextDocumentPositionParams position) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public CompletableFuture<List<? extends SymbolInformation>> documentSymbol(DocumentSymbolParams params) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public CompletableFuture<List<? extends Command>> codeAction(CodeActionParams params) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public void didOpen(DidOpenTextDocumentParams params) {
				try {
					String language = params.getTextDocument().getLanguageId();
					if (addSource(language, new SourceURLModule(new URL(params.getTextDocument().getUri())))) {
						analyze(language);
					}
				} catch (MalformedURLException e) {
					e.printStackTrace();
				}
			}
			

			@Override
			public void didChange(DidChangeTextDocumentParams params) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void didClose(DidCloseTextDocumentParams params) {
				try {
					Module M = new SourceURLModule(new URL(params.getTextDocument().getUri()));
					for(Map.Entry<String, Set<Module>> sl : languageSources.entrySet()) {
						if (sl.getValue().contains(M)) {
							sl.getValue().remove(M);
							analyze(sl.getKey());
						}
					}
				} catch (MalformedURLException e) {
					e.printStackTrace();
				}
			}

			@Override
			public void didSave(DidSaveTextDocumentParams params) {
				try {
					Module M = new SourceURLModule(new URL(params.getTextDocument().getUri()));
					for(Map.Entry<String, Set<Module>> sl : languageSources.entrySet()) {
						if (sl.getValue().contains(M)) {
							analyze(sl.getKey());
						}
					}
				} catch (MalformedURLException e) {
					e.printStackTrace();
				}
			}

		};
	}

	@Override
	public WorkspaceService getWorkspaceService() {
		return new WorkspaceService() {

			@Override
			public CompletableFuture<List<? extends SymbolInformation>> symbol(WorkspaceSymbolParams params) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public void didChangeConfiguration(DidChangeConfigurationParams params) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
				// TODO Auto-generated method stub
				
			}
		};
	}

	@Override
	public void connect(LanguageClient client) {
		this.client = client;
	}

	private boolean within(Position a, Position b) {
		return (a.getFirstLine() < b.getFirstLine() ||
				(a.getFirstLine() == b.getFirstLine() &&
				 a.getFirstCol() <= b.getFirstCol())) 
				&&
				(a.getLastLine() > a.getLastLine() ||
				 (a.getLastLine() == b.getLastLine() &&
				  a.getLastCol() >= b.getLastCol()));
	}
				 
	private Position getNearest(NavigableMap<Position, ?> scriptPositions, Position pos) {
		Entry<Position, ?> entry = scriptPositions.floorEntry(pos);
		if (entry == null) {
			return null;
		}
		
		Entry<Position, ?> next = entry;
		while (next != null && within(next.getKey(), pos) && next.getKey().getLastCol() <= entry.getKey().getLastCol()) {
			entry = next;
			next = scriptPositions.higherEntry(entry.getKey());
		}
		
		return entry.getKey();		
	}
	
	private <T> void positionToString(Position pos, Map<URL, NavigableMap<Position,T>> map, Set<Function<T, String>> analyses, StringBuffer sb) {
		if (map.containsKey(pos.getURL())) {
			NavigableMap<Position, T> scriptPositions = map.get(pos.getURL());
			Position nearest = getNearest(scriptPositions, pos);
		
			if (nearest != null) {
				for(Function<T,String> a : analyses) {
					String s = a.apply(scriptPositions.get(nearest));
					if (s != null) {
						sb.append("\n" + s);
					}	
				}
			}
		}
	}
	
	private String positionToString(Position pos) {
		StringBuffer sb = new StringBuffer();
		positionToString(pos, instructions, instructionAnalyses, sb);
		positionToString(pos, values, valueAnalyses, sb);
		sb.append("\n");
		return sb.toString();
	}

	public String toString() {
		StringBuffer sb = new StringBuffer("WALA Server: ");
		for(URL script : values.keySet()) {
			sb.append(script + "\n");
			for(Position pos : values.get(script).keySet()) {
				sb.append(pos + ": " + positionToString(pos));
			}
		}
		return sb.toString();
	}
	
	final private Integer serverPort;
	public Integer getServerPort() {
		return serverPort;
	}

	public static WALAServer launchOnServerPort(int port, Function<WALAServer, Function<String, AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?>>> languages, boolean runAsDaemon) throws IOException {
		ServerSocket ss = new ServerSocket(port);
		WALAServer server = new WALAServer(languages, ss.getLocalPort()) {
			@Override
			public void finalize() throws IOException {
				ss.close();
			}
		};
		Thread st = new Thread() {
			@Override
			public void run() {
				try {
					while (true) {
						try {
							Socket conn = ss.accept();
							Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, conn.getInputStream(), conn.getOutputStream());
							server.connect(launcher.getRemoteProxy());
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
		return server;
	}
	
	public static WALAServer launchOnStdio(Function<WALAServer, Function<String, AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?>>> languages) throws IOException {
		return launchOnStream(languages, System.in, System.out);
	}

	public static WALAServer launchOnStream(Function<WALAServer, 
			Function<String, AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?>>> languages,
			InputStream in,
			OutputStream out) throws IOException {
		WALAServer server = new WALAServer(languages);
		Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out, true, new PrintWriter(System.err));
		server.connect(launcher.getRemoteProxy());
		launcher.startListening();
		return server;
	}

	public static WALAServer launchOnClientPort(String hostname, int port, Function<WALAServer, Function<String, AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?>>> languages) throws IOException {
		final Socket conn = new Socket(hostname, port);
		final WALAServer server = new WALAServer(languages, conn.getLocalPort()) {
			@Override
			public void finalize() throws IOException {
				conn.close();
			}
		};

		Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, conn.getInputStream(), conn.getOutputStream());
		server.connect(launcher.getRemoteProxy());
		launcher.startListening();
		return server;
	}
}
