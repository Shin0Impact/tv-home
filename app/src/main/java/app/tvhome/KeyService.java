package app.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Catches the remote's Settings / notifications button before the TV's own firmware does, and opens
 * TV Home's quick settings panel. Android only lets an accessibility service see key presses, so this
 * is the one place that can do it. It ignores every other key, never reads the screen (see
 * key_service.xml: no window content access) and does nothing with accessibility events.
 */
public class KeyService extends AccessibilityService {

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_HOME) {
            // Home pressed while TV Home is already showing: scroll to the top. From any other app
            // we do nothing here and Android takes you home as usual.
            HomeActivity home = HomeActivity.visible;
            if (home == null) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                home.goTop();
            }
            return true;
        }
        boolean panel = key == KeyEvent.KEYCODE_NOTIFICATION || key == KeyEvent.KEYCODE_SETTINGS;
        boolean profile = key == KeyEvent.KEYCODE_PROFILE_SWITCH;
        if (!panel && !profile) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            Intent open = (profile
                    ? new Intent(Settings.ACTION_SYNC_SETTINGS)
                    : new Intent(this, PanelActivity.class))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(open);
            } catch (Exception ignored) {
                // nothing to do
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Intentionally empty.
    }

    @Override
    public void onInterrupt() {
        // Intentionally empty.
    }
}
