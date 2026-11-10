# FFI nativa da SlipScript

## Sintaxe (sem mudança de gramática; são funções embutidas)
| Função | Descrição |
|---|---|
| `native_load("libx.so")` | Carrega a biblioteca. Procura primeiro em `files/slip/libs/` (Termux: `<home>/slip/libs/`); se não achar e o nome não tiver `/`, usa o nome simples (lib empacotada no APK em `jniLibs`). Recarregar o mesmo nome devolve a mesma biblioteca. |
| `native_bind(lib, "simbolo", "assinatura")` | Resolve o símbolo (`dlsym`) e devolve uma função Slip chamável. |
| `native_call(lib, "simbolo", "assinatura", args...)` | `native_bind` (com cache) + chamada. |
| `native_close(lib)` | Libera a biblioteca (as demais continuam funcionando). Ao fim de cada execução o app chama `close()` automaticamente. |

Assinatura: `retorno(tipo,tipo,...)`, ex. `"i32(i32,i32)"`. Tipos: `i32 i64 f32 f64 bool str ptr` (parâmetros e retorno), `void` (só retorno), `str_owned` (só retorno: string `malloc`ada que o Slip libera com `free()`).

| Tipo | Slip → C | C → Slip |
|---|---|---|
| i32 / i64 | number inteiro (i32 com checagem de faixa; i64 até 2^53) | number (i64 além de 2^53 gera erro) |
| f32 / f64 | number | number |
| bool | somente `true`/`false` | bool |
| str | texto (UTF-8, sem NUL); `null` → `NULL` | texto UTF-8 copiado; `NULL` → `null`; **não** é liberado (`str_owned` libera) |
| ptr | `null` → `NULL` ou um ponteiro devolvido antes por função nativa; números são recusados | ponteiro opaco (`null` se `NULL`) |

Limites: até 6 argumentos inteiros/ponteiros/texto/bool e 8 decimais por chamada; sem funções variádicas (`printf`), sem structs por valor, sem callbacks. Apenas arm64-v8a e x86_64. Todo número Slip é `Double`, então `somar(10,20)` exibe `30.0`.

## Compilar
**App Android:** o Gradle compila `slipffi` e `slipdemo` via CMake (`app/src/main/cpp`) para `arm64-v8a` e `x86_64` (requer NDK + CMake no SDK Manager).
**Teste em desktop/Termux (JVM):** `./tests/run_host_tests.sh` (JDK + g++/clang++).

Termux, rodando um script:
```
pkg install openjdk-21 clang
JH=$PREFIX/lib/jvm/java-21-openjdk
clang++ -shared -fPIC -std=c++17 -I$JH/include -I$JH/include/linux app/src/main/cpp/slipffi.cpp -o libslipffi.so
clang++ -shared -fPIC -std=c++17 app/src/main/cpp/demo/slipdemo.cpp -o libslipdemo.so
mkdir -p ~/slip/libs && cp libslipdemo.so ~/slip/libs/
javac -encoding UTF-8 -d out app/src/main/java/com/slipscript/ide/{SlipInterpreter,NativeBridge,NativeLibraryManager,SlipCli}.java
java -Dslip.ffi.lib=$PWD/libslipffi.so -cp out com.slipscript.ide.SlipCli --home ~ examples/native.slip
```

## Segurança e limitações
- Código nativo roda no mesmo processo do app, sem sandbox: um crash (SIGSEGV, `abort`) derruba o app/JVM e não pode ser capturado. Só carregue bibliotecas confiáveis. Exceções C++ são convertidas em erro Slip.
- Pointers não são validados (use-after-free é responsabilidade do script/da lib) e não há leitura/escrita de memória a partir do Slip.
- O app usa `targetSdk 28`; em targetSdk mais alto o Android bloqueia executar `.so` gravados em `files/`: nesse caso empacote as libs em `jniLibs`.
