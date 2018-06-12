call plug#begin('~/git')

Plug 'prabirshrestha/async.vim'
Plug 'prabirshrestha/vim-lsp'

call plug#end()


au User lsp_setup call lsp#register_server({
        \ 'name': 'Ariadne',
        \ 'cmd': {server_info->['/Library/Java/JavaVirtualMachines/jdk1.8.0_151.jdk/Contents/Home/bin/java', '-cp', '/Users/dolby/git/ML/com.ibm.wala.cast.python.ml/target/com.ibm.wala.cast.python.ml-0.0.1-SNAPSHOT.jar', 'com.ibm.wala.cast.python.ml.driver.PythonDriver']},
        \ 'whitelist': ['python'],
        \ })
	