package com.ibm.wala.cast.lsp;

import java.io.IOException;
import java.io.Reader;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Consumer;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentItem;

import com.ibm.wala.cast.lsp.WALAServerCore.WALAServerAnalysis;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.cast.tree.impl.AbstractSourcePosition;
import com.ibm.wala.cast.util.SourceBuffer;
import com.ibm.wala.classLoader.Module;
import com.ibm.wala.classLoader.SourceModule;
import com.ibm.wala.util.collections.Pair;

public class TestClientDriver extends ClientDriver {

	public static void main(String[] args) throws IOException {
		TestClientDriver client = new TestClientDriver();
		client.start();
		client.server.shutdown();
	}

	public TestClientDriver() throws IOException {
		super();
	}

	@Override
	protected WALAServerCore createServer() {
		WALAServerCore server = super.createServer();
		server.addAnalysis("testl", new WALAServerAnalysis() {

			@Override
			public String source() {
				return "testa";
			}

			@Override
			public void analyze(Collection<Module> sources, Consumer<AnalysisError> callback) {
				sources.forEach((source) -> {
					URL src = ((SourceModule)source).getURL();
					callback.accept(new AnalysisError() {

						@Override
						public String source() {
							return "testa";
						}

						@Override
						public String toString(boolean useMarkdown) {
							try {
								return "'" + new SourceBuffer(position()).toString() + "' is rubbish!";
							} catch (IOException e) {
								assert false : e;
								return null;
							}
						}

						@Override
						public Position position() {
							return new AbstractSourcePosition() {

								@Override
								public URL getURL() {
									return src;
								}

								@Override
								public Reader getReader() throws IOException {
									return ((SourceModule)source).getInputReader();
								}

								@Override
								public int getFirstLine() {
									return 2;
								}

								@Override
								public int getLastLine() {
									return 3;
								}

								@Override
								public int getFirstCol() {
									return 4;
								}

								@Override
								public int getLastCol() {
									return 17;
								}

								@Override
								public int getFirstOffset() {
									return -1;
								}

								@Override
								public int getLastOffset() {
									return -1;
								}
							};
						}

						@Override
						public Iterable<Pair<Position, String>> related() {
							return Collections.emptyList();
						}

						@Override
						public DiagnosticSeverity severity() {
							return DiagnosticSeverity.Information;
						}

						@Override
						public Kind kind() {
							return Kind.Diagnostic;
						}

						@Override
						public String repair() {
							return null;
						}
						
					});
				});
			}
			
		});
		return server;
	}

	@Override
	protected void start() {
		super.start();
		
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
		TextDocumentItem doc = new TextDocumentItem();
		doc.setLanguageId("testl");
		doc.setText("this is a very silly file\nbut it does have more than one line\nit even has three lines, so lines 0-2 in LSP-speak");
		doc.setUri("file:///some/fake/file");
		params.setTextDocument(doc);
		server.getTextDocumentService().didOpen(params);
	}

	
}
