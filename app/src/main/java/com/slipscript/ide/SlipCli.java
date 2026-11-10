package com.slipscript.ide;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Entrada de linha de comando (Termux/JVM): java -cp out com.slipscript.ide.SlipCli [--check] [--home DIR] script.slip */
public final class SlipCli {
    public static void main(String[] args) throws Exception {
        final int[] code = {0};
        // Pilha de 64 MB: a thread principal da JVM costuma ter só 1 MB, pouco para recursões profundas.
        Thread t = new Thread(null, () -> code[0] = run(args), "slip-main", 64L * 1024 * 1024);
        t.start();
        t.join();
        System.exit(code[0]);
    }

    private static int run(String[] args) {
        String home = System.getProperty("user.home", ".");
        String script = null;
        boolean check = false; // --check: só verifica a sintaxe
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--home") && i + 1 < args.length) home = args[++i];
            else if (args[i].equals("--check")) check = true;
            else script = args[i];
        }
        if (script == null) { System.err.println("uso: SlipCli [--check] [--home DIR] script.slip"); return 2; }
        // Saída sempre em UTF-8: com o charset padrão do sistema (ex.: POSIX/ASCII) os acentos viravam '?'.
        PrintStream stdout, stderr;
        try {
            stdout = new PrintStream(System.out, true, "UTF-8");
            stderr = new PrintStream(System.err, true, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
        try {
            String src = new String(Files.readAllBytes(new File(script).toPath()), StandardCharsets.UTF_8);
            if (check) {
                File tmp = Files.createTempDirectory("slipcheck").toFile();
                try (SlipInterpreter vm = new SlipInterpreter(tmp, null)) { vm.check(src); }
                finally { new File(tmp, "slip").delete(); tmp.delete(); }
                stdout.println("ok: " + script);
                return 0;
            }
            try (SlipInterpreter vm = new SlipInterpreter(new File(home), stdout::println)) {
                vm.run(src);
                String ui = vm.uiTree();
                if (!ui.isEmpty()) stdout.print("\n[interface]\n" + ui);
            }
            return 0;
        } catch (java.io.IOException e) {
            stderr.println("ERRO: não foi possível ler " + script);
        } catch (RuntimeException | LinkageError e) {
            stderr.println("ERRO: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            stdout.flush();
        }
        return 1;
    }
}
