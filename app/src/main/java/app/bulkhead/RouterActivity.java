package app.bulkhead;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Receives links from other apps. It never opens anything by itself: the user
 * always picks the compartment, so an outside app cannot aim a link at, say,
 * the compartment that holds your bank login.
 */
public class RouterActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        String url = null;
        if (Intent.ACTION_VIEW.equals(in.getAction())) {
            Uri u = in.getData();
            String scheme = u == null ? null : u.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) url = u.toString();
        } else if (Intent.ACTION_SEND.equals(in.getAction())) {
            url = Ui.firstUrl(in.getStringExtra(Intent.EXTRA_TEXT));
        }
        if (url == null) {
            Ui.toast(this, "No web link to open");
            finish();
            return;
        }
        choose(url);
    }

    private void choose(final String url) {
        final ArrayList<Compartment> all = Store.load(this);

        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setPadding(Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 8));
        title.addView(Ui.text(this, "Open link in…", 20, Ui.TEXT, true));
        TextView link = Ui.text(this, url, 13, Ui.MUTED, false);
        link.setSingleLine(true);
        link.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        title.addView(link);

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return all.size() + 1;
            }

            @Override
            public Object getItem(int position) {
                return position;
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (position == 0) {
                    return Ui.pickRow(RouterActivity.this, Compartment.COLORS[Compartment.RED],
                            "New disposable", "Starts empty, erased when closed");
                }
                Compartment c = all.get(position - 1);
                return Ui.pickRow(RouterActivity.this, c.argb(), c.name, c.summary());
            }
        };

        new AlertDialog.Builder(this)
                .setCustomTitle(title)
                .setAdapter(adapter, (dialog, which) -> {
                    String id = which == 0 ? Store.newDisposable(this).id : all.get(which - 1).id;
                    Slots.open(this, id, url, 0, this::finish);
                })
                .setOnCancelListener(dialog -> finish())
                .show();
    }
}
