package app.bulkhead;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;

/**
 * One isolated browsing context.
 *
 * The id doubles as the WebView data-directory suffix, so it is restricted to
 * lowercase letters and digits and never changes for the life of the compartment.
 */
final class Compartment {
    static final int[] COLORS = {
            0xFF4C8DF6, 0xFFA371F7, 0xFF3FB950, 0xFFF0883E,
            0xFFE5484D, 0xFFE3B341, 0xFF2BB3A3, 0xFF8B949E };
    static final int BLUE = 0, PURPLE = 1, GREEN = 2, ORANGE = 3, RED = 4;

    String id;
    String name = "";
    int color;
    boolean disposable;
    long created;

    boolean js = true;
    boolean block3p = true;
    boolean httpsOnly = true;
    boolean blockTrackers = true;
    boolean desktop;

    static Compartment create(String name, int color, boolean disposable) {
        Compartment c = new Compartment();
        c.id = newId();
        c.name = name;
        c.color = color;
        c.disposable = disposable;
        c.created = System.currentTimeMillis();
        return c;
    }

    private static String newId() {
        SecureRandom r = new SecureRandom();
        String letters = "abcdefghijklmnopqrstuvwxyz";
        String all = letters + "0123456789";
        StringBuilder sb = new StringBuilder();
        sb.append(letters.charAt(r.nextInt(letters.length())));
        for (int i = 0; i < 9; i++) sb.append(all.charAt(r.nextInt(all.length())));
        return sb.toString();
    }

    static boolean validId(String id) {
        return id != null && id.matches("[a-z][a-z0-9]{5,31}");
    }

    int argb() {
        return COLORS[Math.floorMod(color, COLORS.length)];
    }

    /** adblock is the master switch; a compartment can still opt out with blockTrackers. */
    String summary(boolean adblock) {
        StringBuilder sb = new StringBuilder();
        if (disposable) sb.append("Disposable · ");
        sb.append(js ? "JavaScript on" : "JavaScript off");
        if (block3p) sb.append(" · no 3rd-party cookies");
        if (httpsOnly) sb.append(" · HTTPS only");
        if (adblock && blockTrackers) sb.append(" · ads blocked");
        if (desktop) sb.append(" · desktop site");
        return sb.toString();
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("color", color);
        o.put("disposable", disposable);
        o.put("created", created);
        o.put("js", js);
        o.put("block3p", block3p);
        o.put("httpsOnly", httpsOnly);
        o.put("blockTrackers", blockTrackers);
        o.put("desktop", desktop);
        return o;
    }

    static Compartment fromJson(JSONObject o) {
        Compartment c = new Compartment();
        c.id = o.optString("id", "");
        c.name = o.optString("name", "");
        c.color = o.optInt("color", 0);
        c.disposable = o.optBoolean("disposable", false);
        c.created = o.optLong("created", 0);
        c.js = o.optBoolean("js", true);
        c.block3p = o.optBoolean("block3p", true);
        c.httpsOnly = o.optBoolean("httpsOnly", true);
        c.blockTrackers = o.optBoolean("blockTrackers", true);
        c.desktop = o.optBoolean("desktop", false);
        return c;
    }
}
