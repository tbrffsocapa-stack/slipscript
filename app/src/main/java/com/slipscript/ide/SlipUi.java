package com.slipscript.ide;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Interface visual criada por scripts Slip.
 *
 *   vt<tipo>"nome"<> + <>!valor!<> + <>!valor!<> ...
 *
 * Tipos: texto (padrão), botao, entrada, caixa. O significado de cada valor depois do "+" vem do seu tipo:
 * "#rrggbb" = cor, outro texto = texto, número = tamanho, função = ação (clique), elemento = onde colocar,
 * mapa = propriedades com nome. Este arquivo só guarda o modelo e valida propriedades; quem desenha é o host.
 */
public final class SlipUi {
    private SlipUi() {}

    public static final int MAX_NODES = 2000;
    public static final List<String> TYPES = Collections.unmodifiableList(Arrays.asList("texto", "botao", "entrada", "caixa"));

    /** Quem desenha. Chamado na thread do interpretador: o host deve repassar para a thread de interface. */
    public interface Host {
        /** Elemento novo, já com o pai definitivo (0 = raiz) e todas as propriedades. {@code props} é uma cópia. */
        void create(int id, String type, int parentId, Map<String, Object> props);
        /** Uma propriedade mudou. Para ações (ao_clicar/ao_mudar) o valor é apenas Boolean.TRUE (a ação fica no interpretador). */
        void update(int id, String prop, Object value);
        /** Elemento movido para outro pai (0 = raiz), no fim da lista de filhos. */
        void move(int id, int parentId);
        void remove(int id);
        void clear();
    }

    private static final Pattern COLOR = Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");

    public static boolean isColor(String s) { return COLOR.matcher(s).matches(); }

    /** "#abc" -> "#aabbcc"; sempre minúsculo (o formato de 8 dígitos é #aarrggbb). */
    static String normColor(String s) {
        s = s.toLowerCase(Locale.ROOT);
        if (s.length() == 4) {
            StringBuilder b = new StringBuilder("#");
            for (int i = 1; i < 4; i++) b.append(s.charAt(i)).append(s.charAt(i));
            return b.toString();
        }
        return s;
    }

    private static final Set<String> COLORS = set("cor", "fundo");
    private static final Set<String> NUMBERS = set("tamanho", "raio", "margem", "espaco");
    private static final Set<String> BOOLS = set("negrito", "visivel");
    private static final Set<String> SIZES = set("largura", "altura");
    private static final Set<String> TEXTS = set("texto", "valor", "dica");
    private static final Map<String, List<String>> ENUMS = new HashMap<>();
    static {
        ENUMS.put("direcao", Arrays.asList("horizontal", "vertical"));
        ENUMS.put("alinhar", Arrays.asList("esquerda", "centro", "direita"));
    }
    public static final Set<String> HANDLERS = set("ao_clicar", "ao_mudar");

    private static Set<String> set(String... v) { return new HashSet<>(Arrays.asList(v)); }

    /** Todas as propriedades aceitas (para mensagens de erro). */
    public static List<String> allProps() {
        List<String> l = new ArrayList<>();
        l.addAll(TEXTS); l.addAll(COLORS); l.addAll(NUMBERS); l.addAll(BOOLS); l.addAll(SIZES);
        l.addAll(ENUMS.keySet()); l.addAll(HANDLERS); l.add("pai");
        Collections.sort(l);
        return l;
    }

    public static final class Node {
        public final int id;
        public final String type, name;
        Node parent; // null = raiz
        final List<Node> children = new ArrayList<>();
        final Map<String, Object> props = new LinkedHashMap<>();
        final Map<String, Object> handlers = new LinkedHashMap<>(); // ao_clicar / ao_mudar -> função do Slip
        boolean removed;

        Node(int id, String type, String name) { this.id = id; this.type = type; this.name = name; }

        /** Normaliza o nome de propriedade (em "entrada", texto e valor são a mesma coisa). */
        String key(String prop) { return type.equals("entrada") && prop.equals("texto") ? "valor" : prop; }

        /** Valida e converte o valor; lança IllegalArgumentException com mensagem em português. */
        Object normalize(String prop, Object v) {
            if (v == null) throw new IllegalArgumentException("'" + prop + "' não aceita null");
            if (COLORS.contains(prop)) {
                if (!(v instanceof String) || !isColor((String) v)) throw new IllegalArgumentException("'" + prop + "' espera uma cor como \"#rrggbb\"");
                return normColor((String) v);
            }
            if (NUMBERS.contains(prop)) {
                double d = number(prop, v);
                if (d < 0 || d > 1000) throw new IllegalArgumentException("'" + prop + "' deve estar entre 0 e 1000");
                return d;
            }
            if (BOOLS.contains(prop)) {
                if (!(v instanceof Boolean)) throw new IllegalArgumentException("'" + prop + "' espera true ou false");
                return v;
            }
            if (SIZES.contains(prop)) {
                if (v instanceof String && (v.equals("cheia") || v.equals("auto"))) return v;
                double d = number(prop, v);
                if (d < 0 || d > 5000) throw new IllegalArgumentException("'" + prop + "' deve estar entre 0 e 5000, ou ser \"cheia\" / \"auto\"");
                return d;
            }
            if (TEXTS.contains(prop)) return SlipInterpreter.str(v);
            List<String> allowed = ENUMS.get(prop);
            if (allowed != null) {
                if (!(v instanceof String) || !allowed.contains(v)) throw new IllegalArgumentException("'" + prop + "' aceita: " + String.join(", ", allowed));
                return v;
            }
            throw new IllegalArgumentException("propriedade desconhecida: " + prop + " (válidas: " + String.join(", ", allProps()) + ")");
        }

        private static double number(String prop, Object v) {
            if (!(v instanceof Number)) throw new IllegalArgumentException("'" + prop + "' espera um número");
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("'" + prop + "' espera um número válido");
            return d;
        }

        /** Leitura vinda do script: elem.texto, elem.tipo, elem.filhos... */
        Object get(String prop) {
            switch (prop) {
                case "tipo": return type;
                case "nome": return name;
                case "pai": return parent;
                case "filhos": return new ArrayList<Object>(children);
                default: break;
            }
            String k = key(prop);
            if (HANDLERS.contains(k)) return handlers.get(k);
            return props.get(k);
        }

        boolean isAncestorOf(Node other) {
            for (Node n = other; n != null; n = n.parent) if (n == this) return true;
            return false;
        }

        @Override public String toString() { return "<elemento " + type + " \"" + name + "\">"; }
    }

    /** Árvore em texto (usada pelo CLI e útil em testes). */
    static String tree(List<Node> roots) {
        StringBuilder sb = new StringBuilder();
        for (Node n : roots) tree(sb, n, 0);
        return sb.toString();
    }

    private static void tree(StringBuilder sb, Node n, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(n.type).append(" \"").append(n.name).append('"');
        for (Map.Entry<String, Object> e : n.props.entrySet()) {
            sb.append(' ').append(e.getKey()).append('=');
            sb.append(e.getValue() instanceof String ? "\"" + e.getValue() + "\"" : SlipInterpreter.str(e.getValue()));
        }
        for (String h : n.handlers.keySet()) sb.append(' ').append(h).append("=<função>");
        sb.append('\n');
        for (Node c : n.children) tree(sb, c, depth + 1);
    }
}
