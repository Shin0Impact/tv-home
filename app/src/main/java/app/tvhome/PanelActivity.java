package app.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

/**
 * The quick settings panel as a screen of its own, opened from the gear in TV Home (and by the
 * system's notification-panel request). Over a playing video the remote's button uses the overlay
 * in KeyService instead, because opening an activity makes most players pause.
 */
public class PanelActivity extends Activity {

    /** The panel while it is on screen; the remote's Settings button closes it instead of opening a second one. */
    static volatile PanelActivity open;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        open = this;
        float density = getResources().getDisplayMetrics().density;
        setContentView(new QuickPanel(this, this::finish, null).build());
        Window window = getWindow();
        window.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        window.setLayout((int) (360 * density + 0.5f), ViewGroup.LayoutParams.MATCH_PARENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.5f);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // The button was pressed again while the panel is open: toggle it closed.
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (open == this) {
            open = null;
        }
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP
                && (e.getKeyCode() == KeyEvent.KEYCODE_SETTINGS
                || e.getKeyCode() == KeyEvent.KEYCODE_NOTIFICATION)) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
