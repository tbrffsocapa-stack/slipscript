# Linguagem mais completa

## Novo na linguagem
- **Funções anônimas e closures:** `x => x * 2`, `(a, b) => a + b`, `function(x) ... end` (inclusive dentro de chamadas). Funções são valores de verdade: variáveis, listas, mapas, argumentos. `ao_clicar` e `ao_mudar` aceitam lambdas.
- **Classes e objetos:** `class`, `extends`, `init`, `self`, `super`; chamar a classe cria a instância. `type(obj)` devolve o nome da classe, `is_a(obj, Classe)`. Objetos comparam por identidade.
- **Erros:** `try / catch e / finally / end` e `throw valor`. `e` traz `mensagem`, `linha` e `valor`; `throw e` relança. O limite de execução não é capturável.
- `continue` em `for` e `while`.
- **Texto:** `split`, `join`, `replace`, `trim`, `starts_with`, `ends_with`, `repeat`, `pad_left`, `pad_right`, `format`, `fixed`, `char`, `code`, `slice`, `index_of`, `reverse`.
- **Listas e mapas:** `sort` (com comparador), `sort_by`, `map`, `filter`, `reduce`, `find`, `any`, `all`, `each`, `sum`, `unique`, `insert`, `remove`, `copy`, `values`, `entries`, `merge`; `lista + lista` concatena; `round(x, casas)`, `int`, `is_num`.
- **Chamada por ponto em texto e lista:** `"a,b".split(",")`, `lista.map(x => x * 2)`.
- **JSON:** `json_parse`, `json_str` (com opção de indentar). **Data/hora:** `now`, `date_str`, `date_parse`, `date_parts`, `sleep`.
- **Rede:** `http_get`, `http_post`, `http` (HTTPS no Android; 100 requisições por execução; resposta até 1 MB; 10 s/15 s de timeout). O manifest ganhou a permissão `INTERNET`.
- Palavras reservadas novas: `try`, `catch`, `finally`, `throw`, `continue`, `class`, `extends`. Scripts antigos que usavam esses nomes como variável precisam renomeá-los.
- Funções do usuário com o mesmo nome de uma função nativa continuam valendo (a do script tem prioridade).
- Testes: 217 (eram 132), incluindo um servidor HTTP local para a rede.
- Módulos: `use("nome")` aceita `nome.slip` e `nome.sp`.

# Compilar para APK
- **`tools/slip2apk.sh`:** transforma uma pasta (ou um `.slip`/`.sp`) em um APK próprio — com nome, id, versão, ícone, módulos e bibliotecas `.so`. Antes de empacotar, verifica a sintaxe do script e dos módulos.
- **Modo app (`RunnerActivity`):** o APK gerado abre direto no script, sem editor; o IDE fica desligado por manifest placeholders.
- **`SlipCli --check`** e `SlipInterpreter.check()`: só analisa a sintaxe, sem executar.
- **Projeto buildável:** Gradle wrapper (8.9), build de release com assinatura por keystore (variáveis de ambiente ou `-P`), `tools/make-keystore.sh`, workflow do GitHub Actions para gerar APK na nuvem e `BUILD.md`. Exemplo em `examples/apps/contador`.
- **Não verificado:** o build Gradle completo e a execução do APK em aparelho (sem Android SDK no ambiente de desenvolvimento); as classes Android foram compiladas contra o `android.jar` da API 28.

# Correções e otimizações

## Desempenho
- Interpretador reescrito em duas etapas: o código vira uma árvore (AST) uma única vez e é só percorrido depois.
  Antes, o corpo de cada `for`/`while`/função era relido e reinterpretado a cada iteração e a cada chamada.
  Resultado medido (só a execução): fib recursivo ~1130 ms -> ~83 ms; laço de 90 mil iterações ~800 ms -> ~108 ms.
- `for i in range(...)` não monta mais a lista. `return` e `break` não usam mais exceções.
- Funções nativas criadas uma vez (antes, um `switch` de texto + lambda novo a cada variável lida).
- Limite de execução subiu de 100 mil para 1 milhão de instruções (`setStepLimit` ajusta). Recursão: 200 -> 1000 níveis.
- O script roda numa thread separada no app (antes, na thread de interface: congelava a tela). Saída limitada a 100 mil caracteres.

## Bugs corrigidos
- `and`/`or` agora têm curto-circuito; `elseif` só é avaliado se necessário (`if x != null and x.a > 1` quebrava).
- Módulos que se chamam em ciclo derrubavam o app (`StackOverflowError`); agora cada módulo carrega uma vez por execução.
- `return`/`break` fora de função/laço e erros de índice/argumento davam mensagens vazias ou do Java; agora são erros do Slip com número da linha.
- Alterar uma lista dentro do `for` dava `ConcurrentModificationException`.
- `contains(lista, x)` comparava texto da lista; agora compara elementos. `texto[i]` agora devolve texto (era `Character`).
- Valores `Long` do SQL nunca eram `==` a números Slip (`linha.nivel == 8` dava `false`).
- `+=` em texto; `%` e `/=` por zero; comparações com `-0` e `NaN`.
- Barras invertidas e aspas em strings dentro de blocos se perdiam (o corpo era reconstruído como texto).
- Lista/mapa em várias linhas; `it` como nome de variável; `vl<x>=5` sem espaços; mensagem de variável indefinida.
- Erros de sintaxe agora são detectados antes de executar qualquer coisa.
- App: saída produzida antes de um erro não é mais descartada; conexão do SQLite não vaza a cada execução; console sobrevive a rotação.
- SlipCli: acentos corretos em qualquer locale.

## Novo: interface visual
- `vt<tipo>"nome"<> + <>!valor!<> ...` cria elementos de tela (texto, botao, entrada, caixa); o tipo de cada valor define a propriedade
  (cor, texto, tamanho, função = clique, elemento = pai, mapa = propriedades com nome). Detalhes na seção "Interface visual" da SPECIFICACAO.md.
- Elementos se alteram por ponto (`titulo.texto = "x"`); cliques e digitação chamam funções do script; `ui_remover`, `ui_limpar`.
- App: novo painel "Interface" (UiRenderer) com Views reais; o interpretador fica vivo enquanto houver elementos na tela. CLI imprime a árvore.
- Não testado em aparelho: o desenho com Views só foi compilado contra stubs do Android. A lógica (criação, eventos, erros) tem 38 testes.

## Segurança
- Scripts não podem mais escrever/apagar em `slip/libs` (evita criar código nativo carregável).
- `sql()` bloqueia `ATTACH`, `DETACH` e `VACUUM` (escapavam do banco privado).

## Módulos .slip e .sp
- `use("nome")` agora encontra `nome.slip` ou `nome.sp` em `slip/modules` (`.slip` tem prioridade se ambos existirem); `use("nome.sp")` escolhe o arquivo exato.

## Mudanças de comportamento
- Números inteiros aparecem sem `.0` (`15`, não `15.0`); listas/mapas no formato Slip: `[1, "a"]`, `{nome: "Kai"}`.
- Novo: atribuição por índice/chave (`lista[0] = 1`, `mapa.nivel += 1`), `for` sobre texto e mapa, `min`/`max` de lista, `has` em lista.

## Não alterado (decisão sua)
- `native_load("libc.so")` ainda carrega qualquer biblioteca do sistema por nome simples (contradiz "somente slip/libs ou jniLibs" na spec).
- `targetSdk 28` continua (necessário para `dlopen` em `files/`; a Play Store não aceita).
- Testes de FFI (`tests/run_host_tests.sh`) não puderam rodar aqui (sem headers JNI); só as expectativas de formato foram ajustadas.
