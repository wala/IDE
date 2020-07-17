const {AutoLanguageClient} = require('atom-languageclient')
const cp = require('child_process')

class Wala织女LanguageClient extends AutoLanguageClient {
    getGrammarScopes () { return [ "source.java", "java" ] }
    getLanguageName () { return 'Java' }
    getServerName () { return '织女' }

    startServerProcess (projectPath) {
	'-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=6661,quiet=y'
	return cp.spawn('/Library/Java/JavaVirtualMachines/jdk1.8.0_151.jdk/Contents/Home/bin/java', ['-DandroidJar=/Users/dolby/Library/Android/sdk/platforms/android-29/android.jar', '-jar', '/Users/dolby/git/Examples/织女/target/zhinu-0.0.1-SNAPSHOT-织女.jar']);
    }
}

module.exports = new Wala织女LanguageClient()
