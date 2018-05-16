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
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
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
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ExecuteCommandParams;
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
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
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

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.ibm.wala.analysis.pointers.HeapGraph;
import com.ibm.wala.cast.ir.ssa.AstIRFactory.AstIR;
import com.ibm.wala.cast.ir.ssa.SSAConversion.CopyPropagationRecord;
import com.ibm.wala.cast.ir.ssa.SSAConversion.SSAInformation;
import com.ibm.wala.cast.loader.AstFunctionClass;
import com.ibm.wala.cast.loader.AstMethod;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.cast.types.AstMethodReference;
import com.ibm.wala.cast.util.SourceBuffer;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IClassLoader;
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
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.CancelRuntimeException;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Pair;

public class WALAServer implements LanguageClientAware, LanguageServer {
	private LanguageClient client;
	private final Map<URL, NavigableMap<Position,PointerKey>> values = HashMapFactory.make();
	private final Map<URL, NavigableMap<Position,int[]>> instructions = HashMapFactory.make();

	private final Function<String, AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?>> languages;
	
	private final Map<String, Set<Module>> languageSources = HashMapFactory.make();

	private final Map<String, CallGraph> languageBuilders = HashMapFactory.make();

	private final Map<String, Map<String, WalaSymbolInformation>> documentSymbols = HashMapFactory.make();
	
	private final Set<Pair<String, BiFunction<Boolean, PointerKey,String>>> valueAnalyses = HashSetFactory.make();
	private final Set<Map<PointerKey,AnalysisError>> valueErrors = HashSetFactory.make();
	private final Set<Pair<String,BiFunction<Boolean,int[],String>>> instructionAnalyses = HashSetFactory.make();

	private Function<int[],Set<Position>> findDefinitionAnalysis = null;

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


	private String traverse(
		HeapGraph<InstanceKey> H, 
		BiFunction<Boolean,PointerKey,String> analysis,
//		Collector<Pair<IField,R>, A, R> collector,
		Boolean useMarkdown,
		PointerKey ptr) {
		String mine = analysis.apply(useMarkdown, ptr);
		if (mine != null) {
			return mine;
		} else if (H.containsNode(ptr)) {
			Map<String, String> entries = new LinkedHashMap<String, String>();
			for(Iterator<?> Is = H.getSuccNodes(ptr); Is.hasNext(); ) {
				for(Iterator<?> fields = H.getSuccNodes(Is.next()); fields.hasNext(); ) {
					Object f = fields.next();
					if (f instanceof InstanceFieldKey) {
						InstanceFieldKey field = (InstanceFieldKey) f;
						String sub = traverse(H, analysis, useMarkdown, field);
						if (sub != null) {
							entries.putIfAbsent(field.getField().getName().toString(), sub);
						}
					}
				}
			}
			if(entries.isEmpty()) {
				return null;
			}
			final String contents = entries.entrySet().stream()
				.map((Entry<String,String> entry) -> (useMarkdown ? ("_" + entry.getKey() + "_") : entry.getKey()) + ": " + entry.getValue())
				.collect(Collectors.joining(","+newline(useMarkdown)));
			return "{" + contents + "}";
		} else {
			return null;
		}
	};

	public void addValueErrors(Map<PointerKey,AnalysisError> errors) {
		valueErrors.add(errors);
	}
	
	public void addValueAnalysis(String name, HeapGraph<InstanceKey> H, BiFunction<Boolean, PointerKey,String> analysis) {
		valueAnalyses.add(Pair.make(name, (Boolean format, PointerKey key) -> traverse(H, analysis, format, key)));
	}

	public void addInstructionAnalysis(String name, BiFunction<Boolean, int[],String> analysis) {
		instructionAnalyses.add(Pair.make(name, analysis));
	}

	public void setFindDefinitionAnalysis(Function<int[],Set<Position>> analysis) {
		this.findDefinitionAnalysis = analysis;
	}

	public PointerKey getValue(Position p) {
		NavigableMap<Position, PointerKey> m = values.get(p.getURL());
		if (m.containsKey(p)) {
			return m.get(p);
		} else {
			return m.lowerEntry(p).getValue();
		}
	}
	
	private org.eclipse.lsp4j.Position positionFromWALA(Supplier<Integer> line, Supplier<Integer> column) {
		org.eclipse.lsp4j.Position codeStart = new org.eclipse.lsp4j.Position();
		codeStart.setLine(line.get()-1);
		codeStart.setCharacter(column.get());
		return codeStart;
	}
	
	private Location locationFromWALA(Position walaCodePosition) {
		Location codeLocation = new Location();
		codeLocation.setUri(getPositionUri(walaCodePosition).toString());
		Range codeRange = new Range();
		codeRange.setStart(positionFromWALA(walaCodePosition::getFirstLine, walaCodePosition::getFirstCol));
		codeRange.setEnd(positionFromWALA(walaCodePosition::getLastLine, walaCodePosition::getLastCol));
		codeLocation.setRange(codeRange);
		return codeLocation;
	}

	static URI getPositionUri(Position pos) {
		URL url = pos.getURL();
		try {
			URI uri = url.toURI();
			if(uri.getScheme().equalsIgnoreCase("file")) {
				uri = Paths.get(uri).toUri();
			}
			return uri;
		} catch(URISyntaxException e) {
			System.err.println("Error converting URL " + url + " to a URI:" + e.getMessage());
			return null;
		}
	}
	
	private class WalaSymbolInformation extends SymbolInformation {
		private final MethodReference function;

		public WalaSymbolInformation(MethodReference function) {
			this.function = function;
		}

		public MethodReference getFunction() {
			return function;
		}	
		
		public String toString() {
			return super.toString() + "(" + getFunction() + ")";
			
		}
	}
	
	public void analyze(String language) {
		try {
			AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?> engine = languages.apply(language);

			engine.setModuleFiles(languageSources.get(language));
			PropagationCallGraphBuilder cgBuilder = (PropagationCallGraphBuilder) engine.defaultCallGraphBuilder();

			CallGraph CG = cgBuilder.getCallGraph();
			HeapModel H = cgBuilder.getPointerAnalysis().getHeapModel();

			for(IClass cls : CG.getClassHierarchy()) {
				if (cls instanceof AstFunctionClass) {
					AstMethod code = ((AstFunctionClass)cls).getCodeBody();
					Position sourcePosition = code.getSourcePosition();
					if (sourcePosition != null) {
						WalaSymbolInformation codeSymbol = new WalaSymbolInformation(code.getReference());
						codeSymbol.setKind(SymbolKind.Function);
						codeSymbol.setName(cls.getName().toString());
						codeSymbol.setLocation(locationFromWALA(sourcePosition));
						
						
						URI documentURI = getPositionUri(sourcePosition);
						if(documentURI != null) {
							final String document = documentURI.toString();
							if (! documentSymbols.containsKey(document)) {
								documentSymbols.put(document, HashMapFactory.make());
							}
							documentSymbols.get(document).put(cls.getName().toString(), codeSymbol);
						}
					}
				}
			}
			
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

			languageBuilders.put(language, CG);
			
			Map<String, List<Diagnostic>> diags = HashMapFactory.make();
			for(Map<PointerKey,AnalysisError> ve : valueErrors) {
				errors: for(Map.Entry<PointerKey,AnalysisError> e : ve.entrySet()) {
					Diagnostic d = new Diagnostic();
					// Diagnostics do not currently support markdown
					d.setMessage(e.getValue().toString(false));
					Position pos = e.getValue().position();
					Location loc = locationFromWALA(pos);
					d.setRange(loc.getRange());
					d.setSource(loc.getUri());
					String uri = loc.getUri();
					if (! diags.containsKey(uri)) {
						diags.put(uri, new LinkedList<>());
					}
					for(Diagnostic od : diags.get(uri)) {
						if (od.toString().equals(d.toString())) {
							continue errors;
						}
					}
					diags.get(uri).add(d);
				}
			}

			for(Map.Entry<String,List<Diagnostic>> d : diags.entrySet()) {
				PublishDiagnosticsParams pdp = new PublishDiagnosticsParams();
				pdp.setUri(d.getKey());
				pdp.setDiagnostics(d.getValue());
				client.publishDiagnostics(pdp);
			}

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

	public static enum WalaCommand {
		CALLS, TYPES, FIXES
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
		CodeLensOptions cl = new CodeLensOptions();
		cl.setResolveProvider(false);
		caps.setCodeLensProvider(cl);
		caps.setDocumentSymbolProvider(true);
		caps.setDefinitionProvider(true);
		ExecuteCommandOptions exec = new ExecuteCommandOptions();
		List<String> cmds = 
		Arrays.stream(WalaCommand.values())
		.map(WalaCommand::toString)
		.collect(Collectors.toList());
		exec.setCommands(cmds);
		caps.setExecuteCommandProvider(exec);
		InitializeResult v = new InitializeResult(caps);
		caps.setCodeActionProvider(true);
		System.err.println("server responding with " + v);
		return CompletableFuture.completedFuture(v);
	}

	public void initialized(InitializedParams params) {
		System.err.println("client sent " + params);
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

	private String extractFix(String message) {
		return message.substring(message.indexOf("possible fix:")+13, message.length()-1);
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
						final String hoverMarkupKind = getHoverFormatRequested();
						final boolean hoverKind = MarkupKind.MARKDOWN.equals(hoverMarkupKind);
						String msg = positionToString(lookupPos, hoverKind);
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
				return CompletableFuture.supplyAsync(() -> {
				try {
					if (findDefinitionAnalysis == null) {
						return null;
					}
					Position pos = lookupPos(position.getPosition(), new URI(position.getTextDocument().getUri()).toURL());

					if (instructions.containsKey(pos.getURL())) {
						NavigableMap<Position, int[]> scriptPositions = instructions.get(pos.getURL());
						Position nearest = getNearest(scriptPositions, pos);
						if(nearest == null) {
							return null;
						}
						Set<Position> locations = findDefinitionAnalysis.apply(scriptPositions.get(nearest));
						if(locations == null || locations.isEmpty()) {
							return null;
						}
						List<Location> locs = locations.stream()
						.filter(x -> x != null)
						.map(x -> locationFromWALA(x))
						.collect(Collectors.toList());
						if(locs == null | locs.isEmpty()) {
							return null;
						} else {
							return locs;
						}
					} else {
						return null;
					}
				} catch (MalformedURLException | URISyntaxException e) {
					assert false : e.toString();
					return null;
				}
			});
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
				return CompletableFuture.supplyAsync(() -> {
					String document = params.getTextDocument().getUri();
					if (! documentSymbols.containsKey(document)) {
						return Collections.emptyList();
					} else {
						return new LinkedList<WalaSymbolInformation>(documentSymbols.get(document).values());
					}
				});
			}

			@Override
			public CompletableFuture<List<? extends Command>> codeAction(CodeActionParams params) {
				return CompletableFuture.supplyAsync(() -> {
					if (params.getContext().getDiagnostics().toString().contains("possible fix:")) {
						Command fix = new Command();
						fix.setCommand(WalaCommand.FIXES.toString());
						String message = params.getContext().getDiagnostics().get(0).getMessage();
						fix.setTitle(extractFix(message));
						List<Object> args = new LinkedList<Object>(params.getContext().getDiagnostics());
						fix.setArguments(args);
						return Collections.singletonList(fix);					
					} else {
						return Collections.emptyList();
					}
				});
			}

			public void addTypesCodeLensesForSymbol(WalaSymbolInformation sym, List<CodeLens> result) {
					CodeLens cl = new CodeLens();
					final String typeName = sym.getFunction().getDeclaringClass().getName().toString();
					if(typeName == null) {
						return;
					}
					final String type = getTypeListForName(typeName);
					if(type == null) {
						return;
					}

					final String command = WalaCommand.TYPES.toString();
					final String title = type;
					Command cmd = new Command(title, command);
					cmd.setArguments(Arrays.asList(typeName));
					cl.setCommand(cmd);
					cl.setRange(sym.getLocation().getRange());
					result.add(cl);
			}

			public void addRefsCodeLensesForSymbol(WalaSymbolInformation sym, List<CodeLens> result) {
				CodeLens cl = new CodeLens();
				final String command = WalaCommand.CALLS.toString();
				final String title = "refs";
				Command cmd = new Command(title, command);
				cmd.setArguments(Arrays.asList(sym.getFunction().getDeclaringClass().getName().toString()));
				cl.setCommand(cmd);
				cl.setRange(sym.getLocation().getRange());
				result.add(cl);
		}

			public void addCodeLensesForSymbol(WalaSymbolInformation sym, List<CodeLens> result) {
				addTypesCodeLensesForSymbol(sym, result);
				addRefsCodeLensesForSymbol(sym, result);
			}

			@Override
			public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
				return CompletableFuture.supplyAsync(() -> {
					List<CodeLens> result = new LinkedList<CodeLens>();
					String document = params.getTextDocument().getUri();
					for(WalaSymbolInformation sym : documentSymbols.get(document).values()) {
						addCodeLensesForSymbol(sym, result);
					}
					return result;
				});
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
				PublishDiagnosticsParams diagnostics = new PublishDiagnosticsParams();
				diagnostics.setUri(params.getTextDocument().getUri());
				
				client.publishDiagnostics(diagnostics);
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

	private String getTypeListForName(String typeName) {
		final Set<String> types = getTypesForName(typeName);
		if(types == null || types.isEmpty()) {
			return null;
		}

		final String type = types.stream().collect(Collectors.joining(", "));
		return type;
	}

	private Set<String> getTypesForName(String typeName) {
		Set<String> result = HashSetFactory.make();
		for (CallGraph CG : languageBuilders.values()) {
			for (IClassLoader loader : CG.getClassHierarchy().getLoaders()) {
				MethodReference function = AstMethodReference
						.fnReference(TypeReference.findOrCreate(loader.getReference(), typeName));
				for (CGNode n : CG.getNodes(function)) {
					AstIR ir = (AstIR) n.getIR();
					DefUse du = n.getDU();
					for (int v = 1; v <= ir.getSymbolTable().getMaxValueNumber(); v++) {
						if (du.getUses(v).hasNext()) {
							SSAInstruction inst = du.getUses(v).next();
							if (inst.iindex != -1) {
								for (int i = 0; i < inst.getNumberOfUses(); i++) {
									if (inst.getUse(i) == v) {
										Position pos = ir.getMethod().debugInfo().getOperandPosition(inst.iindex, i);
										if (pos != null) {
											String type = positionToType(pos, false);
											if(type != null) {
												result.add(type);
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}
		return result;
	}

	private CompletableFuture<Object> typesCommand(ExecuteCommandParams params) {
		return CompletableFuture.supplyAsync(() -> {
			String typeName = ((JsonPrimitive)params.getArguments().get(0)).getAsString();
			return getTypesForName(typeName);
		});
	}
					
	private CompletableFuture<Object> callersCommand(ExecuteCommandParams params) {
		return CompletableFuture.supplyAsync(() -> {
			Set<Either<String,SymbolInformation>> result = HashSetFactory.make();
			for(CallGraph CG : languageBuilders.values()) {
				for(IClassLoader loader : CG.getClassHierarchy().getLoaders()) {
					String typeName = ((JsonPrimitive)params.getArguments().get(0)).getAsString();
					MethodReference function = AstMethodReference.fnReference(TypeReference.findOrCreate(loader.getReference(), typeName));
					for(CGNode n : CG.getNodes(function)) {
						CG.getPredNodes(n).forEachRemaining((CGNode caller) -> {
							IClass functionType = caller.getMethod().getDeclaringClass();
							String functionName = functionType.getName().toString();
							if (functionType instanceof AstFunctionClass) {
								AstFunctionClass fun = (AstFunctionClass) functionType;
								String file = fun.getSourcePosition().getURL().toString();
								if (documentSymbols.containsKey(file)) {
									SymbolInformation fs = documentSymbols.get(file).get(functionName);
									result.add(Either.forRight(fs));
									return;
								}
							}
							result.add(Either.forLeft(functionName));
						});
					}
				}
			}
			return result;
		});
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
			public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
				final String cmdString = params.getCommand();
				final WalaCommand cmd = WalaCommand.valueOf(cmdString);
				switch(cmd) {
					case CALLS:
						return callersCommand(params);
					case TYPES:
						return typesCommand(params);
					case FIXES:
						return fix(params);
					default:
						throw new UnsupportedOperationException("The \"" + cmdString + "\" operations is not currently supported by the WALA LSP server.");
				}
			}

			private org.eclipse.lsp4j.Position positionFromJSON(JsonObject o) {
				org.eclipse.lsp4j.Position codeStart = new org.eclipse.lsp4j.Position();
				codeStart.setLine(o.get("line").getAsInt());
				codeStart.setCharacter(o.get("character").getAsInt());
				return codeStart;
			}
			
			private Range rangeFromJSON(JsonObject o) {
				Range range = new Range();
				range.setStart(positionFromJSON(o.get("start").getAsJsonObject()));
				range.setEnd(positionFromJSON(o.get("end").getAsJsonObject()));
				return range;
			}
			
			private CompletableFuture<Object> fix(ExecuteCommandParams params) {
				for (Object o : params.getArguments()) {
					JsonObject d = (JsonObject)o;
					ApplyWorkspaceEditParams editParams = new ApplyWorkspaceEditParams();
					editParams.setLabel("fix");
					WorkspaceEdit edit = new WorkspaceEdit();
					editParams.setEdit(edit);
					TextEdit change = new TextEdit();
					String msg = d.get("message").getAsString();
					change.setNewText(extractFix(msg));
					change.setRange(rangeFromJSON(d.get("range").getAsJsonObject()));
					edit.getChanges().put(d.get("source").getAsString(), Collections.singletonList(change));
					client.applyEdit(editParams);
				}
				
				return CompletableFuture.completedFuture(null);
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

	private static String newline(boolean useMarkdown) {
		return useMarkdown ? "\n\n" : "\n";
	}

	private static String compactName(String name) {
		return name.trim().replaceAll("\\s++", " ");
	}
	
	private <T> void positionToString(Position pos, Map<URL, NavigableMap<Position,T>> map, Set<Pair<String,BiFunction<Boolean, T, String>>> analyses, StringBuffer sb, boolean addLabel, boolean useMarkdown) {
		if (map.containsKey(pos.getURL())) {
			NavigableMap<Position, T> scriptPositions = map.get(pos.getURL());
			Position nearest = getNearest(scriptPositions, pos);

			if (nearest != null) {
				for(Pair<String, BiFunction<Boolean, T,String>> na : analyses) {
					String n = na.fst;
					BiFunction<Boolean, T, String> a = na.snd;
					String s = a.apply(useMarkdown, scriptPositions.get(nearest));
					if (s != null) {
						if(addLabel) {
							if(useMarkdown) {
								sb.append("_");
								sb.append(n);
								sb.append("_: ");
							} else {
								sb.append(n);
								sb.append(": ");
							}
						}
						sb.append(s);
						sb.append(newline(useMarkdown));
					}
				}
			}
		}
	}

	private String positionToType(Position pos, boolean useMarkdown) {
		StringBuffer sb = new StringBuffer();
		final int sblen = sb.length();
		positionToString(pos, values, valueAnalyses, sb, false, useMarkdown);
		if(sb.length() == 0) {
			return null;
		}
		try {
			final String name = new SourceBuffer(getNearest(values.get(pos.getURL()), pos)).toString();
			sb.insert(sblen, name + ": ");
		} catch (IOException e) {
			e.printStackTrace();
		}
		return sb.toString();
	}
	
	private String positionToString(Position pos, boolean useMarkdown) {
		StringBuffer sb = new StringBuffer();
		int sblen = 0;
		positionToString(pos, values, valueAnalyses, sb, true, useMarkdown);
		if(sblen != 0) {
			sblen = sb.length();
		}
		positionToString(pos, instructions, instructionAnalyses, sb, true, useMarkdown);
		if(sb.length() == 0) {
			return "";
		}
		if(sblen > 0) {
			sb.insert(sblen, newline(useMarkdown));
		}

		String name = "";
		try {
			name = new SourceBuffer(getNearest(values.get(pos.getURL()), pos)).toString();
			name = compactName(name);
			if (!name.isEmpty()) {
				if (useMarkdown) {
					name = "```python\n" + name + "\n```\n";
				} else {
					name += "\n";
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

//		sb.append("\n");
		return name + sb.toString();
	}

	public String toString() {
		StringBuffer sb = new StringBuffer("WALA Server: ");
		for(URL script : values.keySet()) {
			sb.append(script + "\n");
			for(Position pos : values.get(script).keySet()) {
				sb.append(pos + ": " + positionToString(pos, false));
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
