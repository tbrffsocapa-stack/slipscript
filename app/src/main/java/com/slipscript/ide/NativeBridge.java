package com.slipscript.ide;

/** NOVO. Ponte JNI de baixo nível: dlopen/dlsym/dlclose e invocação por ponteiro de função (ver slipffi.cpp). */
final class NativeBridge {
    /** Falha na camada nativa (carregamento, símbolo, conversão ou execução). */
    public static final class NativeException extends RuntimeException {
        public NativeException(String message) { super(message); }
    }

    static final int RET_VOID = 0, RET_I32 = 1, RET_I64 = 2, RET_F32 = 3, RET_F64 = 4,
            RET_BOOL = 5, RET_STR = 6, RET_PTR = 7, RET_STR_OWNED = 8;
    static final int MAX_INT_ARGS = 6, MAX_FP_ARGS = 8;

    private static boolean loaded;
    private static String loadError;

    private NativeBridge() {}

    /**
     * Android (APK): System.loadLibrary("slipffi") encontra a lib empacotada em jniLibs.
     * Termux/JVM: use -Dslip.ffi.lib=/caminho/absoluto/libslipffi.so.
     */
    static synchronized void ensureLoaded() {
        if (loaded) return;
        if (loadError != null) throw new NativeException(loadError);
        try {
            String path = System.getProperty("slip.ffi.lib");
            if (path != null && !path.isEmpty()) System.load(path); else System.loadLibrary("slipffi");
            loaded = true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            loadError = "camada nativa 'slipffi' indisponível: " + e.getMessage();
            throw new NativeException(loadError);
        }
    }

    private static native String nativeAbi();
    private static native long nativeOpen(String path);
    private static native long nativeSym(long handle, String name);
    private static native void nativeClose(long handle);
    private static native long nativeInvoke(long fn, int ret, long[] ia, long[] fa, byte[][] sargs, byte[][] outS);

    static String abi() { ensureLoaded(); return nativeAbi(); }
    static long open(String path) { ensureLoaded(); return nativeOpen(path); }
    static long sym(long handle, String name) { ensureLoaded(); return nativeSym(handle, name); }
    static void close(long handle) { ensureLoaded(); nativeClose(handle); }
    static long invoke(long fn, int ret, long[] ia, long[] fa, byte[][] sargs, byte[][] outS) {
        ensureLoaded();
        return nativeInvoke(fn, ret, ia, fa, sargs, outS);
    }
}
