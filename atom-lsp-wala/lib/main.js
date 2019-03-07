const {AutoLanguageClient} = require('atom-languageclient')
const cp = require('child_process')

class WalaPythonLanguageClient extends AutoLanguageClient {
    getGrammarScopes () { return [ "source.python", "python" ] }
    getLanguageName () { return 'Python' }
    getServerName () { return 'CodeBreaker' }

    startServerProcess (projectPath) {
	return cp.spawn('/Library/Java/JavaVirtualMachines/jdk1.8.0_181.jdk/Contents/Home/bin/java', ["-cp", "/Users/dolby/git/code_knowledge_graph/code_breaker/target/CodeBreaker-0.0.1-SNAPSHOT.jar", "com.ibm.wala.cast.lsp.codeBreaker.WALAServerCodeSearch"]);
    }
}

class WalaJavaLanguageClient extends AutoLanguageClient {
    getGrammarScopes () { return [ "source.java", "java" ] }
    getLanguageName () { return 'Java' }
    getServerName () { return 'CogniCrypt' }

    startServerProcess (projectPath) {
	return cp.spawn('/Library/Java/JavaVirtualMachines/jdk1.8.0_151.jdk/Contents/Home/bin/java', ['-jar', '/Users/dolby/WalaWorkspace/SootIntegration/target/SootIntegration-0.0.1-SNAPSHOT.jar']);
    }
}

module.exports = new WalaJavaLanguageClient()
