# Especificação Slip 1.0 — decisões da primeira versão

Este documento fixa uma sintaxe inicial coerente para os comandos criados nesta edição. As palavras reservadas em inglês foram mantidas para facilitar leitura por quem programa; os comandos curtos originais do projeto continuam existindo.

## Núcleo da linguagem

| Recurso | Sintaxe |
|---|---|
| Declarar variável | `vl<nome> = expressão` |
| Exibir na interface/console | `ut<>!expressão!<>` |
| Registrar arquivo no projeto | `it<caminho>` |
| Condição | `if condição then ... elseif ... else ... end` |
| Laço condicional | `while condição do ... end` |
| Laço por coleção | `for item in coleção do ... end` |
| Função | `function nome(parametros) ... end` |
| Retornar | `return expressão` |
| Módulo | `use("nome")` |
| SQL | `sql("instrução SQL")` |
| Injeção no runtime | `inject("nome", valor)` |

## Tipos dinâmicos

A versão 1.0 usa tipagem dinâmica: `number`, `string`, `bool`, `list`, `map`, `function` e `null`. A função `type(valor)` informa o tipo. Conversões explícitas: `str(valor)` e `num(valor)`.

## Operadores

- Matemática: `+ - * / %`
- Comparação: `== != > < >= <=`
- Lógicos: `and or not`, também `&& || !`
- Atribuição: `= += -= *= /=`
- Índices: `lista[0]`
- Propriedades de mapa: `objeto.chave`

## Comandos e funções nativas

- Texto/valores: `ut`, `type`, `str`, `num`, `len`, `upper`, `lower`, `contains` (mais a biblioteca de texto, listas, JSON, data e rede na seção "Biblioteca")
- Matemática: `PI`, `abs`, `sqrt`, `floor`, `ceil`, `round`, `min`, `max`, `random`
- Coleções: `range`, `push`, `pop`, `keys`, `has`
- Runtime: `inject`, `assert`, `clock`
- Arquivos: `read`, `write`, `append`, `exists`, `delete`
- Projeto: `it`, `use`
- Dados: `sql`
- FFI: `native_load`, `native_bind`, `native_call`, `native_close` (ver `FFI.md`)

## Integração e segurança

`inject` altera o ambiente global do interpretador. `use` carrega outro arquivo `.slip` ou `.sp` da pasta `slip/modules` (sem extensão: tenta `.slip` e depois `.sp`; com extensão explícita: usa esse arquivo). `sql` opera no banco SQLite privado do app. As funções `native_*` carregam código nativo no mesmo processo (somente de `slip/libs` ou `jniLibs`) e não têm sandbox. Os demais comandos acima não recebem permissão para atravessar o sandbox do Android, injetar código em outro processo ou contornar permissões do sistema.

## Fora do escopo 1.0

Interfaces, generics, tipagem estática, sistema de pacotes remoto, compilação nativa, integração direta com APIs de jogos/Android e depurador ainda não fazem parte da linguagem. Não são comandos falsos: estão explicitamente fora do escopo.

## Funções anônimas e closures

```slip
vl<dobro> = x => x * 2                  // um parâmetro
vl<soma> = (a, b) => a + b              // vários (ou () => ... sem nenhum)
vl<f> = function(x)                     // corpo em várias linhas; vale até dentro de uma chamada
    vl<y> = x + 1
    return y * 2
end
```

`=>` só aceita uma **expressão** (atribuição não é expressão: para `+=`, `=` etc. use `function ... end`). Toda função fecha sobre o escopo
onde nasceu, então guarda e altera as variáveis de fora. Cada volta de um `for` tem a sua própria variável do laço.
Funções são valores: podem ir em variáveis, listas, mapas, campos de objetos e ser passadas como argumento.

## Classes e objetos

```slip
class Animal
    function init(nome, som)         // roda ao criar: Animal("Rex", "au")
        self.nome = nome
        self.som = som
    end
    function falar()
        return self.nome + " faz " + self.som
    end
end

class Cao extends Animal
    function init(nome)
        super.init(nome, "au")       // chama o método da classe pai
    end
    function falar()
        return super.falar() + "!"
    end
end

vl<c> = Cao("Rex")
ut<>!c.falar()!<>                    // Rex faz au!
```

- Chamar a classe cria a instância e roda `init` (o do pai, se a classe não tiver). Não há `new`.
- Dentro de um método, `self` é o objeto e `super` procura métodos a partir da classe pai.
- Campos nascem por atribuição (`self.x = 1`) e se leem por ponto ou `obj["x"]`. Métodos são valores: `vl<f> = c.falar` já vem preso ao objeto.
- `type(obj)` devolve o nome da classe; `is_a(obj, Classe)` testa a classe e as ancestrais; `ut<>!obj!<>` mostra `Cao{nome: "Rex"}`.
- `==` entre objetos compara identidade (o mesmo objeto), não o conteúdo.
- Dentro de `class` só cabem `function`. Não há campos estáticos nem herança múltipla.

## Erros: `try`, `catch`, `finally`, `throw`

```slip
try
    vl<d> = json_parse(texto)
    throw {codigo: 7}                // qualquer valor
catch e                              // e = {mensagem, linha, valor}
    ut<>!e.mensagem!<>
finally
    ut<>!"sempre roda"!<>
end
```

`e.mensagem` é o texto do erro, `e.linha` a linha (ou `null`) e `e.valor` o que foi passado ao `throw` (para erros do próprio Slip, o texto).
`throw e` dentro do `catch` relança o erro original. `return`, `break` e `continue` atravessam o `try` normalmente.
O **limite de execução** (laço infinito) não pode ser capturado.

## Controle de fluxo extra

`continue` pula para a próxima volta de `for`/`while`.

## Biblioteca

| Área | Funções |
|---|---|
| Texto | `split(t, sep)`, `join(lista, sep)`, `replace(t, velho, novo)`, `trim`, `starts_with`, `ends_with`, `repeat(t, n)`, `pad_left(t, largura, c)`, `pad_right`, `format("{} e {1}", ...)`, `fixed(n, casas)`, `char(n)`, `code(c)` |
| Texto e lista | `slice(x, ini, fim)` (negativos contam do fim), `index_of(x, item)` (-1 se não achar), `reverse(x)` |
| Listas | `sort(l)`, `sort(l, (a, b) => ...)`, `sort_by(l, chave)`, `map`, `filter`, `reduce(l, f, inicial)`, `find`, `any`, `all`, `each`, `sum`, `unique`, `insert(l, i, v)`, `remove(l, i)`, `copy(x)` |
| Mapas | `keys`, `values`, `entries`, `merge(a, b, ...)`, `remove(m, chave)`, `has` |
| Números | `round(x, casas)`, `int(x)`, `is_num(x)` |
| JSON | `json_parse(texto)`, `json_str(valor)`, `json_str(valor, true)` (indentado) |
| Data/hora | `now()` (ms desde 1970), `date_str(ms, formato, fuso)`, `date_parse(texto, formato, fuso)`, `date_parts(ms, fuso)`, `sleep(ms)` (máx. 5 s) |
| Rede | `http_get(url, cabecalhos)`, `http_post(url, corpo, cabecalhos)`, `http(metodo, url, corpo, cabecalhos)` |
| Classes | `is_a(obj, Classe)` |

Funções que devolvem lista (`sort`, `map`, `filter`, `reverse`, `slice`, `unique`, `merge`) **não alteram** a original; `push`, `insert` e `remove` alteram.
`lista + lista` concatena. Em texto e lista dá para usar ponto: `"a,b".split(",")`, `lista.map(x => x * 2)`.
`map`, `filter`, `find`, `any`, `all` e `each` chamam a função com `(item, índice)`; `reduce` e `sort`, com `(a, b)`.

Formato de data (`SimpleDateFormat`): `yyyy-MM-dd HH:mm:ss` é o padrão. Fuso: `"UTC"`, `"America/Sao_Paulo"`, `"GMT-3"`; sem fuso usa o do aparelho.
`date_parts` devolve `{ano, mes, dia, hora, minuto, segundo, dia_semana (1 = domingo), dia_do_ano}`.

### Rede

`http_*` devolvem `{status, ok, corpo, cabecalhos}` (nomes dos cabeçalhos em minúsculas). Resposta 404/500 **não** é erro: olhe `status`/`ok`.
Falha de conexão, tempo esgotado (10 s para conectar, 15 s para ler) ou resposta acima de 1 MB viram erro do Slip, que `try/catch` captura.
Corpo em mapa/lista vai como JSON (`Content-Type: application/json`); texto vai como `text/plain`. Só `http` e `https`; no Android o
manifest declara `INTERNET` e **HTTP sem criptografia fica bloqueado** (`usesCleartextTraffic="false"`): use `https://`. Máximo de 100 requisições por execução.
Como a rede bloqueia, scripts com `http` só podem rodar fora da thread de interface (o app já executa o script numa thread própria; ao responder a um clique, o
Android pode recusar a chamada com "falha de rede").

```slip
try
    vl<r> = http_get("https://api.exemplo.com/dados")
    if r.ok then
        vl<d> = json_parse(r.corpo)
        ut<>!d.nome!<>
    end
catch e
    ut<>!"sem rede: " + e.mensagem!<>
end
```

## Interface visual (`vt<>`)
Scripts criam elementos de tela com `vt<tipo>"nome"<>`. O nome vira uma variável que guarda o elemento. Cada `+ <>!valor!<>` depois dele
acrescenta uma propriedade, e **o tipo do valor** diz qual:

| Valor | Efeito |
|---|---|
| `"#rgb"`, `"#rrggbb"`, `"#aarrggbb"` | cor. `texto`/`entrada`: 1ª = cor do texto, 2ª = fundo. `botao`: 1ª = fundo, 2ª = texto. `caixa`: fundo |
| outro texto | `texto` (em `entrada`: 1º = dica, 2º = valor; em `caixa`: `"horizontal"`/`"vertical"` = direção) |
| número | `tamanho` do texto (sp) |
| função (nome, sem parênteses) | ação: `ao_clicar` (em `entrada`: `ao_mudar`, recebe o texto digitado) |
| elemento | `pai`: coloca dentro dessa `caixa` |
| mapa | propriedades com nome |

Tipos: `texto` (padrão), `botao`, `entrada`, `caixa`. Propriedades com nome: `texto`, `valor`, `dica`, `cor`, `fundo`, `tamanho`, `raio`,
`margem`, `espaco` (números em dp, exceto `tamanho` em sp), `negrito`, `visivel` (booleanos), `largura`, `altura` (número ou `"cheia"`/`"auto"`),
`alinhar` (`"esquerda"`, `"centro"`, `"direita"`), `direcao`, `ao_clicar`, `ao_mudar`, `pai`.

Depois de criado, o elemento se altera por ponto (`titulo.texto = "Olá"`, `contador.tamanho += 2`), por `vt<>"nome"<> + <>!valor!<>`
(tipo vazio em nome que já existe) e lê-se do mesmo jeito (`campo.valor`, `el.tipo`, `el.nome`, `el.pai`, `el.filhos`).
`ui_remover(el)` apaga um elemento e seus filhos; `ui_limpar()` apaga tudo. Limite: 2000 elementos.
O `+` pode ficar no fim da linha ou no começo da seguinte. Veja `examples/ui.slip`.
