package com.slipscript.ide;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Desenha com Views reais os elementos criados por vt<> nos scripts Slip.
 * O interpretador chama estes métodos na sua própria thread; todo o trabalho com Views é repassado para a thread de interface.
 */
final class UiRenderer implements SlipUi.Host {
    /** Clique ("clique") ou texto digitado ("mudou", arg = novo texto): devem ir para a thread do interpretador. */
    interface Events { void onEvent(int id, String event, Object arg); }

    private static final int TEXT = 0xFFF4F1FF, HINT = 0xFF827D99, ACCENT = 0xFF6B58D3, FIELD = 0xFF2E2B42;

    private static final class Style { Integer bg; float radius; }

    private final Activity activity;
    private final LinearLayout root;
    private final View[] panels; // áreas da tela que só aparecem quando há elementos
    private final Events events;
    private final Map<Integer, View> views = new HashMap<>(); // só a thread de interface mexe
    private boolean silent; // true enquanto o script troca o texto de uma entrada (evita eco)

    UiRenderer(Activity activity, LinearLayout root, View[] panels, Events events) {
        this.activity = activity; this.root = root; this.panels = panels; this.events = events;
    }

    // ---- SlipUi.Host (thread do interpretador)

    @Override public void create(int id, String type, int parentId, Map<String, Object> props) {
        final Map<String, Object> p = new LinkedHashMap<>(props);
        activity.runOnUiThread(() -> doCreate(id, type, parentId, p));
    }
    @Override public void update(int id, String prop, Object value) { activity.runOnUiThread(() -> doUpdate(id, prop, value)); }
    @Override public void move(int id, int parentId) { activity.runOnUiThread(() -> doMove(id, parentId)); }
    @Override public void remove(int id) { activity.runOnUiThread(() -> doRemove(id)); }
    @Override public void clear() { activity.runOnUiThread(this::doClear); }

    // ---- thread de interface

    private int dp(float v) { return Math.round(v * activity.getResources().getDisplayMetrics().density); }

    private void doCreate(final int id, String type, int parentId, Map<String, Object> props) {
        final View v;
        Style st = new Style();
        int width = ViewGroup.LayoutParams.WRAP_CONTENT;
        switch (type) {
            case "botao": {
                Button b = new Button(activity);
                b.setAllCaps(false);
                b.setTextColor(Color.WHITE);
                st.bg = ACCENT; st.radius = 8;
                v = b;
                break;
            }
            case "entrada": {
                EditText et = new EditText(activity);
                et.setSingleLine(true);
                et.setTextColor(TEXT);
                et.setHintTextColor(HINT);
                et.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                    @Override public void afterTextChanged(Editable s) { if (!silent) events.onEvent(id, "mudou", s.toString()); }
                });
                st.bg = FIELD; st.radius = 6;
                v = et;
                width = ViewGroup.LayoutParams.MATCH_PARENT;
                break;
            }
            case "caixa": {
                LinearLayout l = new LinearLayout(activity);
                l.setOrientation(LinearLayout.VERTICAL);
                v = l;
                width = ViewGroup.LayoutParams.MATCH_PARENT;
                break;
            }
            default: {
                TextView t = new TextView(activity);
                t.setTextColor(TEXT);
                v = t;
            }
        }
        v.setTag(st);
        background(v, st);
        if (type.equals("botao") || type.equals("entrada")) v.setPadding(dp(12), dp(8), dp(12), dp(8));

        ViewGroup parent = parentId == 0 ? root : (ViewGroup) views.get(parentId);
        if (parent == null) parent = root;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        parent.addView(v, lp);
        views.put(id, v);
        for (Map.Entry<String, Object> en : props.entrySet()) apply(id, v, en.getKey(), en.getValue());
        for (View panel : panels) panel.setVisibility(View.VISIBLE);
    }

    private void doUpdate(int id, String prop, Object value) {
        View v = views.get(id);
        if (v != null) apply(id, v, prop, value);
    }

    private void doMove(int id, int parentId) {
        View v = views.get(id);
        if (v == null) return;
        ViewGroup target = parentId == 0 ? root : (ViewGroup) views.get(parentId);
        if (target == null) target = root;
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        target.addView(v, v.getLayoutParams());
    }

    private void doRemove(int id) {
        View v = views.remove(id);
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private void doClear() {
        root.removeAllViews();
        views.clear();
        for (View panel : panels) panel.setVisibility(View.GONE);
    }

    private void background(View v, Style st) {
        if (st.bg == null && st.radius <= 0) { v.setBackground(null); return; }
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(st.bg == null ? Color.TRANSPARENT : st.bg);
        gd.setCornerRadius(dp(st.radius));
        v.setBackground(gd);
    }

    private int size(Object value) {
        if ("cheia".equals(value)) return ViewGroup.LayoutParams.MATCH_PARENT;
        if ("auto".equals(value)) return ViewGroup.LayoutParams.WRAP_CONTENT;
        return dp(((Number) value).floatValue());
    }

    private void setField(EditText et, String text) {
        silent = true;
        try { if (!et.getText().toString().equals(text)) et.setText(text); }
        finally { silent = false; }
    }

    /** Aplica uma propriedade já validada pelo interpretador (cores "#rrggbb"/"#aarrggbb", números como Double). */
    private void apply(final int id, View v, String prop, Object value) {
        Style st = (Style) v.getTag();
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        LinearLayout.LayoutParams lp = raw instanceof LinearLayout.LayoutParams ? (LinearLayout.LayoutParams) raw : null;
        switch (prop) {
            case "texto":
            case "valor":
                if (v instanceof EditText) setField((EditText) v, String.valueOf(value));
                else if (v instanceof TextView) ((TextView) v).setText(String.valueOf(value));
                break;
            case "dica": if (v instanceof EditText) ((EditText) v).setHint(String.valueOf(value)); break;
            case "cor": if (v instanceof TextView) ((TextView) v).setTextColor(Color.parseColor((String) value)); break;
            case "fundo": st.bg = Color.parseColor((String) value); background(v, st); break;
            case "raio": st.radius = ((Number) value).floatValue(); background(v, st); break;
            case "tamanho":
                if (v instanceof TextView) ((TextView) v).setTextSize(TypedValue.COMPLEX_UNIT_SP, ((Number) value).floatValue());
                break;
            case "negrito":
                if (v instanceof TextView) {
                    TextView t = (TextView) v;
                    t.setTypeface(t.getTypeface(), Boolean.TRUE.equals(value) ? Typeface.BOLD : Typeface.NORMAL);
                }
                break;
            case "margem":
                if (lp != null) { int m = dp(((Number) value).floatValue()); lp.setMargins(m, m, m, m); v.setLayoutParams(lp); }
                break;
            case "espaco": { int d = dp(((Number) value).floatValue()); v.setPadding(d, d, d, d); break; }
            case "visivel": v.setVisibility(Boolean.TRUE.equals(value) ? View.VISIBLE : View.GONE); break;
            case "largura": if (lp != null) { lp.width = size(value); v.setLayoutParams(lp); } break;
            case "altura": if (lp != null) { lp.height = size(value); v.setLayoutParams(lp); } break;
            case "alinhar":
                if (v instanceof TextView) {
                    ((TextView) v).setGravity("centro".equals(value) ? Gravity.CENTER_HORIZONTAL : "direita".equals(value) ? Gravity.END : Gravity.START);
                }
                break;
            case "direcao":
                if (v instanceof LinearLayout) ((LinearLayout) v).setOrientation("horizontal".equals(value) ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                break;
            case "ao_clicar":
                v.setOnClickListener(Boolean.TRUE.equals(value) ? x -> events.onEvent(id, "clique", null) : null);
                break;
            default: break; // ao_mudar: a entrada sempre avisa quando o texto muda
        }
    }
}
