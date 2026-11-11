package app.bulkhead;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;

/** The launcher: lists compartments, opens them, and manages their data. */
public class MainActivity extends Activity {
    private static final String ABOUT =
            "What a compartment isolates\n\n"
            + "• Each one runs in its own Android process with its own browser profile. Cookies, "
            + "logins, site storage, cache and service workers are kept in a separate directory "
            + "and are never shared.\n"
            + "• Pages are rendered in Android's sandboxed WebView renderer.\n"
            + "• Links never cross by themselves. You pick the compartment, and no cookie or "
            + "referrer travels with the link.\n"
            + "• A disposable compartment is erased from storage when you close it.\n"
            + "• The ad blocker refuses connections to known ad and tracker servers in every "
            + "compartment. The switch on this screen turns it on or off everywhere, including "
            + "compartments that are already running. A single compartment can opt out in its "
            + "settings if a site breaks.\n\n"
            + "What it does not do\n\n"
            + "• This is not a virtual machine. All compartments belong to one app, so the "
            + "boundary is Android's process and WebView sandbox, not a hypervisor. An exploit "
            + "that escapes the WebView sandbox could reach other compartments' files.\n"
            + "• Every compartment uses the same network connection and IP address. Sites "
            + "can still correlate you by IP or device fingerprint.\n"
            + "• The ad blocker works by server name. Ads served from the same server as "
            + "the page itself, such as YouTube's video ads, get through.\n"
            + "• Downloads are saved to the shared Downloads folder.";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = this::refresh;
    private LinearLayout list;
    private TextView adNote;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = Ui.dp(this, 20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        Ui.fitInsets(root, 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(pad, pad, pad, Ui.dp(this, 14));
        head.addView(Ui.text(this, "Bulkhead", 28, Ui.TEXT, true));
        TextView sub = Ui.text(this, "Every compartment is its own process with its own cookies, "
                + "logins, storage and cache. Nothing crosses unless you send it.", 14, Ui.MUTED, false);
        sub.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        head.addView(sub);
        TextView how = Ui.text(this, "What this does and doesn't isolate", 14, Ui.ACCENT, true);
        how.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("How isolation works")
                .setMessage(ABOUT)
                .setPositiveButton("Close", null)
                .show());
        head.addView(how);
        head.addView(adblockRow());
        root.addView(head);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Ui.SURFACE);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        TextView disposable = Ui.button(this, "New disposable", Compartment.COLORS[Compartment.RED], Ui.INK);
        disposable.setOnClickListener(v -> Slots.open(this, Store.newDisposable(this).id, null, 0, null));
        TextView add = Ui.button(this, "Add compartment", Ui.RAISED, Ui.TEXT);
        add.setOnClickListener(v -> edit(null));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f);
        left.rightMargin = Ui.dp(this, 10);
        bar.addView(disposable, left);
        bar.addView(add, new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f));
        root.addView(bar);

        setContentView(root);
    }

    /** The master ad-blocker switch. It applies to every compartment, running or not. */
    private View adblockRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.round(Ui.SURFACE, Ui.dp(this, 14)));
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, "Ad blocker", 16, Ui.TEXT, true));
        adNote = Ui.text(this, "", 12.5f, Ui.MUTED, false);
        texts.addView(adNote);
        row.addView(texts, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));

        Switch sw = new Switch(this);
        sw.setContentDescription("Ad blocker");
        sw.setChecked(Store.adblock(this));
        sw.setOnCheckedChangeListener((button, on) -> {
            Store.setAdblock(this, on);
            refresh();
        });
        row.addView(sw);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.topMargin = Ui.dp(this, 14);
        row.setLayoutParams(lp);
        return row;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        final Context app = getApplicationContext();
        new Thread(() -> {
            Slots.sweep(app);
            handler.post(refresher);
        }).start();
        // A compartment that was just closed finishes cleaning up a moment later.
        handler.postDelayed(refresher, 1500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private void refresh() {
        if (isFinishing() || isDestroyed()) return;
        ArrayList<Compartment> all = Store.load(this);
        HashMap<String, Integer> states = Slots.states(this);
        final boolean adblock = Store.adblock(this);
        adNote.setText(adblock ? "On in every compartment" : "Off in every compartment");
        list.removeAllViews();
        for (int pass = 0; pass < 2; pass++) {
            for (Compartment cp : all) {
                if (cp.disposable != (pass == 1)) continue;
                Integer st = states.get(cp.id);
                list.addView(row(cp, st == null ? Slots.IDLE : st, adblock));
            }
        }
        if (all.isEmpty()) {
            TextView empty = Ui.text(this, "No compartments yet. Add one, or start a disposable.",
                    15, Ui.MUTED, false);
            empty.setPadding(Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 8), 0);
            list.addView(empty);
        }
    }

    private View row(final Compartment cp, final int st, boolean adblock) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.pressable(Ui.round(Ui.SURFACE, Ui.dp(this, 14))));
        row.setClipToOutline(true);

        View stripe = new View(this);
        stripe.setBackgroundColor(cp.argb());
        row.addView(stripe, new LinearLayout.LayoutParams(Ui.dp(this, 7), Ui.MATCH));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14));
        texts.addView(Ui.text(this, cp.name, 17, Ui.TEXT, true));
        TextView summary = Ui.text(this, cp.summary(adblock), 12.5f, Ui.MUTED, false);
        summary.setPadding(0, Ui.dp(this, 3), 0, 0);
        texts.addView(summary);
        row.addView(texts, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));

        if (st != Slots.IDLE) {
            boolean running = st == Slots.RUNNING;
            TextView pill = Ui.text(this, running ? "RUNNING" : "PAUSED", 10.5f,
                    running ? Ui.OK : Ui.MUTED, true);
            pill.setLetterSpacing(0.08f);
            pill.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
            pill.setBackground(Ui.outline(this, 0, running ? Ui.OK : Ui.LINE, Ui.dp(this, 10)));
            row.addView(pill);
        }

        ImageButton more = new ImageButton(this);
        more.setImageResource(R.drawable.ic_more);
        more.setBackground(null);
        more.setColorFilter(Ui.MUTED);
        more.setContentDescription("Options for " + cp.name);
        more.setOnClickListener(v -> rowMenu(v, cp, st));
        row.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 52)));

        row.setOnClickListener(v -> Slots.open(this, cp.id, null, 0, null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.topMargin = Ui.dp(this, 8);
        row.setLayoutParams(lp);
        return row;
    }

    private void rowMenu(View anchor, final Compartment cp, int st) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Open");
        if (cp.disposable) {
            m.add(0, 3, 0, "Destroy now");
        } else {
            m.add(0, 2, 0, "Edit…");
            if (st != Slots.IDLE) m.add(0, 3, 0, "Stop");
            m.add(0, 4, 0, "Wipe data…");
            m.add(0, 5, 0, "Delete…");
        }
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    Slots.open(this, cp.id, null, 0, null);
                    break;
                case 2:
                    edit(cp);
                    break;
                case 3:
                    end(cp.id, false, false);
                    break;
                case 4:
                    confirm("Wipe " + cp.name + "?",
                            "Cookies, logins, site storage, cache and open tabs in this compartment "
                            + "will be erased. The compartment itself stays.",
                            "Wipe", () -> end(cp.id, true, false));
                    break;
                case 5:
                    confirm("Delete " + cp.name + "?",
                            "The compartment and everything stored in it will be erased.",
                            "Delete", () -> end(cp.id, true, true));
                    break;
                default:
                    break;
            }
            return true;
        });
        pm.show();
    }

    private void confirm(String title, String message, String action, final Runnable onYes) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(action, (dialog, which) -> onYes.run())
                .show();
    }

    private void end(final String id, final boolean wipe, final boolean delete) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            Slots.end(app, id, wipe, delete);
            handler.post(refresher);
        }).start();
    }

    /** Add (cp == null) or edit a compartment. Changes reach a running compartment when it is next shown. */
    private void edit(final Compartment existing) {
        final Compartment cp = existing != null ? existing : Compartment.create("", Compartment.BLUE, false);
        int pad = Ui.dp(this, 22);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);

        final EditText name = new EditText(this);
        name.setHint("Name");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setText(cp.name);
        box.addView(name);

        final int[] picked = { Math.floorMod(cp.color, Compartment.COLORS.length) };
        final View[] dots = new View[Compartment.COLORS.length];
        LinearLayout swatches = new LinearLayout(this);
        swatches.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        for (int i = 0; i < dots.length; i++) {
            final int index = i;
            View dot = new View(this);
            dot.setOnClickListener(v -> {
                picked[0] = index;
                paint(dots, index);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1f);
            lp.leftMargin = Ui.dp(this, 3);
            lp.rightMargin = Ui.dp(this, 3);
            swatches.addView(dot, lp);
            dots[i] = dot;
        }
        paint(dots, picked[0]);
        box.addView(swatches);

        final CheckBox js = check(box, "JavaScript", cp.js);
        final CheckBox block3p = check(box, "Block third-party cookies", cp.block3p);
        final CheckBox https = check(box, "HTTPS only (ask before loading unencrypted pages)", cp.httpsOnly);
        final CheckBox trackers = check(box, "Ad blocker (uncheck if sites break here)", cp.blockTrackers);
        final CheckBox desktop = check(box, "Request desktop sites", cp.desktop);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "New compartment" : "Edit compartment")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String n = name.getText().toString().trim();
                    cp.name = n.isEmpty() ? "Compartment" : n;
                    cp.color = picked[0];
                    cp.js = js.isChecked();
                    cp.block3p = block3p.isChecked();
                    cp.httpsOnly = https.isChecked();
                    cp.blockTrackers = trackers.isChecked();
                    cp.desktop = desktop.isChecked();
                    Store.put(this, cp);
                    refresh();
                })
                .show();
    }

    private CheckBox check(LinearLayout box, String label, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(on);
        c.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), 0, Ui.dp(this, 10));
        box.addView(c);
        return c;
    }

    private void paint(View[] dots, int selected) {
        for (int i = 0; i < dots.length; i++) {
            GradientDrawable d = Ui.round(Compartment.COLORS[i], Ui.dp(this, 9));
            if (i == selected) d.setStroke(Ui.dp(this, 3), 0xFFFFFFFF);
            dots[i].setBackground(d);
        }
    }
}
