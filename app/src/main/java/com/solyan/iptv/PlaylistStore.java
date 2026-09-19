package com.solyan.iptv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class PlaylistStore {
    private static final String PREFS = "solyan_playlist_store";
    private static final String KEY_INDEX = "index_json";
    private static final String KEY_LAST = "last_name";

    public static final class Entry {
        public final String name;
        public final String fileName;
        public final String source;

        Entry(String name, String fileName, String source) {
            this.name = name;
            this.fileName = fileName;
            this.source = source;
        }

        @Override public String toString() {
            return name;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final File dir;

    public PlaylistStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.dir = new File(this.context.getFilesDir(), "playlists");
        if (!dir.exists()) dir.mkdirs();
    }

    public synchronized void save(String name, String text, String source) throws Exception {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Tên list không được để trống");

        List<Entry> entries = list();
        String fileName = null;
        for (Entry e : entries) {
            if (e.name.equalsIgnoreCase(name)) {
                fileName = e.fileName;
                break;
            }
        }
        if (fileName == null) {
            fileName = "list_" + System.currentTimeMillis() + ".m3u";
            entries.add(new Entry(name, fileName, source == null ? "" : source));
        } else {
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (e.name.equalsIgnoreCase(name)) {
                    entries.set(i, new Entry(name, fileName, source == null ? e.source : source));
                    break;
                }
            }
        }

        File out = new File(dir, fileName);
        FileOutputStream fos = new FileOutputStream(out, false);
        fos.write(text.getBytes(StandardCharsets.UTF_8));
        fos.flush();
        fos.close();

        writeIndex(entries);
        prefs.edit().putString(KEY_LAST, name).apply();
    }

    public synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        String raw = prefs.getString(KEY_INDEX, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String name = o.optString("name", "");
                String fileName = o.optString("file", "");
                String source = o.optString("source", "");
                if (!name.isEmpty() && !fileName.isEmpty() && new File(dir, fileName).exists()) {
                    out.add(new Entry(name, fileName, source));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized String read(Entry entry) throws Exception {
        File f = new File(dir, entry.fileName);
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[(int) f.length()];
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        in.close();
        return new String(buf, 0, off, StandardCharsets.UTF_8);
    }

    public synchronized Entry last() {
        String last = prefs.getString(KEY_LAST, "");
        if (last.isEmpty()) return null;
        for (Entry e : list()) {
            if (e.name.equals(last)) return e;
        }
        return null;
    }

    public void setLast(String name) {
        prefs.edit().putString(KEY_LAST, name == null ? "" : name).apply();
    }

    private void writeIndex(List<Entry> entries) throws Exception {
        JSONArray a = new JSONArray();
        for (Entry e : entries) {
            JSONObject o = new JSONObject();
            o.put("name", e.name);
            o.put("file", e.fileName);
            o.put("source", e.source);
            a.put(o);
        }
        prefs.edit().putString(KEY_INDEX, a.toString()).apply();
    }
}
