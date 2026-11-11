package app.bulkhead;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The browser for one compartment. Never used directly: the manifest declares
 * the Slot.S0 to Slot.S7 subclasses, each in its own process.
 *
 * Before any WebView exists, the process is bound to its compartment by giving
 * WebView a data directory that belongs to that compartment alone. That binding
 * lasts until the process dies.
 */
public class BrowserActivity extends Activity {
    static final String EXTRA_ID = "id";
    static final String EXTRA_URL = "url";

    private static final String BLANK = "about:blank";
    private static final int REQ_FILE = 7;

    /** The compartment this process is bound to. Set once per process. */
    private static String boundId;
    /** Counts activity instances, so a delayed kill can tell if the process was reused. */
    private static int generation;

    /** Hides the empty boxes that blocked ads leave behind. Runs only where JavaScript is on. */
    private static final String HIDE_AD_SLOTS = "(function(){try{"
            + "if(document.getElementById('bulkhead-hide'))return;"
            + "var s=document.createElement('style');s.id='bulkhead-hide';"
            + "s.textContent='ins.adsbygoogle,[id^=\"google_ads_\"],[id^=\"div-gpt-ad\"],"
            + "iframe[id^=\"aswift_\"],iframe[src*=\"doubleclick.net\"],"
            + "iframe[src*=\"googlesyndication.com\"],[id^=\"taboola-\"],.OUTBRAIN,.ob-widget,"
            + "amp-ad{display:none!important}';"
            + "(document.head||document.documentElement).appendChild(s);"
            + "}catch(e){}})()";

    private volatile Compartment cp;
    /** The master ad-blocker switch, re-read whenever the compartment comes to the front. */
    private volatile boolean adblock = true;
    private int slot = -1;
    private boolean closing;
    private boolean asking;
    private String webViewUa;

    private FrameLayout frame;
    private LinearLayout content;
    private FrameLayout webHost;
    private EditText urlBar;
    private TextView chip;
    private TextView tabsButton;
    private View progress;

    private final ArrayList<WebView> tabs = new ArrayList<>();
    private int current = -1;

    private ValueCallback<Uri[]> fileCallback;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    /** Hosts the user allowed over plain HTTP, for this session only. */
    private final HashSet<String> httpAllowed = new HashSet<>();
    /** Hosts recently switched from HTTP to HTTPS, to detect ones that can't do HTTPS. */
    private final HashMap<String, Long> upgraded = new HashMap<>();
    private final AtomicInteger trackersBlocked = new AtomicInteger();

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        generation++;
        for (int k = 0; k < Slots.COUNT; k++) {
            if (Slots.CLASSES[k] == getClass()) slot = k;
        }
        String id = getIntent().getStringExtra(EXTRA_ID);
        if (!bind(id)) return;

        Compartment found = Store.find(this, id);
        if (found == null) {
            Ui.toast(this, "That compartment no longer exists");
            die();
            return;
        }
        cp = found;
        adblock = Store.adblock(this);
        Blocklist.loadAsync(getApplicationContext());
        webViewUa = WebSettings.getDefaultUserAgent(this);
        buildUi();

        ArrayList<String> urls = null;
        int index = 0;
        if (state != null) {
            urls = state.getStringArrayList("tabs");
            index = state.getInt("current", 0);
        } else if (!found.disposable) {
            try {
                JSONObject o = new JSONObject(Store.read(Store.sessionFile(this, id)));
                JSONArray a = o.getJSONArray("tabs");
                urls = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) urls.add(a.getString(i));
                index = o.optInt("current", 0);
            } catch (Exception ignored) {
                urls = null; // no saved session
            }
        }
        if (urls != null) {
            for (String u : urls) addTab(u, false);
        }
        String url = state == null ? getIntent().getStringExtra(EXTRA_URL) : null;
        if (url != null) {
            addTab(url, true);
        } else if (tabs.isEmpty()) {
            addTab(null, true);
        } else {
            show(Math.max(0, Math.min(index, tabs.size() - 1)));
        }

        // Ask the main process to clear out anything left by compartments that died uncleanly.
        sendBroadcast(new Intent(this, CleanupReceiver.class).setAction(CleanupReceiver.SWEEP));
    }

    /**
     * Binds this process to the compartment, or returns false (and winds the
     * process down) if that is impossible.
     */
    private boolean bind(String id) {
        if (slot < 0 || !Compartment.validId(id)) {
            closing = true;
            finish();
            return false;
        }
        if (boundId == null) {
            if (!id.equals(Slots.owner(this, slot))) {
                // A task Android restored after its slot was given to another compartment.
                die();
                return false;
            }
            try {
                WebView.setDataDirectorySuffix(id);
            } catch (RuntimeException e) {
                bounce(id);
                return false;
            }
            boundId = id;
            return true;
        }
        if (boundId.equals(id)) return true;
        bounce(id);
        return false;
    }

    /** This process belongs to another compartment: end it and have the main process relaunch. */
    private void bounce(String id) {
        closing = true;
        Intent i = new Intent(this, OpenActivity.class)
                .putExtra(EXTRA_ID, id)
                .putExtra(OpenActivity.EXTRA_WAIT_PID, Process.myPid())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url != null) i.putExtra(EXTRA_URL, url);
        try {
            startActivity(i);
        } catch (RuntimeException ignored) {
        }
        finishAndRemoveTask();
        killSoon(200);
    }

    private void die() {
        closing = true;
        finishAndRemoveTask();
        killSoon(300);
    }

    private void killSoon(long delayMs) {
        final int mine = generation;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // If the compartment was reopened in this process meanwhile, leave it running.
            if (generation == mine) Process.killProcess(Process.myPid());
        }, delayMs);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (cp == null || closing) return;
        String id = intent.getStringExtra(EXTRA_ID);
        if (id != null && !id.equals(boundId)) {
            saveSession();
            bounce(id);
            return;
        }
        String url = intent.getStringExtra(EXTRA_URL);
        if (url != null) addTab(url, true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        WebView w = currentTab();
        if (w != null) w.onResume();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cp == null || closing) return;
        Compartment fresh = Store.find(this, cp.id);
        if (fresh == null) {
            closeCompartment();
            return;
        }
        cp = fresh; // settings may have been edited in the launcher
        adblock = Store.adblock(this);
        applyIdentity();
        for (WebView w : tabs) applySettings(w);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (cp == null || closing) return;
        saveSession();
        CookieManager.getInstance().flush();
        WebView w = currentTab();
        if (w != null) w.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (cp == null || closing) return;
        out.putStringArrayList("tabs", tabUrls());
        out.putInt("current", current);
    }

    @Override
    protected void onDestroy() {
        if (cp != null && !closing) {
            if (isFinishing()) {
                closeCompartment(); // task swiped away or removed from outside
            } else {
                for (WebView w : tabs) w.destroy();
                tabs.clear();
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        WebView w = currentTab();
        if (w != null && w.canGoBack()) {
            w.goBack();
            return;
        }
        moveTaskToBack(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    // ------------------------------------------------------------------------- UI

    private void buildUi() {
        frame = new FrameLayout(this);
        // The colored frame around the page is the compartment's identity, as in Qubes.
        Ui.fitInsets(frame, Ui.dp(this, 3));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Ui.BG);
        frame.addView(content, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.SURFACE);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 7), Ui.dp(this, 2), Ui.dp(this, 7));

        chip = Ui.text(this, "", 12.5f, Ui.INK, true);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setMaxWidth(Ui.dp(this, 112));
        chip.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
        chip.setOnClickListener(v -> switchCompartment());
        bar.addView(chip);

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setTextSize(15);
        urlBar.setTextColor(Ui.TEXT);
        urlBar.setHintTextColor(Ui.MUTED);
        urlBar.setHint("Search or enter address");
        urlBar.setBackground(Ui.round(Ui.RAISED, Ui.dp(this, 20)));
        urlBar.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                go(urlBar.getText().toString());
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f);
        urlLp.leftMargin = Ui.dp(this, 8);
        urlLp.rightMargin = Ui.dp(this, 8);
        bar.addView(urlBar, urlLp);

        tabsButton = Ui.text(this, "1", 13, Ui.TEXT, true);
        tabsButton.setGravity(Gravity.CENTER);
        tabsButton.setBackground(Ui.outline(this, 0, Ui.TEXT, Ui.dp(this, 7)));
        tabsButton.setContentDescription("Tabs");
        tabsButton.setOnClickListener(v -> showTabs());
        bar.addView(tabsButton, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));

        ImageButton menu = new ImageButton(this);
        menu.setImageResource(R.drawable.ic_more);
        menu.setBackground(null);
        menu.setColorFilter(Ui.TEXT);
        menu.setContentDescription("Menu");
        menu.setOnClickListener(this::showMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 40)));

        content.addView(bar, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        FrameLayout track = new FrameLayout(this);
        track.setBackgroundColor(Ui.SURFACE);
        progress = new View(this);
        progress.setPivotX(0);
        progress.setScaleX(0);
        track.addView(progress, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        content.addView(track, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 3)));

        webHost = new FrameLayout(this);
        content.addView(webHost, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        setContentView(frame);
        applyIdentity();
    }

    /** Paints everything that tells the user which compartment this is. */
    private void applyIdentity() {
        int color = cp.argb();
        frame.setBackgroundColor(color);
        chip.setText(cp.name);
        chip.setBackground(Ui.round(color, Ui.dp(this, 14)));
        progress.setBackgroundColor(color);
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(Ui.BG);
        // The label colors are all light enough to need dark status-bar icons.
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) {
                ic.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        setTaskDescription(new ActivityManager.TaskDescription(cp.name, (Bitmap) null, color));
    }

    private void showProgress(int percent) {
        progress.setScaleX(percent >= 100 ? 0f : Math.max(0.05f, percent / 100f));
    }

    private void syncBar() {
        WebView w = currentTab();
        tabsButton.setText(String.valueOf(tabs.size()));
        if (w == null || urlBar.hasFocus()) return;
        Object pending = w.getTag();
        String url = pending != null ? (String) pending : w.getUrl();
        urlBar.setText(url == null || url.startsWith("about:") ? "" : url);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        urlBar.clearFocus();
    }

    private void focusUrlBar() {
        urlBar.requestFocus();
        urlBar.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(urlBar, 0);
        });
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || text == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("link", text));
        if (Build.VERSION.SDK_INT < 33) Ui.toast(this, "Copied");
    }

    private void switchCompartment() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ----------------------------------------------------------------------- tabs

    private WebView currentTab() {
        return current >= 0 && current < tabs.size() ? tabs.get(current) : null;
    }

    /** Adds a tab. Its page is loaded the first time the tab is shown. */
    private WebView addTab(String url, boolean showNow) {
        WebView w = makeWebView();
        w.setTag(url == null || !url.matches("(?i)^https?://.*") ? BLANK : url);
        tabs.add(w);
        if (showNow) show(tabs.size() - 1);
        else tabsButton.setText(String.valueOf(tabs.size()));
        return w;
    }

    private void show(int index) {
        if (index < 0 || index >= tabs.size()) return;
        current = index;
        WebView w = tabs.get(index);
        for (WebView other : tabs) {
            if (other != w) other.onPause();
        }
        webHost.removeAllViews();
        webHost.addView(w, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        w.onResume();
        Object pending = w.getTag();
        if (pending != null) {
            w.setTag(null);
            load(w, (String) pending);
        }
        showProgress(w.getProgress());
        syncBar();
        w.requestFocus();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        WebView w = tabs.remove(index);
        webHost.removeView(w);
        w.destroy();
        if (tabs.isEmpty()) {
            current = -1;
            addTab(null, true);
            return;
        }
        int next = index < current ? current - 1 : current;
        show(Math.min(next, tabs.size() - 1));
    }

    private ArrayList<String> tabUrls() {
        ArrayList<String> out = new ArrayList<>();
        for (WebView w : tabs) {
            Object pending = w.getTag();
            String url = pending != null ? (String) pending : w.getUrl();
            out.add(url != null && url.matches("(?i)^https?://.*") ? url : BLANK);
        }
        return out;
    }

    private void saveSession() {
        if (cp == null || cp.disposable) return;
        try {
            JSONObject o = new JSONObject();
            o.put("tabs", new JSONArray(tabUrls()));
            o.put("current", current);
            Store.writeAtomic(Store.sessionFile(this, cp.id), o.toString());
        } catch (Exception ignored) {
        }
    }

    private void showTabs() {
        String[] names = new String[tabs.size()];
        for (int i = 0; i < names.length; i++) {
            WebView w = tabs.get(i);
            Object pending = w.getTag();
            String title = pending != null ? Ui.host((String) pending) : w.getTitle();
            names[i] = title == null || title.isEmpty() || title.startsWith("about:") ? "New tab" : title;
        }
        new AlertDialog.Builder(this)
                .setTitle("Tabs in " + cp.name)
                .setSingleChoiceItems(names, current, (dialog, which) -> {
                    dialog.dismiss();
                    show(which);
                })
                .setPositiveButton("New tab", (dialog, which) -> {
                    addTab(null, true);
                    focusUrlBar();
                })
                .setNegativeButton("Close this tab", (dialog, which) -> closeTab(current))
                .show();
    }

    // ----------------------------------------------------------------- navigation

    private void go(String input) {
        String url = Ui.toUrl(input);
        WebView w = currentTab();
        if (url == null || w == null) return;
        hideKeyboard();
        load(w, url);
        w.requestFocus();
    }

    private void load(WebView w, String url) {
        if (url == null || url.startsWith("about:")) {
            w.loadDataWithBaseURL(BLANK, startPage(), "text/html", "UTF-8", null);
            return;
        }
        Uri u = Uri.parse(url);
        String host = String.valueOf(u.getHost());
        if (cp.httpsOnly && "http".equalsIgnoreCase(u.getScheme()) && !httpAllowed.contains(host)) {
            upgraded.put(host, SystemClock.elapsedRealtime());
            url = "https" + url.substring(4);
        }
        w.loadUrl(url);
    }

    private String startPage() {
        String color = String.format(Locale.ROOT, "#%06X", cp.argb() & 0xFFFFFF);
        String note = cp.disposable
                ? "Everything in here is erased when you close it."
                : "Long-press a link to open it in a different compartment.";
        return "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;background:#0d1117;color:#e6edf3;font:16px/1.5 sans-serif;"
                + "display:flex;min-height:100vh;align-items:center;justify-content:center}"
                + ".c{max-width:21rem;padding:2rem}.t{display:inline-block;padding:.25rem .75rem;"
                + "border-radius:1rem;background:" + color + ";color:#0d1117;font-weight:700;font-size:.85rem}"
                + "h1{font-size:1.35rem;line-height:1.3;margin:1rem 0 .5rem}p{color:#8b949e;margin:.4rem 0}"
                + "</style><div class=c><span class=t>" + TextUtils.htmlEncode(cp.name) + "</span>"
                + "<h1>You're inside this compartment.</h1>"
                + "<p>Cookies, logins, storage and cache stay here. Other compartments can't see them.</p>"
                + "<p>" + note + "</p></div>";
    }

    private void showMenu(View anchor) {
        final WebView w = currentTab();
        if (w == null) return;
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Back").setEnabled(w.canGoBack());
        m.add(0, 2, 0, "Forward").setEnabled(w.canGoForward());
        m.add(0, 3, 0, "Reload");
        m.add(0, 4, 0, "New tab");
        m.add(0, 5, 0, "Open this page in…");
        m.add(0, 6, 0, "Copy address");
        if (adblock && cp.blockTrackers) {
            m.add(0, 0, 0, "Ads and trackers blocked: " + trackersBlocked.get()).setEnabled(false);
        }
        m.add(0, 7, 0, "Switch compartment");
        m.add(0, 8, 0, cp.disposable ? "Close and destroy" : "Close compartment");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    w.goBack();
                    break;
                case 2:
                    w.goForward();
                    break;
                case 3:
                    w.reload();
                    break;
                case 4:
                    addTab(null, true);
                    focusUrlBar();
                    break;
                case 5:
                    sendTo(w.getUrl());
                    break;
                case 6:
                    copy(w.getUrl());
                    break;
                case 7:
                    switchCompartment();
                    break;
                case 8:
                    closeCompartment();
                    break;
                default:
                    break;
            }
            return true;
        });
        pm.show();
    }

    /** Hands a URL to another compartment. Only the address crosses; no cookies, no referrer. */
    private void sendTo(final String url) {
        if (url == null || !url.matches("(?i)^https?://.*")) {
            Ui.toast(this, "No web page to send");
            return;
        }
        final ArrayList<Compartment> others = new ArrayList<>();
        for (Compartment c : Store.load(this)) {
            if (!c.id.equals(cp.id)) others.add(c);
        }
        String[] names = new String[others.size() + 1];
        names[0] = "New disposable";
        for (int i = 0; i < others.size(); i++) names[i + 1] = others.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("Open in…")
                .setItems(names, (dialog, which) -> {
                    Intent i = new Intent(this, OpenActivity.class).putExtra(EXTRA_URL, url);
                    if (which == 0) i.putExtra(OpenActivity.EXTRA_DISPOSABLE, true);
                    else i.putExtra(EXTRA_ID, others.get(which - 1).id);
                    startActivity(i);
                })
                .show();
    }

    private void linkMenu(final String url) {
        if (url == null || closing) return;
        String[] items = { "Open in new tab", "Open in new disposable",
                "Open in another compartment…", "Copy link" };
        new AlertDialog.Builder(this)
                .setTitle(Ui.shorten(url, 80))
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        addTab(url, true);
                    } else if (which == 1) {
                        startActivity(new Intent(this, OpenActivity.class)
                                .putExtra(EXTRA_URL, url)
                                .putExtra(OpenActivity.EXTRA_DISPOSABLE, true));
                    } else if (which == 2) {
                        sendTo(url);
                    } else {
                        copy(url);
                    }
                })
                .show();
    }

    private void askHttp(final WebView view, final String httpUrl) {
        if (asking || closing || isFinishing()) return;
        asking = true;
        final String host = Ui.host(httpUrl);
        new AlertDialog.Builder(this)
                .setTitle("No secure connection")
                .setMessage(host + " can't be reached over HTTPS. Without it, anyone on the network "
                        + "can read or change what you see. Load it unencrypted in this compartment?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Load over HTTP", (dialog, which) -> {
                    httpAllowed.add(host);
                    view.loadUrl(httpUrl);
                })
                .setOnDismissListener(dialog -> asking = false)
                .show();
    }

    private void askExternal(Uri uri) {
        if (asking || closing || isFinishing()) return;
        final Intent target;
        try {
            if ("intent".equalsIgnoreCase(uri.getScheme())) {
                target = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                target.addCategory(Intent.CATEGORY_BROWSABLE);
                target.setComponent(null);
                target.setSelector(null);
            } else {
                target = new Intent(Intent.ACTION_VIEW, uri);
            }
        } catch (URISyntaxException e) {
            return;
        }
        asking = true;
        new AlertDialog.Builder(this)
                .setTitle("Leave this compartment?")
                .setMessage("This page wants to open another app:\n\n" + Ui.shorten(uri.toString(), 160))
                .setNegativeButton("Stay", null)
                .setPositiveButton("Open", (dialog, which) -> {
                    try {
                        startActivity(target);
                    } catch (RuntimeException e) {
                        Ui.toast(this, "No app can open that link");
                    }
                })
                .setOnDismissListener(dialog -> asking = false)
                .show();
    }

    private void download(final String url, final String userAgent, String disposition, final String mime) {
        if (url == null || !url.matches("(?i)^https?://.*")) {
            Ui.toast(this, "This kind of download isn't supported");
            return;
        }
        final String name = URLUtil.guessFileName(url, disposition, mime);
        new AlertDialog.Builder(this)
                .setTitle("Download file?")
                .setMessage(name + "\nfrom " + Ui.host(url) + "\n\nIt will be saved to the shared "
                        + "Downloads folder, outside this compartment.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Download", (dialog, which) -> {
                    try {
                        DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url));
                        String cookies = CookieManager.getInstance().getCookie(url);
                        if (cookies != null) rq.addRequestHeader("Cookie", cookies);
                        if (userAgent != null) rq.addRequestHeader("User-Agent", userAgent);
                        if (mime != null) rq.setMimeType(mime);
                        rq.setTitle(name);
                        rq.setNotificationVisibility(
                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        if (Build.VERSION.SDK_INT >= 29) {
                            rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                        } else {
                            rq.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
                        }
                        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                        dm.enqueue(rq);
                        Ui.toast(this, "Downloading " + name);
                    } catch (RuntimeException e) {
                        Ui.toast(this, "The download couldn't start");
                    }
                })
                .show();
    }

    // -------------------------------------------------------------------- WebView

    private WebView makeWebView() {
        final WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSafeBrowsingEnabled(true);
        // Pages get no route to local files, content providers or location.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        applySettings(w);

        w.setWebViewClient(new Client());
        w.setWebChromeClient(new Chrome());
        w.setDownloadListener((url, userAgent, disposition, mime, length) ->
                download(url, userAgent, disposition, mime));
        w.setOnLongClickListener(v -> {
            int type = w.getHitTestResult().getType();
            if (type != WebView.HitTestResult.SRC_ANCHOR_TYPE
                    && type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                return false;
            }
            // Ask WebView for the link target (for an image link, the hit result only has the image).
            Handler h = new Handler(Looper.getMainLooper(), msg -> {
                linkMenu(msg.getData().getString("url"));
                return true;
            });
            w.requestFocusNodeHref(h.obtainMessage());
            return true;
        });
        return w;
    }

    /** The per-compartment settings, which can change while the compartment is running. */
    private void applySettings(WebView w) {
        WebSettings s = w.getSettings();
        if (s.getJavaScriptEnabled() != cp.js) s.setJavaScriptEnabled(cp.js);
        String ua = Ui.browserUa(webViewUa, cp.desktop);
        if (!ua.equals(s.getUserAgentString())) s.setUserAgentString(ua);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, !cp.block3p);
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.equals("https") || scheme.equals("about") || scheme.equals("data")
                    || scheme.equals("blob") || scheme.equals("javascript")) {
                return false;
            }
            // A page never gets to navigate to local files or other apps' content providers.
            if (scheme.equals("file") || scheme.equals("content")) return true;
            if (scheme.equals("http")) {
                String host = String.valueOf(u.getHost());
                if (!cp.httpsOnly || !request.isForMainFrame() || httpAllowed.contains(host)) return false;
                long now = SystemClock.elapsedRealtime();
                Long last = upgraded.get(host);
                if (last != null && now - last < 4000) {
                    // We already switched this host to HTTPS and it sent us straight back.
                    askHttp(view, u.toString());
                    return true;
                }
                upgraded.put(host, now);
                view.loadUrl("https" + u.toString().substring(4));
                return true;
            }
            // Anything else (tel:, mailto:, intent:, market:) would leave the compartment.
            if (request.isForMainFrame() && request.hasGesture()) askExternal(u);
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            // Called on a background thread.
            Compartment c = cp;
            if (c != null && adblock && c.blockTrackers && !request.isForMainFrame()
                    && Blocklist.blocked(request.getUrl().getHost())) {
                trackersBlocked.incrementAndGet();
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (view == currentTab()) {
                showProgress(5);
                syncBar();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (adblock && cp.blockTrackers && url != null && url.startsWith("http")) {
                view.evaluateJavascript(HIDE_AD_SLOTS, null);
            }
            if (view == currentTab()) {
                showProgress(100);
                syncBar();
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            if (view == currentTab()) syncBar();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            offerHttpIfUpgraded(view, request.getUrl().toString());
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel(); // never proceed past a bad certificate
            String url = error.getUrl();
            if (url == null) return;
            if (!offerHttpIfUpgraded(view, url) && view == currentTab()
                    && Ui.host(url).equals(Ui.host(String.valueOf(view.getUrl())))) {
                Ui.toast(BrowserActivity.this, "Blocked: invalid certificate for " + Ui.host(url));
            }
        }

        /** If an HTTPS load we forced has failed, offer the plain-HTTP original instead. */
        private boolean offerHttpIfUpgraded(WebView view, String httpsUrl) {
            if (!httpsUrl.regionMatches(true, 0, "https://", 0, 8)) return false;
            String host = Ui.host(httpsUrl);
            Long last = upgraded.get(host);
            if (last == null || SystemClock.elapsedRealtime() - last > 20000) return false;
            upgraded.remove(host);
            askHttp(view, "http" + httpsUrl.substring(5));
            return true;
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The sandboxed renderer died. Replace the dead WebView instead of crashing.
            int index = tabs.indexOf(view);
            if (index < 0 || closing) return true;
            Object pending = view.getTag();
            String url = pending != null ? (String) pending : view.getUrl();
            webHost.removeView(view);
            tabs.remove(index);
            view.destroy();
            WebView fresh = makeWebView();
            // The visible tab restarts blank so a page that crashes on load can't loop.
            fresh.setTag(index == current || url == null ? BLANK : url);
            tabs.add(index, fresh);
            if (index == current) {
                show(index);
                Ui.toast(BrowserActivity.this, "The page crashed, so this tab was reset");
            }
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (view == currentTab()) showProgress(newProgress);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            if (!isUserGesture || closing) return false;
            WebView fresh = makeWebView();
            tabs.add(fresh);
            show(tabs.size() - 1);
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(fresh);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            closeTab(tabs.indexOf(window));
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
            } catch (RuntimeException e) {
                fileCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            // Fullscreen video stays inside the colored frame.
            customView = view;
            customCallback = callback;
            view.setBackgroundColor(Color.BLACK);
            content.setVisibility(View.GONE);
            frame.addView(view, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        frame.removeView(customView);
        content.setVisibility(View.VISIBLE);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
    }

    // ------------------------------------------------------------------- shutdown

    /**
     * Ends this compartment's process. For a disposable, WebView's own stores are
     * cleared here and the main process then deletes the files once we are dead.
     */
    private void closeCompartment() {
        if (closing) return;
        closing = true;
        Compartment c = cp;
        try {
            if (c.disposable) {
                CookieManager.getInstance().removeAllCookies(null);
                WebStorage.getInstance().deleteAllData();
                for (WebView w : tabs) {
                    w.clearCache(true);
                    w.clearHistory();
                    w.clearFormData();
                }
            } else {
                saveSession();
                CookieManager.getInstance().flush();
            }
            webHost.removeAllViews();
            for (WebView w : tabs) w.destroy();
            tabs.clear();
        } catch (RuntimeException ignored) {
        }
        sendBroadcast(new Intent(this, CleanupReceiver.class)
                .setAction(CleanupReceiver.CLOSED)
                .putExtra("slot", slot)
                .putExtra("id", c.id)
                .putExtra("pid", Process.myPid()));
        finishAndRemoveTask();
        // A kept compartment gets a moment for WebView to finish writing site storage.
        killSoon(c.disposable ? 250 : 900);
    }
}
