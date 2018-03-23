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
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.IntSupplier;

import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import com.ibm.wala.cast.loader.AstMethod;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.HeapModel;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;

public class WALAServer implements LanguageClientAware, LanguageServer {
	private final Set<LanguageClient> clients = HashSetFactory.make();
	private final Map<URL, NavigableMap<Position,PointerKey>> values = HashMapFactory.make();
	private final Map<URL, NavigableMap<Position,int[]>> instructions = HashMapFactory.make();

	private final Set<Function<PointerKey,String>> valueAnalyses = HashSetFactory.make();
	private final Set<Function<int[],String>> instructionAnalyses = HashSetFactory.make();
	
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
	
	private void add(Position p, PointerKey v) {
		URL url = p.getURL();
		ensureUrlEntry(url, values).put(p, v);
	}

	private void add(Position p, int[] v) {
		URL url = p.getURL();
		ensureUrlEntry(url, instructions).put(p, v);
	}

	public void addValueAnalysis(Function<PointerKey,String> analysis) {
		valueAnalyses.add(analysis);
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
	
	public WALAServer(CallGraph CG, HeapModel H) {
		CG.iterator().forEachRemaining((CGNode n) -> { 
			IMethod M = n.getMethod();
			if (M instanceof AstMethod) {
			IR ir = n.getIR();
			ir.iterateAllInstructions().forEachRemaining((SSAInstruction inst) -> {
				Position pos = ((AstMethod)M).debugInfo().getInstructionPosition(inst.iindex);
				if (pos != null) {
					add(pos, new int[] {CG.getNumber(n), inst.iindex});
				}
				if (inst.hasDef()) {
					PointerKey v = H.getPointerKeyForLocal(n, inst.getDef());
					if (M instanceof AstMethod) {
						if (pos != null) {
							add(pos, v);
						}
					}
				}
			});
			}
		});
	}

	@Override
	public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
		InitializeResult v = new InitializeResult();
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
		// TODO Auto-generated method stub

	}

	private Position lookupPos(org.eclipse.lsp4j.Position pos, URL url) {
		return new Position() {

			@Override
			public int getFirstLine() {
				return pos.getLine();
			}

			@Override
			public int getLastLine() {
				return pos.getLine();
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
			
		};
	}

	@Override
	public TextDocumentService getTextDocumentService() {
		return new TextDocumentService() {

			@Override
			public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
					TextDocumentPositionParams position) {
				// TODO Auto-generated method stub
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
						Hover reply = new Hover();
						reply.setContents(Collections.singletonList(Either.forLeft(msg)));
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
				// TODO Auto-generated method stub
				
			}

			@Override
			public void didChange(DidChangeTextDocumentParams params) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void didClose(DidCloseTextDocumentParams params) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void didSave(DidSaveTextDocumentParams params) {
				// TODO Auto-generated method stub
				
			}	
		};
	}

	@Override
	public WorkspaceService getWorkspaceService() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void connect(LanguageClient client) {
		clients.add(client);
	}

	private String positionToString(Position pos) {
		StringBuffer sb = new StringBuffer(pos.toString());
	
		for(Function<int[],String> a : instructionAnalyses) {
			String s = a.apply(instructions.get(pos.getURL()).get(pos));
			if (s != null) {
				sb.append(" :" + s);
			}	
		}
		if (values.containsKey(pos.getURL()) && values.get(pos.getURL()).containsKey(pos)) {
			for(Function<PointerKey,String> a : valueAnalyses) {
				String s = a.apply(values.get(pos.getURL()).get(pos));
				if (s != null) {
					sb.append(" :" + s);
				}
			}
		}
		sb.append("\n");
		return sb.toString();
	}

	public String toString() {
		StringBuffer sb = new StringBuffer("WALA Server:\n");
		for(URL script : instructions.keySet()) {
			for(Map.Entry<Position, int[]> v : instructions.get(script).entrySet()) {
				Position pos = v.getKey();
				sb.append(positionToString(pos));
			}
		}
		return sb.toString();
	}
	
	public static void main(String[] args) {
		// TODO Auto-generated method stub

	}

}
