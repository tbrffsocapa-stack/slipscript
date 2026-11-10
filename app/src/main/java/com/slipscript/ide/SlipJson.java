package com.slipscript.ide;

import java.util.*;

/** JSON <-> valores Slip (null, Boolean, Double, String, List, Map). Sem dependência do Android. */
final class SlipJson {
    private static final int MAX_DEPTH = 100;
    private final String s;
    private int i;

    private SlipJson(String s) { this.s = s; }

    static Object parse(String text) {
        SlipJson p = new SlipJson(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw p.fail("texto sobrando depois do valor");
        return v;
    }

    private IllegalArgumentException fail(String m) {
        return new IllegalArgumentException("json inválido: " + m + " (posição " + i + ")");
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
        }
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw fail("aninhamento profundo demais");
        if (i >= s.length()) throw fail("fim inesperado");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': return lit("true", Boolean.TRUE);
            case 'f': return lit("false", Boolean.FALSE);
            case 'n': return lit("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw fail("caractere inesperado '" + c + "'");
        }
    }

    private Object lit(String word, Object v) {
        if (!s.startsWith(word, i)) throw fail("valor inválido");
        i += word.length();
        return v;
    }

    private Object number() {
        int j = i;
        while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
        try { return Double.parseDouble(s.substring(j, i)); }
        catch (NumberFormatException e) { i = j; throw fail("número inválido"); }
    }

    private String string() {
        i++; // aspas de abertura
        StringBuilder b = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) { i--; throw fail("caractere de controle dentro do texto"); }
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw fail("escape \\u incompleto");
                    try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                    catch (NumberFormatException ex) { throw fail("escape \\u inválido"); }
                    i += 4;
                    break;
                default: i--; throw fail("escape inválido");
            }
        }
        throw fail("texto sem fechamento");
    }

    private Object array(int depth) {
        i++;
        List<Object> l = new ArrayList<>();
        ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            if (i >= s.length()) throw fail("lista sem fechamento");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') { i--; throw fail("esperado ',' ou ']'"); }
        }
    }

    private Object object(int depth) {
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw fail("esperada uma chave entre aspas");
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw fail("esperado ':'");
            i++;
            ws();
            m.put(k, value(depth + 1));
            ws();
            if (i >= s.length()) throw fail("mapa sem fechamento");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') { i--; throw fail("esperado ',' ou '}'"); }
        }
    }

    // ------------------------------------------------------------------ texto -> JSON

    static String stringify(Object v, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, pretty, 0, new IdentityHashMap<>());
        return sb.toString();
    }

    private static void indent(StringBuilder sb, int level) {
        sb.append('\n');
        for (int k = 0; k < level; k++) sb.append("  ");
    }

    private static void write(StringBuilder sb, Object v, boolean pretty, int level, IdentityHashMap<Object, Boolean> seen) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null"); else sb.append(SlipInterpreter.numStr(d));
            return;
        }
        if (v instanceof String) { quote(sb, (String) v); return; }
        if (v instanceof List || v instanceof Map) {
            if (level > MAX_DEPTH) throw new IllegalArgumentException("json_str: estrutura profunda demais");
            if (seen.put(v, Boolean.TRUE) != null) throw new IllegalArgumentException("json_str: referência circular");
            if (v instanceof List) {
                List<?> l = (List<?>) v;
                if (l.isEmpty()) sb.append("[]");
                else {
                    sb.append('[');
                    boolean first = true;
                    for (Object x : l) {
                        if (!first) sb.append(',');
                        first = false;
                        if (pretty) indent(sb, level + 1);
                        write(sb, x, pretty, level + 1, seen);
                    }
                    if (pretty) indent(sb, level);
                    sb.append(']');
                }
            } else {
                Map<?, ?> m = (Map<?, ?>) v;
                if (m.isEmpty()) sb.append("{}");
                else {
                    sb.append('{');
                    boolean first = true;
                    for (Map.Entry<?, ?> en : m.entrySet()) {
                        if (!first) sb.append(',');
                        first = false;
                        if (pretty) indent(sb, level + 1);
                        quote(sb, String.valueOf(en.getKey()));
                        sb.append(pretty ? ": " : ":");
                        write(sb, en.getValue(), pretty, level + 1, seen);
                    }
                    if (pretty) indent(sb, level);
                    sb.append('}');
                }
            }
            seen.remove(v);
            return;
        }
        sb.append("null"); // funções, elementos de tela e objetos opacos não existem em JSON
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        sb.append('"');
    }
}
