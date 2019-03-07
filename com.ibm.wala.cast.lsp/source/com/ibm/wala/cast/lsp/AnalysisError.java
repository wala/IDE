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

import org.eclipse.lsp4j.DiagnosticSeverity;

import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.util.collections.Pair;

public interface AnalysisError {
	public enum Kind {
		Diagnostic, Hover, CodeLens
	}
	
	default public Kind kind() { return Kind.Diagnostic; };
	public String source();
	public String toString(boolean useMarkdown);
	public Position position();
	public Iterable<Pair<Position,String>> related();
	public DiagnosticSeverity severity();
	default public String repair() { return null; } ;
}
