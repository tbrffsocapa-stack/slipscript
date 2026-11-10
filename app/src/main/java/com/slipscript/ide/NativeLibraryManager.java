package com.slipscript.ide;

import com.slipscript.ide.NativeBridge.NativeException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * NOVO. Gerencia bibliotecas nativas de uma execução do interpretador, valida assinaturas e converte
 * valores Slip (Double, String, Boolean, null) de/para tipos C. Não depende de classes do interpretador.
 */
public final class NativeLibraryManager implements AutoCloseable {
    public interface NativeFunction { Object call(List<Object> args); }

    /** Biblioteca carregada (valor opaco visível ao script). */
    public static final class Library {
        final String name; final String key; final long handle; volatile boolean closed;
        Library(String name, String key, long handle) { this.name = name; this.key = key; this.handle = handle; }
        @Override public String toString() { return "native-lib(" + name + ")"; }
    }

    /** Ponteiro opaco devolvido por funções nativas (valor opaco visível ao script). */
    public static final class Pointer {
        final long address;
        Pointer(long address) { this.address = address; }
        @Override public boolean equals(Object o) { return o instanceof Pointer && ((Pointer) o).address == address; }
        @Override public int hashCode() { return Long.hashCode(address); }
        @Override public String toString() { return "ptr(0x" + Long.toHexString(address) + ")"; }
    }

    private enum T {
        VOID("void", NativeBridge.RET_VOID), I32("i32", NativeBridge.RET_I32), I64("i64", NativeBridge.RET_I64),
        F32("f32", NativeBridge.RET_F32), F64("f64", NativeBridge.RET_F64), BOOL("bool", NativeBridge.RET_BOOL),
        STR("str", NativeBridge.RET_STR), STR_OWNED("str_owned", NativeBridge.RET_STR_OWNED), PTR("ptr", NativeBridge.RET_PTR);
        final String text; final int code;
        T(String text, int code) { this.text = text; this.code = code; }
        boolean isFp() { return this == F32 || this == F64; }
        static T of(String s) { for (T t : values()) if (t.text.equals(s)) return t; return null; }
    }

    private static final class Signature {
        final T ret; final T[] params; final String text;
        Signature(T ret, T[] params, String text) { this.ret = ret; this.params = params; this.text = text; }
    }

    /** Processo inteiro: um dlopen por caminho, com contagem de referências entre sessões. */
    private static final Map<String, long[]> SHARED = new HashMap<>(); // key -> {handle, refs}
    private static final double MAX_SAFE = 9007199254740992.0; // 2^53

    private final File libsDir;
    private final Map<String, Library> loaded = new LinkedHashMap<>();
    private final Map<String, NativeFunction> bound = new HashMap<>();

    public NativeLibraryManager(File slipHome) { this.libsDir = new File(slipHome, "libs"); }

    // ---------- API usada pelo interpretador ----------

    /** native_load("libx.so"): procura em files/slip/libs/ e, se não houver, pelo nome simples no caminho do linker. */
    public synchronized Library load(List<Object> a) {
        need(a, 1, "native_load(nome)");
        if (!(a.get(0) instanceof String)) throw new NativeException("native_load exige o nome da biblioteca como texto");
        String name = (String) a.get(0);
        if (!name.matches("[A-Za-z0-9_.+\\-]+(/[A-Za-z0-9_.+\\-]+)*") || name.contains(".."))
            throw new NativeException("nome de biblioteca inválido: " + name);
        Library existing = loaded.get(name);
        if (existing != null && !existing.closed) return existing;
        String target;
        File f = new File(libsDir, name);
        try {
            String base = libsDir.getCanonicalPath();
            if (f.isFile()) {
                String c = f.getCanonicalPath();
                if (!c.startsWith(base + File.separator)) throw new NativeException("biblioteca fora da pasta slip/libs");
                target = c;
            } else if (name.indexOf('/') < 0) {
                target = name; // lib empacotada no APK (jniLibs) ou resolvida pelo linker do sistema
            } else {
                throw new NativeException("biblioteca não encontrada em slip/libs: " + name);
            }
        } catch (IOException e) { throw new NativeException("caminho de biblioteca inválido: " + name); }
        long handle;
        synchronized (SHARED) {
            long[] s = SHARED.get(target);
            if (s == null) { s = new long[]{NativeBridge.open(target), 0}; SHARED.put(target, s); }
            s[1]++;
            handle = s[0];
        }
        Library lib = new Library(name, target, handle);
        loaded.put(name, lib);
        return lib;
    }

    /** native_bind(lib, "simbolo", "i32(i32,i32)"): resolve o símbolo e devolve uma função chamável. */
    public synchronized NativeFunction bind(List<Object> a) {
        need(a, 3, "native_bind(lib, simbolo, assinatura)");
        Library lib = lib(a.get(0));
        if (!(a.get(1) instanceof String) || !((String) a.get(1)).matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new NativeException("nome de símbolo inválido: " + a.get(1));
        if (!(a.get(2) instanceof String)) throw new NativeException("a assinatura deve ser texto, ex.: \"i32(i32,i32)\"");
        String sym = (String) a.get(1);
        Signature sig = parse((String) a.get(2));
        String key = lib.handle + ":" + sym + ":" + sig.text;
        NativeFunction cached = bound.get(key);
        if (cached != null) return cached;
        final long addr = NativeBridge.sym(lib.handle, sym);
        NativeFunction fn = args -> invoke(lib, addr, sig, sym, args);
        bound.put(key, fn);
        return fn;
    }

    /** native_call(lib, "simbolo", "assinatura", args...): bind (com cache) + chamada. */
    public Object call(List<Object> a) {
        need(a, 3, "native_call(lib, simbolo, assinatura, args...)");
        return bind(a.subList(0, 3)).call(new ArrayList<>(a.subList(3, a.size())));
    }

    /** native_close(lib). */
    public synchronized void closeLibrary(List<Object> a) {
        need(a, 1, "native_close(lib)");
        release(lib(a.get(0)));
    }

    @Override public synchronized void close() {
        for (Library l : new ArrayList<>(loaded.values())) release(l);
        loaded.clear(); bound.clear();
    }

    // ---------- internos ----------

    private static void need(List<Object> a, int n, String usage) {
        if (a.size() < n) throw new NativeException("argumentos insuficientes; uso: " + usage);
    }

    private Library lib(Object o) {
        Library l = null;
        if (o instanceof Library) l = (Library) o;
        else if (o instanceof String) l = loaded.get(o);
        if (l == null) throw new NativeException("biblioteca não carregada: use native_load antes (" + describe(o) + ")");
        if (l.closed) throw new NativeException("a biblioteca '" + l.name + "' já foi fechada");
        return l;
    }

    private void release(Library l) {
        if (l.closed) return;
        l.closed = true;
        synchronized (SHARED) {
            long[] s = SHARED.get(l.key);
            if (s != null && --s[1] <= 0) {
                SHARED.remove(l.key);
                try { NativeBridge.close(s[0]); } catch (RuntimeException ignored) { }
            }
        }
        bound.keySet().removeIf(k -> k.startsWith(l.handle + ":"));
    }

    private static Signature parse(String text) {
        String s = text.replace(" ", "");
        int open = s.indexOf('('), close = s.lastIndexOf(')');
        if (open < 0 || close != s.length() - 1 || close < open)
            throw new NativeException("assinatura inválida '" + text + "'; formato: retorno(tipo,tipo,...)");
        T ret = T.of(s.substring(0, open));
        if (ret == null) throw new NativeException("tipo de retorno desconhecido em '" + text + "'");
        String inner = s.substring(open + 1, close);
        String[] parts = inner.isEmpty() ? new String[0] : inner.split(",", -1);
        T[] params = new T[parts.length];
        int ni = 0, nf = 0;
        for (int i = 0; i < parts.length; i++) {
            T t = T.of(parts[i]);
            if (t == null || t == T.VOID || t == T.STR_OWNED)
                throw new NativeException("tipo de parâmetro inválido '" + parts[i] + "' em '" + text + "'");
            params[i] = t;
            if (t.isFp()) nf++; else ni++;
        }
        if (ni > NativeBridge.MAX_INT_ARGS || nf > NativeBridge.MAX_FP_ARGS)
            throw new NativeException("assinatura excede o limite (" + NativeBridge.MAX_INT_ARGS + " inteiros/ponteiros e "
                    + NativeBridge.MAX_FP_ARGS + " decimais): " + text);
        StringBuilder canon = new StringBuilder(ret.text).append('(');
        for (int i = 0; i < params.length; i++) canon.append(i > 0 ? "," : "").append(params[i].text);
        return new Signature(ret, params, canon.append(')').toString());
    }

    private Object invoke(Library lib, long fn, Signature sig, String sym, List<Object> args) {
        if (lib.closed) throw new NativeException("a biblioteca '" + lib.name + "' já foi fechada");
        if (args.size() != sig.params.length)
            throw new NativeException(sym + ": esperava " + sig.params.length + " argumento(s), recebeu " + args.size());
        long[] ia = new long[NativeBridge.MAX_INT_ARGS];
        long[] fa = new long[NativeBridge.MAX_FP_ARGS];
        byte[][] sargs = null;
        int ni = 0, nf = 0;
        for (int k = 0; k < sig.params.length; k++) {
            Object v = args.get(k); T t = sig.params[k];
            switch (t) {
                case I32: { long x = integral(sym, k, t, v);
                    if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE) throw bad(sym, k, t, "valor fora do intervalo int32: " + x);
                    ia[ni++] = x; break; }
                case I64: ia[ni++] = integral(sym, k, t, v); break;
                case BOOL: if (!(v instanceof Boolean)) throw bad(sym, k, t, "recebido " + describe(v));
                    ia[ni++] = ((Boolean) v) ? 1 : 0; break;
                case F32: { double d = real(sym, k, t, v);
                    float f = (float) d;
                    if (Float.isInfinite(f) && !Double.isInfinite(d)) throw bad(sym, k, t, "valor fora do intervalo float");
                    fa[nf++] = Float.floatToRawIntBits(f) & 0xFFFFFFFFL; break; }
                case F64: fa[nf++] = Double.doubleToRawLongBits(real(sym, k, t, v)); break;
                case STR: {
                    if (v != null && !(v instanceof String)) throw bad(sym, k, t, "recebido " + describe(v));
                    if (sargs == null) sargs = new byte[NativeBridge.MAX_INT_ARGS][];
                    if (v != null) {
                        if (((String) v).indexOf('\0') >= 0) throw bad(sym, k, t, "texto contém caractere NUL");
                        sargs[ni] = ((String) v).getBytes(StandardCharsets.UTF_8);
                    }
                    ia[ni++] = 0; break; }
                case PTR: if (v == null) ia[ni++] = 0;
                    else if (v instanceof Pointer) ia[ni++] = ((Pointer) v).address;
                    else throw bad(sym, k, t, "recebido " + describe(v) + " (use null ou um ponteiro devolvido por função nativa)");
                    break;
                default: throw new NativeException("tipo de parâmetro inválido");
            }
        }
        byte[][] outS = new byte[1][];
        long raw = NativeBridge.invoke(fn, sig.ret.code, ia, fa, sargs, outS);
        switch (sig.ret) {
            case VOID: return null;
            case I32: return (double) (int) raw;
            case I64: if (Math.abs((double) raw) > MAX_SAFE)
                    throw new NativeException(sym + ": retorno int64 " + raw + " não é representável como número Slip (limite 2^53)");
                return (double) raw;
            case F32: return (double) Float.intBitsToFloat((int) raw);
            case F64: return Double.longBitsToDouble(raw);
            case BOOL: return raw != 0;
            case STR: case STR_OWNED: return outS[0] == null ? null : new String(outS[0], StandardCharsets.UTF_8);
            case PTR: return raw == 0 ? null : new Pointer(raw);
            default: throw new NativeException("tipo de retorno inválido");
        }
    }

    private static long integral(String sym, int k, T t, Object v) {
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) return ((Number) v).longValue();
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) throw bad(sym, k, t, "esperava inteiro, recebido " + d);
            if (Math.abs(d) > MAX_SAFE) throw bad(sym, k, t, "valor além de 2^53 não é representável com exatidão");
            return (long) d;
        }
        throw bad(sym, k, t, "recebido " + describe(v));
    }

    private static double real(String sym, int k, T t, Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        throw bad(sym, k, t, "recebido " + describe(v));
    }

    private static NativeException bad(String sym, int k, T t, String why) {
        return new NativeException(sym + ": argumento " + (k + 1) + " (" + t.text + ") incompatível: " + why);
    }

    private static String describe(Object o) {
        if (o == null) return "null";
        if (o instanceof Boolean) return "bool";
        if (o instanceof Number) return "number";
        if (o instanceof String) return "string";
        if (o instanceof List) return "list";
        if (o instanceof Map) return "map";
        if (o instanceof Pointer) return "pointer";
        if (o instanceof Library) return "library";
        return "object";
    }
}
