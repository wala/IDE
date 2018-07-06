package com.ibm.wala.cast.lsp;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import com.ibm.wala.util.collections.HashMapFactory;

public class Util {

	private static int fake = 0;
	private static Map<String,String> nameMapping = HashMapFactory.make();
	private static Map<String,String> nameUnmapping = HashMapFactory.make();
	public static String mangleUri(String uri) {
			try {
				if (nameMapping.containsKey(uri)) {
					return nameMapping.get(uri);
				} else {
					new URI(uri).toURL();
					return uri;
				}
			} catch (MalformedURLException | URISyntaxException | IllegalArgumentException e) {
				String x = "file://fake" + (fake++);
				nameMapping.put(uri, x);
				nameUnmapping.put(x, uri);
				return x;
			}
	}
	public static String unmangleUri(String uri) {
		return (nameUnmapping.containsKey(uri))? nameUnmapping.get(uri): uri;
	}

}
