package com.ibm.wala.cast.lsp;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Iterator;

import com.ibm.wala.classLoader.Module;
import com.ibm.wala.classLoader.ModuleEntry;
import com.ibm.wala.classLoader.SourceModule;
import com.ibm.wala.util.collections.NonNullSingletonIterator;

public class LSPStringModule implements Module, ModuleEntry, SourceModule {
	private final String fileName;
	private final URL fileURL;
	private final String contents;

	public LSPStringModule(String fileName, String contents) {
		URL url = null;
		String fn = null;
		try {
			url = new URL(fileName);
			fn = fileName;
		} catch (MalformedURLException e) {
			fn = "file://" + (fileName.contains("/")? fileName.substring(fileName.lastIndexOf('/')): fileName);
			try {
				url = new URL(fn);
			} catch (MalformedURLException e1) {
				assert false : e1;
			}
		}
		this.fileName = fn;
		this.fileURL = url;
		this.contents = contents;
	}

	@Override
	public String getName() {
		return fileName;
	}

	@Override
	public boolean isClassFile() {
		return false;
	}

	@Override
	public boolean isSourceFile() {
		return true;
	}

	@Override
	public InputStream getInputStream() {
		return new InputStream() {
			private int i = 0;
			@Override
			public int read() {
				if (i >= contents.getBytes().length) {
					return -1;
				} else {
					return contents.getBytes()[i++];
				}
			}
		};
	}

	@Override
	public boolean isModuleFile() {
		return false;
	}

	@Override
	public Module asModule() {
		return null;
	}

	@Override
	public String getClassName() {
		return null;
	}

	@Override
	public Module getContainer() {
		return null;
	}

	@Override
	public Iterator<? extends ModuleEntry> getEntries() {
		return new NonNullSingletonIterator<LSPStringModule>(this);
	}

	@Override
	public Reader getInputReader() {
		return new InputStreamReader(getInputStream());
	}

	@Override
	public URL getURL() {
		return fileURL;
	}
}

