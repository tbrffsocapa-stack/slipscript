package com.slipscript.ide;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.*;

public final class SqlStore extends SQLiteOpenHelper implements SlipInterpreter.Database {
    /** Comandos que devolvem linhas (execSQL falharia com eles). */
    private static final Set<String> QUERIES = new HashSet<>(Arrays.asList("select", "with", "pragma", "explain", "values"));
    /** Comandos que escapariam do banco privado do app: ATTACH/VACUUM INTO criam ou abrem arquivos em qualquer caminho. */
    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList("attach", "detach", "vacuum"));

    public SqlStore(Context c) { super(c, "slip_data.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) { }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    @Override public Object execute(String statement) {
        String s = statement.trim();
        int end = 0;
        while (end < s.length() && Character.isLetter(s.charAt(end))) end++;
        String head = s.substring(0, end).toLowerCase(Locale.ROOT);
        if (BLOCKED.contains(head)) throw new IllegalArgumentException("comando SQL não permitido: " + head.toUpperCase(Locale.ROOT));

        SQLiteDatabase db = getWritableDatabase();
        if (QUERIES.contains(head)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (Cursor c = db.rawQuery(s, null)) {
                String[] names = c.getColumnNames();
                while (c.moveToNext()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < names.length; i++) {
                        Object v;
                        switch (c.getType(i)) {
                            case Cursor.FIELD_TYPE_INTEGER: v = c.getLong(i); break;
                            case Cursor.FIELD_TYPE_FLOAT: v = c.getDouble(i); break;
                            case Cursor.FIELD_TYPE_BLOB: v = c.getBlob(i); break;
                            case Cursor.FIELD_TYPE_NULL: v = null; break;
                            default: v = c.getString(i);
                        }
                        row.put(names[i], v);
                    }
                    rows.add(row);
                }
            }
            return rows;
        }
        db.execSQL(s);
        return true;
    }
}
