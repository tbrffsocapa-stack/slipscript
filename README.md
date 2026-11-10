# Slip Script — Slip 1.0

Projeto Android nativo em Java + XML. O aplicativo contém editor, botão de execução, exemplos e console. O interpretador é próprio; não executa Java, JavaScript ou Python por baixo.

## O que esta versão implementa

- Variáveis dinâmicas com `vl<nome> = valor` e reatribuição.
- Saída pela sintaxe original `ut<>! ... !<>`, incluindo o formato original sem aspas de fechamento: `ut<>!"texto>!<>`.
- Condicionais `if / elseif / else / end`.
- Laços `for ... in ... do ... end` e `while ... do ... end`.
- Funções, parâmetros e `return`.
- Números, textos, booleanos, `null`, listas e mapas.
- Operações matemáticas, comparações e operadores lógicos.
- Funções nativas de texto, listas, mapas, matemática e validação.
- Arquivos privados do app por `read`, `write`, `append`, `exists` e `delete`.
- **Gera APK:** `tools/slip2apk.sh meu_app` empacota um script Slip como app Android; `./gradlew :app:assembleRelease` gera o IDE. Veja `BUILD.md`.
- Funções anônimas (`x => x * 2`, `function(x) ... end`) e closures; classes com herança (`class A extends B`, `self`, `super`); `try/catch/finally/throw`; `continue`.
- Biblioteca de texto e listas (`split`, `join`, `sort`, `map`, `filter`, `reduce`...), JSON, data/hora e HTTP. Veja `SPECIFICACAO.md`.
- Módulos Slip por `use("nome")`, guardados na pasta privada `slip/modules`, como `.slip` ou `.sp`.
- SQL real por `sql("...")`, usando SQLite do Android no banco privado `slip_data.db`.
- `inject(nome, valor)` injeta/define um valor no ambiente global do interpretador. Não injeta código nativo em outros processos.
- Limite de passos e sandbox de arquivos para evitar laços infinitos e acesso arbitrário ao armazenamento do aparelho.

## Sintaxe da linguagem

### Variáveis e saída

```slip
vl<nome> = "Slip"
vl<versao> = 1.0
ut<>!"Olá, mundo>!<>
ut<>!"Versão: " + versao!<>
nome = "Slip Script"
```

`vl` declara uma variável. Atribuição simples posterior atualiza uma variável já declarada.

### Condições

```slip
vl<pontos> = 75
if pontos >= 90 then
    ut<>!"Excelente!>!<>
elseif pontos >= 60 then
    ut<>!"Aprovado>!<>
else
    ut<>!"Tente novamente>!<>
end
```

### Repetição

```slip
for i in range(1, 6) do
    ut<>!i!<>
end

vl<contador> = 0
while contador < 3 do
    contador += 1
end
```

`range(inicio, fim, passo)` inclui o início e não inclui o fim.

### Funções

```slip
function somar(a, b)
    return a + b
end

vl<resultado> = somar(10, 5)
ut<>!resultado!<>
```

### Listas e mapas

```slip
vl<itens> = ["espada", "escudo", "poção"]
push(itens, "chave")
ut<>!itens[0]!<>

vl<jogador> = {nome: "Kai", nivel: 8}
ut<>!jogador.nome!<>
```

### SQL / SQLite

O banco é privado do aplicativo e persiste entre execuções.

```slip
sql("CREATE TABLE IF NOT EXISTS jogadores (id INTEGER PRIMARY KEY, nome TEXT, nivel INTEGER)")
sql("INSERT INTO jogadores (nome, nivel) VALUES ('Kai', 8)")
vl<linhas> = sql("SELECT id, nome, nivel FROM jogadores")
ut<>!linhas!<>
```

`sql()` aceita SQL SQLite. Consultas `SELECT` retornam uma lista de mapas; comandos de escrita retornam `true`. Não concatene texto não confiável em comandos SQL.

### Arquivos e módulos

```slip
write("dados/score.txt", "150")
vl<score> = read("dados/score.txt")
append("dados/score.txt", "\nnovo registro")
```

Os caminhos são relativos à pasta privada `files/slip` do app. Caminhos absolutos, `..` e barras invertidas são bloqueados.

Crie `files/slip/modules/matematica.slip` (ou `matematica.sp`) e carregue com:

```slip
use("matematica")      // procura matematica.slip e, se não existir, matematica.sp
use("matematica.sp")   // extensão explícita escolhe exatamente esse arquivo
```

Sem extensão, `.slip` tem prioridade quando os dois arquivos existem.

### Integração / injeção no runtime

```slip
inject("sessao_atual", "jogador_01")
ut<>!sessao_atual!<>
```

`inject` coloca um valor no ambiente global do programa. Não contorna permissões do Android e não injeta bibliotecas em outros aplicativos ou processos.

## FFI para bibliotecas `.so`

`native_load`, `native_bind`, `native_call` e `native_close` chamam funções C/C++ de bibliotecas compartilhadas. Veja `FFI.md` e `examples/native.slip`.

## Funções nativas

`type`, `str`, `num`, `len`, `range`, `abs`, `sqrt`, `floor`, `ceil`, `round`, `min`, `max`, `random`, `upper`, `lower`, `contains`, `push`, `pop`, `keys`, `has`, `assert`, `clock`, `inject`, `sql`, `read`, `write`, `append`, `exists`, `delete`, `use`.

A biblioteca estendida (texto, listas, mapas, JSON, data/hora, rede, `is_a`) está tabelada na seção "Biblioteca" de `SPECIFICACAO.md`.

## Limites conhecidos desta edição

Esta é a primeira especificação executável do núcleo Slip, não uma linguagem universal com todos os recursos de C#, Python, SQL e TypeScript. Ainda não há interfaces, generics, tipagem estática, compilador nativo, depurador passo a passo, gerenciamento de pacotes ou acesso direto a APIs de outros apps. O comando `it<...>` registra o caminho informado no manifesto interno do projeto; a importação de arquivos externos pelo seletor Android ainda precisa ser conectada.

A execução respeita o sandbox do app. `inject` não significa injeção de código nativo. Para integrar recursos do Android/Windows, o host precisa expor uma API e solicitar as permissões apropriadas.

## Abrir o projeto

Abra a pasta `SlipScript` como projeto Gradle Android em uma IDE compatível. O projeto usa Android Gradle Plugin 8.7.3, compile SDK 35 e min SDK 23. O código do interpretador foi compilado isoladamente com `javac`; o APK não está incluído neste ZIP.
