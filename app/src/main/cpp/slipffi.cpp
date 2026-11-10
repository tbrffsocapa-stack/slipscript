// SlipScript FFI shim (NOVO). Ponte JNI entre NativeBridge.java e bibliotecas .so arbitrarias.
//
// Estratégia de chamada: despacho por classe de registrador. Em AArch64 (AAPCS64) e x86-64 (SysV),
// argumentos inteiros/ponteiros e de ponto flutuante usam bancos de registradores independentes.
// Chamar o ponteiro de função como f(i0..i5, d0..d7) preenche corretamente QUALQUER assinatura com
// até 6 argumentos inteiros e 8 de ponto flutuante, em qualquer ordem; argumentos a mais são ignorados pelo callee.
// Limitações: sem funções variádicas (printf...), sem structs por valor, sem argumentos na pilha, só 64-bit.
#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <string>

#if defined(__aarch64__) || defined(__x86_64__)
#define SLIP_ABI_SUPPORTED 1
#else
#define SLIP_ABI_SUPPORTED 0
#endif

namespace {
constexpr int MAX_INT = 6;
constexpr int MAX_FP = 8;
enum Ret { R_VOID = 0, R_I32 = 1, R_I64 = 2, R_F32 = 3, R_F64 = 4, R_BOOL = 5, R_STR = 6, R_PTR = 7, R_STR_OWNED = 8 };

typedef int64_t (*FnI)(int64_t, int64_t, int64_t, int64_t, int64_t, int64_t,
                       double, double, double, double, double, double, double, double);
typedef double (*FnD)(int64_t, int64_t, int64_t, int64_t, int64_t, int64_t,
                      double, double, double, double, double, double, double, double);
typedef float (*FnF)(int64_t, int64_t, int64_t, int64_t, int64_t, int64_t,
                     double, double, double, double, double, double, double, double);

void throwNative(JNIEnv* env, const std::string& msg) {
    jclass c = env->FindClass("com/slipscript/ide/NativeBridge$NativeException");
    if (c) env->ThrowNew(c, msg.c_str());
}
inline double bitsToDouble(jlong b) { double d; std::memcpy(&d, &b, sizeof d); return d; }
}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_com_slipscript_ide_NativeBridge_nativeAbi(JNIEnv* env, jclass) {
#if defined(__aarch64__)
    return env->NewStringUTF("arm64-v8a");
#elif defined(__x86_64__)
    return env->NewStringUTF("x86_64");
#elif defined(__arm__)
    return env->NewStringUTF("armeabi-v7a (chamadas nativas NÃO suportadas)");
#else
    return env->NewStringUTF("desconhecida (chamadas nativas NÃO suportadas)");
#endif
}

JNIEXPORT jlong JNICALL Java_com_slipscript_ide_NativeBridge_nativeOpen(JNIEnv* env, jclass, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return 0;
    std::string spath(p);
    env->ReleaseStringUTFChars(path, p);
    dlerror();
    void* h = dlopen(spath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        const char* e = dlerror();
        throwNative(env, "não foi possível carregar '" + spath + "': " + (e ? e : "erro desconhecido"));
        return 0;
    }
    return (jlong)(intptr_t)h;
}

JNIEXPORT jlong JNICALL Java_com_slipscript_ide_NativeBridge_nativeSym(JNIEnv* env, jclass, jlong handle, jstring name) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    if (!n) return 0;
    std::string sname(n);
    env->ReleaseStringUTFChars(name, n);
    dlerror();
    void* s = dlsym((void*)(intptr_t)handle, sname.c_str());
    const char* e = dlerror();
    if (!s || e) {
        throwNative(env, "símbolo '" + sname + "' não encontrado" + (e ? std::string(": ") + e : std::string()));
        return 0;
    }
    return (jlong)(intptr_t)s;
}

JNIEXPORT void JNICALL Java_com_slipscript_ide_NativeBridge_nativeClose(JNIEnv*, jclass, jlong handle) {
    if (handle) dlclose((void*)(intptr_t)handle);
}

// ia: 6 inteiros; fa: 8 padrões de bits de FP (f32 nos 32 bits baixos); sargs: bytes UTF-8 por slot inteiro (ou null);
// outS[0]: bytes de retorno de string. Retorna o valor bruto (inteiro, ou bits de float/double).
JNIEXPORT jlong JNICALL Java_com_slipscript_ide_NativeBridge_nativeInvoke(
        JNIEnv* env, jclass, jlong fn, jint ret, jlongArray ia, jlongArray fa, jobjectArray sargs, jobjectArray outS) {
#if !SLIP_ABI_SUPPORTED
    throwNative(env, "ABI não suportada: chamadas nativas exigem arm64-v8a ou x86_64");
    return 0;
#else
    if (!fn) { throwNative(env, "ponteiro de função nulo"); return 0; }
    jlong iv[MAX_INT]; jlong fv[MAX_FP];
    env->GetLongArrayRegion(ia, 0, MAX_INT, iv);
    env->GetLongArrayRegion(fa, 0, MAX_FP, fv);
    std::string bufs[MAX_INT];
    if (sargs) {
        for (int k = 0; k < MAX_INT; k++) {
            jbyteArray b = (jbyteArray)env->GetObjectArrayElement(sargs, k);
            if (!b) continue;
            jsize n = env->GetArrayLength(b);
            bufs[k].assign((size_t)n, '\0');
            if (n > 0) env->GetByteArrayRegion(b, 0, n, (jbyte*)&bufs[k][0]);
            env->DeleteLocalRef(b);
            iv[k] = (jlong)(intptr_t)bufs[k].c_str();  // std::string garante terminador NUL
        }
    }
    double d[MAX_FP];
    for (int k = 0; k < MAX_FP; k++) d[k] = bitsToDouble(fv[k]);
    void* p = (void*)(intptr_t)fn;
    jlong result = 0;
    try {
        if (ret == R_F32) {
            float r = ((FnF)p)(iv[0], iv[1], iv[2], iv[3], iv[4], iv[5], d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]);
            uint32_t bits; std::memcpy(&bits, &r, sizeof bits);
            result = (jlong)bits;
        } else if (ret == R_F64) {
            double r = ((FnD)p)(iv[0], iv[1], iv[2], iv[3], iv[4], iv[5], d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]);
            std::memcpy(&result, &r, sizeof result);
        } else {
            int64_t r = ((FnI)p)(iv[0], iv[1], iv[2], iv[3], iv[4], iv[5], d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]);
            if (ret == R_BOOL) r = (r & 0xFF) != 0;  // só o byte baixo é definido pela ABI para bool
            result = (jlong)r;
            if (ret == R_STR || ret == R_STR_OWNED) {
                const char* s = (const char*)(intptr_t)r;
                if (s) {
                    size_t n = std::strlen(s);
                    jbyteArray arr = env->NewByteArray((jsize)n);
                    if (arr) {
                        env->SetByteArrayRegion(arr, 0, (jsize)n, (const jbyte*)s);
                        env->SetObjectArrayElement(outS, 0, arr);
                        env->DeleteLocalRef(arr);
                    }
                    if (ret == R_STR_OWNED) std::free((void*)s);
                }
            }
        }
    } catch (const std::exception& e) {
        throwNative(env, std::string("exceção C++ na função nativa: ") + e.what());
        return 0;
    } catch (...) {
        throwNative(env, "exceção C++ desconhecida na função nativa");
        return 0;
    }
    return result;
#endif
}

}  // extern "C"
