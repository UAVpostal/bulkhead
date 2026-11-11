package app.bulkhead;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // This runs in every process. Compartment processes must not touch WebView
        // here: their data directory is chosen later, when the compartment is known.
        if (getPackageName().equals(Application.getProcessName())) {
            Store.init(this);
            new Thread(() -> Slots.sweep(this)).start();
        }
    }
}
