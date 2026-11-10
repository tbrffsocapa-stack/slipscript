package com.slipscript.ide;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;

/** Rede (HTTP) e data/hora para a Slip. Erros viram IllegalArgumentException; o interpretador os converte em erros do Slip. */
final class SlipSys {
    private static final int MAX_BODY = 1 << 20; // 1 MB por resposta
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList("GET", "POST", "PUT", "DELETE", "HEAD"));

    private SlipSys() {}

    // ------------------------------------------------------------------ HTTP

    static Map<String, Object> http(String method, String url, Object body, Object headers) {
        if (!METHODS.contains(method)) throw new IllegalArgumentException("http: método não suportado: " + method);
        if (headers != null && !(headers instanceof Map)) throw new IllegalArgumentException("http: os cabeçalhos devem ser um mapa");
        boolean canSend = method.equals("POST") || method.equals("PUT");
        if (body != null && !canSend) throw new IllegalArgumentException("http: " + method + " não leva corpo");

        URL u;
        try { u = new URL(url); } catch (IOException e) { throw new IllegalArgumentException("http: url inválida"); }
        String proto = u.getProtocol();
        if (!proto.equals("http") && !proto.equals("https")) throw new IllegalArgumentException("http: só http e https são permitidos");

        boolean json = body instanceof Map || body instanceof List;
        String payload = body == null ? null : json ? SlipJson.stringify(body, false) : SlipInterpreter.str(body);

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setInstanceFollowRedirects(true);
            boolean hasType = false, hasAgent = false;
            if (headers != null) {
                for (Map.Entry<?, ?> en : ((Map<?, ?>) headers).entrySet()) {
                    String k = String.valueOf(en.getKey()), v = SlipInterpreter.str(en.getValue());
                    if (k.isEmpty() || k.indexOf('\n') >= 0 || k.indexOf('\r') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0)
                        throw new IllegalArgumentException("http: cabeçalho inválido");
                    if (k.equalsIgnoreCase("content-type")) hasType = true;
                    if (k.equalsIgnoreCase("user-agent")) hasAgent = true;
                    c.setRequestProperty(k, v);
                }
            }
            if (!hasAgent) c.setRequestProperty("User-Agent", "SlipScript/1.0");
            if (payload != null) {
                if (!hasType) c.setRequestProperty("Content-Type", json ? "application/json" : "text/plain; charset=utf-8");
                byte[] data = payload.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream o = c.getOutputStream()) { o.write(data); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readCapped(in);

            Map<String, Object> hs = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> en : c.getHeaderFields().entrySet()) {
                if (en.getKey() == null || en.getValue() == null || en.getValue().isEmpty()) continue;
                hs.put(en.getKey().toLowerCase(Locale.ROOT), en.getValue().get(en.getValue().size() - 1));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", (double) code);
            r.put("ok", code >= 200 && code < 300);
            r.put("corpo", text);
            r.put("cabecalhos", hs);
            return r;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            String m = e.getMessage();
            throw new IllegalArgumentException("falha de rede: " + (m == null ? e.getClass().getSimpleName() : m));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readCapped(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (out.size() + n > MAX_BODY) throw new IllegalArgumentException("http: resposta maior que 1 MB");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ data e hora

    private static TimeZone zone(String tz) {
        if (tz == null) return TimeZone.getDefault();
        if (!tz.startsWith("GMT") && !Arrays.asList(TimeZone.getAvailableIDs()).contains(tz))
            throw new IllegalArgumentException("fuso horário desconhecido: " + tz);
        return TimeZone.getTimeZone(tz);
    }

    private static SimpleDateFormat format(String pattern, String tz) {
        try {
            SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ROOT);
            f.setTimeZone(zone(tz));
            return f;
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("fuso")) throw e;
            throw new IllegalArgumentException("formato de data inválido: " + pattern);
        }
    }

    static String dateStr(double ms, String pattern, String tz) {
        return format(pattern, tz).format(new Date((long) ms));
    }

    static double dateParse(String text, String pattern, String tz) {
        SimpleDateFormat f = format(pattern, tz);
        f.setLenient(false);
        ParsePosition pos = new ParsePosition(0);
        Date d = f.parse(text, pos);
        if (d == null || pos.getIndex() != text.length()) throw new IllegalArgumentException("data inválida: \"" + text + "\" (formato " + pattern + ")");
        return (double) d.getTime();
    }

    /** dia_semana: 1 = domingo ... 7 = sábado. */
    static Map<String, Object> dateParts(double ms, String tz) {
        Calendar c = Calendar.getInstance(zone(tz), Locale.ROOT);
        c.setTimeInMillis((long) ms);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ano", (double) c.get(Calendar.YEAR));
        m.put("mes", (double) (c.get(Calendar.MONTH) + 1));
        m.put("dia", (double) c.get(Calendar.DAY_OF_MONTH));
        m.put("hora", (double) c.get(Calendar.HOUR_OF_DAY));
        m.put("minuto", (double) c.get(Calendar.MINUTE));
        m.put("segundo", (double) c.get(Calendar.SECOND));
        m.put("dia_semana", (double) c.get(Calendar.DAY_OF_WEEK));
        m.put("dia_do_ano", (double) c.get(Calendar.DAY_OF_YEAR));
        return m;
    }

    /** Pausa de até 5 segundos (o script roda numa thread própria no app). */
    static void sleep(double ms) {
        long t = (long) Math.max(0, Math.min(5000, ms));
        try { Thread.sleep(t); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalArgumentException("sleep interrompido"); }
    }
}
