package app.bulkhead;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** View and string helpers shared by every screen. */
final class Ui {
    static final int BG = 0xFF0D1117;
    static final int SURFACE = 0xFF161B22;
    static final int RAISED = 0xFF21262D;
    static final int CARD = 0xFF2D333B;
    static final int LINE = 0xFF30363D;
    static final int TEXT = 0xFFE6EDF3;
    static final int MUTED = 0xFF8B949E;
    static final int ACCENT = 0xFF58A6FF;
    static final int INK = 0xFF0D1117;
    static final int OK = 0xFF3FB950;

    static final int MATCH = -1; // ViewGroup.LayoutParams.MATCH_PARENT
    static final int WRAP = -2;  // ViewGroup.LayoutParams.WRAP_CONTENT

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(int fill, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        return d;
    }

    static GradientDrawable outline(Context c, int fill, int stroke, float radius) {
        GradientDrawable d = round(fill, radius);
        d.setStroke(dp(c, 1.5f), stroke);
        return d;
    }

    /** Wraps a background so the view shows touch feedback. */
    static Drawable pressable(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null);
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView button(Context c, String label, int fill, int textColor) {
        TextView t = text(c, label, 15, textColor, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setBackground(pressable(round(fill, dp(c, 12))));
        return t;
    }

    /** A list row with a color dot, a title and a subtitle, used by the compartment pickers. */
    static View pickRow(Context c, int color, String title, String sub) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 22), dp(c, 12), dp(c, 22), dp(c, 12));

        View dot = new View(c);
        dot.setBackground(round(color, dp(c, 8)));
        row.addView(dot, new LinearLayout.LayoutParams(dp(c, 16), dp(c, 16)));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(c, 16), 0, 0, 0);
        texts.addView(text(c, title, 16, TEXT, true));
        if (sub != null) {
            TextView s = text(c, sub, 12.5f, MUTED, false);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));
        return row;
    }

    /**
     * Pads the view by the system bars, cutout and keyboard, plus a fixed extra.
     * Needed on Android 15+, where every app draws edge to edge.
     */
    static void fitInsets(View v, final int extra) {
        v.setPadding(extra, extra, extra, extra);
        v.setOnApplyWindowInsetsListener((view, in) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets x = in.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = x.left;
                t = x.top;
                r = x.right;
                b = x.bottom;
            } else {
                l = in.getSystemWindowInsetLeft();
                t = in.getSystemWindowInsetTop();
                r = in.getSystemWindowInsetRight();
                b = in.getSystemWindowInsetBottom();
            }
            view.setPadding(l + extra, t + extra, r + extra, b + extra);
            return in;
        });
    }

    static void toast(Context c, String s) {
        Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }

    static String host(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? url : h;
        } catch (RuntimeException e) {
            return url;
        }
    }

    static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Turns address-bar input into a URL: a web address is completed, anything else is searched. */
    static String toUrl(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return null;
        if (s.matches("(?i)^https?://\\S+$")) return s;
        boolean hostLike = !s.contains(" ") && !s.contains("://")
                && s.matches("^[^/?#]*[A-Za-z0-9]\\.[A-Za-z0-9][^\\s]*$");
        if (hostLike || s.matches("(?i)^localhost(:\\d+)?(/\\S*)?$")) return "https://" + s;
        return "https://duckduckgo.com/?q=" + Uri.encode(s);
    }

    /** What a tab is called in the tab grid. */
    static String tabLabel(String url, String title) {
        if (url == null || url.startsWith("about:")) return "New tab";
        if (title == null || title.trim().isEmpty() || title.equals(url) || title.startsWith("about:")) {
            return host(url);
        }
        return title.trim();
    }

    /** Whether a tab matches what was typed into the tab search box. */
    static boolean matches(String query, String label, String url) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        return (label != null && label.toLowerCase(Locale.ROOT).contains(q))
                || (url != null && !url.startsWith("about:") && url.toLowerCase(Locale.ROOT).contains(q));
    }

    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    static String firstUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_IN_TEXT.matcher(text);
        return m.find() ? m.group() : null;
    }

    private static final Pattern CHROME_MAJOR = Pattern.compile("Chrome/(\\d+)");

    /**
     * A plain Chrome user agent built from WebView's own version number, so every
     * compartment presents the same common string instead of one that names the device.
     */
    static String browserUa(String webViewUa, boolean desktop) {
        Matcher m = CHROME_MAJOR.matcher(webViewUa == null ? "" : webViewUa);
        String v = (m.find() ? m.group(1) : "130") + ".0.0.0";
        if (desktop) {
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                    + v + " Safari/537.36";
        }
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + v + " Mobile Safari/537.36";
    }
}
