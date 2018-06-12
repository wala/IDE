(require 'package)
(add-to-list 'package-archives
             '("melpa-stable" . "https://stable.melpa.org/packages/"))
(package-initialize)

(add-to-list 'load-path "/Users/dolby/git/lsp-mode")
(add-to-list 'load-path "/Users/dolby/git/lsp-ui")

(require 'lsp-mode)
(require 'lsp-ui)

(lsp-define-stdio-client
 lsp-python-mode
 "python"
 (lambda () "/Users/dolby/git/ML/com.ibm.wala.cast.python.test/data")
 '("/Library/Java/JavaVirtualMachines/jdk1.8.0_151.jdk/Contents/Home/bin/java" "-cp" "/Users/dolby/git/ML/com.ibm.wala.cast.python.ml/target/com.ibm.wala.cast.python.ml-0.0.1-SNAPSHOT.jar" "com.ibm.wala.cast.python.ml.driver.PythonDriver"))

(add-hook 'python-mode-hook 'lsp-python-mode-enable)
(add-hook 'python-mode-hook 'flycheck-mode)
(add-hook 'lsp-mode-hook 'lsp-ui-mode)
