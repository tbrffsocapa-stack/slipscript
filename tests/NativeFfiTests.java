import com.slipscript.ide.SlipInterpreter;
import java.io.File;
import java.nio.file.*;
import java.util.*;

/** Testes de integração Slip -> Java -> JNI -> C++ (sem JUnit). Uso: ver tests/run_host_tests.sh */
public class NativeFfiTests {
    static File home; static int pass, fail;

    static List<String> run(String src) {
        List<String> out = new ArrayList<>();
        try (SlipInterpreter vm = new SlipInterpreter(home, out::add)) { vm.run(src); }
        catch (RuntimeException e) { out.add("ERR:" + e.getMessage()); }
        return out;
    }
    static void check(String name, boolean ok, Object got) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name + " -> " + got); }
    }
    static void expect(String name, String src, String... lines) {
        List<String> r = run(src); check(name, r.equals(Arrays.asList(lines)), r);
    }
    static void expectErr(String name, String src, String fragment) {
        List<String> r = run(src);
        boolean ok = !r.isEmpty() && r.get(r.size() - 1).startsWith("ERR:Slip: nativo:") && r.get(r.size() - 1).contains(fragment);
        check(name, ok, r);
    }
    static final String L = "vl<lib> = native_load(\"libslipdemo.so\")\n";

    public static void main(String[] a) throws Exception {
        home = Files.createTempDirectory("slip-test").toFile();
        File libs = new File(new File(home, "slip"), "libs"); libs.mkdirs();
        Files.copy(Paths.get(a[0]), new File(libs, "libslipdemo.so").toPath());

        System.out.println("1. carregar lib válida");
        expect("load", L + "ut<>!type(lib)!<>", "object");
        System.out.println("2. lib inexistente");
        expectErr("load missing", "native_load(\"libnaoexiste.so\")", "não foi possível carregar");
        expectErr("load path traversal", "native_load(\"../x.so\")", "inválido");
        System.out.println("3. resolver função");
        expect("bind", L + "vl<f> = native_bind(lib, \"somar\", \"i32(i32,i32)\")\nut<>!type(f)!<>", "function");
        System.out.println("4. símbolo inexistente");
        expectErr("missing symbol", L + "native_bind(lib, \"nao_existe\", \"i32()\")", "não encontrado");
        System.out.println("5/6. conversão de argumentos e retornos");
        expect("i32", L + "ut<>!native_call(lib, \"somar\", \"i32(i32,i32)\", 10, 20)!<>", "30");
        expect("i32 negativo", L + "ut<>!native_call(lib, \"somar\", \"i32(i32,i32)\", -5, 2)!<>", "-3");
        expect("i64", L + "ut<>!native_call(lib, \"somar64\", \"i64(i64,i64)\", 4000000000, 4000000000)!<>", "8000000000");
        expect("f64", L + "ut<>!native_call(lib, \"hipotenusa\", \"f64(f64,f64)\", 3, 4)!<>", "5");
        expect("f32", L + "ut<>!native_call(lib, \"metade\", \"f32(f32)\", 5)!<>", "2.5");
        expect("mix int/fp/int64/float", L + "ut<>!native_call(lib, \"mix\", \"f64(i32,f64,i64,f32)\", 1, 2.5, 3, 0.5)!<>", "7");
        expect("bool ret", L + "ut<>!native_call(lib, \"eh_par\", \"bool(i32)\", 4)!<>\nut<>!native_call(lib, \"eh_par\", \"bool(i32)\", 5)!<>", "true", "false");
        expect("str arg (UTF-8)", L + "ut<>!native_call(lib, \"tamanho\", \"i32(str)\", \"ação\")!<>", "6");
        expect("str null -> NULL", L + "ut<>!native_call(lib, \"tamanho\", \"i32(str)\", null)!<>", "-1");
        expect("str ret", L + "ut<>!native_call(lib, \"saudacao\", \"str(str)\", \"Slip\")!<>", "Olá, Slip!");
        expect("str_owned ret", L + "ut<>!native_call(lib, \"repetir\", \"str_owned(str,i32)\", \"ab\", 3)!<>", "ababab");
        expect("NULL str ret -> null", L + "ut<>!native_call(lib, \"nulo\", \"str()\")!<>", "null");
        expect("ptr ciclo de vida", L +
            "vl<c> = native_call(lib, \"contador_novo\", \"ptr()\")\n" +
            "vl<inc> = native_bind(lib, \"contador_inc\", \"i32(ptr)\")\n" +
            "ut<>!inc(c)!<>\nut<>!inc(c)!<>\n" +
            "native_call(lib, \"contador_livre\", \"void(ptr)\", c)\n" +
            "ut<>!inc(null)!<>", "1", "2", "-1");
        System.out.println("7. quantidade de argumentos");
        expectErr("too few", L + "native_call(lib, \"somar\", \"i32(i32,i32)\", 1)", "esperava 2");
        expectErr("too many", L + "native_call(lib, \"somar\", \"i32(i32,i32)\", 1, 2, 3)", "esperava 2");
        System.out.println("8. incompatibilidade de tipos");
        expectErr("string -> i32", L + "native_call(lib, \"somar\", \"i32(i32,i32)\", \"a\", 2)", "incompatível");
        expectErr("1.5 -> i32", L + "native_call(lib, \"somar\", \"i32(i32,i32)\", 1.5, 2)", "inteiro");
        expectErr("overflow i32", L + "native_call(lib, \"somar\", \"i32(i32,i32)\", 3000000000, 1)", "int32");
        expectErr("number -> str", L + "native_call(lib, \"tamanho\", \"i32(str)\", 5)", "incompatível");
        expectErr("number -> ptr", L + "native_call(lib, \"contador_inc\", \"i32(ptr)\", 1234)", "incompatível");
        expectErr("null -> i32", L + "native_call(lib, \"somar\", \"i32(i32,i32)\", null, 1)", "incompatível");
        expectErr("assinatura inválida", L + "native_bind(lib, \"somar\", \"int(int)\")", "retorno desconhecido");
        expectErr("limite de args", L + "native_bind(lib, \"somar\", \"i32(i32,i32,i32,i32,i32,i32,i32)\")", "limite");
        System.out.println("9. reutilização");
        expect("mesma lib no mesmo script", "vl<a> = native_load(\"libslipdemo.so\")\nvl<b> = native_load(\"libslipdemo.so\")\nut<>!a == b!<>", "true");
        expect("segunda execução (novo interpretador)", L + "ut<>!native_call(lib, \"somar\", \"i32(i32,i32)\", 1, 1)!<>", "2");
        System.out.println("10. falhas na camada nativa");
        expectErr("exceção C++", L + "native_call(lib, \"lanca_excecao\", \"i32()\")", "falha simulada");
        expect("código de erro propagado", L + "ut<>!native_call(lib, \"dividir\", \"i32(i32,i32)\", 1, 0)!<>", "-2147483648");
        expectErr("uso após native_close", L + "native_close(lib)\nnative_call(lib, \"somar\", \"i32(i32,i32)\", 1, 2)", "fechada");
        expectErr("lib não carregada", "native_bind(\"libx.so\", \"f\", \"void()\")", "não carregada");
        expect("outras funções continuam OK", "ut<>!upper(\"ok\")!<>", "OK");

        System.out.println("\n" + pass + " ok, " + fail + " falhas");
        System.exit(fail == 0 ? 0 : 1);
    }
}
