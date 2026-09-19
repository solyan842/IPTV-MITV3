package com.solyan.iptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UsbFileBrowser {
    public interface Callback {
        void onFileSelected(File file);
        void onNoExternalStorage();
    }

    private UsbFileBrowser() {}

    public static void show(Activity activity, Callback callback) {
        List<File> roots = discoverRoots();
        if (roots.isEmpty()) {
            callback.onNoExternalStorage();
            return;
        }

        String[] labels = new String[roots.size()];
        for (int i = 0; i < roots.size(); i++) {
            labels[i] = rootLabel(roots.get(i));
        }

        new AlertDialog.Builder(activity)
                .setTitle("Chọn bộ nhớ / USB")
                .setItems(labels, (d, which) -> browse(activity, roots.get(which), callback))
                .setNegativeButton("ĐÓNG", null)
                .show();
    }

    private static List<File> discoverRoots() {
        Map<String, File> found = new LinkedHashMap<>();

        addRoot(found, Environment.getExternalStorageDirectory());

        String secondary = System.getenv("SECONDARY_STORAGE");
        if (secondary != null) {
            for (String p : secondary.split(":")) addRoot(found, new File(p));
        }

        scanChildren(found, new File("/storage"));
        scanChildren(found, new File("/mnt/media_rw"));
        scanChildren(found, new File("/mnt/usb_storage"));
        scanChildren(found, new File("/mnt/usb"));
        scanChildren(found, new File("/storage/usb_storage"));

        String[] direct = {
                "/storage/sdcard1",
                "/storage/usbdisk",
                "/storage/usb0",
                "/storage/usb1",
                "/mnt/sdcard1",
                "/mnt/usbdisk",
                "/mnt/usbhost1",
                "/mnt/usbhost2"
        };
        for (String p : direct) addRoot(found, new File(p));

        parseMounts(found);

        ArrayList<File> out = new ArrayList<>(found.values());
        Collections.sort(out, (a, b) -> {
            boolean ai = isInternal(a);
            boolean bi = isInternal(b);
            if (ai != bi) return ai ? 1 : -1;
            return a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath());
        });
        return out;
    }

    private static void parseMounts(Map<String, File> found) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\\s+");
                if (p.length < 3) continue;
                String mount = p[1].replace("\\040", " ");
                String fs = p[2].toLowerCase();
                boolean likelyRemovable = mount.startsWith("/storage/")
                        || mount.startsWith("/mnt/media_rw/")
                        || mount.toLowerCase().contains("usb");
                boolean likelyFs = fs.contains("vfat") || fs.contains("exfat") || fs.contains("fuse")
                        || fs.contains("sdcardfs") || fs.contains("ntfs");
                if (likelyRemovable && likelyFs) addRoot(found, new File(mount));
            }
        } catch (Exception ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
        }
    }

    private static void scanChildren(Map<String, File> found, File base) {
        try {
            File[] children = base.listFiles();
            if (children == null) return;
            for (File f : children) {
                if (!f.isDirectory()) continue;
                String n = f.getName().toLowerCase();
                if ("emulated".equals(n) || "self".equals(n) || "enc_emulated".equals(n)) continue;
                addRoot(found, f);
            }
        } catch (Throwable ignored) {}
    }

    private static void addRoot(Map<String, File> found, File f) {
        if (f == null) return;
        try {
            if (!f.exists() || !f.isDirectory() || !f.canRead()) return;
            String path = f.getCanonicalPath();
            if ("/storage/emulated/0".equals(path) || "/sdcard".equals(path)) {
                found.put("internal", f);
                return;
            }
            found.put(path, f);
        } catch (Exception ignored) {}
    }

    private static boolean isInternal(File f) {
        try {
            String p = f.getCanonicalPath();
            return p.contains("/emulated/0") || p.equals("/sdcard");
        } catch (Exception e) {
            return false;
        }
    }

    private static String rootLabel(File f) {
        String p = f.getAbsolutePath();
        if (isInternal(f)) return "Bộ nhớ trong  ·  " + p;
        return "USB / Bộ nhớ ngoài  ·  " + p;
    }

    private static void browse(Activity activity, File dir, Callback callback) {
        File[] list;
        try {
            list = dir.listFiles();
        } catch (Throwable e) {
            list = null;
        }

        if (list == null) {
            new AlertDialog.Builder(activity)
                    .setTitle("Không đọc được USB")
                    .setMessage(dir.getAbsolutePath())
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        List<File> items = new ArrayList<>();
        for (File f : list) {
            if (f.isDirectory() || isPlaylist(f)) items.add(f);
        }

        Collections.sort(items, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        ArrayList<String> labels = new ArrayList<>();
        ArrayList<File> targets = new ArrayList<>();

        File parent = dir.getParentFile();
        if (parent != null && parent.canRead() && !isMountBoundary(dir)) {
            labels.add("⬆  Lên thư mục");
            targets.add(parent);
        }

        for (File f : items) {
            labels.add((f.isDirectory() ? "📁  " : "▶  ") + f.getName());
            targets.add(f);
        }

        if (labels.isEmpty()) labels.add("(Không có file M3U trong thư mục này)");

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle(dir.getAbsolutePath())
                .setNegativeButton("ĐÓNG", null);

        if (!targets.isEmpty()) {
            b.setItems(labels.toArray(new String[0]), (d, which) -> {
                if (which >= targets.size()) return;
                File selected = targets.get(which);
                if (selected.isDirectory()) browse(activity, selected, callback);
                else callback.onFileSelected(selected);
            });
        } else {
            b.setItems(labels.toArray(new String[0]), null);
        }
        b.show();
    }

    private static boolean isMountBoundary(File dir) {
        String p = dir.getAbsolutePath();
        return p.matches("^/storage/[^/]+$") || p.matches("^/mnt/media_rw/[^/]+$")
                || p.matches("^/mnt/usb[^/]*/?[^/]*$");
    }

    private static boolean isPlaylist(File f) {
        if (f == null || !f.isFile() || !f.canRead()) return false;
        String n = f.getName().toLowerCase();
        return n.endsWith(".m3u") || n.endsWith(".m3u8") || n.endsWith(".ndl") || n.endsWith(".dl");
    }
}
