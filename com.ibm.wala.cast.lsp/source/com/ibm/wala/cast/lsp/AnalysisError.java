package com.ibm.wala.cast.lsp;

import org.eclipse.lsp4j.DiagnosticSeverity;

import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.util.collections.Pair;

public interface AnalysisError {
	public String toString(boolean useMarkdown);
	public Position position();
	public Iterable<Pair<Position,String>> related();
	public DiagnosticSeverity severity();
}
