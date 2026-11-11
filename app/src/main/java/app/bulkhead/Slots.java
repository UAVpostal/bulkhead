package app.bulkhead;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Assigns compartments to process slots. Main process only (except owner(),
 * which compartment processes read to detect a stale task).
 *
 * The rule that makes the isolation hold: a slot's process serves exactly one
 * compartment for its whole life. WebView's data directory is chosen once per
 * process, so before a slot is handed to a different compartment its old
 * process is killed and confirmed dead.
 */
final class Slots {
    static final Class<?>[] CLASSES = {
            Slot.S0.class, Slot.S1.class, Slot.S2.class, Slot.S3.class,
            Slot.S4.class, Slot.S5.class, Slot.S6.class, Slot.S7.class };
    static final int COUNT = CLASSES.length;

    static final int IDLE = 0, RUNNING = 1, PAUSED = 2;

    /** A just-assigned slot has no process yet; give it time before judging it dead. */
    private static final long GRACE_MS = 10000;

    private Slots() {}

    // ---------------------------------------------------------------- slot table

    private static File file(Context c, int k) {
        File dir = new File(c.getFilesDir(), "slots");
        dir.mkdirs();
        return new File(dir, "c" + k);
    }

    private static String[] record(Context c, int k) {
        try {
            File f = file(c, k);
            if (!f.exists()) return null;
            String[] parts = Store.read(f).trim().split("\n");
            return Compartment.validId(parts[0]) ? parts : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The compartment id this slot is assigned to, or null if the slot is free. */
    static String owner(Context c, int k) {
        if (k < 0 || k >= COUNT) return null;
        String[] r = record(c, k);
        return r == null ? null : r[0];
    }

    private static boolean inGrace(Context c, int k) {
        String[] r = record(c, k);
        if (r == null || r.length < 2) return false;
        try {
            return Math.abs(System.currentTimeMillis() - Long.parseLong(r[1])) < GRACE_MS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void setOwner(Context c, int k, String id) {
        File f = file(c, k);
        if (id == null) {
            f.delete();
            return;
        }
        try {
            Store.writeAtomic(f, id + "\n" + System.currentTimeMillis());
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------- process and task inspection

    private static List<ActivityManager.RunningAppProcessInfo> processes(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> ps = am == null ? null : am.getRunningAppProcesses();
        return ps == null ? new ArrayList<ActivityManager.RunningAppProcessInfo>() : ps;
    }

    private static String processName(Context c, int k) {
        return c.getPackageName() + ":c" + k;
    }

    /** Pid of the slot's process, or 0 if it is not running. */
    private static int pidOf(Context c, int k) {
        String name = processName(c, k);
        for (ActivityManager.RunningAppProcessInfo p : processes(c)) {
            if (name.equals(p.processName)) return p.pid;
        }
        return 0;
    }

    private static boolean alive(Context c, int k) {
        return pidOf(c, k) != 0;
    }

    private static boolean pidAlive(Context c, int pid) {
        for (ActivityManager.RunningAppProcessInfo p : processes(c)) {
            if (p.pid == pid) return true;
        }
        return false;
    }

    private static void waitDead(Context c, int pid, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (pid != 0 && pidAlive(c, pid) && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static List<ActivityManager.AppTask> tasksOf(Context c, int k) {
        ArrayList<ActivityManager.AppTask> out = new ArrayList<>();
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ComponentName cn = new ComponentName(c, CLASSES[k]);
            for (ActivityManager.AppTask t : am.getAppTasks()) {
                Intent base = t.getTaskInfo().baseIntent;
                if (base != null && cn.equals(base.getComponent())) out.add(t);
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private static void removeTask(Context c, int k) {
        for (ActivityManager.AppTask t : tasksOf(c, k)) {
            try {
                t.finishAndRemoveTask();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------- queries

    static int slotOf(Context c, String id) {
        for (int k = 0; k < COUNT; k++) if (id.equals(owner(c, k))) return k;
        return -1;
    }

    /** id to RUNNING or PAUSED, for every compartment that currently holds a slot. */
    static HashMap<String, Integer> states(Context c) {
        HashSet<String> live = new HashSet<>();
        for (ActivityManager.RunningAppProcessInfo p : processes(c)) live.add(p.processName);
        HashMap<String, Integer> out = new HashMap<>();
        for (int k = 0; k < COUNT; k++) {
            String id = owner(c, k);
            if (id != null) out.put(id, live.contains(processName(c, k)) ? RUNNING : PAUSED);
        }
        return out;
    }

    // ---------------------------------------------------------------- allocation

    /** Frees a slot whose process is dead, erasing its compartment if that was disposable. */
    private static void evictLocked(Context c, int k) {
        String old = owner(c, k);
        removeTask(c, k);
        setOwner(c, k, null);
        if (old == null) return;
        Compartment cp = Store.find(c, old);
        if (cp == null || cp.disposable) {
            Store.wipeData(c, old);
            if (cp != null) Store.remove(c, old);
        }
    }

    /**
     * Returns the slot for this compartment, assigning one if needed, or -1 if
     * all slots hold running compartments. Blocks briefly; call off the main thread.
     */
    static int acquire(Context c, String id) {
        int got;
        int kill;
        synchronized (Store.LOCK) {
            got = slotOf(c, id);
            if (got >= 0) return got;

            for (int k = 0; k < COUNT && got < 0; k++) {
                if (owner(c, k) == null && !alive(c, k)) got = k;
            }
            for (int k = 0; k < COUNT && got < 0; k++) {
                if (owner(c, k) == null) got = k;
            }
            // Nothing free: reclaim a slot Android already stopped. Prefer one whose
            // compartment keeps its data over a disposable that would be erased.
            for (int pass = 0; pass < 2 && got < 0; pass++) {
                for (int k = 0; k < COUNT && got < 0; k++) {
                    if (alive(c, k) || inGrace(c, k)) continue;
                    Compartment cp = Store.find(c, owner(c, k));
                    boolean disposable = cp == null || cp.disposable;
                    if (disposable == (pass == 1)) {
                        evictLocked(c, k);
                        got = k;
                    }
                }
            }
            if (got < 0) return -1;

            removeTask(c, got);
            kill = pidOf(c, got);
            if (kill != 0) Process.killProcess(kill);
            setOwner(c, got, id);
        }
        waitDead(c, kill, 2500);
        return got;
    }

    static Intent intent(Context c, int k, String id, String url) {
        Intent i = new Intent(c, CLASSES[k]);
        i.putExtra(BrowserActivity.EXTRA_ID, id);
        if (url != null) i.putExtra(BrowserActivity.EXTRA_URL, url);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /**
     * Opens a compartment (optionally at a URL) from any main-process activity.
     * waitPid, if non-zero, is a process that must be dead before launching.
     */
    static void open(final Activity a, final String id, final String url, final int waitPid, final Runnable done) {
        final Context app = a.getApplicationContext();
        new Thread(() -> {
            waitDead(app, waitPid, 3000);
            final int k = Store.find(app, id) == null ? -2 : acquire(app, id);
            a.runOnUiThread(() -> {
                if (k == -2) {
                    Ui.toast(app, "That compartment no longer exists");
                } else if (k < 0) {
                    Ui.toast(app, "All " + COUNT + " slots are running. Close a compartment first.");
                } else {
                    try {
                        a.startActivity(intent(app, k, id, url));
                    } catch (RuntimeException e) {
                        Ui.toast(app, "Couldn't open the compartment");
                    }
                }
                if (done != null) done.run();
            });
        }).start();
    }

    // ------------------------------------------------------------------ teardown

    /**
     * A compartment process announced that it is closing. A disposable's record is
     * removed immediately so it cannot be reopened; its files are erased once the
     * process is confirmed dead.
     */
    static void onClosed(final Context c, final int k, final String id, final int pid, final Runnable done) {
        final boolean wipe;
        synchronized (Store.LOCK) {
            Compartment cp = Store.find(c, id);
            wipe = cp == null || cp.disposable;
            if (cp != null && cp.disposable) Store.remove(c, id);
        }
        new Thread(() -> {
            try {
                waitDead(c, pid, 4000);
                if (wipe) {
                    int live;
                    synchronized (Store.LOCK) {
                        live = id.equals(owner(c, k)) ? pidOf(c, k) : 0;
                        if (live != 0) Process.killProcess(live);
                    }
                    waitDead(c, live, 2000);
                }
                synchronized (Store.LOCK) {
                    if (id.equals(owner(c, k)) && !alive(c, k)) setOwner(c, k, null);
                    if (wipe && slotOf(c, id) < 0) Store.wipeData(c, id);
                }
            } finally {
                done.run();
            }
        }).start();
    }

    /**
     * Stops a compartment from the launcher, optionally erasing its data or the
     * compartment itself. Disposables are always erased. Blocks; call off the main thread.
     */
    static void end(Context c, String id, boolean wipe, boolean delete) {
        int pid = 0;
        boolean erase = wipe || delete;
        synchronized (Store.LOCK) {
            Compartment cp = Store.find(c, id);
            if (cp == null || cp.disposable) erase = true;
            int k = slotOf(c, id);
            if (k >= 0) {
                removeTask(c, k);
                pid = pidOf(c, k);
                if (pid != 0) Process.killProcess(pid);
            }
            // Remove the record first so nothing can reopen it while its process dies.
            if (delete || (cp != null && cp.disposable)) Store.remove(c, id);
        }
        waitDead(c, pid, 3000);
        synchronized (Store.LOCK) {
            int k = slotOf(c, id);
            if (k >= 0 && !alive(c, k)) {
                setOwner(c, k, null);
                k = -1;
            }
            if (erase && k < 0) Store.wipeData(c, id);
        }
    }

    /**
     * Releases slots whose process is dead and whose task is gone (swiped away,
     * crashed), and erases any disposable left behind that way.
     */
    static void sweep(Context c) {
        synchronized (Store.LOCK) {
            for (int k = 0; k < COUNT; k++) {
                if (owner(c, k) == null || alive(c, k) || inGrace(c, k)) continue;
                if (tasksOf(c, k).isEmpty()) evictLocked(c, k);
            }
            long now = System.currentTimeMillis();
            for (Compartment cp : Store.load(c)) {
                if (cp.disposable && slotOf(c, cp.id) < 0 && Math.abs(now - cp.created) > GRACE_MS) {
                    Store.wipeData(c, cp.id);
                    Store.remove(c, cp.id);
                }
            }
        }
    }
}
