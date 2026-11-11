package app.bulkhead;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Runs in the main process. Compartment processes cannot safely erase their own
 * files (WebView is still writing them), so they announce that they are closing
 * and the main process cleans up once they are dead.
 */
public class CleanupReceiver extends BroadcastReceiver {
    static final String CLOSED = "app.bulkhead.CLOSED";
    static final String SWEEP = "app.bulkhead.SWEEP";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        String id = intent.getStringExtra("id");
        if (CLOSED.equals(intent.getAction()) && Compartment.validId(id)) {
            Slots.onClosed(app, intent.getIntExtra("slot", -1), id,
                    intent.getIntExtra("pid", 0), pending::finish);
        } else {
            new Thread(() -> {
                try {
                    Slots.sweep(app);
                } finally {
                    pending.finish();
                }
            }).start();
        }
    }
}
