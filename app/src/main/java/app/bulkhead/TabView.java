package app.bulkhead;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.webkit.WebView;

/**
 * One tab: a WebView plus what the tab grid shows for it.
 *
 * The thumbnail and site icon are held in memory only. They are never written
 * to storage, so they disappear with the compartment's process.
 */
final class TabView extends WebView {
    /** Thumbnail height divided by its width. */
    static final float THUMB_RATIO = 1.1f;

    /** Address to load when the tab is first shown; null once it has been loaded. */
    String pending;
    /** Title remembered from the last session, used until the page is loaded again. */
    String savedTitle;
    /** The site's icon, once the page has supplied one. */
    Bitmap icon;
    /** A small picture of the page as it last looked, or null if it has not been shown yet. */
    Bitmap thumb;

    TabView(Context c) {
        super(c);
    }

    String address() {
        return pending != null ? pending : getUrl();
    }

    String label() {
        return pending != null ? Ui.tabLabel(pending, savedTitle) : Ui.tabLabel(getUrl(), getTitle());
    }

    /** Redraws the thumbnail from what the tab shows right now. Does nothing until the tab is laid out. */
    void capture(int maxWidth) {
        int vw = getWidth();
        int vh = getHeight();
        if (pending != null || vw <= 0 || vh <= 0) return;
        int tw = Math.max(1, Math.min(Math.round(vw * 0.4f), maxWidth));
        int th = Math.max(1, Math.round(tw * THUMB_RATIO));
        try {
            Bitmap b = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(b);
            c.drawColor(Color.WHITE);
            // Draw the visible part of the page, from its top-left corner, scaled to fill the thumbnail.
            int sx = getScrollX();
            int sy = getScrollY();
            float scale = Math.max(tw / (float) vw, th / (float) vh);
            c.translate(-sx, -sy);
            c.scale(scale, scale, sx, sy);
            draw(c);
            thumb = b;
        } catch (RuntimeException | OutOfMemoryError e) {
            // Keep whatever thumbnail there was.
        }
    }
}
