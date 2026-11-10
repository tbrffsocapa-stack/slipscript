// Biblioteca de demonstração da FFI da SlipScript (NOVO).
// Todo símbolo precisa de extern "C" para não sofrer name mangling do C++.
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <stdexcept>
#include <string>

extern "C" {

int32_t somar(int32_t a, int32_t b) { return a + b; }
int64_t somar64(int64_t a, int64_t b) { return a + b; }
double hipotenusa(double a, double b) { return std::sqrt(a * a + b * b); }
float metade(float x) { return x / 2.0f; }
bool eh_par(int32_t n) { return (n % 2) == 0; }

// Mistura classes de registrador (int, double, int64, float) para validar a ordem.
double mix(int32_t a, double b, int64_t c, float d) { return a + b + (double)c + (double)d; }

int32_t tamanho(const char* s) { return s ? (int32_t)std::strlen(s) : -1; }

const char* saudacao(const char* nome) {
    static thread_local std::string buf;
    buf = std::string("Olá, ") + (nome ? nome : "mundo") + "!";
    return buf.c_str();  // propriedade da biblioteca: tipo "str" (copiado, não liberado)
}

char* repetir(const char* s, int32_t n) {  // propriedade do chamador: tipo "str_owned" (free())
    std::string r;
    for (int32_t i = 0; i < n; i++) r += (s ? s : "");
    return strdup(r.c_str());
}

const char* nulo() { return nullptr; }

// Ponteiro opaco com ciclo de vida explícito.
struct Contador { int32_t v; };
void* contador_novo() { return new Contador{0}; }
int32_t contador_inc(void* c) { return c ? ++static_cast<Contador*>(c)->v : -1; }
void contador_livre(void* c) { delete static_cast<Contador*>(c); }

// Falhas "esperadas" da camada nativa.
int32_t dividir(int32_t a, int32_t b) { return b == 0 ? INT32_MIN : a / b; }
int32_t lanca_excecao(void) { throw std::runtime_error("falha simulada na biblioteca"); }

}  // extern "C"
