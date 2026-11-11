package app.bulkhead;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

/**
 * A short built-in list of well-known advertising and tracking hosts.
 * It is a baseline, not a full content blocker. A listed domain also blocks its subdomains.
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

    private Blocklist() {}

    static boolean blocked(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        while (true) {
            if (HOSTS.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false;
            h = h.substring(dot + 1);
        }
    }
}
