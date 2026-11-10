package com.slipscript.ide;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Modo "app": roda o script empacotado em assets/slip-app/main.slip (APK criado por tools/slip2apk.sh), sem editor.
 * Módulos (assets/slip-app/modules) e bibliotecas nativas (assets/slip-app/libs) são copiados para a pasta privada
 * do app antes de rodar, onde `use` e `native_load` esperam encontrá-los. A tela mostra só a interface do script;
 * o console aparece apenas se o script imprimir algo ou falhar.
 */
public class RunnerActivity extends Activity {
    private static final String ASSET_DIR = "slip-app";
    private static final int MAX_OUTPUT_CHARS = 100_000;

    private TextView console;
    private View consolePanel;
    private SqlStore store;
    private UiRenderer renderer;
    // Mesmo desenho do MainActivity: o interpretador vive numa única thread (8 MB de pilha) e eventos da tela passam pela mesma fila.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            r -> new Thread(null, r, "slip-runner", 8L * 1024 * 1024));
    private final StringBuilder outBuf = new StringBuilder();
    private boolean truncated;
    private volatile SlipInterpreter current;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_runner);
        console = findViewById(R.id.console);
        consolePanel = findViewById(R.id.consolePanel);
        store = new SqlStore(this);
        renderer = new UiRenderer(this, (LinearLayout) findViewById(R.id.uiRoot),
                new View[]{findViewById(R.id.uiPanel)},
                (id, event, arg) -> worker.execute(() -> handle(id, event, arg)));
        worker.execute(this::boot); // sempre roda: se o processo foi recriado, o estado do script se perdeu
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        if (current != null) current.close();
        store.close();
        super.onDestroy();
    }

    private void boot() {
        SlipInterpreter vm = new SlipInterpreter(getFilesDir(), this::appendOut, store);
        vm.setUiHost(renderer);
        try {
            File home = new File(getFilesDir(), "slip");
            copyAssetDir(ASSET_DIR + "/modules", new File(home, "modules"));
            copyAssetDir(ASSET_DIR + "/libs", new File(home, "libs"));
            vm.run(readAsset(ASSET_DIR + "/main.slip"));
        } catch (Throwable e) { appendError(e); } // inclui StackOverflowError e LinkageError da FFI
        if (vm.uiTree().isEmpty()) vm.close(); else current = vm; // com elementos na tela o interpretador continua vivo
        showConsole();
    }

    private void handle(int id, String event, Object arg) {
        SlipInterpreter vm = current;
        if (vm == null) return;
        int before = outBuf.length();
        boolean hadError = false;
        try { vm.dispatch(id, event, arg); }
        catch (Throwable e) { appendError(e); hadError = true; }
        if (hadError || outBuf.length() != before) showConsole();
    }

    private String readAsset(String path) throws IOException {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Copia uma pasta de assets (se existir) para o disco, sobrescrevendo. Pastas ausentes são ignoradas. */
    private void copyAssetDir(String assetPath, File dest) throws IOException {
        String[] names = getAssets().list(assetPath);
        if (names == null || names.length == 0) return;
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("não foi possível criar " + dest);
        for (String n : names) {
            String child = assetPath + "/" + n;
            String[] sub = getAssets().list(child);
            if (sub != null && sub.length > 0) { copyAssetDir(child, new File(dest, n)); continue; }
            try (InputStream in = getAssets().open(child); OutputStream out = new FileOutputStream(new File(dest, n))) {
                byte[] buf = new byte[8192];
                int k;
                while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            }
        }
    }

    private void appendOut(String line) {
        if (outBuf.length() < MAX_OUTPUT_CHARS) outBuf.append(line).append('\n');
        else truncated = true;
    }

    private void appendError(Throwable e) {
        outBuf.append("ERRO: ").append(e.getMessage() != null ? e.getMessage() : e.toString()).append('\n');
    }

    private void showConsole() {
        StringBuilder text = new StringBuilder(outBuf);
        if (truncated) text.append("... saída truncada (limite de ").append(MAX_OUTPUT_CHARS).append(" caracteres)\n");
        final String result = text.toString().trim();
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            console.setText(result);
            consolePanel.setVisibility(result.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }
}
