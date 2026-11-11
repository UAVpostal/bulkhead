package app.bulkhead;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

/**
 * The ad and tracker blocker's host lists. A listed domain also blocks its subdomains.
 *
 * There are two: a short built-in list that is always present, and a large one
 * (assets/blocklist.bin, made by tools/make_blocklist.py at build time) that is
 * loaded in the background when a compartment starts. If the large list is
 * missing from a build, the built-in one is used alone.
 */
final class Blocklist {
    private static final HashSet<String> HOSTS = new HashSet<>(Arrays.asList(
            // Google ads and analytics
            "doubleclick.net", "googlesyndication.com", "google-analytics.com",
            "googletagmanager.com", "googletagservices.com", "googleadservices.com",
            "adservice.google.com", "app-measurement.com", "googleoptimize.com",
            // Meta, Microsoft, X, TikTok, LinkedIn, Pinterest, Snap, Yahoo, Yandex
            "connect.facebook.net", "pixel.facebook.com", "bat.bing.com", "clarity.ms",
            "ads-twitter.com", "analytics.twitter.com", "analytics.tiktok.com",
            "px.ads.linkedin.com", "snap.licdn.com", "ct.pinterest.com", "tr.snapchat.com",
            "sc-static.net", "ads.yahoo.com", "analytics.yahoo.com", "mc.yandex.ru", "an.yandex.ru",
            // Ad exchanges and networks
            "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
            "amazon-adsystem.com", "rubiconproject.com", "pubmatic.com", "openx.net",
            "casalemedia.com", "indexww.com", "adsrvr.org", "adform.net", "smartadserver.com",
            "teads.tv", "media.net", "zedo.com", "advertising.com", "yieldmo.com",
            "sharethrough.com", "33across.com", "lijit.com", "bidswitch.net", "mathtag.com",
            "contextweb.com", "gumgum.com", "3lift.com", "spotxchange.com", "tremorhub.com",
            "stickyadstv.com", "mgid.com", "revcontent.com", "popads.net", "propellerads.com",
            "exoclick.com", "juicyads.com", "trafficjunky.net", "serving-sys.com", "sizmek.com",
            "onetag-sys.com", "smaato.net", "inmobi.com", "applovin.com", "vungle.com",
            "chartboost.com", "mopub.com", "zemanta.com", "simpli.fi", "bidr.io",
            // Data brokers and identity sync
            "scorecardresearch.com", "quantserve.com", "quantcount.com", "bluekai.com",
            "demdex.net", "omtrdc.net", "2o7.net", "everesttech.net", "rlcdn.com", "krxd.net",
            "exelator.com", "agkn.com", "tapad.com", "crwdcntrl.net", "liadm.com",
            "id5-sync.com", "adsymptotic.com", "pippio.com", "eyeota.net", "mookie1.com",
            "turn.com", "imrworldwide.com",
            // Session recording and analytics
            "hotjar.com", "mouseflow.com", "crazyegg.com", "fullstory.com", "mixpanel.com",
            "api.segment.io", "cdn.segment.com", "api.amplitude.com", "cdn.amplitude.com",
            "nr-data.net", "chartbeat.com", "chartbeat.net", "parsely.com", "statcounter.com",
            "histats.com", "getclicky.com", "appsflyer.com", "adjust.com",
            // Verification and measurement
            "moatads.com", "doubleverify.com", "adsafeprotected.com",
            // Share widgets that track
            "addthis.com", "sharethis.com"));

    /** Sorted hashes of the large list, or null until (or unless) it has loaded. */
    private static volatile long[] big;
    private static boolean started;

    private Blocklist() {}

    /** Loads the large list once per process, off the main thread. */
    static synchronized void loadAsync(final Context app) {
        if (started) return;
        started = true;
        new Thread(() -> {
            try (InputStream in = app.getAssets().open("blocklist.bin")) {
                big = read(in);
            } catch (IOException | RuntimeException ignored) {
                // No large list in this build: the built-in list still applies.
            }
        }).start();
    }

    static long[] read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(new BufferedInputStream(in, 1 << 16));
        if (d.readInt() != 0x424C4B31) throw new IOException("not a block list"); // "BLK1"
        int n = d.readInt();
        if (n < 0 || n > 2000000) throw new IOException("bad size");
        long[] a = new long[n];
        for (int i = 0; i < n; i++) a[i] = d.readLong();
        return a;
    }

    static void use(long[] hashes) {
        big = hashes;
    }

    /** How many hosts the blocker knows. */
    static int size() {
        long[] b = big;
        return HOSTS.size() + (b == null ? 0 : b.length);
    }

    /** 64-bit FNV-1a of the host from the given position. Must match tools/make_blocklist.py. */
    private static long hash(String s, int from) {
        long h = 0xCBF29CE484222325L;
        for (int i = from; i < s.length(); i++) {
            h ^= s.charAt(i) & 0xFF;
            h *= 0x100000001B3L;
        }
        return h;
    }

    static boolean blocked(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        long[] b = big;
        int from = 0;
        while (true) {
            if (HOSTS.contains(from == 0 ? h : h.substring(from))) return true;
            if (b != null && Arrays.binarySearch(b, hash(h, from)) >= 0) return true;
            int dot = h.indexOf('.', from);
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false;
            from = dot + 1;
        }
    }
}
