package com.slipscript.ide;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    /** Evita travar a interface com scripts que imprimem sem parar. */
    private static final int MAX_OUTPUT_CHARS = 100_000;

    private EditText editor;
    private TextView console;
    private Button run;
    private SqlStore store;
    private UiRenderer renderer;
    // O script roda fora da thread de interface (um laço longo não congela o app) e numa thread com pilha
    // de 8 MB, para que recursões profundas (até 1000 chamadas) caibam com folga. Cliques e digitação vindos
    // dos elementos visuais passam pela mesma fila: o interpretador nunca roda em duas threads ao mesmo tempo.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            r -> new Thread(null, r, "slip-runner", 8L * 1024 * 1024));
    // Só a thread do worker mexe em outBuf e truncated:
    private final StringBuilder outBuf = new StringBuilder();
    private boolean truncated;
    private volatile SlipInterpreter current; // fica vivo enquanto houver elementos na tela, para atender os eventos

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        editor = findViewById(R.id.editor);
        console = findViewById(R.id.console);
        run = findViewById(R.id.run);
        store = new SqlStore(this); // um só por tela; o banco é aberto sob demanda
        renderer = new UiRenderer(this, (LinearLayout) findViewById(R.id.uiRoot),
                new View[]{findViewById(R.id.uiLabel), findViewById(R.id.uiPanel)},
                (id, event, arg) -> worker.execute(() -> handle(id, event, arg)));
        findViewById(R.id.example).setOnClickListener(v -> editor.setText(SAMPLE));
        findViewById(R.id.clear).setOnClickListener(v -> console.setText(""));
        run.setOnClickListener(v -> execute());
        if (b == null) editor.setText(SAMPLE); // após girar a tela, o próprio EditText restaura o texto
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        if (current != null) current.close();
        store.close();
        super.onDestroy();
    }

    private void execute() {
        final String source = editor.getText().toString();
        run.setEnabled(false);
        console.setText("Executando...");
        renderer.clear(); // esconde e esvazia a área de interface da execução anterior
        worker.execute(() -> {
            if (current != null) { current.close(); current = null; }
            outBuf.setLength(0);
            truncated = false;
            SlipInterpreter vm = new SlipInterpreter(getFilesDir(), this::appendOut, store);
            vm.setUiHost(renderer);
            try { vm.run(source); }
            catch (Throwable e) { appendError(e); } // inclui StackOverflowError, OutOfMemoryError e LinkageError da FFI
            if (vm.uiTree().isEmpty()) vm.close(); else current = vm; // com elementos na tela, o interpretador continua vivo
            showConsole();
            runOnUiThread(() -> run.setEnabled(true));
        });
    }

    /** Evento de um elemento visual (clique ou texto digitado), já na thread do interpretador. */
    private void handle(int id, String event, Object arg) {
        SlipInterpreter vm = current;
        if (vm == null) return;
        int before = outBuf.length();
        boolean hadError = false;
        try { vm.dispatch(id, event, arg); }
        catch (Throwable e) { appendError(e); hadError = true; }
        if (hadError || outBuf.length() != before) showConsole();
    }

    private void appendOut(String line) {
        if (outBuf.length() < MAX_OUTPUT_CHARS) outBuf.append(line).append('\n');
        else truncated = true;
    }

    private void appendError(Throwable e) {
        outBuf.append("ERRO: ").append(e.getMessage() != null ? e.getMessage() : e.toString()).append('\n');
    }

    /** Monta o texto do console e o publica na thread de interface. */
    private void showConsole() {
        StringBuilder text = new StringBuilder(outBuf);
        if (truncated) text.append("... saída truncada (limite de ").append(MAX_OUTPUT_CHARS).append(" caracteres)\n");
        final String result = text.length() == 0 ? "Execução concluída." : text.toString().trim();
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) console.setText(result); });
    }

    private static final String SAMPLE =
        "vl<nome> = \"Slip\"\n" +
        "vl<versao> = 1.0\n" +
        "ut<>!\"Olá, \" + nome + \"!\"!<>\n" +
        "ut<>!\"Versão: \" + versao!<>\n" +
        "vl<total> = 0\n" +
        "for i in range(1, 4) do\n" +
        "    total = total + i\n" +
        "end\n" +
        "ut<>!\"Soma: \" + total!<>\n" +
        "if total == 6 then\n" +
        "    ut<>!\"Tudo funcionando.\"!<>\n" +
        "else\n" +
        "    ut<>!\"Resultado inesperado.\"!<>\n" +
        "end";
}
