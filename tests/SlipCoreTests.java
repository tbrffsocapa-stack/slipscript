import com.slipscript.ide.SlipInterpreter;
import java.io.File;
import java.nio.file.*;
import java.util.*;

/** Testes do núcleo da linguagem (sem JUnit, sem JNI). Uso: tests/run_core_tests.sh */
public class SlipCoreTests {
    static File home;
    static int pass, fail;

    /** Banco falso: devolve Long nas colunas inteiras, como o SQLite do Android. */
    static final SlipInterpreter.Database FAKE_DB = sql -> {
        if (sql.toLowerCase().startsWith("select")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", 1L); r.put("nome", "Kai"); r.put("nivel", 8L);
            rows.add(r);
            return rows;
        }
        return true;
    };

    static List<String> run(String src) {
        List<String> out = new ArrayList<>();
        try (SlipInterpreter vm = new SlipInterpreter(home, out::add, FAKE_DB)) { vm.run(src); }
        catch (RuntimeException | StackOverflowError e) { out.add("ERR:" + (e.getMessage() != null ? e.getMessage() : e.toString())); }
        return out;
    }

    // ---------- interface visual: host que grava o que o interpretador pede
    static final class RecHost implements com.slipscript.ide.SlipUi.Host {
        final List<String> log = new ArrayList<>();
        public void create(int id, String type, int parent, Map<String, Object> props) { log.add("create " + id + " " + type + " pai=" + parent + " " + props); }
        public void update(int id, String prop, Object v) { log.add("update " + id + " " + prop + "=" + v); }
        public void move(int id, int parent) { log.add("move " + id + " pai=" + parent); }
        public void remove(int id) { log.add("remove " + id); }
        public void clear() { log.add("clear"); }
    }
    static final class Ui { SlipInterpreter vm; RecHost host = new RecHost(); List<String> out = new ArrayList<>(); String err; }

    static Ui ui(String src) {
        Ui u = new Ui();
        u.vm = new SlipInterpreter(home, u.out::add);
        u.vm.setUiHost(u.host);
        try { u.vm.run(src); } catch (RuntimeException e) { u.err = e.getMessage(); }
        return u;
    }
    static void uiLog(String name, String src, String... lines) {
        Ui u = ui(src);
        check(name, u.err == null && u.host.log.equals(Arrays.asList(lines)), u.err != null ? u.err : u.host.log + "  (esperado " + Arrays.asList(lines) + ")");
    }
    static void uiErr(String name, String src, String fragment) {
        Ui u = ui(src);
        check(name, u.err != null && u.err.contains(fragment), u.err + "  (esperado erro contendo '" + fragment + "')");
    }

    static void check(String name, boolean ok, Object got) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + got); }
    }
    static void expect(String name, String src, String... lines) {
        List<String> r = run(src);
        check(name, r.equals(Arrays.asList(lines)), r + "  (esperado " + Arrays.asList(lines) + ")");
    }
    static void expectErr(String name, String src, String fragment) {
        List<String> r = run(src);
        boolean ok = !r.isEmpty() && r.get(r.size() - 1).startsWith("ERR:") && r.get(r.size() - 1).contains(fragment);
        check(name, ok, r + "  (esperado erro contendo '" + fragment + "')");
    }

    public static void main(String[] args) throws Exception {
        // pilha grande, como no app e no CLI: a recursão profunda precisa de espaço
        Thread t = new Thread(null, () -> { try { runAll(); } catch (Exception e) { throw new RuntimeException(e); } }, "tests", 64L * 1024 * 1024);
        t.start();
        t.join();
    }

    static void runAll() throws Exception {
        home = Files.createTempDirectory("slip-core").toFile();

        // ---------- núcleo (comportamento que não pode mudar)
        expect("saída e variáveis", "vl<n> = \"Slip\"\nut<>!\"Olá, \" + n!<>", "Olá, Slip");
        expect("formato legado sem aspas de fechamento", "ut<>!\"texto>!<>", "texto");
        expect("legado concatenado", "vl<a> = 1\nut<>!\"a=\" + a + \">!<>", "a=1");
        expect("aritmética e precedência", "ut<>!2 + 3 * 4 - 1!<>\nut<>!(2 + 3) * 4!<>\nut<>!10 % 4!<>\nut<>!7 / 2!<>", "13", "20", "2", "3.5");
        expect("if/elseif/else", "vl<p> = 75\nif p >= 90 then\nut<>!\"A\"!<>\nelseif p >= 60 then\nut<>!\"B\"!<>\nelse\nut<>!\"C\"!<>\nend", "B");
        expect("while com +=", "vl<c> = 0\nwhile c < 3 do\nc += 1\nend\nut<>!c!<>", "3");
        expect("for com range", "vl<t> = 0\nfor i in range(1, 6) do\nt += i\nend\nut<>!t!<>", "15");
        expect("range com passo e negativo", "for i in range(10, 0, -3) do\nut<>!i!<>\nend", "10", "7", "4", "1");
        expect("range de um argumento", "vl<l> = range(3)\nut<>!l!<>", "[0, 1, 2]");
        expect("função e return", "function somar(a, b)\nreturn a + b\nend\nut<>!somar(10, 5)!<>", "15");
        expect("recursão", "function fat(n)\nif n <= 1 then\nreturn 1\nend\nreturn n * fat(n - 1)\nend\nut<>!fat(10)!<>", "3628800");
        expect("closure/escopo", "function mk()\nvl<c> = 0\nfunction inc()\nc += 1\nreturn c\nend\nreturn inc\nend\nvl<f> = mk()\nf()\nut<>!f()!<>", "2");
        expect("return dentro de laço", "function achar(l, x)\nfor v in l do\nif v == x then\nreturn true\nend\nend\nreturn false\nend\nut<>!achar([1,2,3], 2)!<>\nut<>!achar([1,2,3], 9)!<>", "true", "false");
        expect("break", "for i in range(1, 100) do\nif i == 3 then\nbreak\nend\nut<>!i!<>\nend", "1", "2");
        expect("break em while", "vl<i> = 0\nwhile true do\ni += 1\nif i > 2 then\nbreak\nend\nend\nut<>!i!<>", "3");
        expect("listas e mapas", "vl<l> = [\"a\", \"b\"]\npush(l, \"c\")\nut<>!l[2]!<>\nvl<m> = {nome: \"Kai\", nivel: 8}\nut<>!m.nome!<>\nut<>!m[\"nivel\"]!<>\nut<>!len(l)!<>\nut<>!pop(l)!<>", "c", "Kai", "8", "3", "c");
        expect("keys e has", "vl<m> = {a: 1, b: 2}\nut<>!keys(m)!<>\nut<>!has(m, \"a\")!<>\nut<>!has(m, \"z\")!<>", "[\"a\", \"b\"]", "true", "false");
        expect("escapes de texto", "ut<>!\"a\\tb\"!<>\nut<>!\"linha1\\nlinha2\"!<>", "a\tb", "linha1\nlinha2");
        expect("comentários e ;", "# comentário\nvl<a> = 1 // outro\nvl<b> = 2; ut<>!a + b!<>", "3");
        expect("funções de texto", "ut<>!upper(\"ab\")!<>\nut<>!lower(\"AB\")!<>\nut<>!contains(\"banana\", \"nan\")!<>\nut<>!len(\"abc\")!<>", "AB", "ab", "true", "3");
        expect("math", "ut<>!abs(-3)!<>\nut<>!sqrt(16)!<>\nut<>!floor(2.7)!<>\nut<>!ceil(2.1)!<>\nut<>!round(2.5)!<>\nut<>!min(3, 1, 2)!<>\nut<>!max(3, 1, 2)!<>", "3", "4", "2", "3", "3", "1", "3");
        expect("lógicos", "ut<>!true and false!<>\nut<>!true or false!<>\nut<>!not true!<>\nut<>!1 < 2 && 2 < 3!<>\nut<>!!false!<>", "false", "true", "false", "true", "true");
        expect("comparação de textos", "ut<>!\"a\" < \"b\"!<>\nut<>!\"a\" == \"a\"!<>\nut<>!\"a\" != \"b\"!<>", "true", "true", "true");
        expect("type", "ut<>!type(1)!<>\nut<>!type(\"a\")!<>\nut<>!type(true)!<>\nut<>!type(null)!<>\nut<>!type([])!<>\nut<>!type({})!<>\nut<>!type(len)!<>", "number", "string", "bool", "null", "list", "map", "function");
        expect("num e str", "ut<>!num(\"3.5\") + 1!<>\nut<>!str(7) + \"x\"!<>", "4.5", "7x");
        expect("inject", "inject(\"sessao\", \"j1\")\nut<>!sessao!<>", "j1");
        expect("PI", "ut<>!floor(PI * 100)!<>", "314");
        expect("vl com reatribuição", "vl<x> = 1\nx = x + 1\nut<>!x!<>", "2");
        expect("escopo de bloco", "vl<x> = 1\nif true then\nvl<x> = 2\nend\nut<>!x!<>", "1");
        expect("atribuição em bloco altera externo", "vl<x> = 1\nif true then\nx = 5\nend\nut<>!x!<>", "5");

        // ---------- arquivos, módulos, SQL
        expect("arquivos", "write(\"d/a.txt\", \"oi\")\nappend(\"d/a.txt\", \"!\")\nut<>!read(\"d/a.txt\")!<>\nut<>!exists(\"d/a.txt\")!<>\ndelete(\"d/a.txt\")\nut<>!exists(\"d/a.txt\")!<>", "oi!", "true", "false");
        expectErr("caminho absoluto bloqueado", "read(\"/etc/passwd\")", "bloqueado");
        expectErr("caminho com .. bloqueado", "read(\"../x\")", "bloqueado");
        new File(home, "slip/modules").mkdirs();
        Files.write(new File(home, "slip/modules/mat.slip").toPath(), "function dobro(x)\nreturn x * 2\nend\nut<>!\"mat carregado\"!<>".getBytes("UTF-8"));
        expect("módulo", "use(\"mat\")\nut<>!dobro(4)!<>", "mat carregado", "8");
        expect("módulo carrega uma vez só", "use(\"mat\")\nuse(\"mat\")", "mat carregado");
        Files.write(new File(home, "slip/modules/ciclo_a.slip").toPath(), "use(\"ciclo_b\")\nut<>!\"A\"!<>".getBytes("UTF-8"));
        Files.write(new File(home, "slip/modules/ciclo_b.slip").toPath(), "use(\"ciclo_a\")\nut<>!\"B\"!<>".getBytes("UTF-8"));
        expect("módulos cíclicos não estouram a pilha", "use(\"ciclo_a\")", "B", "A");
        Files.write(new File(home, "slip/modules/spmod.sp").toPath(), "function triplo(x)\nreturn x * 3\nend\nut<>!\"sp carregado\"!<>".getBytes("UTF-8"));
        expect("módulo .sp sem extensão no use", "use(\"spmod\")\nut<>!triplo(2)!<>", "sp carregado", "6");
        expect("módulo .sp com extensão explícita", "use(\"spmod.sp\")\nut<>!triplo(3)!<>", "sp carregado", "9");
        Files.write(new File(home, "slip/modules/dupl.slip").toPath(), "ut<>!\"versao slip\"!<>".getBytes("UTF-8"));
        Files.write(new File(home, "slip/modules/dupl.sp").toPath(), "ut<>!\"versao sp\"!<>".getBytes("UTF-8"));
        expect("com .slip e .sp, sem extensão prefere .slip", "use(\"dupl\")", "versao slip");
        expect("com .slip e .sp, extensão explícita escolhe o .sp", "use(\"dupl.sp\")", "versao sp");
        Files.write(new File(home, "slip/modules/sp_a.sp").toPath(), "use(\"sp_b\")\nut<>!\"A\"!<>".getBytes("UTF-8"));
        Files.write(new File(home, "slip/modules/sp_b.sp").toPath(), "use(\"sp_a\")\nut<>!\"B\"!<>".getBytes("UTF-8"));
        expect("módulos .sp cíclicos não estouram a pilha", "use(\"sp_a\")", "B", "A");
        expectErr("módulo .sp com erro cita o módulo", "write(\"modules/ruim2.sp\", \"vl<b> = 1 / 0\")\nuse(\"ruim2\")", "no módulo 'ruim2'");
        expectErr("módulo inexistente", "use(\"nao_existe\")", "módulo não encontrado");
        expectErr("scripts não escrevem em slip/libs", "write(\"libs/x.so\", \"x\")", "slip/libs");
        expectErr("scripts não apagam em slip/libs", "delete(\"libs/x.so\")", "slip/libs");
        expect("sql: Long do banco == número do Slip", "vl<r> = sql(\"SELECT * FROM t\")\nut<>!r[0].nivel == 8!<>\nut<>!r[0].nivel + 1!<>\nut<>!r!<>", "true", "9", "[{id: 1, nome: \"Kai\", nivel: 8}]");
        expect("sql escrita devolve true", "ut<>!sql(\"INSERT INTO t VALUES (1)\")!<>", "true");

        // ---------- bugs corrigidos
        expect("[bug] 'and' com curto-circuito", "vl<m> = null\nif m != null and m.x > 1 then\nut<>!\"sim\"!<>\nelse\nut<>!\"nao\"!<>\nend", "nao");
        expect("[bug] 'or' com curto-circuito", "vl<m> = {x: 5}\nif m.x > 1 or m.y.z then\nut<>!\"ok\"!<>\nend", "ok");
        expect("[bug] elseif só é avaliado se necessário", "vl<m> = null\nif m == null then\nut<>!\"nulo\"!<>\nelseif m.x > 1 then\nut<>!\"x\"!<>\nend", "nulo");
        expect("[bug] += em texto concatena", "vl<s> = \"a\"\ns += \"b\"\ns += 1\nut<>!s!<>", "ab1");
        expect("[bug] atribuição por índice (lista)", "vl<l> = [1, 2, 3]\nl[0] = 9\nl[1] += 5\nut<>!l!<>", "[9, 7, 3]");
        expect("[bug] atribuição por chave (mapa)", "vl<m> = {nivel: 1}\nm.nivel = 2\nm.nivel += 1\nm[\"novo\"] = \"x\"\nut<>!m!<>", "{nivel: 3, novo: \"x\"}");
        expect("[bug] contains em lista é por elemento", "vl<l> = [\"abc\", 2]\nut<>!contains(l, \"a\")!<>\nut<>!contains(l, \"abc\")!<>\nut<>!contains(l, 2)!<>", "false", "true", "true");
        expect("[bug] índice de texto devolve texto", "vl<s> = \"abc\"\nut<>!s[1] == \"b\"!<>\nut<>!type(s[1])!<>", "true", "string");
        expect("[bug] alterar lista durante o for", "vl<l> = [1, 2]\nfor x in l do\npush(l, x)\nend\nut<>!len(l)!<>", "4");
        expect("[bug] lista e mapa em várias linhas", "vl<l> = [\n  1,\n  2,\n]\nvl<m> = {\n  a: 1,\n  b: 2\n}\nut<>!l!<>\nut<>!m!<>\nut<>!max(\n1,\n2\n)!<>", "[1, 2]", "{a: 1, b: 2}", "2");
        expect("[bug] 'it' como nome de variável", "vl<it> = 1\nit = it + 1\nut<>!it!<>", "2");
        expect("it<> ainda registra o arquivo", "it<examples/a.slip>", "Arquivo registrado no projeto: examples/a.slip");
        expect("[bug] vl<x>=5 sem espaços", "vl<x>=5\nut<>!x!<>", "5");
        expect("[bug] barra invertida dentro de blocos", "if true then\nut<>!\"a\\\\b\"!<>\nend", "a\\b");
        expect("[bug] texto com aspas dentro de função", "function f()\nreturn \"diz \\\"oi\\\"\"\nend\nut<>!f()!<>", "diz \"oi\"");
        expect("[bug] números inteiros sem .0", "ut<>!1.0!<>\nut<>!2 * 3!<>\nut<>!0.1 + 0.2!<>\nut<>!10 / 4!<>\nut<>!-0!<>", "1", "6", "0.30000000000000004", "2.5", "0");
        expect("[bug] números grandes sem notação científica", "ut<>!4000000000 * 2!<>", "8000000000");
        expect("for em texto", "for c in \"ab\" do\nut<>!c!<>\nend", "a", "b");
        expect("for em mapa", "for k in {x: 1, y: 2} do\nut<>!k!<>\nend", "x", "y");
        expect("listas aninhadas e ciclos", "vl<l> = [1, [2, \"x\"], {a: null}]\nut<>!l!<>\npush(l, l)\nut<>!len(l)!<>", "[1, [2, \"x\"], {a: null}]", "4");
        expect("str de lista cíclica não estoura", "vl<l> = [1]\npush(l, l)\nut<>!l!<>", "[1, ...]");
        expect("[bug] comparações usam IEEE 754 (-0 e NaN)", "ut<>!-0 >= 0!<>\nut<>!-0 <= 0!<>\nut<>!sqrt(-1) > 1!<>\nut<>!sqrt(-1) < 1!<>", "true", "true", "false", "false");
        expect("recursão de 900 níveis", "function s(n)\nif n == 0 then\nreturn 0\nend\nreturn n + s(n - 1)\nend\nut<>!s(900)!<>", "405450");
        expect("igualdade profunda", "ut<>![1, [2]] == [1, [2]]!<>\nut<>!{a: 1} == {a: 1}!<>\nut<>![1] == [2]!<>", "true", "true", "false");

        // ---------- mensagens de erro
        expectErr("[bug] variável indefinida", "ut<>!naoexiste!<>", "variável não definida: naoexiste");
        expectErr("[bug] atribuir sem declarar", "x = 1", "variável não declarada: x");
        expectErr("[bug] índice fora do limite", "vl<l> = [1]\nut<>!l[5]!<>", "índice fora do limite: 5 (tamanho 1)");
        expectErr("[bug] índice de texto fora do limite", "ut<>!\"ab\"[9]!<>", "índice fora do limite");
        expectErr("[bug] % por zero", "ut<>!5 % 0!<>", "divisão por zero");
        expectErr("[bug] /= por zero", "vl<x> = 5\nx /= 0", "divisão por zero");
        expectErr("divisão por zero", "ut<>!5 / 0!<>", "divisão por zero");
        expectErr("[bug] return fora de função", "return 1", "return fora de função");
        expectErr("[bug] break fora de laço", "break", "break fora de laço");
        expectErr("[bug] argumentos faltando em função nativa", "ut<>!contains(\"a\")!<>", "argumentos inválidos em contains()");
        expectErr("[bug] tipo errado em função nativa", "push(5, 1)", "argumentos inválidos em push()");
        expectErr("[bug] range inválido", "range()", "range espera");
        expectErr("valor não é função", "vl<x> = 1\nx()", "valor não é função");
        expectErr("valor não numérico", "ut<>!\"abc\" * 2!<>", "valor não numérico");
        expectErr("[bug] linha no erro", "vl<a> = 1\nvl<b> = 2\nut<>!a / 0!<>", "(linha 3)");
        expectErr("[bug] linha do erro dentro de função", "function f()\nvl<x> = 1\nreturn x / 0\nend\nf()", "(linha 3)");
        expectErr("[bug] erro dentro de módulo cita o módulo", "write(\"modules/ruim.slip\", \"vl<a> = 1\\nvl<b> = a / 0\")\nuse(\"ruim\")", "no módulo 'ruim'");
        expectErr("texto sem fechamento", "ut<>!\"abc!<>", "texto sem fechamento");
        expectErr("bloco sem end", "if true then\nut<>!1!<>", "bloco sem 'end'");
        expectErr("end solto", "end", "bloco inesperado: end");
        expectErr("caractere inválido", "vl<a> = 1 @", "caractere inválido");
        expect("[bug] erro de sintaxe não executa nada antes", "ut<>!\"antes\"!<>\nif true then", "ERR:Slip: bloco sem 'end' (linha 2)");
        expectErr("limite de execução", "while true do\nend", "limite de execução");
        expectErr("recursão infinita", "function f()\nreturn f()\nend\nf()", "profundidade de recursão");
        expectErr("[bug] expressão profunda não derruba o app", "ut<>!" + "(".repeat(2_000_000) + "1" + ")".repeat(2_000_000) + "!<>", "pilha excedida");


        // ---------- interface visual (vt<>)
        uiLog("[ui] texto com cor e tamanho por tipo de valor", "vt<>\"t\"<> + <>!\"Olá\"!<> + <>!\"#FFF\"!<> + <>!20!<>",
            "create 1 texto pai=0 {texto=Olá, cor=#ffffff, tamanho=20.0}");
        uiLog("[ui] botão: 1ª cor = fundo, 2ª = texto", "vt<botao>\"b\"<> + <>!\"Ok\"!<> + <>!\"#112233\"!<> + <>!\"#ffffff\"!<>",
            "create 1 botao pai=0 {texto=Ok, fundo=#112233, cor=#ffffff}");
        uiLog("[ui] texto: 1ª cor = texto, 2ª = fundo", "vt<>\"t\"<> + <>!\"#111111\"!<> + <>!\"#222222\"!<>",
            "create 1 texto pai=0 {cor=#111111, fundo=#222222}");
        uiLog("[ui] caixa: direção por texto e fundo", "vt<caixa>\"c\"<> + <>!\"horizontal\"!<> + <>!\"#000\"!<>",
            "create 1 caixa pai=0 {direcao=horizontal, fundo=#000000}");
        uiLog("[ui] entrada: 1º texto = dica, 2º = valor", "vt<entrada>\"e\"<> + <>!\"Seu nome\"!<> + <>!\"Kai\"!<>",
            "create 1 entrada pai=0 {dica=Seu nome, valor=Kai}");
        uiLog("[ui] mapa com propriedades nomeadas", "vt<>\"t\"<> + <>!{texto: \"x\", negrito: true, margem: 4, largura: \"cheia\", alinhar: \"centro\"}!<>",
            "create 1 texto pai=0 {texto=x, negrito=true, margem=4.0, largura=cheia, alinhar=centro}");
        uiLog("[ui] elemento como valor = pai; ações viram marcadores", "function f()\nend\nvt<caixa>\"c\"<>\nvt<botao>\"b\"<> + <>!\"Go\"!<> + <>!f!<> + <>!c!<>",
            "create 1 caixa pai=0 {}", "create 2 botao pai=1 {texto=Go, ao_clicar=true}");
        uiLog("[ui] vt<> sem tipo altera elemento existente", "vt<>\"t\"<> + <>!\"a\"!<>\nvt<>\"t\"<> + <>!\"b\"!<> + <>!\"#ff0000\"!<>",
            "create 1 texto pai=0 {texto=a}", "update 1 texto=b", "update 1 cor=#ff0000");
        uiLog("[ui] + no começo e no fim da linha", "vt<>\"t\"<> + <>!\"a\"!<> +\n    <>!20!<>\n    + <>!\"#fff\"!<>",
            "create 1 texto pai=0 {texto=a, tamanho=20.0, cor=#ffffff}");
        uiLog("[ui] atribuição por ponto notifica o host", "vt<>\"t\"<> + <>!\"a\"!<>\nt.texto = 5\nt.cor = \"#abc\"\nt.tamanho = 10\nt.tamanho += 2",
            "create 1 texto pai=0 {texto=a}", "update 1 texto=5", "update 1 cor=#aabbcc", "update 1 tamanho=10.0", "update 1 tamanho=12.0");
        expect("[ui] ler propriedades e metadados", "vt<caixa>\"c\"<>\nvt<>\"t\"<> + <>!\"oi\"!<> + <>!c!<>\nut<>!t.texto!<>\nut<>!t.tipo!<>\nut<>!t.nome!<>\nut<>!len(c.filhos)!<>\nut<>!t.pai == c!<>\nut<>!type(t)!<>\nut<>!t!<>\nut<>!t.cor!<>",
            "oi", "texto", "t", "1", "true", "ui", "<elemento texto \"t\">", "null");
        uiLog("[ui] mover para outra caixa", "vt<caixa>\"a\"<>\nvt<caixa>\"b\"<>\nvt<>\"t\"<> + <>!a!<>\nt.pai = b\nt.pai = null",
            "create 1 caixa pai=0 {}", "create 2 caixa pai=0 {}", "create 3 texto pai=1 {}", "move 3 pai=2", "move 3 pai=0");
        uiLog("[ui] remover apaga os filhos primeiro", "vt<caixa>\"c\"<>\nvt<>\"t\"<> + <>!c!<>\nui_remover(c)\nvt<>\"x\"<>\nui_limpar()",
            "create 1 caixa pai=0 {}", "create 2 texto pai=1 {}", "remove 2", "remove 1", "create 3 texto pai=0 {}", "clear");
        uiErr("[ui] usar elemento removido", "vt<>\"t\"<>\nui_remover(t)\nt.texto = \"x\"", "foi removido");
        uiErr("[ui] tipo desconhecido", "vt<slider>\"s\"<>", "tipo de elemento desconhecido: slider");
        uiErr("[ui] cores demais", "vt<caixa>\"c\"<> + <>!\"#111\"!<> + <>!\"#222\"!<>", "cores demais");
        uiErr("[ui] cor inválida em mapa", "vt<>\"t\"<> + <>!{cor: \"vermelho\"}!<>", "espera uma cor");
        uiErr("[ui] propriedade desconhecida", "vt<>\"t\"<> + <>!{brilho: 3}!<>", "propriedade desconhecida: brilho");
        uiErr("[ui] número fora do intervalo", "vt<>\"t\"<> + <>!{raio: 5000}!<>", "entre 0 e 1000");
        uiErr("[ui] booleano sem nome é recusado", "vt<>\"t\"<> + <>!true!<>", "valor sem significado");
        uiErr("[ui] pai precisa ser caixa", "vt<>\"a\"<>\nvt<>\"b\"<> + <>!a!<>", "só uma caixa");
        uiErr("[ui] ciclo de pais", "vt<caixa>\"a\"<>\nvt<caixa>\"b\"<> + <>!a!<>\na.pai = b", "dentro de si mesmo");
        uiErr("[ui] ação precisa ser função", "vt<botao>\"b\"<>\nb.ao_clicar = 5", "espera uma função");
        uiErr("[ui] nome inválido", "vt<>\"meu nome\"<>", "nome de elemento inválido");
        uiErr("[ui] sem nome", "vt<>5<>", "esperado o nome do elemento");
        uiErr("[ui] erro com linha", "vt<>\"a\"<>\nvt<>\"b\"<> + <>!{raio: -1}!<>", "(linha 2)");
        uiErr("[ui] limite de elementos", "for i in range(0, 2001) do\nvt<>\"x\"<>\nend", "limite de 2000 elementos");

        {   // eventos: clique e digitação
            Ui u = ui("function somar()\nc.texto = num(c.texto) + 1\nend\nfunction digitou(t)\ng.texto = \"Olá, \" + t\nut<>!\"digitou:\" + t!<>\nend\n"
                    + "vt<>\"c\"<> + <>!\"0\"!<>\nvt<botao>\"b\"<> + <>!somar!<>\nvt<entrada>\"e\"<> + <>!digitou!<>\nvt<>\"g\"<>");
            check("[ui] setup dos eventos", u.err == null && u.host.log.size() == 4, u.err + " " + u.host.log);
            u.vm.dispatch(2, "clique", null);
            u.vm.dispatch(2, "clique", null);
            check("[ui] clique chama a função do script", u.host.log.contains("update 1 texto=2") && u.host.log.contains("update 1 texto=1"), u.host.log);
            u.vm.dispatch(3, "mudou", "Kai");
            check("[ui] digitar chama ao_mudar com o texto", u.out.contains("digitou:Kai") && u.host.log.contains("update 4 texto=Olá, Kai"), u.out + " " + u.host.log);
            u.vm.dispatch(3, "mudou", "Kai Silva");
            u.vm.run("ut<>!e.valor!<>");
            check("[ui] valor da entrada acompanha o que foi digitado", u.out.get(u.out.size() - 1).equals("Kai Silva"), u.out);
            u.vm.dispatch(99, "clique", null); // id inexistente é ignorado
            u.vm.dispatch(1, "clique", null);  // sem ação: ignorado
            check("[ui] eventos sem alvo ou sem ação são ignorados", true, "");
        }
        {   // erro dentro de um handler aparece como erro do Slip, com linha
            Ui u = ui("function ruim()\nvl<x> = 1 / 0\nend\nvt<botao>\"b\"<> + <>!ruim!<>");
            String msg = null;
            try { u.vm.dispatch(1, "clique", null); } catch (RuntimeException e) { msg = e.getMessage(); }
            check("[ui] erro em handler vem com mensagem e linha", msg != null && msg.contains("divisão por zero") && msg.contains("(linha 2)"), msg);
        }
        {   // árvore em texto
            Ui u = ui("vt<caixa>\"c\"<> + <>!\"#fff\"!<>\nvt<>\"t\"<> + <>!\"oi\"!<> + <>!c!<>");
            check("[ui] árvore em texto", u.vm.uiTree().equals("caixa \"c\" fundo=\"#ffffff\"\n  texto \"t\" texto=\"oi\"\n"), u.vm.uiTree());
        }
        expect("[ui] sem host os elementos existem em memória", "vt<>\"t\"<> + <>!\"a\"!<>\nt.texto = \"b\"\nut<>!t.texto!<>", "b");
        expect("[ui] 'vt' ainda funciona como nome de variável", "vl<vt> = 3\nvt = vt + 1\nut<>!vt!<>", "4");

        expect("[novo] continue em for", "for i in range(1, 6) do\nif i % 2 == 0 then\ncontinue\nend\nut<>!i!<>\nend", "1", "3", "5");
        expect("[novo] continue em while", "vl<i> = 0\nwhile i < 5 do\ni += 1\nif i == 3 then\ncontinue\nend\nut<>!i!<>\nend", "1", "2", "4", "5");
        expectErr("[novo] continue fora de laço", "continue", "continue fora de laço");
        expect("[novo] lambda com seta", "vl<dobro> = x => x * 2\nut<>!dobro(21)!<>", "42");
        expect("[novo] lambda com vários parâmetros", "vl<soma> = (a, b) => a + b\nut<>!soma(2, 3)!<>", "5");
        expect("[novo] lambda sem parâmetros", "vl<f> = () => \"oi\"\nut<>!f()!<>", "oi");
        expect("[novo] function anônima com corpo em várias linhas", "vl<f> = function(x)\nvl<y> = x + 1\nreturn y * 2\nend\nut<>!f(4)!<>", "10");
        expect("[novo] function anônima dentro de chamada", "function aplica(f, v)\nreturn f(v)\nend\nut<>!aplica(function(x)\nreturn x * 3\nend, 5)!<>", "15");
        expect("[novo] lambda fecha sobre variável e a altera via função interna", "function mk()\nvl<c> = 0\nreturn function()\nc += 1\nreturn c\nend\nend\nvl<f> = mk()\nf()\nut<>!f()!<>", "2");
        expect("[novo] lambda captura a variável do laço", "vl<fs> = []\nfor i in range(3) do\npush(fs, () => i)\nend\nut<>!fs[0]() + fs[1]() + fs[2]()!<>", "3");
        expect("[novo] expressão entre parênteses não vira lambda", "vl<a> = 2\nut<>!(a + 1) * 2!<>\nut<>!(a)!<>", "6", "2");
        expect("[novo] lambda chamada na hora", "ut<>!((x) => x + 1)(1)!<>", "2");
        expect("[novo] split e join", "ut<>!split(\"a,b,c\", \",\")!<>\nut<>!join([\"x\", 1, true], \"-\")!<>\nut<>!split(\"abc\")!<>\nut<>!split(\"a,,b\", \",\")!<>", "[\"a\", \"b\", \"c\"]", "x-1-true", "[\"a\", \"b\", \"c\"]", "[\"a\", \"\", \"b\"]");
        expect("[novo] replace trim starts ends", "ut<>!replace(\"a-b-c\", \"-\", \"+\")!<>\nut<>!\"[\" + trim(\"  oi  \") + \"]\"!<>\nut<>!starts_with(\"banana\", \"ba\")!<>\nut<>!ends_with(\"banana\", \"na\")!<>", "a+b+c", "[oi]", "true", "true");
        expect("[novo] slice de texto e lista", "ut<>!slice(\"abcdef\", 1, 3)!<>\nut<>!slice(\"abcdef\", -2)!<>\nut<>!slice([1,2,3,4], 1)!<>\nut<>!slice([1,2,3,4], 0, -1)!<>\nut<>!slice(\"abc\", 5)!<>", "bc", "ef", "[2, 3, 4]", "[1, 2, 3]", "");
        expect("[novo] index_of repeat pad", "ut<>!index_of(\"banana\", \"nan\")!<>\nut<>!index_of([5,6,7], 7)!<>\nut<>!index_of([5], 9)!<>\nut<>!repeat(\"ab\", 3)!<>\nut<>!pad_left(\"7\", 3, \"0\")!<>\nut<>!pad_right(\"ab\", 4, \".\") + \"|\"!<>", "2", "2", "-1", "ababab", "007", "ab..|");
        expect("[novo] format", "ut<>!format(\"{} tem {} anos\", \"Kai\", 8)!<>\nut<>!format(\"{1}-{0}\", \"a\", \"b\")!<>\nut<>!format(\"{{}} {}\", 1)!<>", "Kai tem 8 anos", "b-a", "{} 1");
        expectErr("[novo] format sem argumentos", "format(\"{} {}\", 1)", "faltam argumentos");
        expect("[novo] fixed round int is_num", "ut<>!fixed(3.14159, 2)!<>\nut<>!round(3.14159, 3)!<>\nut<>!int(-2.7)!<>\nut<>!is_num(\"12\")!<>\nut<>!is_num(\"x\")!<>", "3.14", "3.142", "-2", "true", "false");
        expect("[novo] char e code", "ut<>!code(\"A\")!<>\nut<>!char(66)!<>", "65", "B");
        expect("[novo] métodos em texto e lista com ponto", "ut<>!\"a,b\".split(\",\")!<>\nut<>!\"abc\".upper()!<>\nvl<l> = [3, 1, 2]\nut<>!l.sort()!<>\nut<>!l.len()!<>", "[\"a\", \"b\"]", "ABC", "[1, 2, 3]", "3");
        expectErr("[novo] ponto em texto sem função com esse nome", "\"a\".naoexiste()", "não existe em string");
        expect("[novo] sort padrão e com comparador", "ut<>!sort([3, 1, 2])!<>\nut<>!sort([\"b\", \"a\"])!<>\nut<>!sort([1, 3, 2], (a, b) => b - a)!<>", "[1, 2, 3]", "[\"a\", \"b\"]", "[3, 2, 1]");
        expect("[novo] sort não altera a original", "vl<l> = [2, 1]\nvl<s> = sort(l)\nut<>!l!<>\nut<>!s!<>", "[2, 1]", "[1, 2]");
        expect("[novo] sort_by", "vl<ps> = [{n: \"b\", i: 30}, {n: \"a\", i: 20}]\nut<>!map(sort_by(ps, p => p.i), p => p.n)!<>", "[\"a\", \"b\"]");
        expectErr("[novo] sort de tipos misturados", "sort([1, \"a\"])", "só compara");
        expect("[novo] map filter reduce", "ut<>!map([1, 2, 3], x => x * 2)!<>\nut<>!filter([1, 2, 3, 4], x => x % 2 == 0)!<>\nut<>!reduce([1, 2, 3, 4], (a, b) => a + b)!<>\nut<>!reduce([], (a, b) => a + b, 7)!<>", "[2, 4, 6]", "[2, 4]", "10", "7");
        expect("[novo] map com índice e com função nativa", "ut<>!map([\"a\", \"b\"], (x, i) => x + i)!<>\nut<>!map([\"a\", \"b\"], upper)!<>\nut<>!reduce([3, 9, 2], max)!<>", "[\"a0\", \"b1\"]", "[\"A\", \"B\"]", "9");
        expect("[novo] find any all each sum unique", "ut<>!find([1, 5, 9], x => x > 3)!<>\nut<>!find([1], x => x > 3)!<>\nut<>!any([1, 2], x => x > 1)!<>\nut<>!all([1, 2], x => x > 1)!<>\nvl<t> = 0\neach([1, 2, 3], function(x)\nt += x\nend)\nut<>!t!<>\nut<>!sum([1, 2, 3.5])!<>\nut<>!unique([1, 2, 1, 3, 2])!<>", "5", "null", "true", "false", "6", "6.5", "[1, 2, 3]");
        expect("[novo] insert remove reverse", "vl<l> = [1, 3]\ninsert(l, 1, 2)\nut<>!l!<>\nut<>!remove(l, 0)!<>\nut<>!l!<>\nut<>!reverse([1, 2, 3])!<>\nut<>!reverse(\"abc\")!<>", "[1, 2, 3]", "1", "[2, 3]", "[3, 2, 1]", "cba");
        expect("[novo] mapas: values entries merge remove copy", "vl<m> = {a: 1, b: 2}\nut<>!values(m)!<>\nut<>!entries(m)!<>\nut<>!merge(m, {b: 9, c: 3})!<>\nut<>!remove(m, \"a\")!<>\nut<>!m!<>\nvl<x> = [[1]]\nvl<y> = copy(x)\ny[0][0] = 5\nut<>!x!<>", "[1, 2]", "[[\"a\", 1], [\"b\", 2]]", "{a: 1, b: 9, c: 3}", "1", "{b: 2}", "[[1]]");
        expect("[novo] lista + lista concatena", "ut<>!([1] + [2, 3])!<>\nvl<l> = [1]\nl += [2]\nut<>!l!<>", "[1, 2, 3]", "[1, 2]");
        expect("[novo] try/catch captura erro do Slip", "try\nvl<x> = 1 / 0\ncatch e\nut<>!\"erro: \" + e.mensagem!<>\nut<>!e.linha!<>\nend", "erro: divisão por zero", "2");
        expect("[novo] throw de texto e de mapa", "try\nthrow \"falhou\"\ncatch e\nut<>!e.mensagem!<>\nut<>!e.valor!<>\nend\ntry\nthrow {codigo: 7}\ncatch e\nut<>!e.valor.codigo!<>\nend", "falhou", "falhou", "7");
        expect("[novo] erro dentro de função é capturado fora", "function ruim()\nreturn undefined_x\nend\ntry\nruim()\ncatch e\nut<>!e.mensagem!<>\nend", "variável não definida: undefined_x");
        expect("[novo] finally roda com e sem erro", "try\nut<>!\"a\"!<>\nfinally\nut<>!\"f1\"!<>\nend\ntry\nthrow \"x\"\ncatch\nut<>!\"c\"!<>\nfinally\nut<>!\"f2\"!<>\nend", "a", "f1", "c", "f2");
        expectErr("[novo] sem catch o erro continua e o finally roda", "try\nthrow \"boom\"\nfinally\nut<>!\"limpou\"!<>\nend", "boom");
        expect("[novo] throw e relança o erro original", "try\ntry\nthrow \"interno\"\ncatch e\nthrow e\nend\ncatch outro\nut<>!outro.mensagem!<>\nend", "interno");
        expect("[novo] return dentro de try preserva o valor mesmo com finally que chama função", "function g()\nreturn 1\nend\nfunction f()\ntry\nreturn 42\nfinally\ng()\nend\nend\nut<>!f()!<>", "42");
        expect("[novo] break e continue atravessam try", "for i in range(5) do\ntry\nif i == 1 then\ncontinue\nend\nif i == 3 then\nbreak\nend\nut<>!i!<>\ncatch e\nend\nend", "0", "2");
        expectErr("[novo] limite de execução não é capturado por try", "vl<n> = 0\ntry\nwhile true do\nn += 1\nend\ncatch e\nut<>!\"engoliu\"!<>\nend", "limite de execução");
        expectErr("[novo] try sem catch nem finally", "try\nvl<a> = 1\nend", "precisa de");
        expect("[novo] erro de índice é capturável", "try\nvl<l> = [1]\nut<>!l[5]!<>\ncatch e\nut<>!e.mensagem!<>\nend", "índice fora do limite: 5 (tamanho 1)");
        expect("[novo] classe, init e método", "class Animal\nfunction init(nome, som)\nself.nome = nome\nself.som = som\nend\nfunction falar()\nreturn self.nome + \" faz \" + self.som\nend\nfunction renomear(n)\nself.nome = n\nreturn self\nend\nend\nvl<a> = Animal(\"Rex\", \"au\")\nut<>!a.falar()!<>\nut<>!a.nome!<>", "Rex faz au", "Rex");
        expect("[novo] métodos alteram o objeto", "class Animal\nfunction init(nome, som)\nself.nome = nome\nself.som = som\nend\nfunction falar()\nreturn self.nome + \" faz \" + self.som\nend\nfunction renomear(n)\nself.nome = n\nreturn self\nend\nend\nvl<a> = Animal(\"Rex\", \"au\")\na.renomear(\"Bob\")\nut<>!a.falar()!<>", "Bob faz au");
        expect("[novo] herança e super", "class Animal\nfunction init(nome, som)\nself.nome = nome\nself.som = som\nend\nfunction falar()\nreturn self.nome + \" faz \" + self.som\nend\nfunction renomear(n)\nself.nome = n\nreturn self\nend\nend\nclass Cao extends Animal\nfunction init(nome)\nsuper.init(nome, \"au\")\nself.truques = 0\nend\nfunction falar()\nreturn super.falar() + \"!\"\nend\nend\nvl<c> = Cao(\"Rex\")\nut<>!c.falar()!<>\nut<>!c.truques!<>", "Rex faz au!", "0");
        expect("[novo] herança sem init próprio usa o do pai", "class Animal\nfunction init(nome, som)\nself.nome = nome\nself.som = som\nend\nfunction falar()\nreturn self.nome + \" faz \" + self.som\nend\nfunction renomear(n)\nself.nome = n\nreturn self\nend\nend\nclass Gato extends Animal\nfunction miar()\nreturn self.nome + \" mia\"\nend\nend\nvl<g> = Gato(\"Tom\", \"miau\")\nut<>!g.miar()!<>\nut<>!g.falar()!<>", "Tom mia", "Tom faz miau");
        expect("[novo] type is_a e impressão", "class Animal\nfunction init(nome, som)\nself.nome = nome\nself.som = som\nend\nfunction falar()\nreturn self.nome + \" faz \" + self.som\nend\nfunction renomear(n)\nself.nome = n\nreturn self\nend\nend\nclass Cao extends Animal\nend\nvl<c> = Cao(\"R\", \"au\")\nut<>!type(c)!<>\nut<>!is_a(c, Animal)!<>\nut<>!is_a(c, Cao)!<>\nut<>!is_a(Animal(\"x\", \"y\"), Cao)!<>\nut<>!is_a(5, Animal)!<>\nut<>!c!<>\nut<>!type(Cao)!<>", "Cao", "true", "true", "false", "false", "Cao{nome: \"R\", som: \"au\"}", "class");
        expect("[novo] objetos comparam por identidade", "class Animal\nfunction init(nome, som)\nself.nome = nome\nself.som = som\nend\nfunction falar()\nreturn self.nome + \" faz \" + self.som\nend\nfunction renomear(n)\nself.nome = n\nreturn self\nend\nend\nvl<a> = Animal(\"x\", \"y\")\nvl<b> = Animal(\"x\", \"y\")\nvl<c> = a\nut<>!a == b!<>\nut<>!a == c!<>", "false", "true");
        expect("[novo] objetos em listas e métodos como valores", "class Animal\nfunction init(nome, som)\nself.nome = nome\nself.som = som\nend\nfunction falar()\nreturn self.nome + \" faz \" + self.som\nend\nfunction renomear(n)\nself.nome = n\nreturn self\nend\nend\nvl<l> = [Animal(\"a\", \"1\"), Animal(\"b\", \"2\")]\nut<>!map(l, x => x.falar())!<>\nvl<f> = l[0].falar\nut<>!f()!<>", "[\"a faz 1\", \"b faz 2\"]", "a faz 1");
        expect("[novo] classe com contador e instâncias independentes", "class Cont\nfunction init()\nself.n = 0\nend\nfunction inc()\nself.n += 1\nreturn self\nend\nend\nvl<a> = Cont()\nvl<b> = Cont()\na.inc()\na.inc()\nb.inc()\nut<>!a.n + \",\" + b.n!<>", "2,1");
        expectErr("[novo] classe sem init com argumentos", "class A\nend\nA(1)", "não tem 'init'");
        expectErr("[novo] extends de algo que não é classe", "vl<x> = 1\nclass A extends x\nend", "não é uma classe");
        expectErr("[novo] class só aceita funções", "class A\nvl<x> = 1\nend", "só cabem funções");
        expectErr("[novo] super sem método", "class A\nfunction init()\nend\nend\nclass B extends A\nfunction f()\nsuper.nada()\nend\nend\nB().f()", "super não tem o método");
        expect("[novo] erro em método é capturável com a linha", "class Animal\nfunction init(nome, som)\nself.nome = nome\nself.som = som\nend\nfunction falar()\nreturn self.nome + \" faz \" + self.som\nend\nfunction renomear(n)\nself.nome = n\nreturn self\nend\nend\ntry\nAnimal(\"a\", \"b\").voar()\ncatch e\nut<>!e.mensagem!<>\nend", "valor não é função");
        expect("[novo] json_parse", "vl<d> = json_parse(\"{\\\"a\\\": [1, 2.5, true, null], \\\"b\\\": {\\\"c\\\": \\\"x\\\\ny\\\"}}\")\nut<>!d.a!<>\nut<>!d.b.c!<>", "[1, 2.5, true, null]", "x\ny");
        expect("[novo] json_str", "ut<>!json_str({a: [1, \"x\", true, null], b: {c: 1.5}})!<>\nut<>!json_str(\"oi\\\"\")!<>", "{\"a\":[1,\"x\",true,null],\"b\":{\"c\":1.5}}", "\"oi\\\"\"");
        expect("[novo] json_str bonito", "ut<>!json_str({a: [1], b: {}}, true)!<>", "{\n  \"a\": [\n    1\n  ],\n  \"b\": {}\n}");
        expect("[novo] json ida e volta", "vl<m> = {nome: \"Kai\", itens: [1, 2], ok: true}\nut<>!json_parse(json_str(m)) == m!<>", "true");
        expectErr("[novo] json inválido", "json_parse(\"{a:1}\")", "json inválido");
        expectErr("[novo] json truncado", "json_parse(\"[1, 2\")", "json inválido");
        expect("[novo] json inválido é capturável", "try\njson_parse(\"[\")\ncatch e\nut<>!starts_with(e.mensagem, \"json inválido\")!<>\nend", "true");
        expectErr("[novo] json_str com referência circular", "vl<a> = []\npush(a, a)\njson_str(a)", "referência circular");
        expect("[novo] date_str e date_parts em UTC", "ut<>!date_str(0, \"yyyy-MM-dd HH:mm:ss\", \"UTC\")!<>\nvl<p> = date_parts(86400000 * 365, \"UTC\")\nut<>!p.ano + \"-\" + p.mes + \"-\" + p.dia!<>\nut<>!p.dia_semana!<>", "1970-01-01 00:00:00", "1971-1-1", "6");
        expect("[novo] date_parse", "ut<>!date_parse(\"2026-11-08 12:00:00\", \"yyyy-MM-dd HH:mm:ss\", \"UTC\")!<>\nut<>!date_parse(\"08/11/2026\", \"dd/MM/yyyy\", \"UTC\")!<>", "1794139200000", "1794096000000");
        expectErr("[novo] date_parse inválida", "date_parse(\"xx\", \"yyyy-MM-dd\")", "data inválida");
        expectErr("[novo] formato de data inválido", "date_str(0, \"iiii\")", "formato de data inválido");
        expectErr("[novo] fuso desconhecido", "date_str(0, \"yyyy\", \"Marte/Olimpo\")", "fuso horário desconhecido");
        expect("[novo] now devolve milissegundos", "ut<>!now() > 1700000000000!<>\nut<>!type(date_str(now()))!<>", "true", "string");
        expect("[novo] sleep curto", "sleep(5)\nut<>!\"ok\"!<>", "ok");
        expectErr("[novo] http só http/https", "http_get(\"file:///etc/passwd\")", "só http e https");
        expectErr("[novo] http url inválida", "http_get(\"nao e url\")", "url inválida");
        expectErr("[novo] http GET não leva corpo", "http(\"GET\", \"http://localhost\", \"x\")", "não leva corpo");
        expectErr("[novo] http cabeçalhos precisam ser mapa", "http_get(\"http://localhost\", [1])", "cabeçalhos devem ser um mapa");
        expectErr("[novo] http método inválido", "http(\"TRACE\", \"http://localhost\")", "método não suportado");
        expectErr("[novo] http recusa cabeçalho com quebra de linha", "http_get(\"http://localhost:1\", {\"X\": \"a\\nb\"})", "cabeçalho inválido");
        expect("[novo] falha de rede é capturável", "try\nhttp_get(\"http://127.0.0.1:1/\")\ncatch e\nut<>!starts_with(e.mensagem, \"falha de rede\")!<>\nend", "true");

        {   // servidor HTTP local: GET, POST com JSON, erro 404 e limite de requisições
            com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            srv.createContext("/eco", ex -> {
                byte[] in = ex.getRequestBody().readAllBytes();
                String resp = ex.getRequestMethod() + "|" + new String(in, "UTF-8") + "|" + ex.getRequestHeaders().getFirst("X-Teste") + "|" + ex.getRequestHeaders().getFirst("Content-Type");
                byte[] b = resp.getBytes("UTF-8");
                ex.getResponseHeaders().add("X-Resp", "ola");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            srv.createContext("/json", ex -> {
                byte[] b = "{\"nome\": \"Kai\", \"n\": [1, 2]}".getBytes("UTF-8");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            srv.start();
            String base = "http://127.0.0.1:" + srv.getAddress().getPort();
            try {
                expect("[novo] http_get", "vl<r> = http_get(\"" + base + "/eco\", {\"X-Teste\": \"abc\"})\nut<>!r.status!<>\nut<>!r.ok!<>\nut<>!r.corpo!<>\nut<>!r.cabecalhos[\"x-resp\"]!<>", "200", "true", "GET||abc|null", "ola");
                expect("[novo] http_post com texto", "ut<>!http_post(\"" + base + "/eco\", \"oi\").corpo!<>", "POST|oi|null|text/plain; charset=utf-8");
                expect("[novo] http_post com mapa vira JSON", "ut<>!http_post(\"" + base + "/eco\", {a: 1}).corpo!<>", "POST|{\"a\":1}|null|application/json");
                expect("[novo] http_get + json_parse", "vl<d> = json_parse(http_get(\"" + base + "/json\").corpo)\nut<>!d.nome!<>\nut<>!d.n!<>", "Kai", "[1, 2]");
                expect("[novo] 404 não é erro: status e ok", "vl<r> = http_get(\"" + base + "/nada\")\nut<>!r.status!<>\nut<>!r.ok!<>", "404", "false");
                expect("[novo] http genérico", "ut<>!http(\"put\", \"" + base + "/eco\", \"x\").corpo!<>", "PUT|x|null|text/plain; charset=utf-8");
                expectErr("[novo] limite de 100 requisições por execução", "for i in range(101) do\nhttp_get(\"" + base + "/json\")\nend", "limite de 100 requisições");
            } finally { srv.stop(0); }
        }

        {   // check(): só analisa a sintaxe (usado pelo slip2apk), sem executar nada
            List<String> out = new ArrayList<>();
            String msg = null;
            try (SlipInterpreter vm = new SlipInterpreter(home, out::add)) {
                vm.check("ut<>!\"não pode sair\"!<>\nfunction f(a)\nreturn a\nend\nclass A\nfunction g()\nend\nend\nvl<l> = x => x + 1\ntry\nthrow 1\ncatch e\nend");
                try { vm.check("vl<x> = 1\nif x then\nut<>!x!<>"); } catch (RuntimeException e) { msg = e.getMessage(); }
            }
            check("[novo] check aceita código válido sem executá-lo", out.isEmpty(), out);
            check("[novo] check acusa erro de sintaxe com a linha", msg != null && msg.contains("sem 'end'") && msg.contains("linha 2"), msg);
        }
        System.out.println(pass + " ok, " + fail + " falhas");
        System.exit(fail == 0 ? 0 : 1);
    }
}
