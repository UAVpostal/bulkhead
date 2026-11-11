package app.bulkhead;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Invisible, not exported. A compartment process starts this to send a link to
 * another compartment or a new disposable: slot assignment has to happen in the
 * main process. Because it is not exported, other apps cannot use it to push a
 * URL into a compartment of their choosing.
 */
public class OpenActivity extends Activity {
    static final String EXTRA_DISPOSABLE = "disposable";
    static final String EXTRA_WAIT_PID = "waitPid";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            finish();
            return;
        }
        Intent in = getIntent();
        String id = in.getStringExtra(BrowserActivity.EXTRA_ID);
        if (in.getBooleanExtra(EXTRA_DISPOSABLE, false)) id = Store.newDisposable(this).id;
        if (!Compartment.validId(id)) {
            finish();
            return;
        }
        Slots.open(this, id, in.getStringExtra(BrowserActivity.EXTRA_URL),
                in.getIntExtra(EXTRA_WAIT_PID, 0), this::finish);
    }
}
