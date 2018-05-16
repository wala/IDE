package com.ibm.wala.cast.lsp;

import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;

public interface AnalysisError {
	public String toString(boolean useMarkdown);
	public Position position();
}
