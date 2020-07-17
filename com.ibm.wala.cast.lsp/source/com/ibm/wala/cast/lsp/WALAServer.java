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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
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
import java.util.stream.Collectors;

import org.apache.commons.io.input.TeeInputStream;
import org.apache.commons.io.output.TeeOutputStream;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.eclipse.lsp4j.DiagnosticSeverity;
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
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WillSaveTextDocumentParams;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
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
import com.ibm.wala.cast.loader.AstMethod.DebuggingInformation;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.cast.types.AstMethodReference;
import com.ibm.wala.cast.util.SourceBuffer;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IClassLoader;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Module;
import com.ibm.wala.client.AbstractAnalysisEngine;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.HeapModel;
import com.ibm.wala.ipa.callgraph.propagation.InstanceFieldKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PropagationCallGraphBuilder;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.ParamCallee;
import com.ibm.wala.ipa.slicer.ParamCaller;
import com.ibm.wala.ipa.slicer.SDG;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ipa.slicer.Slicer.ControlDependenceOptions;
import com.ibm.wala.ipa.slicer.Slicer.DataDependenceOptions;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.CancelRuntimeException;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Pair;

public class WALAServer extends WALAServerCore {
	private final Map<URL, NavigableMap<Position,PointerKey>> values = HashMapFactory.make();
	private final Map<URL, NavigableMap<Position,int[]>> instructions = HashMapFactory.make();

	private final Function<String, AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?>> languages;

	private final Map<String, CallGraph> languageBuilders = HashMapFactory.make();

	private final Map<String, Map<String, WalaSymbolInformation>> documentSymbols = HashMapFactory.make();

	private final Set<Pair<String, BiFunction<Boolean, PointerKey,String>>> valueAnalyses = HashSetFactory.make();
	private final Map<String, Map<PointerKey,AnalysisError>> valueErrors = HashMapFactory.make();
	private final Set<Pair<String,BiFunction<Boolean,int[],String>>> instructionAnalyses = HashSetFactory.make();

	private Function<int[],Set<Position>> findDefinitionAnalysis = null;
	
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

	public void addValueErrors(String language, Map<PointerKey,AnalysisError> errors) {
		valueErrors.put(language, errors);
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

	private class WalaSymbolInformation extends SymbolInformation {
		private final MethodReference function;
		private final Position namePosition;
		
		private WalaSymbolInformation(MethodReference function, Position namePosition) {
			this.function = function;
			this.namePosition = namePosition;
		}

		private MethodReference getFunction() {
			return function;
		}	

		public String toString() {
			return super.toString() + "(" + getFunction() + ")";
		}
	}

	public static Map<String, List<Diagnostic>> getDiagnostics(Function<WALAServer, 
	Function<String, AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?>>> languages,
	String language, Map<String, String> uriTextPairs) {
		WALAServer server = new WALAServer(languages);

		for(Entry<String, String> pair : uriTextPairs.entrySet()) {
			final String uri  = pair.getKey();
			final String text = pair.getValue();
			if(!server.addSource(language, uri, new LSPStringModule(uri, text))) {
				return null;
			}
		}

		Map<String, List<Diagnostic>> diags = server.calculateDiagnostics(language);
		return diags;
	}

	public void analyze(String language) {
		Map<String, List<Diagnostic>> diags = calculateDiagnostics(language);
		for(Map.Entry<String,List<Diagnostic>> d : diags.entrySet()) {
			PublishDiagnosticsParams pdp = new PublishDiagnosticsParams();
			if (d.getValue() != null && !d.getValue().isEmpty()) {
				pdp.setUri(Util.unmangleUri(d.getKey()));
				pdp.setDiagnostics(d.getValue());
				client.publishDiagnostics(pdp);
			}
		}
	}

	private Map<String, List<Diagnostic>> calculateDiagnostics(String language) {
		try {
			if (valueErrors.containsKey(language)) {
				valueErrors.get(language).clear();
			}

			AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?> engine = languages.apply(language);

			engine.setModuleFiles(languageSources.get(language).values());
			
			PropagationCallGraphBuilder cgBuilder = (PropagationCallGraphBuilder) engine.defaultCallGraphBuilder();
			CallGraph CG = cgBuilder.makeCallGraph(cgBuilder.getOptions());
			HeapModel H = cgBuilder.getPointerAnalysis().getHeapModel();

			CG.iterator().forEachRemaining((CGNode n) -> { 
				IMethod m = n.getMethod();
				if (m instanceof AstMethod) {
					URL url = ((AstMethod)m).debugInfo().getCodeBodyPosition().getURL();
					if (values.containsKey(url)) {
						values.get(url).clear();
						instructions.get(url).clear();
					}
				}
			});

			for(IClass cls : CG.getClassHierarchy()) {
				if (cls instanceof AstFunctionClass) {
					AstMethod code = ((AstFunctionClass)cls).getCodeBody();
					Position sourcePosition = code.debugInfo().getCodeBodyPosition();
					if (sourcePosition != null) {
						URI documentURI = getPositionUri(sourcePosition);
						if(documentURI != null) {

							WalaSymbolInformation codeSymbol = 
								new WalaSymbolInformation(code.getReference(), code.debugInfo().getCodeNamePosition());
							codeSymbol.setKind(SymbolKind.Function);
							codeSymbol.setName(cls.getName().toString());
							codeSymbol.setLocation(locationFromWALA(sourcePosition));
							codeSymbol.setContainerName(documentURI.toString());
							codeSymbol.setDeprecated(false);
							
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
						if (inst.iIndex() != -1) {
							Position pos = ((AstMethod)M).debugInfo().getInstructionPosition(inst.iIndex());
							if (pos != null) {
								add(pos, new int[] {CG.getNumber(n), inst.iIndex()});
							}
							if (inst.hasDef()) {
								if (pos != null) {
									PointerKey v = H.getPointerKeyForLocal(n, inst.getDef());
									add(pos, v);
								}
							}
							for(int i = 0; i < inst.getNumberOfUses(); i++) {
								Position p = ((AstMethod)M).debugInfo().getOperandPosition(inst.iIndex(), i);
								if (p != null) {
									PointerKey v = H.getPointerKeyForLocal(n, inst.getUse(i));
									add(p, v);
								}
							}
						}
					});

					SSAInformation info = ir.getLocalMap();
					Map<?, CopyPropagationRecord> copyHistory = info.getCopyHistory();
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
			errors: for(AnalysisError e : valueErrors.get(language).values()) {
				Diagnostic d = new Diagnostic();
				// Diagnostics do not currently support markdown
				d.setMessage(e.toString(false));
				
				Position pos = e.position();
				d.setRange(locationFromWALA(pos).getRange());
			
				d.setSource(e.source());
				d.setSeverity(e.severity());
				
				if (supportsRelatedInformation()) {
					Set<DiagnosticRelatedInformation> relList = HashSetFactory.make();

					if (values.containsKey(pos.getURL()) && values.get(pos.getURL()).containsKey(pos)) {
						PointerKey messageVal = values.get(pos.getURL()).get(pos);
						if (messageVal instanceof LocalPointerKey) {
							LocalPointerKey lpk = (LocalPointerKey)messageVal;
							CGNode node = lpk.getNode();
							SSAInstruction def = node.getDU().getDef(lpk.getValueNumber());
							NormalStatement root = new NormalStatement(node, def.iIndex());
							Slicer s = new Slicer();
							Collection<Statement> deps = s.slice(new SDG<InstanceKey>(CG, cgBuilder.getPointerAnalysis(), DataDependenceOptions.FULL, ControlDependenceOptions.NONE), Collections.singleton(root), true);
							for(Statement dep : deps) {
								if (dep.getNode().getMethod() instanceof AstMethod) {
									if ((dep instanceof NormalStatement) || (dep instanceof ParamCaller) || (dep instanceof ParamCallee)) {
										DebuggingInformation debugInfo = ((AstMethod)dep.getNode().getMethod()).debugInfo();
										Position depPos = null;
										if (dep instanceof NormalStatement) {
											depPos = debugInfo.getInstructionPosition(((NormalStatement)dep).getInstructionIndex());
										}  else if (dep instanceof ParamCaller) {
											ParamCaller clr = (ParamCaller) dep;
											int vn = clr.getValueNumber();
											SSAAbstractInvokeInstruction inst = clr.getInstruction();
											for(int i = 0; i < inst.getNumberOfUses(); i++) {
												if (vn == inst.getUse(i)) {
													depPos = debugInfo.getOperandPosition(inst.iIndex(), i);
													break;
												}
											}
										}  else {
											assert dep instanceof ParamCallee;
											ParamCallee cle = (ParamCallee) dep;
											AstMethod m = (AstMethod) cle.getNode().getMethod();
											depPos = m.getParameterPosition(cle.getValueNumber()-1);
										}

										if (depPos != null) {
											DiagnosticRelatedInformation di = new DiagnosticRelatedInformation();
											di.setLocation(locationFromWALA(depPos));
											di.setMessage(new SourceBuffer(depPos).toString().replaceAll("[\\s]*[\\n][\\s]*", " "));
											relList.add(di);
										}
									}
								}
							}
						}
					}

					if (e.related() != null) {
						for (Pair<Position, String> related : e.related()) {
							DiagnosticRelatedInformation di = new DiagnosticRelatedInformation();
							di.setLocation(locationFromWALA(related.fst));
							di.setMessage(related.snd);
							relList.add(di);
						}
					}

					if (! relList.isEmpty()) {
						d.setRelatedInformation(new LinkedList<>(relList));
					}
				}

				String uri = Util.unmangleUri(getPositionUri(pos).toString());
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

			if (supportsRelatedInformation()) {
				CG.forEach((CGNode n) -> {
					if (n.getMethod() instanceof AstMethod) {
						{
							List<DiagnosticRelatedInformation> relList = new LinkedList<>();
							CG.getPredNodes(n).forEachRemaining((CGNode caller) -> {
								if (caller.getMethod() instanceof AstMethod) {
									CG.getPossibleSites(caller, n).forEachRemaining((CallSiteReference site) -> {
										for(SSAAbstractInvokeInstruction call : caller.getIR().getCalls(site)) {
											Position callPos = ((AstMethod)caller.getMethod()).getSourcePosition(call.iIndex());
											DiagnosticRelatedInformation di = new DiagnosticRelatedInformation();
											di.setLocation(locationFromWALA(callPos));
											try {
												di.setMessage("call site " + new SourceBuffer(callPos));
											} catch (IOException e1) {
												di.setMessage("call site");
											}
											relList.add(di);}
									});
								}
							});
							if (! relList.isEmpty()) {
								addInfoDiagnostic(diags, relList, ((AstMethod)n.getMethod()).debugInfo().getCodeNamePosition());
							}
						}
						n.iterateCallSites().forEachRemaining((CallSiteReference site) -> {
							for(SSAAbstractInvokeInstruction inst : n.getIR().getCalls(site)) {
								List<DiagnosticRelatedInformation> relList = new LinkedList<>();
								CG.getPossibleTargets(n, site).forEach((CGNode callee) -> {
									if (callee.getMethod() instanceof AstMethod) {
										Position p = ((AstMethod)callee.getMethod()).getSourcePosition();
										DiagnosticRelatedInformation di = new DiagnosticRelatedInformation();
										di.setLocation(locationFromWALA(p));
										di.setMessage("callee " + callee.getMethod());
										relList.add(di);
									}
								});
								
								if (! relList.isEmpty()) {
									CG.getPredNodes(n).forEachRemaining((CGNode caller) -> {
										if (caller.getMethod() instanceof AstMethod) {
											CG.getPossibleSites(caller, n).forEachRemaining((CallSiteReference callerSite) -> {
												for(SSAAbstractInvokeInstruction call : caller.getIR().getCalls(callerSite)) {
													Position callerPos = ((AstMethod)caller.getMethod()).getSourcePosition(call.iIndex());
													DiagnosticRelatedInformation di = new DiagnosticRelatedInformation();
													di.setLocation(locationFromWALA(callerPos));
													di.setMessage("caller " + caller.getMethod());
													relList.add(di);											
												}
											});
										}
									});

									Position call = ((AstMethod)n.getMethod()).getSourcePosition(inst.iIndex());
									addInfoDiagnostic(diags, relList, call);
								}
							}
						});
					}
				});
			}
			
			return diags;
		} catch (IOException | IllegalArgumentException | CancelException e) {
			assert false : e;
			return null;
		}
	}

	private void addInfoDiagnostic(Map<String, List<Diagnostic>> diags, List<DiagnosticRelatedInformation> relList,
			Position call) {
		Diagnostic d = new Diagnostic();
		Location callPos = locationFromWALA(call);
		d.setRange(callPos.getRange());
		d.setSeverity(DiagnosticSeverity.Information);
		d.setSource("Ariadne");
		d.setMessage("call information");
		d.setRelatedInformation(relList);
		String uri = callPos.getUri();
		if (! diags.containsKey(uri)) {
			diags.put(uri, new LinkedList<>());
		}
		diags.get(uri).add(d);
	}

	private Boolean supportsRelatedInformation() {
		return initializeParams != null && 
			initializeParams.getCapabilities() != null &&
			initializeParams.getCapabilities().getTextDocument() != null &&
			initializeParams.getCapabilities().getTextDocument().getPublishDiagnostics() != null &&
			initializeParams.getCapabilities().getTextDocument().getPublishDiagnostics().getRelatedInformation();
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
		System.err.println("client sent " + params);
		if(this.initializeParams != null) {
			// initialize should only be called once
			client.logMessage(new MessageParams(MessageType.Error, "initialize called multiple times."));
		}
		this.initializeParams = params;
		final ServerCapabilities caps = new ServerCapabilities();
		caps.setHoverProvider(true);
		caps.setTextDocumentSync(TextDocumentSyncKind.Full);
		CodeLensOptions cl = new CodeLensOptions();
		cl.setResolveProvider(false);
		caps.setCodeLensProvider(cl);
		caps.setDocumentSymbolProvider(true);
		caps.setDefinitionProvider(true);
		caps.setReferencesProvider(true);
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

	private String extractFix(String message) {
		return message.substring(message.indexOf("possible fix:")+14, message.length()-1);
	}

	@Override
	public TextDocumentService getTextDocumentService() {
		return new WALATextDocumentService() {

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
					Hover reply = new Hover();
					try {
						String uri = Util.mangleUri(position.getTextDocument().getUri());
						URL url = new URI(uri).toURL();
						Position lookupPos = lookupPos(position.getPosition(), url);
						final String hoverMarkupKind = getHoverFormatRequested();
						final boolean hoverKind = MarkupKind.MARKDOWN.equals(hoverMarkupKind);
						String msg = positionToString(lookupPos, hoverKind);
						
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
						reply.setContents(Collections.singletonList(Either.forLeft("")));
					    return reply;
					}
				});
			}

			@Override
			public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(TextDocumentPositionParams position) {
				return CompletableFuture.supplyAsync(() -> {
					try {
						if (findDefinitionAnalysis == null) {
							return null;
						}
						Position pos = lookupPos(position.getPosition(), new URI(Util.mangleUri(position.getTextDocument().getUri())).toURL());

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
								return Either.forLeft(locs);
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
				return CompletableFuture.supplyAsync(() -> {
					Set<Location> result = HashSetFactory.make();
					org.eclipse.lsp4j.Position pos = params.getPosition();
					String file = Util.mangleUri(params.getTextDocument().getUri());
					if (documentSymbols.containsKey(file)) {
						Collection<WalaSymbolInformation> symbols = documentSymbols.get(file).values();
						for(WalaSymbolInformation symbol : symbols) {
							org.eclipse.lsp4j.Position sp = symbol.getLocation().getRange().getStart();
							if (pos.equals(sp)) {
								for(CallGraph CG : languageBuilders.values()) {
									for(IClassLoader loader : CG.getClassHierarchy().getLoaders()) {
										String typeName = symbol.getName();
										MethodReference function = AstMethodReference.fnReference(TypeReference.findOrCreate(loader.getReference(), typeName));
										for(CGNode symbolNode : CG.getNodes(function)) {
											for(Iterator<CGNode> callerNodes = CG.getPredNodes(symbolNode); callerNodes.hasNext(); ) {
												CGNode callerNode = callerNodes.next();
												if (callerNode.getMethod() instanceof AstMethod) {
													IR callerIR = callerNode.getIR();
													for (Iterator<CallSiteReference> sites = CG.getPossibleSites(callerNode, symbolNode); sites.hasNext(); ) {
														CallSiteReference site = sites.next();
														SSAInstruction inst = callerIR.getCalls(site)[0];
														Position p = ((AstMethod)callerNode.getMethod()).getSourcePosition(inst.iIndex());
														result.add(locationFromWALA(p));
													}
												}
											}
										}
									}
								}
							}
						}
					}
					return new LinkedList<>(result);
				});
			}

			@Override
			public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(
					TextDocumentPositionParams position) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
				return CompletableFuture.supplyAsync(() -> {
					String document = Util.mangleUri(params.getTextDocument().getUri());
					if (! documentSymbols.containsKey(document)) {
						return Collections.emptyList();
					} else {
						List<Either<SymbolInformation, DocumentSymbol>> l = new LinkedList<>();
						for(WalaSymbolInformation s : documentSymbols.get(document).values()) {
							l.add(Either.forLeft(s));
						}
						return l;
					}
				});
			}

			@Override
			public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
				return CompletableFuture.supplyAsync(() -> {
					Range fixRange = params.getRange();
					Command fix = new Command();
					fix.setCommand(WalaCommand.FIXES.toString());
					for(Diagnostic d : params.getContext().getDiagnostics()) {
						if (d.toString().contains("possible fix:")) {
							if (within(d.getRange(), fixRange)) {
								String message = d.getMessage();
								fix.setTitle(extractFix(message));
								List<Object> args = new LinkedList<Object>(params.getContext().getDiagnostics());
								args.add(0, params.getTextDocument().getUri());
								fix.setArguments(args);
								return Collections.singletonList(Either.forLeft(fix));	
							}
						}
					} 
					
					return Collections.emptyList();
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
				cl.setRange(locationFromWALA(sym.namePosition).getRange());
				cl.setData(type);
				result.add(cl);
			}

			public void addCallsCodeLensesForSymbol(WalaSymbolInformation sym, List<CodeLens> result) {
				CodeLens cl = new CodeLens();
				final String command = WalaCommand.CALLS.toString();
				final String title = "callers";
				Command cmd = new Command(title, command);
				cmd.setArguments(Arrays.asList(sym.getFunction().getDeclaringClass().getName().toString()));
				cl.setCommand(cmd);
				cl.setRange(sym.getLocation().getRange());
				cl.setData(title);
				result.add(cl);
			}

			public void addAssignCodeLensesForSymbol(WalaSymbolInformation sym, List<CodeLens> result) {
				final Set<Range> done = HashSetFactory.make();
				final String typeName = sym.getFunction().getDeclaringClass().getName().toString();
				for (CallGraph CG : languageBuilders.values()) {
					for (IClassLoader loader : CG.getClassHierarchy().getLoaders()) {
						MethodReference function = AstMethodReference
								.fnReference(TypeReference.findOrCreate(loader.getReference(), typeName));
						for (CGNode n : CG.getNodes(function)) {
							AstIR ir = (AstIR) n.getIR();
							SSAInstruction[] insts = ir.getInstructions();
							DebuggingInformation debugInfo = ir.getMethod().debugInfo();
							for(int i = 0; i < insts.length; i++) {
								if (insts[i] == null) {
									Position assignPos = debugInfo.getInstructionPosition(i);

									if (assignPos != null) {
										CodeLens cl = new CodeLens();
										final String command = WalaCommand.TYPES.toString();
										final String title = positionToType(assignPos, false);
										if(title == null) {
											continue;
										}
										try {
											String code = new SourceBuffer(assignPos).toString();
											if (! "".equals(title) && code.startsWith(title.substring(0, 1))) {
												Command cmd = new Command(title, command);
												cmd.setArguments(Arrays.asList(typeName));
												cl.setCommand(cmd);
												cl.setRange(locationFromWALA(assignPos).getRange());
												if (! done.contains(cl.getRange())) {
													done.add(cl.getRange());
													result.add(cl);
												}
											}
										} catch (IOException e) {
											// TODO Auto-generated catch block
											e.printStackTrace();
										}
									}
								}
							}
						}
					}
				}
			}

			public void addCodeLensesForSymbol(WalaSymbolInformation sym, List<CodeLens> result) {
				addTypesCodeLensesForSymbol(sym, result);
				addAssignCodeLensesForSymbol(sym, result);
				//addCallsCodeLensesForSymbol(sym, result);
			}

			@Override
			public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
				return CompletableFuture.supplyAsync(() -> {
					List<CodeLens> result = new LinkedList<CodeLens>();
					String document = Util.mangleUri(params.getTextDocument().getUri());
					if (documentSymbols.containsKey(document)) {
						for(WalaSymbolInformation sym : documentSymbols.get(document).values()) {
							addCodeLensesForSymbol(sym, result);
						}
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
					for (int v = 1; v <= ir.getSymbolTable().getNumberOfParameters(); v++) {
						if (du.getUses(v).hasNext()) {
							SSAInstruction inst = du.getUses(v).next();
							if (inst.iIndex() != -1) {
								for (int i = 0; i < inst.getNumberOfUses(); i++) {
									if (inst.getUse(i) == v) {
										Position pos = ir.getMethod().debugInfo().getOperandPosition(inst.iIndex(), i);
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
			Set<Either<String,Location>> result = HashSetFactory.make();
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
									result.add(Either.forRight(fs.getLocation()));
									return;
								}
							}
							result.add(Either.forLeft(functionName));
						});
					}
				}
			}
			
			ShowMessageRequestParams reply = new ShowMessageRequestParams();
			reply.setType(MessageType.Info);
			reply.setMessage(result.toString());
			client.showMessageRequest(reply);

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
				String uri = null;
				for (Object o : params.getArguments()) {
					if (o instanceof JsonObject) {
						JsonObject d = (JsonObject)o;
						ApplyWorkspaceEditParams editParams = new ApplyWorkspaceEditParams();
						editParams.setLabel("fix");
						WorkspaceEdit edit = new WorkspaceEdit();
						editParams.setEdit(edit);
						TextEdit change = new TextEdit();
						String msg = d.get("message").getAsString();
						change.setNewText(extractFix(msg));
						change.setRange(rangeFromJSON(d.get("range").getAsJsonObject()));
						edit.getChanges().put(uri, Collections.singletonList(change));
						client.applyEdit(editParams);
					} else {
						uri = ((JsonPrimitive)o).getAsString();
					}
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

	private boolean within(Range a, Range b) {
		return (a.getStart().getLine() < b.getStart().getLine() ||
				(a.getStart().getLine() == b.getStart().getLine()) &&
				a.getStart().getCharacter() <= b.getStart().getCharacter())
				&&
				(a.getEnd().getLine() > b.getEnd().getLine() ||
						(a.getEnd().getLine() == b.getEnd().getLine() &&
						a.getEnd().getCharacter() >= b.getEnd().getCharacter()));
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
			String name = new SourceBuffer(getNearest(values.get(pos.getURL()), pos)).toString();
			name = compactName(name);
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

	public static WALAServerCore launchOnServerPort(int port, Function<WALAServer, Function<String, AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?>>> languages, boolean runAsDaemon) throws IOException {
		WALAServerCore server = new WALAServer(languages);
		server.launchOnServerPort(port, runAsDaemon);
		return server;
	}

	public static WALAServerCore launchOnStdio(Function<WALAServer, Function<String, AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?>>> languages) throws IOException {
		return launchOnStream(languages, System.in, System.out);
	}

	public static WALAServerCore launchOnStream(Function<WALAServer, 
			Function<String, AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?>>> languages,
			InputStream in,
			OutputStream out) throws IOException {
		WALAServerCore server = new WALAServer(languages);
		server.launchOnStream(in, out);
		return server;
	}

	public static WALAServerCore launchOnClientPort(String hostname, int port, Function<WALAServer, Function<String, AbstractAnalysisEngine<InstanceKey, ? extends PropagationCallGraphBuilder, ?>>> languages) throws IOException {
		WALAServerCore s = new WALAServer(languages);
		s.launchOnClientPort(hostname, port);
		return s;
	}
}
