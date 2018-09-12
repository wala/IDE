(require 'package)
(add-to-list
 'package-archives
 '("melpa-stable" . "https://stable.melpa.org/packages/"))
(package-initialize)
(package-install 'eglot)
(require 'eglot)
(custom-set-variables
 ;; custom-set-variables was added by Custom.
 ;; If you edit it by hand, you could mess it up, so be careful.
 ;; Your init file should contain only one such instance.
 ;; If there is more than one, they won't work right.
 '(package-selected-packages (quote (eglot markdown-mode flycheck dash-functional))))
(custom-set-faces
 ;; custom-set-faces was added by Custom.
 ;; If you edit it by hand, you could mess it up, so be careful.
 ;; Your init file should contain only one such instance.
 ;; If there is more than one, they won't work right.
 )

(add-to-list 'eglot-server-programs '(python-mode . ("/Library/Java/JavaVirtualMachines/jdk1.8.0_151.jdk/Contents/Home/bin/java" "-jar" "/Users/dolby/git/ML/com.ibm.wala.cast.python.ml/target/com.ibm.wala.cast.python.ml-0.0.1-SNAPSHOT.jar" "--mode" "stdio")))
