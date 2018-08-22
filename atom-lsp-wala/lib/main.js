const {AutoLanguageClient} = require('atom-languageclient')
const cp = require('child_process')

class WalaLanguageClient extends AutoLanguageClient {
    getGrammarScopes () { return [ "source.python", "python" ] }
    getLanguageName () { return 'Python' }
    getServerName () { return 'Ariadne' }

    startServerProcess (projectPath) {
	return cp.spawn('/Library/Java/JavaVirtualMachines/jdk1.8.0_151.jdk/Contents/Home/bin/java', ['-Dpython.import.site=false', '-jar', '/Users/dolby/git/ML/com.ibm.wala.cast.python.ml/target/com.ibm.wala.cast.python.ml-0.0.1-SNAPSHOT.jar', '--mode', 'stdio']);
    }
}

module.exports = new WalaLanguageClient()
