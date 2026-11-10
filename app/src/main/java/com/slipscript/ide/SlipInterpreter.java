package com.slipscript.ide;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Interpretador Slip 1.0.
 *
 * Pipeline: texto -> tokens (lex) -> árvore de nós (Parser) -> execução (Node.eval / Node.exec).
 * O código é lido e analisado UMA vez. Laços, funções e blocos só percorrem a árvore já pronta
 * (a versão anterior re-lexava e re-interpretava o corpo a cada iteração e a cada chamada).
 */
@SuppressWarnings("unchecked")
public final class SlipInterpreter implements AutoCloseable {
    public interface Database { Object execute(String sql); }

    private static final int DEFAULT_STEP_LIMIT = 1_000_000;
    private static final int MAX_CALL_DEPTH = 1000;
    private static final int MAX_RANGE = 1_000_000;
    private static final int NORMAL = 0, BREAK = 1, RETURN = 2, CONTINUE = 3; // sinais de controle (sem exceções)
    private static final int MAX_HTTP_CALLS = 100;
    private static final int MAX_TEXT = 10_000_000;
    private static final Object UNSET = new Object();
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    private final File home;
    private final Consumer<String> out;
    private final Database db;
    private final Map<String, Object> global = new LinkedHashMap<>();
    private final Map<String, Fn> builtins = new HashMap<>();
    private final Set<String> loadedModules = new HashSet<>();
    private int steps, stepLimit = DEFAULT_STEP_LIMIT, callDepth;
    private Object retVal;
    private int httpCalls;
    private NativeLibraryManager natives; // FFI: criado sob demanda
    // Interface visual (vt<>): elementos vivos, raiz e quem desenha
    private SlipUi.Host uiHost;
    private final Map<Integer, SlipUi.Node> uiById = new HashMap<>();
    private final List<SlipUi.Node> uiRoot = new ArrayList<>();
    private int uiSeq;

    public SlipInterpreter(File dir, Consumer<String> output) { this(dir, output, null); }

    public SlipInterpreter(File dir, Consumer<String> output, Database database) {
        home = new File(dir, "slip");
        home.mkdirs();
        out = output == null ? s -> {} : output;
        db = database;
        global.put("PI", Math.PI);
        initBuiltins();
        initLib();
    }

    /** Quem desenha os elementos criados com vt<>. Sem host, os elementos existem só em memória. */
    public void setUiHost(SlipUi.Host host) { uiHost = host; }

    /** Árvore de elementos em texto (CLI e testes). */
    public String uiTree() { return SlipUi.tree(uiRoot); }

    /**
     * Entrega um evento da interface ao script: "clique" (ao_clicar) ou "mudou" (entrada digitada; arg = novo texto).
     * Chame sempre da mesma thread que executou run(): o interpretador não é thread-safe.
     */
    public void dispatch(int id, String event, Object arg) {
        SlipUi.Node n = uiById.get(id);
        if (n == null || n.removed) return;
        boolean change = event.equals("mudou");
        if (change) n.props.put("valor", arg == null ? "" : str(arg)); // o campo é do host: sem eco de volta
        Object h = n.handlers.get(change ? "ao_mudar" : "ao_clicar");
        if (!(h instanceof Fn)) return;
        steps = 0; callDepth = 0; retVal = null; httpCalls = 0;
        List<Object> args = new ArrayList<>();
        if (change) args.add(arg);
        try { ((Fn) h).call(args); }
        catch (StackOverflowError so) { throw error("pilha excedida (recursão ou expressão aninhada demais)"); }
    }

    /** Limite de instruções por execução (proteção contra laços infinitos). */
    public void setStepLimit(int limit) { stepLimit = limit; }

    public void run(String source) {
        steps = 0; callDepth = 0; retVal = null; httpCalls = 0;
        loadedModules.clear();
        try {
            execTop(new Parser(source).program(), new Env(null, global));
        } catch (StackOverflowError so) {
            throw error("pilha excedida (recursão ou expressão aninhada demais)");
        }
    }

    /** Só analisa a sintaxe, sem executar nada: lança o erro (com a linha) se o código não compila. */
    public void check(String source) {
        try { new Parser(source).program(); }
        catch (StackOverflowError so) { throw error("pilha excedida (expressão aninhada demais)"); }
    }

    /** Libera as bibliotecas nativas desta execução. Seguro chamar mais de uma vez. */
    @Override public void close() {
        if (natives != null) { natives.close(); natives = null; }
        uiById.clear(); uiRoot.clear();
    }

    private NativeLibraryManager natives() {
        if (natives == null) natives = new NativeLibraryManager(home);
        return natives;
    }

    private static Object ffi(Supplier<Object> s) {
        try { return s.get(); }
        catch (NativeBridge.NativeException e) { throw error("nativo: " + e.getMessage()); }
        catch (UnsatisfiedLinkError e) { throw error("nativo: " + e.getMessage()); }
    }

    // ------------------------------------------------------------------ erros

    /** Erro de Slip. Sem stack trace (barato de criar); o número da linha é preenchido pelo bloco que executa a instrução. */
    private static final class SlipError extends RuntimeException {
        int line; // 0 = ainda sem linha; -1 = sem linha e não deve receber uma
        boolean fatal;     // limite de execução: try/catch não captura
        boolean hasValue;  // veio de 'throw valor'
        Object value;
        SlipError(String message, int line) { super(message, null, false, false); this.line = line; }
        String plain() { return super.getMessage().replaceFirst("^Slip: ", ""); }
        @Override public String getMessage() {
            String m = super.getMessage();
            return line > 0 ? m + " (linha " + line + ")" : m;
        }
    }

    private static SlipError error(String s) { return new SlipError("Slip: " + s, 0); }

    private void tick() {
        if (++steps > stepLimit) { SlipError x = error("limite de execução excedido"); x.fatal = true; throw x; }
    }

    // ------------------------------------------------------------------ valores

    private interface Fn { Object call(List<Object> a); }

    /** Função nativa do Slip: converte erros de argumento do Java em mensagens claras. */
    private static final class Builtin implements Fn {
        final String name; final Fn impl;
        Builtin(String name, Fn impl) { this.name = name; this.impl = impl; }
        @Override public Object call(List<Object> a) {
            try { return impl.call(a); }
            catch (ClassCastException | IndexOutOfBoundsException | NullPointerException | UnsupportedOperationException e) {
                throw error("argumentos inválidos em " + name + "()");
            }
        }
    }

    /** Instância de classe: um mapa de campos que lembra a classe. */
    private static final class SlipObject extends LinkedHashMap<String, Object> {
        final SlipClass cls;
        SlipObject(SlipClass c) { cls = c; }
        @Override public boolean equals(Object o) { return this == o; }
        @Override public int hashCode() { return System.identityHashCode(this); }
    }

    /** Valor do 'catch': mapa {mensagem, linha, valor} que lembra o erro original (para 'throw e'). */
    private static final class ErrMap extends LinkedHashMap<String, Object> {
        final SlipError cause;
        ErrMap(SlipError c) { cause = c; }
    }

    /** 'super' dentro de um método: procura métodos a partir da classe pai. */
    private static final class SuperRef {
        final SlipObject obj; final SlipClass from;
        SuperRef(SlipObject o, SlipClass f) { obj = o; from = f; }
    }

    private final class SlipClass implements Fn {
        final String name; final SlipClass parent; final Env env;
        final Map<String, FuncDef> methods = new LinkedHashMap<>();
        SlipClass(String n, SlipClass p, Env e) { name = n; parent = p; env = e; }
        SlipClass ownerOf(String m) {
            for (SlipClass c = this; c != null; c = c.parent) if (c.methods.containsKey(m)) return c;
            return null;
        }
        /** Chamar a classe cria uma instância e roda o 'init' (próprio ou herdado). */
        @Override public Object call(List<Object> a) {
            SlipObject o = new SlipObject(this);
            SlipClass owner = ownerOf("init");
            if (owner != null) bind(o, owner, owner.methods.get("init")).call(a);
            else if (!a.isEmpty()) throw error("a classe " + name + " não tem 'init', mas recebeu argumentos");
            return o;
        }
    }

    /** Método preso a uma instância: 'self' e 'super' ficam num escopo próprio. */
    private Fn bind(SlipObject obj, SlipClass owner, FuncDef f) {
        Env be = new Env(owner.env);
        be.define("self", obj);
        if (owner.parent != null) be.define("super", new SuperRef(obj, owner.parent));
        return new UserFn(f.params, f.body, be);
    }

    private static boolean yes(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).doubleValue() != 0;
        if (o instanceof String) return !((String) o).isEmpty();
        return true;
    }

    private static double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            String s = ((String) o).trim();
            if (NUMBER.matcher(s).matches()) return Double.parseDouble(s);
        }
        throw error("valor não numérico: " + str(o));
    }

    private static String type(Object o) {
        if (o == null) return "null";
        if (o instanceof Boolean) return "bool";
        if (o instanceof Number) return "number";
        if (o instanceof String) return "string";
        if (o instanceof List) return "list";
        if (o instanceof SlipObject) return ((SlipObject) o).cls.name;
        if (o instanceof SlipClass) return "class";
        if (o instanceof SuperRef) return "super";
        if (o instanceof Map) return "map";
        if (o instanceof Fn) return "function";
        if (o instanceof SlipUi.Node) return "ui";
        return "object";
    }

    /** Igualdade do Slip: números por valor (Long do SQL == Double), listas e mapas em profundidade. */
    private static boolean same(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof SlipObject || b instanceof SlipObject) return false; // objetos: só identidade
        if (a instanceof Number && b instanceof Number) return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        if (a instanceof List && b instanceof List) {
            List<?> x = (List<?>) a, y = (List<?>) b;
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> x = (Map<?, ?>) a, y = (Map<?, ?>) b;
            if (x.size() != y.size()) return false;
            for (Map.Entry<?, ?> en : x.entrySet()) {
                if (!y.containsKey(en.getKey()) || !same(en.getValue(), y.get(en.getKey()))) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    private static String keyOf(Object k) { return k instanceof String ? (String) k : str(k); }

    // ------------------------------------------------------------------ texto

    /** Converte qualquer valor Slip em texto. Números inteiros saem sem ".0"; listas e mapas no formato Slip. */
    static String str(Object o) {
        if (o instanceof String) return (String) o;
        StringBuilder sb = new StringBuilder();
        fmt(sb, o, null);
        return sb.toString();
    }

    static String numStr(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        String s = Double.toString(d);
        return s.indexOf('E') < 0 ? s : new BigDecimal(s).stripTrailingZeros().toString().toLowerCase(Locale.ROOT);
    }

    private static void fmt(StringBuilder sb, Object o, IdentityHashMap<Object, Boolean> seen) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { sb.append((String) o); return; }
        if (o instanceof Double || o instanceof Float) { sb.append(numStr(((Number) o).doubleValue())); return; }
        if (o instanceof Number || o instanceof Boolean) { sb.append(o); return; }
        if (o instanceof SlipClass) { sb.append("<classe ").append(((SlipClass) o).name).append('>'); return; }
        if (o instanceof SuperRef) { sb.append("<super>"); return; }
        if (o instanceof Fn) { sb.append("<função>"); return; }
        if (o instanceof byte[]) { sb.append("<blob ").append(((byte[]) o).length).append(" bytes>"); return; }
        if (o instanceof List || o instanceof Map) {
            if (seen == null) seen = new IdentityHashMap<>();
            if (seen.put(o, Boolean.TRUE) != null) { sb.append("..."); return; }
            if (o instanceof List) {
                sb.append('[');
                boolean first = true;
                for (Object x : (List<?>) o) {
                    if (!first) sb.append(", ");
                    first = false;
                    fmtInner(sb, x, seen);
                }
                sb.append(']');
            } else {
                if (o instanceof SlipObject) sb.append(((SlipObject) o).cls.name);
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> en : ((Map<?, ?>) o).entrySet()) {
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append(en.getKey()).append(": ");
                    fmtInner(sb, en.getValue(), seen);
                }
                sb.append('}');
            }
            seen.remove(o);
            return;
        }
        sb.append(o); // objetos opacos da FFI (biblioteca, ponteiro)
    }

    /** Dentro de listas e mapas, textos aparecem entre aspas. */
    private static void fmtInner(StringBuilder sb, Object x, IdentityHashMap<Object, Boolean> seen) {
        if (x instanceof String) {
            sb.append('"').append(((String) x).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append('"');
        } else fmt(sb, x, seen);
    }

    // ------------------------------------------------------------------ ambiente

    private static final class Env {
        final Env parent;
        Map<String, Object> vars; // criado só quando algo é definido no escopo
        Env(Env parent) { this.parent = parent; }
        Env(Env parent, Map<String, Object> vars) { this.parent = parent; this.vars = vars; }
        Object lookup(String n) {
            for (Env s = this; s != null; s = s.parent) {
                Map<String, Object> m = s.vars;
                if (m != null) {
                    Object v = m.get(n);
                    if (v != null || m.containsKey(n)) return v;
                }
            }
            return UNSET;
        }
        void define(String n, Object v) {
            if (vars == null) vars = new HashMap<>(8);
            vars.put(n, v);
        }
        boolean assign(String n, Object v) {
            for (Env s = this; s != null; s = s.parent) {
                if (s.vars != null && s.vars.containsKey(n)) { s.vars.put(n, v); return true; }
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ léxico

    private static final int T_NUM = 0, T_STR = 1, T_ID = 2, T_OP = 3, T_NL = 4, T_EOF = 5, T_ITPATH = 6;

    private static final class Tok {
        final int type; final String text; final int line;
        Tok(int type, String text, int line) { this.type = type; this.text = text; this.line = line; }
    }

    private static final String[] TWO = {"==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "=>"};

    private static boolean digit(char c) { return c >= '0' && c <= '9'; }

    private static List<Tok> lex(String s) {
        List<Tok> t = new ArrayList<>();
        int line = 1, i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r') { i++; continue; }
            if (c == '\n') { t.add(new Tok(T_NL, "\n", line)); line++; i++; continue; }
            if (c == ';') { t.add(new Tok(T_NL, "\n", line)); i++; continue; }
            if (c == '#' || (c == '/' && i + 1 < n && s.charAt(i + 1) == '/')) {
                while (i < n && s.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                char q = c;
                int startLine = line;
                i++;
                StringBuilder b = new StringBuilder();
                boolean closed = false, legacy = false;
                while (i < n) {
                    char d = s.charAt(i);
                    if (d == q) { i++; closed = true; break; }
                    if (q == '"' && s.startsWith(">!<>", i)) { i += 4; legacy = true; break; } // formato original: "texto>!<>
                    if (d == '\\' && i + 1 < n) {
                        i++;
                        char z = s.charAt(i++);
                        if (z == '\n') line++;
                        b.append(z == 'n' ? '\n' : z == 't' ? '\t' : z);
                        continue;
                    }
                    if (d == '\n') line++;
                    b.append(d);
                    i++;
                }
                if (!closed && !legacy) throw new SlipError("Slip: texto sem fechamento", startLine);
                t.add(new Tok(T_STR, b.toString(), startLine));
                if (legacy) { t.add(new Tok(T_OP, "!", line)); t.add(new Tok(T_OP, "<", line)); t.add(new Tok(T_OP, ">", line)); }
                continue;
            }
            if (digit(c)) {
                int j = i;
                while (i < n && digit(s.charAt(i))) i++;
                if (i + 1 < n && s.charAt(i) == '.' && digit(s.charAt(i + 1))) {
                    i++;
                    while (i < n && digit(s.charAt(i))) i++;
                }
                t.add(new Tok(T_NUM, s.substring(j, i), line));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i++;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                String w = s.substring(j, i);
                boolean lineStart = t.isEmpty() || t.get(t.size() - 1).type == T_NL;
                if (w.equals("it") && lineStart && i < n && s.charAt(i) == '<') {
                    // it<caminho>: o caminho é lido cru até o '>' da mesma linha (aceita ':', '~', espaços...)
                    int e = s.indexOf('>', i), nl = s.indexOf('\n', i);
                    if (e > 0 && (nl < 0 || e < nl)) {
                        t.add(new Tok(T_ITPATH, s.substring(i + 1, e).trim(), line));
                        i = e + 1;
                        continue;
                    }
                }
                t.add(new Tok(T_ID, w, line));
                continue;
            }
            if (i + 1 < n) {
                String two = s.substring(i, i + 2);
                boolean hit = false;
                for (String o : TWO) if (o.equals(two)) { hit = true; break; }
                if (hit) { t.add(new Tok(T_OP, two, line)); i += 2; continue; }
            }
            if ("+-*/%=<>!()[]{}.,:".indexOf(c) >= 0) { t.add(new Tok(T_OP, String.valueOf(c), line)); i++; continue; }
            throw new SlipError("Slip: caractere inválido: " + c, line);
        }
        t.add(new Tok(T_EOF, "", line));
        return t;
    }

    // ------------------------------------------------------------------ árvore

    private abstract class Node {
        int line;
        Object eval(Env e) { throw new IllegalStateException("não é expressão"); }
        int exec(Env e) { eval(e); return NORMAL; }
    }

    private final class Const extends Node {
        final Object value;
        Const(Object v) { value = v; }
        @Override Object eval(Env e) { return value; }
    }

    private final class Var extends Node {
        final String name;
        Var(String n) { name = n; }
        @Override Object eval(Env e) {
            Object v = e.lookup(name);
            if (v != UNSET) return v;
            Fn b = builtins.get(name);
            if (b != null) return b;
            throw error("variável não definida: " + name);
        }
    }

    private final class ListLit extends Node {
        final Node[] items;
        ListLit(Node[] items) { this.items = items; }
        @Override Object eval(Env e) {
            List<Object> l = new ArrayList<>(items.length);
            for (Node n : items) l.add(n.eval(e));
            return l;
        }
    }

    private final class MapLit extends Node {
        final String[] keys; final Node[] values;
        MapLit(String[] k, Node[] v) { keys = k; values = v; }
        @Override Object eval(Env e) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < keys.length; i++) m.put(keys[i], values[i].eval(e));
            return m;
        }
    }

    private int index(Object k, int size) {
        double d = num(k);
        int i = (int) d;
        if (d != i) throw error("o índice deve ser inteiro: " + numStr(d));
        if (i < 0 || i >= size) throw error("índice fora do limite: " + i + " (tamanho " + size + ")");
        return i;
    }

    private Object getIndex(Object o, Object k) {
        if (o instanceof List) { List<?> l = (List<?>) o; return l.get(index(k, l.size())); }
        if (o instanceof Map) return ((Map<?, ?>) o).get(keyOf(k));
        if (o instanceof String) { String s = (String) o; return String.valueOf(s.charAt(index(k, s.length()))); }
        if (o instanceof SlipUi.Node) return ((SlipUi.Node) o).get(keyOf(k));
        throw error("índice inválido: " + type(o) + " não aceita [ ]");
    }

    private void setIndex(Object o, Object k, Object v) {
        if (o instanceof List) {
            List<Object> l = (List<Object>) o;
            try { l.set(index(k, l.size()), v); }
            catch (UnsupportedOperationException ex) { throw error("esta lista não pode ser alterada"); }
        } else if (o instanceof Map) ((Map<String, Object>) o).put(keyOf(k), v);
        else if (o instanceof SlipUi.Node) uiSet((SlipUi.Node) o, keyOf(k), v, true);
        else throw error("atribuição por índice exige lista, mapa ou elemento");
    }

    private final class Index extends Node {
        final Node obj, key;
        Index(Node o, Node k) { obj = o; key = k; }
        @Override Object eval(Env e) { return getIndex(obj.eval(e), key.eval(e)); }
    }

    private final class Prop extends Node {
        final Node obj; final String name;
        Prop(Node o, String n) { obj = o; name = n; }
        @Override Object eval(Env e) {
            Object o = obj.eval(e);
            if (o instanceof SlipObject) {
                SlipObject so = (SlipObject) o;
                if (so.containsKey(name)) return so.get(name);
                SlipClass ow = so.cls.ownerOf(name);
                return ow == null ? null : bind(so, ow, ow.methods.get(name));
            }
            if (o instanceof SuperRef) {
                SuperRef sr = (SuperRef) o;
                SlipClass ow = sr.from.ownerOf(name);
                if (ow == null) throw error("super não tem o método '" + name + "'");
                return bind(sr.obj, ow, ow.methods.get(name));
            }
            if (o instanceof SlipUi.Node) return ((SlipUi.Node) o).get(name);
            if (o instanceof Map) return ((Map<?, ?>) o).get(name);
            if (o instanceof String || o instanceof List) { // "a,b".split(",") == split("a,b", ",")
                final Fn b = builtins.get(name);
                if (b == null) throw error("'" + name + "' não existe em " + type(o));
                final Object recv = o;
                return (Fn) a -> { List<Object> l = new ArrayList<>(a.size() + 1); l.add(recv); l.addAll(a); return b.call(l); };
            }
            throw error("ponto exige mapa ou elemento");
        }
    }

    private final class Call extends Node {
        final Node callee; final Node[] args;
        Call(Node c, Node[] a) { callee = c; args = a; }
        boolean isRange(Env e) {
            return callee instanceof Var && ((Var) callee).name.equals("range")
                    && args.length >= 1 && args.length <= 3 && e.lookup("range") == UNSET;
        }
        @Override Object eval(Env e) {
            Object f = callee.eval(e);
            List<Object> a = new ArrayList<>(args.length);
            for (Node n : args) a.add(n.eval(e));
            if (!(f instanceof Fn)) throw error("valor não é função");
            return ((Fn) f).call(a);
        }
    }

    private final class Not extends Node {
        final Node a;
        Not(Node a) { this.a = a; }
        @Override Object eval(Env e) { return !yes(a.eval(e)); }
    }

    private final class Neg extends Node {
        final Node a;
        Neg(Node a) { this.a = a; }
        @Override Object eval(Env e) { return -num(a.eval(e)); }
    }

    /** "or" com curto-circuito: o lado direito só é avaliado se necessário. */
    private final class Or extends Node {
        final Node a, b;
        Or(Node a, Node b) { this.a = a; this.b = b; }
        @Override Object eval(Env e) { return yes(a.eval(e)) || yes(b.eval(e)); }
    }

    private final class And extends Node {
        final Node a, b;
        And(Node a, Node b) { this.a = a; this.b = b; }
        @Override Object eval(Env e) { return yes(a.eval(e)) && yes(b.eval(e)); }
    }

    private final class Eq extends Node {
        final Node a, b; final boolean negate;
        Eq(Node a, Node b, boolean negate) { this.a = a; this.b = b; this.negate = negate; }
        @Override Object eval(Env e) { return same(a.eval(e), b.eval(e)) != negate; }
    }

    private final class Cmp extends Node {
        final Node a, b; final String op;
        Cmp(Node a, Node b, String op) { this.a = a; this.b = b; this.op = op; }
        @Override Object eval(Env e) {
            Object x = a.eval(e), y = b.eval(e);
            if (x instanceof String && y instanceof String) {
                int c = ((String) x).compareTo((String) y);
                switch (op) {
                    case ">": return c > 0;
                    case "<": return c < 0;
                    case ">=": return c >= 0;
                    default: return c <= 0;
                }
            }
            double p = num(x), q = num(y); // operadores primitivos: -0 == 0 e NaN nunca é maior/menor (IEEE 754)
            switch (op) {
                case ">": return p > q;
                case "<": return p < q;
                case ">=": return p >= q;
                default: return p <= q;
            }
        }
    }

    private Object arith(char op, Object x, Object y) {
        switch (op) {
            case '+':
                if (x instanceof List && y instanceof List) {
                    List<Object> r = new ArrayList<>((List<Object>) x);
                    r.addAll((List<Object>) y);
                    return r;
                }
                if (x instanceof String || y instanceof String) return str(x) + str(y);
                return num(x) + num(y);
            case '-': return num(x) - num(y);
            case '*': return num(x) * num(y);
            default: {
                double d = num(y);
                if (d == 0) throw error("divisão por zero");
                return op == '/' ? num(x) / d : num(x) % d;
            }
        }
    }

    private final class Arith extends Node {
        final Node a, b; final char op;
        Arith(Node a, Node b, char op) { this.a = a; this.b = b; this.op = op; }
        @Override Object eval(Env e) { return arith(op, a.eval(e), b.eval(e)); }
    }

    /** Atribuição a variável, a lista[i] ou a mapa.chave, simples (=) ou composta (+= -= *= /=). */
    private final class Assign extends Node {
        final Node target, value; final char op; // op == 0: atribuição simples
        Assign(Node t, char op, Node v) { target = t; this.op = op; value = v; }
        @Override int exec(Env e) {
            if (target instanceof Var) {
                String n = ((Var) target).name;
                Object cur = op == 0 ? null : e.lookup(n);
                if (cur == UNSET) throw error("variável não declarada: " + n);
                Object v = value.eval(e);
                if (op != 0) v = arith(op, cur, v);
                if (!e.assign(n, v)) throw error("variável não declarada: " + n);
            } else {
                Object obj; Object key;
                if (target instanceof Index) { obj = ((Index) target).obj.eval(e); key = ((Index) target).key.eval(e); }
                else { obj = ((Prop) target).obj.eval(e); key = ((Prop) target).name; if (!(obj instanceof Map || obj instanceof SlipUi.Node)) throw error("ponto exige mapa ou elemento"); }
                Object cur = op == 0 ? null : getIndex(obj, key);
                Object v = value.eval(e);
                if (op != 0) v = arith(op, cur, v);
                setIndex(obj, key, v);
            }
            return NORMAL;
        }
    }

    private final class Ut extends Node {
        final Node value;
        Ut(Node v) { value = v; }
        @Override int exec(Env e) { out.accept(str(value.eval(e))); return NORMAL; }
    }

    private final class Vl extends Node {
        final String name; final Node value;
        Vl(String n, Node v) { name = n; value = v; }
        @Override int exec(Env e) { e.define(name, value.eval(e)); return NORMAL; }
    }

    private final class Block extends Node {
        final Node[] stmts;
        Block(Node[] s) { stmts = s; }
        @Override int exec(Env e) {
            for (Node s : stmts) {
                int r;
                try { tick(); r = s.exec(e); }
                catch (SlipError x) { if (x.line == 0) x.line = s.line; throw x; }
                if (r != NORMAL) return r;
            }
            return NORMAL;
        }
    }

    private final class If extends Node {
        final Node[] conds; final Block[] bodies; final Block otherwise;
        If(Node[] c, Block[] b, Block o) { conds = c; bodies = b; otherwise = o; }
        @Override int exec(Env e) {
            for (int i = 0; i < conds.length; i++) {
                if (yes(conds[i].eval(e))) return bodies[i].exec(new Env(e));
            }
            return otherwise == null ? NORMAL : otherwise.exec(new Env(e));
        }
    }

    private final class While extends Node {
        final Node cond; final Block body;
        While(Node c, Block b) { cond = c; body = b; }
        @Override int exec(Env e) {
            while (yes(cond.eval(e))) {
                tick();
                int r = body.exec(new Env(e));
                if (r == BREAK) break;
                if (r == RETURN) return RETURN;
            }
            return NORMAL;
        }
    }

    private final class For extends Node {
        final String name; final Node iter; final Block body;
        For(String n, Node i, Block b) { name = n; iter = i; body = b; }
        @Override int exec(Env e) {
            if (iter instanceof Call && ((Call) iter).isRange(e)) {
                // for i in range(...): percorre os números sem montar a lista
                Call c = (Call) iter;
                List<Object> a = new ArrayList<>(3);
                for (Node n : c.args) a.add(n.eval(e));
                long[] r = rangeBounds(a);
                for (long i = r[0]; r[2] > 0 ? i < r[1] : i > r[1]; i += r[2]) {
                    int s = step(e, (double) i);
                    if (s == BREAK) break;
                    if (s == RETURN) return RETURN;
                }
                return NORMAL;
            }
            Object v = iter.eval(e);
            if (v instanceof List) {
                List<?> l = (List<?>) v;
                int n = l.size(); // fotografia do tamanho: alterar a lista no laço não dá erro nem laço infinito
                for (int i = 0; i < n && i < l.size(); i++) {
                    int s = step(e, l.get(i));
                    if (s == BREAK) break;
                    if (s == RETURN) return RETURN;
                }
            } else if (v instanceof String) {
                String str = (String) v;
                for (int i = 0; i < str.length(); i++) {
                    int s = step(e, String.valueOf(str.charAt(i)));
                    if (s == BREAK) break;
                    if (s == RETURN) return RETURN;
                }
            } else if (v instanceof Map) {
                for (Object k : new ArrayList<>(((Map<?, ?>) v).keySet())) {
                    int s = step(e, k);
                    if (s == BREAK) break;
                    if (s == RETURN) return RETURN;
                }
            } else throw error("for exige lista, texto, mapa ou range");
            return NORMAL;
        }
        private int step(Env e, Object value) {
            tick();
            Env local = new Env(e);
            local.define(name, value);
            return body.exec(local);
        }
    }

    private final class UserFn implements Fn {
        final String[] params; final Block body; final Env closure;
        UserFn(String[] p, Block b, Env c) { params = p; body = b; closure = c; }
        @Override public Object call(List<Object> a) {
            if (callDepth >= MAX_CALL_DEPTH) throw error("profundidade de recursão excedida (máx. " + MAX_CALL_DEPTH + ")");
            callDepth++;
            try {
                tick();
                Env local = new Env(closure);
                for (int i = 0; i < params.length; i++) local.define(params[i], i < a.size() ? a.get(i) : null);
                int r = body.exec(local);
                if (r == RETURN) { Object v = retVal; retVal = null; return v; }
                if (r == BREAK) throw error("break fora de laço");
                if (r == CONTINUE) throw error("continue fora de laço");
                return null;
            } finally { callDepth--; }
        }
    }

    private final class FuncDef extends Node {
        final String name; final String[] params; final Block body;
        FuncDef(String n, String[] p, Block b) { name = n; params = p; body = b; }
        @Override int exec(Env e) { e.define(name, new UserFn(params, body, e)); return NORMAL; }
    }

    private final class Return extends Node {
        final Node value;
        Return(Node v) { value = v; }
        @Override int exec(Env e) { retVal = value == null ? null : value.eval(e); return RETURN; }
    }

    private final class Break extends Node {
        @Override int exec(Env e) { return BREAK; }
    }

    private final class Continue extends Node {
        @Override int exec(Env e) { return CONTINUE; }
    }

    /** Função anônima: function(x) ... end  ou  x => expr  ou  (a, b) => expr. Fecha sobre o escopo em que foi criada. */
    private final class Lambda extends Node {
        final String[] params; final Block body;
        Lambda(String[] p, Block b) { params = p; body = b; }
        @Override Object eval(Env e) { return new UserFn(params, body, e); }
    }

    private Map<String, Object> errValue(SlipError x) {
        ErrMap m = new ErrMap(x);
        m.put("mensagem", x.plain());
        m.put("linha", x.line > 0 ? (Object) (double) x.line : null);
        m.put("valor", x.hasValue ? x.value : x.plain());
        return m;
    }

    private final class Throw extends Node {
        final Node value;
        Throw(Node v) { value = v; }
        @Override int exec(Env e) {
            Object v = value.eval(e);
            if (v instanceof ErrMap) throw ((ErrMap) v).cause; // 'throw e' dentro do catch relança o erro original
            SlipError x = new SlipError("Slip: " + str(v), 0);
            x.hasValue = true;
            x.value = v;
            throw x;
        }
    }

    private final class Try extends Node {
        final Block body, handler, fin; final String var;
        Try(Block b, String v, Block h, Block f) { body = b; var = v; handler = h; fin = f; }
        private int attempt(Env e) {
            try { return body.exec(new Env(e)); }
            catch (SlipError x) {
                if (handler == null || x.fatal) throw x;
                Env h = new Env(e);
                if (var != null) h.define(var, errValue(x));
                return handler.exec(h);
            }
        }
        @Override int exec(Env e) {
            int r;
            try { r = attempt(e); }
            catch (RuntimeException ex) {
                if (fin != null) { int fr = fin.exec(new Env(e)); if (fr != NORMAL) return fr; }
                throw ex;
            }
            if (fin != null) {
                Object keep = retVal; // o finally pode chamar funções, que zeram retVal
                int fr = fin.exec(new Env(e));
                if (fr != NORMAL) return fr;
                retVal = keep;
            }
            return r;
        }
    }

    private final class ClassDef extends Node {
        final String name, parent; final FuncDef[] methods;
        ClassDef(String n, String p, FuncDef[] m) { name = n; parent = p; methods = m; }
        @Override int exec(Env e) {
            SlipClass base = null;
            if (parent != null) {
                Object p = e.lookup(parent);
                if (!(p instanceof SlipClass)) throw error("'" + parent + "' não é uma classe");
                base = (SlipClass) p;
            }
            SlipClass c = new SlipClass(name, base, e);
            for (FuncDef f : methods) c.methods.put(f.name, f);
            e.define(name, c);
            return NORMAL;
        }
    }

    // ------------------------------------------------------------------ interface visual

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private void uiLive(SlipUi.Node n) {
        if (n.removed) throw error("o elemento '" + n.name + "' foi removido");
    }

    private String uiError(RuntimeException ex) { return ex.getMessage(); }

    /** Define uma propriedade (ou ação, ou pai). notify=false enquanto o elemento ainda não foi enviado ao host. */
    private void uiSet(SlipUi.Node n, String prop, Object v, boolean notify) {
        uiLive(n);
        String k = n.key(prop);
        if (k.equals("pai")) {
            if (v == null) uiMove(n, null, notify);
            else if (v instanceof SlipUi.Node) uiMove(n, (SlipUi.Node) v, notify);
            else throw error("'pai' espera um elemento do tipo caixa");
            return;
        }
        if (SlipUi.HANDLERS.contains(k)) {
            if (v == null) n.handlers.remove(k);
            else if (v instanceof Fn) n.handlers.put(k, v);
            else throw error("'" + k + "' espera uma função (passe o nome dela, sem parênteses)");
            if (notify && uiHost != null) uiHost.update(n.id, k, v == null ? Boolean.FALSE : Boolean.TRUE);
            return;
        }
        Object value;
        try { value = n.normalize(k, v); }
        catch (IllegalArgumentException ex) { throw error("elemento '" + n.name + "': " + uiError(ex)); }
        n.props.put(k, value);
        if (notify && uiHost != null) uiHost.update(n.id, k, value);
    }

    private void uiMove(SlipUi.Node n, SlipUi.Node parent, boolean notify) {
        if (parent != null) {
            uiLive(parent);
            if (!parent.type.equals("caixa")) throw error("só uma caixa pode conter elementos ('" + parent.name + "' é " + parent.type + ")");
            if (n.isAncestorOf(parent)) throw error("um elemento não pode ficar dentro de si mesmo");
        }
        if (notify) { // já registrado: sai da lista antiga
            if (n.parent != null) n.parent.children.remove(n); else uiRoot.remove(n);
            if (parent != null) parent.children.add(n); else uiRoot.add(n);
        }
        n.parent = parent;
        if (notify && uiHost != null) uiHost.move(n.id, parent == null ? 0 : parent.id);
    }

    private static String uiColorRole(String type, int index) {
        String[] roles;
        switch (type) {
            case "botao": roles = new String[]{"fundo", "cor"}; break;
            case "caixa": roles = new String[]{"fundo"}; break;
            default: roles = new String[]{"cor", "fundo"}; // texto, entrada
        }
        return index < roles.length ? roles[index] : null;
    }

    /** Um valor de "+ <>!valor!<>": o significado vem do tipo do valor. pos = {cores já vistas, textos já vistos}. */
    private void uiApply(SlipUi.Node n, Object v, int[] pos, boolean notify) {
        if (v instanceof SlipUi.Node) uiSet(n, "pai", v, notify);
        else if (v instanceof Fn) uiSet(n, n.type.equals("entrada") ? "ao_mudar" : "ao_clicar", v, notify);
        else if (v instanceof Map) {
            for (Map.Entry<?, ?> en : ((Map<?, ?>) v).entrySet()) uiSet(n, String.valueOf(en.getKey()), en.getValue(), notify);
        } else if (v instanceof Number) uiSet(n, "tamanho", v, notify);
        else if (v instanceof String) {
            String s = (String) v;
            if (SlipUi.isColor(s)) {
                String role = uiColorRole(n.type, pos[0]++);
                if (role == null) throw error("cores demais para " + n.type + " '" + n.name + "' (use um mapa: {cor: ..., fundo: ...})");
                uiSet(n, role, s, notify);
            } else if (n.type.equals("caixa") && (s.equals("horizontal") || s.equals("vertical"))) uiSet(n, "direcao", s, notify);
            else if (n.type.equals("entrada")) uiSet(n, pos[1]++ == 0 ? "dica" : "valor", s, notify);
            else uiSet(n, "texto", s, notify);
        } else throw error("valor sem significado para um elemento: " + type(v) + " (use texto, cor, número, função, elemento ou mapa)");
    }

    private void uiRegister(SlipUi.Node n) {
        uiById.put(n.id, n);
        if (n.parent != null) n.parent.children.add(n); else uiRoot.add(n);
        if (uiHost != null) {
            Map<String, Object> snap = new LinkedHashMap<>(n.props);
            for (String h : n.handlers.keySet()) snap.put(h, Boolean.TRUE);
            uiHost.create(n.id, n.type, n.parent == null ? 0 : n.parent.id, snap);
        }
    }

    private void uiRemove(SlipUi.Node n) {
        for (SlipUi.Node c : new ArrayList<>(n.children)) uiRemove(c);
        n.removed = true;
        uiById.remove(n.id);
        if (n.parent != null) n.parent.children.remove(n); else uiRoot.remove(n);
        if (uiHost != null) uiHost.remove(n.id);
    }

    /** vt<tipo>"nome"<> + <>!valor!<> ... : cria um elemento (ou, com tipo vazio e nome já existente, altera). */
    private final class Vt extends Node {
        final String type, name; final Node[] mods;
        Vt(String type, String name, Node[] mods) { this.type = type; this.name = name; this.mods = mods; }
        @Override int exec(Env e) {
            if (!IDENT.matcher(name).matches()) throw error("nome de elemento inválido: " + name);
            Object cur = e.lookup(name);
            boolean fresh = !(cur instanceof SlipUi.Node && type.isEmpty());
            SlipUi.Node n;
            if (fresh) {
                String t = type.isEmpty() ? "texto" : type;
                if (!SlipUi.TYPES.contains(t)) throw error("tipo de elemento desconhecido: " + t + " (use " + String.join(", ", SlipUi.TYPES) + ")");
                if (uiById.size() >= SlipUi.MAX_NODES) throw error("limite de " + SlipUi.MAX_NODES + " elementos atingido");
                n = new SlipUi.Node(++uiSeq, t, name);
            } else n = (SlipUi.Node) cur;
            int[] pos = new int[2];
            for (Node m : mods) uiApply(n, m.eval(e), pos, !fresh);
            if (fresh) { e.define(name, n); uiRegister(n); }
            return NORMAL;
        }
    }

    private final class ItNode extends Node {
        final String path;
        ItNode(String p) { path = p; }
        @Override int exec(Env e) {
            try {
                File registry = new File(home, "project_files.txt");
                Files.write(registry.toPath(), (path + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                out.accept("Arquivo registrado no projeto: " + path);
            } catch (IOException ex) { throw error("não foi possível registrar arquivo: " + path); }
            return NORMAL;
        }
    }

    private void execTop(Block prog, Env env) {
        int r = prog.exec(env);
        if (r == RETURN) { retVal = null; throw error("return fora de função"); }
        if (r == BREAK) throw error("break fora de laço");
        if (r == CONTINUE) throw error("continue fora de laço");
    }

    // ------------------------------------------------------------------ parser

    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
            "end", "else", "elseif", "then", "do", "in", "if", "while", "for", "function", "return", "break",
            "continue", "try", "catch", "finally", "throw", "class", "extends"));
    private static final Set<String> ASSIGN_OPS = new HashSet<>(Arrays.asList("=", "+=", "-=", "*=", "/="));

    private final class Parser {
        final List<Tok> t;
        int p, nest; // nest > 0: dentro de ( [ { — quebras de linha são ignoradas

        Parser(String src) { t = lex(src); }

        Tok peek() {
            if (nest > 0) while (t.get(p).type == T_NL) p++;
            return t.get(p);
        }
        Tok next() { Tok k = peek(); if (k.type != T_EOF) p++; return k; }
        boolean isOp(String s) { Tok k = peek(); return k.type == T_OP && k.text.equals(s); }
        boolean isWord(String s) { Tok k = peek(); return k.type == T_ID && k.text.equals(s); }
        boolean eatOp(String s) { if (isOp(s)) { p++; return true; } return false; }
        boolean eatWord(String s) { if (isWord(s)) { p++; return true; } return false; }

        String describe(Tok k) {
            switch (k.type) {
                case T_EOF: return "fim do arquivo";
                case T_NL: return "fim de linha";
                case T_STR: return "texto \"" + k.text + "\"";
                default: return "'" + k.text + "'";
            }
        }
        SlipError unexpected(String wanted) {
            Tok k = peek();
            return new SlipError("Slip: esperado " + wanted + ", encontrado " + describe(k), k.line);
        }
        void needOp(String s) { if (!eatOp(s)) throw unexpected("'" + s + "'"); }
        void needWord(String s) { if (!eatWord(s)) throw unexpected("'" + s + "'"); }
        void needEnd(int openLine) {
            if (!eatWord("end")) throw new SlipError("Slip: bloco sem 'end'", openLine);
        }
        String needIdent() {
            Tok k = peek();
            if (k.type != T_ID || RESERVED.contains(k.text)) throw unexpected("um nome");
            p++;
            return k.text;
        }
        <N extends Node> N at(N n, int line) { n.line = line; return n; }

        Block program() { return block(); }

        Block block(String... stops) {
            List<Node> list = new ArrayList<>();
            while (true) {
                while (peek().type == T_NL) p++;
                Tok k = peek();
                if (k.type == T_EOF) break;
                if (k.type == T_ID && Arrays.asList(stops).contains(k.text)) break;
                list.add(statement());
            }
            return new Block(list.toArray(new Node[0]));
        }

        Node statement() {
            Tok k = peek();
            int line = k.line;
            if (k.type == T_ITPATH) { p++; return at(new ItNode(k.text), line); }
            if (k.type == T_ID) {
                String w = k.text;
                Tok nx = t.get(p + 1);
                boolean angle = nx.type == T_OP && nx.text.equals("<");
                if (w.equals("ut") && angle) return at(parseUt(), line);
                if (w.equals("vl") && angle) return at(parseVl(), line);
                if (w.equals("vt") && angle) return at(parseVt(), line);
                switch (w) {
                    case "if": return at(parseIf(), line);
                    case "while": return at(parseWhile(), line);
                    case "for": return at(parseFor(), line);
                    case "function":
                        if (nx.type == T_OP && nx.text.equals("(")) break; // função anônima usada como expressão
                        return at(parseFunction(), line);
                    case "try": return at(parseTry(), line);
                    case "throw": p++; return at(new Throw(expr()), line);
                    case "class": return at(parseClass(), line);
                    case "continue": p++; return at(new Continue(), line);
                    case "return": return at(parseReturn(), line);
                    case "break": p++; return at(new Break(), line);
                    case "end": case "else": case "elseif": case "catch": case "finally":
                        throw new SlipError("Slip: bloco inesperado: " + w, line);
                    default: break;
                }
            }
            Node target = expr();
            Tok op = peek();
            if (op.type == T_OP && ASSIGN_OPS.contains(op.text)) {
                if (!(target instanceof Var || target instanceof Index || target instanceof Prop))
                    throw new SlipError("Slip: alvo de atribuição inválido", line);
                p++;
                Node v = expr();
                return at(new Assign(target, op.text.length() == 1 ? (char) 0 : op.text.charAt(0), v), line);
            }
            return at(target, line);
        }

        Node parseUt() {
            p++;
            needOp("<"); needOp(">"); needOp("!");
            Node v = expr();
            needOp("!"); needOp("<"); needOp(">");
            return new Ut(v);
        }

        Node parseVl() {
            p++;
            needOp("<");
            String name = needIdent();
            if (isOp(">=")) p++; // vl<x>=5 (o lexer junta '>' e '=')
            else { needOp(">"); needOp("="); }
            return new Vl(name, expr());
        }

        /** vt<tipo>"nome"<> [+ <>!valor!<>]... — o "+" pode ficar no fim da linha anterior ou no começo da seguinte. */
        Node parseVt() {
            p++;
            needOp("<");
            String type = isOp(">") ? "" : needIdent();
            needOp(">");
            Tok nm = next();
            if (nm.type != T_STR && nm.type != T_ID) throw new SlipError("Slip: esperado o nome do elemento entre aspas, encontrado " + describe(nm), nm.line);
            needOp("<"); needOp(">");
            List<Node> mods = new ArrayList<>();
            while (true) {
                int save = p;
                while (t.get(p).type == T_NL) p++;
                Tok a = t.get(p);
                if (a.type == T_EOF) { p = save; break; }
                int q = p + 1;
                while (t.get(q).type == T_NL) q++; // aceita "+" no fim da linha e o valor na seguinte
                Tok b = t.get(q);
                if (a.type == T_OP && a.text.equals("+") && b.type == T_OP && b.text.equals("<")) {
                    p = q;
                    needOp("<"); needOp(">"); needOp("!");
                    mods.add(expr());
                    needOp("!"); needOp("<"); needOp(">");
                } else { p = save; break; }
            }
            return new Vt(type, nm.text, mods.toArray(new Node[0]));
        }

        Node parseIf() {
            int line = peek().line;
            p++;
            List<Node> conds = new ArrayList<>();
            List<Block> bodies = new ArrayList<>();
            conds.add(expr());
            needWord("then");
            bodies.add(block("elseif", "else", "end"));
            while (eatWord("elseif")) {
                conds.add(expr());
                needWord("then");
                bodies.add(block("elseif", "else", "end"));
            }
            Block otherwise = eatWord("else") ? block("end") : null;
            needEnd(line);
            return new If(conds.toArray(new Node[0]), bodies.toArray(new Block[0]), otherwise);
        }

        Node parseWhile() {
            int line = peek().line;
            p++;
            Node c = expr();
            needWord("do");
            Block b = block("end");
            needEnd(line);
            return new While(c, b);
        }

        Node parseFor() {
            int line = peek().line;
            p++;
            String name = needIdent();
            needWord("in");
            Node it = expr();
            needWord("do");
            Block b = block("end");
            needEnd(line);
            return new For(name, it, b);
        }

        Node parseFunction() {
            int line = peek().line;
            p++;
            String name = needIdent();
            needOp("(");
            nest++;
            List<String> params = new ArrayList<>();
            if (!isOp(")")) {
                do { params.add(needIdent()); } while (eatOp(","));
            }
            needOp(")");
            nest--;
            Block b = block("end");
            needEnd(line);
            return new FuncDef(name, params.toArray(new String[0]), b);
        }

        Node parseTry() {
            int line = peek().line;
            p++;
            Block body = block("catch", "finally", "end");
            String var = null;
            Block handler = null, fin = null;
            if (eatWord("catch")) {
                Tok k = peek();
                if (k.type == T_ID && !RESERVED.contains(k.text)) { var = k.text; p++; }
                handler = block("finally", "end");
            }
            if (eatWord("finally")) fin = block("end");
            if (handler == null && fin == null) throw new SlipError("Slip: 'try' precisa de 'catch' ou 'finally'", line);
            needEnd(line);
            return new Try(body, var, handler, fin);
        }

        Node parseClass() {
            int line = peek().line;
            p++;
            String name = needIdent();
            String parent = eatWord("extends") ? needIdent() : null;
            List<FuncDef> methods = new ArrayList<>();
            while (true) {
                while (peek().type == T_NL) p++;
                Tok k = peek();
                if (k.type == T_EOF || (k.type == T_ID && k.text.equals("end"))) break;
                if (k.type == T_ID && k.text.equals("function")) methods.add((FuncDef) at(parseFunction(), k.line));
                else throw new SlipError("Slip: dentro de 'class' só cabem funções (métodos), encontrado " + describe(k), k.line);
            }
            needEnd(line);
            return new ClassDef(name, parent, methods.toArray(new FuncDef[0]));
        }

        // ---- funções anônimas

        String[] params() {
            needOp("(");
            nest++;
            List<String> ps = new ArrayList<>();
            if (!isOp(")")) do { ps.add(needIdent()); } while (eatOp(","));
            needOp(")");
            nest--;
            return ps.toArray(new String[0]);
        }

        Node arrow(String[] params, int line) {
            Node body = expr();
            return at(new Lambda(params, new Block(new Node[]{at(new Return(body), line)})), line);
        }

        Node anonFunction(int line) {
            String[] ps = params();
            int saved = nest;
            nest = 0; // o corpo tem linhas próprias, mesmo dentro de uma chamada ( ... )
            Block b = block("end");
            nest = saved;
            needEnd(line);
            return at(new Lambda(ps, b), line);
        }

        /** Depois de "(": é a lista de parâmetros de (a, b) => ... ? */
        boolean arrowAhead() {
            Tok first = t.get(p), second = t.get(p + 1);
            boolean shape = (first.type == T_OP && first.text.equals(")"))
                    || (first.type == T_ID && second.type == T_OP && (second.text.equals(",") || second.text.equals(")")));
            if (!shape) return false;
            int depth = 1;
            for (int j = p; j < t.size(); j++) {
                Tok x = t.get(j);
                if (x.type == T_EOF) return false;
                if (x.type != T_OP) continue;
                String o = x.text;
                if (o.equals("(") || o.equals("[") || o.equals("{")) depth++;
                else if (o.equals(")") || o.equals("]") || o.equals("}")) {
                    if (--depth == 0) { Tok n = t.get(Math.min(j + 1, t.size() - 1)); return n.type == T_OP && n.text.equals("=>"); }
                }
            }
            return false;
        }

        Node parseReturn() {
            p++;
            Tok k = peek();
            boolean none = k.type == T_NL || k.type == T_EOF
                    || (k.type == T_ID && (k.text.equals("end") || k.text.equals("else") || k.text.equals("elseif")
                            || k.text.equals("catch") || k.text.equals("finally")));
            return new Return(none ? null : expr());
        }

        // ---- expressões (precedência: or < and < == != < comparação < + - < * / % < unário < postfix)

        Node expr() { return orExpr(); }

        Node orExpr() {
            Node a = andExpr();
            while (isOp("||") || isWord("or")) { p++; a = new Or(a, andExpr()); }
            return a;
        }
        Node andExpr() {
            Node a = eqExpr();
            while (isOp("&&") || isWord("and")) { p++; a = new And(a, eqExpr()); }
            return a;
        }
        Node eqExpr() {
            Node a = cmpExpr();
            while (isOp("==") || isOp("!=")) { boolean neg = next().text.equals("!="); a = new Eq(a, cmpExpr(), neg); }
            return a;
        }
        Node cmpExpr() {
            Node a = term();
            while (isOp(">") || isOp("<") || isOp(">=") || isOp("<=")) { String op = next().text; a = new Cmp(a, term(), op); }
            return a;
        }
        Node term() {
            Node a = factor();
            while (isOp("+") || isOp("-")) { char op = next().text.charAt(0); a = new Arith(a, factor(), op); }
            return a;
        }
        Node factor() {
            Node a = unary();
            while (isOp("*") || isOp("/") || isOp("%")) { char op = next().text.charAt(0); a = new Arith(a, unary(), op); }
            return a;
        }
        Node unary() {
            if (isOp("!") || isWord("not")) { p++; return new Not(unary()); }
            if (isOp("-")) { p++; return new Neg(unary()); }
            return postfix();
        }

        Node postfix() {
            Node x = primary();
            while (true) {
                if (isOp("(")) {
                    p++;
                    nest++;
                    List<Node> args = new ArrayList<>();
                    if (!isOp(")")) {
                        do { if (isOp(")")) break; args.add(expr()); } while (eatOp(","));
                    }
                    needOp(")");
                    nest--;
                    x = at(new Call(x, args.toArray(new Node[0])), x.line);
                } else if (isOp("[")) {
                    p++;
                    nest++;
                    Node k = expr();
                    needOp("]");
                    nest--;
                    x = new Index(x, k);
                } else if (isOp(".")) {
                    p++;
                    Tok nm = next();
                    if (nm.type != T_ID) throw new SlipError("Slip: esperado nome após '.', encontrado " + describe(nm), nm.line);
                    x = new Prop(x, nm.text);
                } else break;
            }
            return x;
        }

        Node primary() {
            Tok k = next();
            switch (k.type) {
                case T_NUM: return new Const(Double.parseDouble(k.text));
                case T_STR: return new Const(k.text);
                case T_ID:
                    switch (k.text) {
                        case "true": return new Const(Boolean.TRUE);
                        case "false": return new Const(Boolean.FALSE);
                        case "null": return new Const(null);
                        case "function":
                            if (isOp("(")) return anonFunction(k.line);
                            break;
                        default: break;
                    }
                    if (RESERVED.contains(k.text)) break;
                    if (isOp("=>")) { p++; return arrow(new String[]{k.text}, k.line); }
                    return at(new Var(k.text), k.line);
                case T_OP:
                    if (k.text.equals("(")) {
                        if (arrowAhead()) {
                            p--; // devolve o "(" para params() ler a lista inteira
                            String[] ps = params();
                            needOp("=>");
                            return arrow(ps, k.line);
                        }
                        nest++;
                        Node x = expr();
                        needOp(")");
                        nest--;
                        return x;
                    }
                    if (k.text.equals("[")) {
                        nest++;
                        List<Node> items = new ArrayList<>();
                        if (!isOp("]")) {
                            do { if (isOp("]")) break; items.add(expr()); } while (eatOp(","));
                        }
                        needOp("]");
                        nest--;
                        return new ListLit(items.toArray(new Node[0]));
                    }
                    if (k.text.equals("{")) {
                        nest++;
                        List<String> keys = new ArrayList<>();
                        List<Node> values = new ArrayList<>();
                        if (!isOp("}")) {
                            do {
                                if (isOp("}")) break;
                                Tok kt = next();
                                if (kt.type != T_ID && kt.type != T_STR && kt.type != T_NUM)
                                    throw new SlipError("Slip: chave de mapa inválida: " + describe(kt), kt.line);
                                keys.add(kt.type == T_NUM ? numStr(Double.parseDouble(kt.text)) : kt.text);
                                needOp(":");
                                values.add(expr());
                            } while (eatOp(","));
                        }
                        needOp("}");
                        nest--;
                        return new MapLit(keys.toArray(new String[0]), values.toArray(new Node[0]));
                    }
                    break;
                default: break;
            }
            throw new SlipError("Slip: expressão inválida: " + describe(k), k.line);
        }
    }

    // ------------------------------------------------------------------ arquivos e módulos

    private File file(String p) {
        if (p.startsWith("/") || p.contains("..") || p.contains("\\")) throw error("caminho bloqueado; use caminho relativo");
        File f = new File(home, p);
        try {
            String b = home.getCanonicalPath(), c = f.getCanonicalPath();
            if (!c.startsWith(b + File.separator) && !c.equals(b)) throw error("acesso fora da pasta Slip");
        } catch (IOException e) { throw error("caminho inválido"); }
        return f;
    }

    /** slip/libs guarda código nativo carregável: scripts não podem criar nem apagar arquivos lá. */
    private void checkWritable(File f) {
        try {
            String libs = new File(home, "libs").getCanonicalPath(), c = f.getCanonicalPath();
            if (c.equals(libs) || c.startsWith(libs + File.separator)) throw error("escrita em slip/libs não é permitida");
        } catch (IOException e) { throw error("caminho inválido"); }
    }

    private String read(String p) {
        try { return new String(Files.readAllBytes(file(p).toPath()), StandardCharsets.UTF_8); }
        catch (IOException e) { throw error("não foi possível ler " + p); }
    }

    private void write(String p, String v, boolean append) {
        File f = file(p);
        checkWritable(f);
        try {
            File d = f.getParentFile();
            if (d != null) d.mkdirs();
            byte[] data = v.getBytes(StandardCharsets.UTF_8);
            if (append) Files.write(f.toPath(), data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            else Files.write(f.toPath(), data);
        } catch (IOException e) { throw error("não foi possível gravar " + p); }
    }

    /** Extensões aceitas para módulos, em ordem de preferência quando o nome vem sem extensão. */
    private static final String[] MODULE_EXTS = {".slip", ".sp"};

    /** Resolve o nome de arquivo do módulo: com extensão explícita usa como está; sem extensão tenta .slip e depois .sp. */
    private static String moduleFileName(File dir, String name) {
        for (String ext : MODULE_EXTS) if (name.endsWith(ext)) return name;
        for (String ext : MODULE_EXTS) if (new File(dir, name + ext).isFile()) return name + ext;
        return name + MODULE_EXTS[0];
    }

    /** Carrega slip/modules/<nome>.slip ou <nome>.sp uma única vez por execução (evita recursão infinita entre módulos). */
    private void module(String name) {
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) throw error("nome de módulo inválido");
        File dir = new File(home, "modules");
        String fileName = moduleFileName(dir, name);
        if (!loadedModules.add(fileName)) return;
        File f = new File(dir, fileName);
        try {
            String src;
            try {
                String b = dir.getCanonicalPath();
                if (!f.getCanonicalPath().startsWith(b + File.separator)) throw error("módulo fora da sandbox");
                src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            } catch (IOException ex) { throw error("módulo não encontrado: " + name); }
            try { execTop(new Parser(src).program(), new Env(null, global)); }
            catch (SlipError ex) {
                if (ex.line == -1 || ex.fatal) throw ex;
                throw new SlipError("Slip: no módulo '" + name + "': " + ex.getMessage().replaceFirst("^Slip: ", ""), -1);
            }
        } catch (RuntimeException ex) { loadedModules.remove(fileName); throw ex; }
    }

    // ------------------------------------------------------------------ funções nativas

    private static long[] rangeBounds(List<Object> a) {
        if (a.isEmpty() || a.size() > 3) throw error("range espera de 1 a 3 argumentos");
        long start = a.size() > 1 ? (long) num(a.get(0)) : 0;
        long end = (long) num(a.get(a.size() > 1 ? 1 : 0));
        long step = a.size() > 2 ? (long) num(a.get(2)) : 1;
        if (step == 0) throw error("range: passo zero");
        return new long[]{start, end, step};
    }

    private void reg(String name, Fn f) { builtins.put(name, new Builtin(name, f)); }

    private void initBuiltins() {
        reg("print", a -> { out.accept(a.isEmpty() ? "null" : str(a.get(0))); return null; });
        reg("type", a -> type(a.get(0)));
        reg("str", a -> str(a.get(0)));
        reg("num", a -> num(a.get(0)));
        reg("len", a -> {
            Object x = a.get(0);
            if (x instanceof String) return (double) ((String) x).length();
            if (x instanceof List) return (double) ((List<?>) x).size();
            if (x instanceof Map) return (double) ((Map<?, ?>) x).size();
            throw error("len exige texto, lista ou mapa");
        });
        reg("range", a -> {
            long[] r = rangeBounds(a);
            double count = Math.ceil(r[2] > 0 ? ((double) r[1] - r[0]) / r[2] : ((double) r[0] - r[1]) / -r[2]);
            if (count < 0) count = 0;
            if (count > MAX_RANGE) throw error("range muito grande");
            List<Object> l = new ArrayList<>((int) count);
            long v = r[0];
            for (int i = 0; i < (int) count; i++, v += r[2]) l.add((double) v);
            return l;
        });
        reg("abs", a -> Math.abs(num(a.get(0))));
        reg("sqrt", a -> Math.sqrt(num(a.get(0))));
        reg("floor", a -> Math.floor(num(a.get(0))));
        reg("ceil", a -> Math.ceil(num(a.get(0))));
        reg("round", a -> {
            double x = num(a.get(0));
            if (a.size() < 2) return Math.floor(x + 0.5);
            int n = (int) num(a.get(1));
            if (n < 0 || n > 15) throw error("round: casas decimais de 0 a 15");
            double m = Math.pow(10, n);
            return Math.floor(x * m + 0.5) / m;
        });
        reg("min", a -> {
            List<Object> v = a.size() == 1 && a.get(0) instanceof List ? (List<Object>) a.get(0) : a;
            if (v.isEmpty()) throw error("min exige ao menos um valor");
            double x = Double.POSITIVE_INFINITY;
            for (Object o : v) x = Math.min(x, num(o));
            return x;
        });
        reg("max", a -> {
            List<Object> v = a.size() == 1 && a.get(0) instanceof List ? (List<Object>) a.get(0) : a;
            if (v.isEmpty()) throw error("max exige ao menos um valor");
            double x = Double.NEGATIVE_INFINITY;
            for (Object o : v) x = Math.max(x, num(o));
            return x;
        });
        reg("random", a -> {
            double lo = a.size() > 0 ? num(a.get(0)) : 0, hi = a.size() > 1 ? num(a.get(1)) : 1;
            return lo + Math.random() * (hi - lo);
        });
        reg("upper", a -> str(a.get(0)).toUpperCase(Locale.ROOT));
        reg("lower", a -> str(a.get(0)).toLowerCase(Locale.ROOT));
        reg("contains", a -> {
            Object c = a.get(0), x = a.get(1);
            if (c instanceof List) { for (Object o : (List<?>) c) if (same(o, x)) return true; return false; }
            if (c instanceof Map) return ((Map<?, ?>) c).containsKey(keyOf(x));
            return str(c).contains(str(x));
        });
        reg("push", a -> { ((List<Object>) a.get(0)).add(a.get(1)); return a.get(0); });
        reg("pop", a -> { List<?> l = (List<?>) a.get(0); return l.isEmpty() ? null : l.remove(l.size() - 1); });
        reg("keys", a -> new ArrayList<Object>(((Map<?, ?>) a.get(0)).keySet()));
        reg("has", a -> {
            Object c = a.get(0), x = a.get(1);
            if (c instanceof Map) return ((Map<?, ?>) c).containsKey(keyOf(x));
            if (c instanceof List) { for (Object o : (List<?>) c) if (same(o, x)) return true; return false; }
            throw error("has exige mapa ou lista");
        });
        reg("assert", a -> {
            if (!yes(a.get(0))) throw error(a.size() > 1 ? str(a.get(1)) : "assert falhou");
            return true;
        });
        reg("clock", a -> System.currentTimeMillis() / 1000.0);
        reg("inject", a -> {
            String name = str(a.get(0));
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw error("nome inválido");
            global.put(name, a.get(1));
            return a.get(1);
        });
        reg("sql", a -> {
            if (db == null) throw error("SQL não conectado ao host");
            String q = str(a.get(0));
            try { return db.execute(q); }
            catch (SlipError e) { throw e; }
            catch (RuntimeException e) { throw error("sql: " + e.getMessage()); }
        });
        reg("read", a -> read(str(a.get(0))));
        reg("write", a -> { write(str(a.get(0)), str(a.get(1)), false); return true; });
        reg("append", a -> { write(str(a.get(0)), str(a.get(1)), true); return true; });
        reg("exists", a -> file(str(a.get(0))).exists());
        reg("delete", a -> {
            File f = file(str(a.get(0)));
            checkWritable(f);
            return f.isFile() && f.delete();
        });
        reg("use", a -> { module(str(a.get(0))); return null; });
        reg("ui_limpar", a -> {
            for (SlipUi.Node n : uiById.values()) n.removed = true;
            uiById.clear(); uiRoot.clear();
            if (uiHost != null) uiHost.clear();
            return null;
        });
        reg("ui_remover", a -> {
            Object o = a.get(0);
            if (!(o instanceof SlipUi.Node)) throw error("ui_remover espera um elemento");
            uiLive((SlipUi.Node) o);
            uiRemove((SlipUi.Node) o);
            return null;
        });
        reg("native_load", a -> ffi(() -> natives().load(a)));
        reg("native_bind", a -> {
            final NativeLibraryManager.NativeFunction nf = (NativeLibraryManager.NativeFunction) ffi(() -> natives().bind(a));
            return (Fn) b -> ffi(() -> nf.call(b));
        });
        reg("native_call", a -> ffi(() -> natives().call(a)));
        reg("native_close", a -> { ffi(() -> { natives().closeLibrary(a); return null; }); return null; });
    }

    // ------------------------------------------------------------------ biblioteca: texto, listas, funções, JSON, data, rede

    private static <T> T lib(Supplier<T> s) {
        try { return s.get(); }
        catch (IllegalArgumentException e) { throw error(e.getMessage()); }
    }

    private Object callFn(Object f, Object... args) {
        if (!(f instanceof Fn)) throw error("esperada uma função, recebeu " + type(f));
        return ((Fn) f).call(new ArrayList<>(Arrays.asList(args)));
    }

    /** Chamada de map/filter/each...: funções do Slip recebem (item, índice); as nativas, só o item. */
    private Object callItem(Object f, Object item, int idx) {
        return f instanceof UserFn ? callFn(f, item, (double) idx) : callFn(f, item);
    }

    private static int cmpValues(Object x, Object y) {
        if (x instanceof Number && y instanceof Number) return Double.compare(((Number) x).doubleValue(), ((Number) y).doubleValue());
        if (x instanceof String && y instanceof String) return ((String) x).compareTo((String) y);
        throw error("sort: só compara número com número e texto com texto");
    }

    private static int clampIdx(Object v, int size, int dflt) {
        if (v == null) return dflt;
        int i = (int) num(v);
        if (i < 0) i += size;
        return Math.max(0, Math.min(size, i));
    }

    private static String capText(String t) {
        if (t.length() > MAX_TEXT) throw error("texto grande demais");
        return t;
    }

    private static Object deepCopy(Object o, int depth) {
        if (depth > 100) throw error("copy: estrutura profunda demais");
        if (o instanceof SlipObject) return o; // objetos são referências
        if (o instanceof List) {
            List<Object> r = new ArrayList<>();
            for (Object x : (List<?>) o) r.add(deepCopy(x, depth + 1));
            return r;
        }
        if (o instanceof Map) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (Map.Entry<?, ?> en : ((Map<?, ?>) o).entrySet()) r.put(String.valueOf(en.getKey()), deepCopy(en.getValue(), depth + 1));
            return r;
        }
        return o;
    }

    private Object httpCall(String method, Object url, Object body, Object headers) {
        if (++httpCalls > MAX_HTTP_CALLS) throw error("limite de " + MAX_HTTP_CALLS + " requisições por execução excedido");
        return lib(() -> SlipSys.http(method, str(url), body, headers));
    }

    private void initLib() {
        // ---- texto
        reg("split", a -> {
            String t = str(a.get(0)), sep = a.size() > 1 ? str(a.get(1)) : "";
            List<Object> r = new ArrayList<>();
            if (sep.isEmpty()) { for (int i = 0; i < t.length(); i++) r.add(String.valueOf(t.charAt(i))); return r; }
            int from = 0, at;
            while ((at = t.indexOf(sep, from)) >= 0) { r.add(t.substring(from, at)); from = at + sep.length(); }
            r.add(t.substring(from));
            return r;
        });
        reg("join", a -> {
            List<?> l = (List<?>) a.get(0);
            String sep = a.size() > 1 ? str(a.get(1)) : "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(sep); sb.append(str(l.get(i))); }
            return capText(sb.toString());
        });
        reg("replace", a -> {
            String old = str(a.get(1));
            if (old.isEmpty()) throw error("replace: o trecho a trocar não pode ser vazio");
            return capText(str(a.get(0)).replace(old, str(a.get(2))));
        });
        reg("trim", a -> str(a.get(0)).trim());
        reg("starts_with", a -> str(a.get(0)).startsWith(str(a.get(1))));
        reg("ends_with", a -> str(a.get(0)).endsWith(str(a.get(1))));
        reg("repeat", a -> {
            String t = str(a.get(0));
            double n = num(a.get(1));
            if (n < 0 || n != Math.rint(n)) throw error("repeat: quantidade deve ser inteira e >= 0");
            if (t.length() * n > MAX_TEXT) throw error("texto grande demais");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < (int) n; i++) sb.append(t);
            return sb.toString();
        });
        reg("pad_left", a -> {
            String t = str(a.get(0)), c = a.size() > 2 ? str(a.get(2)) : " ";
            int w = (int) num(a.get(1));
            if (c.length() != 1) throw error("pad_left: o preenchimento deve ter 1 caractere");
            if (w > MAX_TEXT) throw error("texto grande demais");
            StringBuilder sb = new StringBuilder();
            for (int i = t.length(); i < w; i++) sb.append(c);
            return sb.append(t).toString();
        });
        reg("pad_right", a -> {
            String t = str(a.get(0)), c = a.size() > 2 ? str(a.get(2)) : " ";
            int w = (int) num(a.get(1));
            if (c.length() != 1) throw error("pad_right: o preenchimento deve ter 1 caractere");
            if (w > MAX_TEXT) throw error("texto grande demais");
            StringBuilder sb = new StringBuilder(t);
            for (int i = t.length(); i < w; i++) sb.append(c);
            return sb.toString();
        });
        reg("char", a -> String.valueOf((char) (int) num(a.get(0))));
        reg("code", a -> { String t = str(a.get(0)); if (t.isEmpty()) throw error("code: texto vazio"); return (double) t.charAt(0); });
        reg("int", a -> { double d = num(a.get(0)); return d < 0 ? Math.ceil(d) : Math.floor(d); });
        reg("is_num", a -> {
            Object x = a.get(0);
            if (x instanceof Number) return true;
            return x instanceof String && NUMBER.matcher(((String) x).trim()).matches();
        });
        reg("fixed", a -> {
            int n = (int) num(a.get(1));
            if (n < 0 || n > 20) throw error("fixed: casas decimais de 0 a 20");
            return String.format(Locale.ROOT, "%." + n + "f", num(a.get(0)));
        });
        reg("format", a -> {
            String f = str(a.get(0));
            StringBuilder sb = new StringBuilder();
            int auto = 1;
            for (int i = 0; i < f.length(); i++) {
                char c = f.charAt(i);
                if (c == '{') {
                    if (i + 1 < f.length() && f.charAt(i + 1) == '{') { sb.append('{'); i++; continue; }
                    int j = f.indexOf('}', i);
                    if (j < 0) throw error("format: '{' sem '}'");
                    String k = f.substring(i + 1, j).trim();
                    int idx;
                    if (k.isEmpty()) idx = auto++;
                    else {
                        try { idx = Integer.parseInt(k) + 1; }
                        catch (NumberFormatException ex) { throw error("format: marcador inválido {" + k + "}"); }
                    }
                    if (idx < 1 || idx >= a.size()) throw error("format: faltam argumentos");
                    sb.append(str(a.get(idx)));
                    i = j;
                } else if (c == '}') {
                    if (i + 1 < f.length() && f.charAt(i + 1) == '}') i++;
                    sb.append('}');
                } else sb.append(c);
            }
            return capText(sb.toString());
        });

        // ---- texto e lista
        reg("slice", a -> {
            Object x = a.get(0);
            Object from = a.size() > 1 ? a.get(1) : null, to = a.size() > 2 ? a.get(2) : null;
            if (x instanceof List) {
                List<?> l = (List<?>) x;
                int s = clampIdx(from, l.size(), 0), e = clampIdx(to, l.size(), l.size());
                return s >= e ? new ArrayList<Object>() : new ArrayList<Object>(l.subList(s, e));
            }
            String t = str(x);
            int s = clampIdx(from, t.length(), 0), e = clampIdx(to, t.length(), t.length());
            return s >= e ? "" : t.substring(s, e);
        });
        reg("index_of", a -> {
            Object c = a.get(0), x = a.get(1);
            if (c instanceof List) {
                List<?> l = (List<?>) c;
                for (int i = 0; i < l.size(); i++) if (same(l.get(i), x)) return (double) i;
                return -1.0;
            }
            return (double) str(c).indexOf(str(x));
        });
        reg("reverse", a -> {
            Object x = a.get(0);
            if (x instanceof List) { List<Object> r = new ArrayList<>((List<Object>) x); Collections.reverse(r); return r; }
            return new StringBuilder(str(x)).reverse().toString();
        });

        // ---- listas
        reg("insert", a -> {
            List<Object> l = (List<Object>) a.get(0);
            int i = (int) num(a.get(1));
            if (i < 0) i += l.size();
            if (i < 0 || i > l.size()) throw error("insert: índice fora do limite");
            l.add(i, a.get(2));
            return l;
        });
        reg("remove", a -> {
            Object c = a.get(0);
            if (c instanceof Map) return ((Map<?, ?>) c).remove(keyOf(a.get(1)));
            List<?> l = (List<?>) c;
            return l.remove(index(a.get(1), l.size()));
        });
        reg("sort", a -> {
            List<Object> r = new ArrayList<>((List<Object>) a.get(0));
            final Object f = a.size() > 1 ? a.get(1) : null;
            try {
                if (f == null) Collections.sort(r, (x, y) -> cmpValues(x, y));
                else Collections.sort(r, (x, y) -> {
                    Object c = callFn(f, x, y);
                    if (!(c instanceof Number)) throw error("o comparador do sort deve devolver um número");
                    return (int) Math.signum(((Number) c).doubleValue());
                });
            } catch (IllegalArgumentException ex) { throw error("sort: comparador inconsistente"); }
            return r;
        });
        reg("sort_by", a -> {
            final Object f = a.get(1);
            List<Object[]> pairs = new ArrayList<>();
            for (Object o : (List<Object>) a.get(0)) pairs.add(new Object[]{callFn(f, o), o});
            Collections.sort(pairs, (p, q) -> cmpValues(p[0], q[0]));
            List<Object> r = new ArrayList<>();
            for (Object[] p : pairs) r.add(p[1]);
            return r;
        });
        reg("map", a -> {
            List<Object> r = new ArrayList<>();
            int i = 0;
            for (Object o : new ArrayList<>((List<Object>) a.get(0))) r.add(callItem(a.get(1), o, i++));
            return r;
        });
        reg("filter", a -> {
            List<Object> r = new ArrayList<>();
            int i = 0;
            for (Object o : new ArrayList<>((List<Object>) a.get(0))) if (yes(callItem(a.get(1), o, i++))) r.add(o);
            return r;
        });
        reg("each", a -> {
            int i = 0;
            for (Object o : new ArrayList<>((List<Object>) a.get(0))) callItem(a.get(1), o, i++);
            return null;
        });
        reg("find", a -> {
            int i = 0;
            for (Object o : new ArrayList<>((List<Object>) a.get(0))) if (yes(callItem(a.get(1), o, i++))) return o;
            return null;
        });
        reg("any", a -> {
            int i = 0;
            for (Object o : new ArrayList<>((List<Object>) a.get(0))) if (yes(a.size() > 1 ? callItem(a.get(1), o, i++) : o)) return true;
            return false;
        });
        reg("all", a -> {
            int i = 0;
            for (Object o : new ArrayList<>((List<Object>) a.get(0))) if (!yes(a.size() > 1 ? callItem(a.get(1), o, i++) : o)) return false;
            return true;
        });
        reg("reduce", a -> {
            List<Object> l = new ArrayList<>((List<Object>) a.get(0));
            Object f = a.get(1), acc;
            int start;
            if (a.size() > 2) { acc = a.get(2); start = 0; }
            else {
                if (l.isEmpty()) throw error("reduce de lista vazia exige valor inicial");
                acc = l.get(0); start = 1;
            }
            for (int i = start; i < l.size(); i++) acc = callFn(f, acc, l.get(i));
            return acc;
        });
        reg("sum", a -> { double t = 0; for (Object o : (List<?>) a.get(0)) t += num(o); return t; });
        reg("unique", a -> {
            List<Object> r = new ArrayList<>();
            outer:
            for (Object o : (List<?>) a.get(0)) {
                for (Object x : r) if (same(x, o)) continue outer;
                r.add(o);
            }
            return r;
        });

        // ---- mapas e cópias
        reg("values", a -> new ArrayList<Object>(((Map<?, ?>) a.get(0)).values()));
        reg("entries", a -> {
            List<Object> r = new ArrayList<>();
            for (Map.Entry<?, ?> en : ((Map<?, ?>) a.get(0)).entrySet()) r.add(new ArrayList<Object>(Arrays.asList(en.getKey(), en.getValue())));
            return r;
        });
        reg("merge", a -> {
            Map<String, Object> r = new LinkedHashMap<>();
            for (Object m : a) for (Map.Entry<?, ?> en : ((Map<?, ?>) m).entrySet()) r.put(String.valueOf(en.getKey()), en.getValue());
            return r;
        });
        reg("copy", a -> deepCopy(a.get(0), 0));

        // ---- classes
        reg("is_a", a -> {
            Object o = a.get(0), c = a.get(1);
            if (!(c instanceof SlipClass)) throw error("is_a espera uma classe como segundo argumento");
            if (!(o instanceof SlipObject)) return false;
            for (SlipClass k = ((SlipObject) o).cls; k != null; k = k.parent) if (k == c) return true;
            return false;
        });

        // ---- JSON
        reg("json_parse", a -> lib(() -> SlipJson.parse(str(a.get(0)))));
        reg("json_str", a -> lib(() -> SlipJson.stringify(a.get(0), a.size() > 1 && yes(a.get(1)))));

        // ---- data e hora (milissegundos desde 1970; fuso opcional, ex.: "UTC", "America/Sao_Paulo")
        reg("now", a -> (double) System.currentTimeMillis());
        reg("date_str", a -> lib(() -> SlipSys.dateStr(num(a.get(0)),
                a.size() > 1 && a.get(1) != null ? str(a.get(1)) : "yyyy-MM-dd HH:mm:ss", a.size() > 2 ? str(a.get(2)) : null)));
        reg("date_parse", a -> lib(() -> SlipSys.dateParse(str(a.get(0)),
                a.size() > 1 && a.get(1) != null ? str(a.get(1)) : "yyyy-MM-dd HH:mm:ss", a.size() > 2 ? str(a.get(2)) : null)));
        reg("date_parts", a -> lib(() -> SlipSys.dateParts(
                a.size() > 0 && a.get(0) != null ? num(a.get(0)) : (double) System.currentTimeMillis(), a.size() > 1 ? str(a.get(1)) : null)));
        reg("sleep", a -> { lib(() -> { SlipSys.sleep(num(a.get(0))); return null; }); return null; });

        // ---- rede
        reg("http", a -> httpCall(str(a.get(0)).toUpperCase(Locale.ROOT), a.get(1), a.size() > 2 ? a.get(2) : null, a.size() > 3 ? a.get(3) : null));
        reg("http_get", a -> httpCall("GET", a.get(0), null, a.size() > 1 ? a.get(1) : null));
        reg("http_post", a -> httpCall("POST", a.get(0), a.size() > 1 ? a.get(1) : null, a.size() > 2 ? a.get(2) : null));
    }
}
