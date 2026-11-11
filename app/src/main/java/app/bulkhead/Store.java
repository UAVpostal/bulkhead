package app.bulkhead;

import android.content.Context;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The list of compartments, kept as one JSON file.
 *
 * Only the main process writes it (always via temp-file-then-rename, so a
 * compartment process reading at the same moment sees either the old or the
 * new list, never half of one). Compartment processes only read.
 */
final class Store {
    static final Object LOCK = new Object();

    private Store() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "compartments.json");
    }

    /** Creates the starter compartments on first run. Main process only. */
    static void init(Context c) {
        synchronized (LOCK) {
            if (file(c).exists()) return;
            ArrayList<Compartment> list = new ArrayList<>();
            list.add(Compartment.create("Personal", Compartment.BLUE, false));
            list.add(Compartment.create("Work", Compartment.PURPLE, false));
            list.add(Compartment.create("Banking", Compartment.GREEN, false));
            list.add(Compartment.create("Untrusted", Compartment.ORANGE, false));
            save(c, list);
        }
    }

    static ArrayList<Compartment> load(Context c) {
        synchronized (LOCK) {
            ArrayList<Compartment> out = new ArrayList<>();
            File f = file(c);
            if (!f.exists()) return out;
            try {
                JSONArray a = new JSONArray(read(f));
                for (int i = 0; i < a.length(); i++) {
                    Compartment x = Compartment.fromJson(a.getJSONObject(i));
                    if (Compartment.validId(x.id)) out.add(x);
                }
            } catch (Exception ignored) {
                // An unreadable list is treated as empty rather than crashing every screen.
            }
            return out;
        }
    }

    static void save(Context c, List<Compartment> list) {
        synchronized (LOCK) {
            try {
                JSONArray a = new JSONArray();
                for (Compartment x : list) a.put(x.toJson());
                writeAtomic(file(c), a.toString());
            } catch (Exception ignored) {
            }
        }
    }

    static Compartment find(Context c, String id) {
        if (id == null) return null;
        for (Compartment x : load(c)) if (id.equals(x.id)) return x;
        return null;
    }

    /** Inserts the compartment, or replaces the one with the same id. */
    static void put(Context c, Compartment x) {
        synchronized (LOCK) {
            ArrayList<Compartment> list = load(c);
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(x.id)) {
                    list.set(i, x);
                    replaced = true;
                }
            }
            if (!replaced) list.add(x);
            save(c, list);
        }
    }

    static void remove(Context c, String id) {
        synchronized (LOCK) {
            ArrayList<Compartment> list = load(c);
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).id.equals(id)) list.remove(i);
            }
            save(c, list);
        }
    }

    static Compartment newDisposable(Context c) {
        synchronized (LOCK) {
            ArrayList<Compartment> list = load(c);
            int n = 1;
            boolean taken = true;
            while (taken) {
                taken = false;
                for (Compartment x : list) {
                    if (x.name.equals("Disposable " + n)) {
                        taken = true;
                        n++;
                        break;
                    }
                }
            }
            Compartment d = Compartment.create("Disposable " + n, Compartment.RED, true);
            list.add(d);
            save(c, list);
            return d;
        }
    }

    static File sessionFile(Context c, String id) {
        File dir = new File(c.getFilesDir(), "sessions");
        dir.mkdirs();
        return new File(dir, id + ".json");
    }

    /**
     * Erases everything a compartment has stored. Call only when its process is dead.
     *
     * WebView keeps a suffixed profile in app_webview_ID (cookies, site storage,
     * service workers) and its HTTP cache in cache/WebView_ID. Rather than depend
     * on those exact prefixes, every entry ending in _ID is removed.
     */
    static void wipeData(Context c, String id) {
        if (!Compartment.validId(id)) return;
        String tail = "_" + id;
        deleteMatching(c.getDataDir(), tail);
        deleteMatching(c.getCacheDir(), tail);
        deleteMatching(c.getCodeCacheDir(), tail);
        sessionFile(c, id).delete();
    }

    private static void deleteMatching(File dir, String tail) {
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) return;
        for (File k : kids) if (k.getName().endsWith(tail)) deleteTree(k);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    static String read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void writeAtomic(File f, String s) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }
}
