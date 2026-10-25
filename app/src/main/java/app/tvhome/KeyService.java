package app.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
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
        if (key == KeyEvent.KEYCODE_HOME && overlay != null) {
            // Home closes the panel too, then Android takes you home as usual.
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                closePanel();
            }
            return false;
        }
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
            if (profile) {
                try {
                    startActivity(new Intent(Settings.ACTION_SYNC_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                    // nothing to do
                }
            } else {
                togglePanel();
            }
        }
        return true;
    }

    private View overlay;

    /**
     * Shows the quick settings as a window drawn over whatever is on screen. Unlike opening an
     * activity this does not pause the video underneath. If Android refuses the window, falls back
     * to the activity version.
     */
    private void togglePanel() {
        if (overlay != null) {
            closePanel();
            return;
        }
        try {
            final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            final float density = getResources().getDisplayMetrics().density;
            FrameLayout holder = new FrameLayout(this) {
                @Override
                public boolean dispatchKeyEvent(KeyEvent e) {
                    int code = e.getKeyCode();
                    if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_SETTINGS
                            || code == KeyEvent.KEYCODE_NOTIFICATION) {
                        if (e.getAction() == KeyEvent.ACTION_UP) {
                            closePanel();
                        }
                        return true;
                    }
                    return super.dispatchKeyEvent(e);
                }
            };
            final FrameLayout box = holder;
            final View panel = new QuickPanel(this, this::closePanel, () -> {
                // Settings open in this same window, over the app, instead of going to the home screen.
                box.removeAllViews();
                SettingsPanel sp = new SettingsPanel(this, this::closePanel, () -> {
                    // Change the home screen right away, even while it is behind another app.
                    HomeActivity home = HomeActivity.current;
                    if (home != null) {
                        home.applyThemeQuietly();
                    }
                }, null);
                box.addView(sp.build(), new FrameLayout.LayoutParams(
                        (int) (400 * density + 0.5f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
                box.requestFocus();
            }).build();
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    (int) (400 * density + 0.5f), ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.END);
            holder.addView(panel, plp);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.dimAmount = 0.5f;
            wm.addView(holder, lp);
            overlay = holder;
            holder.requestFocus();
        } catch (Exception e) {
            overlay = null;
            try {
                startActivity(new Intent(this, PanelActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }

    private void closePanel() {
        View v = overlay;
        overlay = null;
        if (v == null) {
            return;
        }
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(v);
        } catch (Exception ignored) {
            // already gone
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        closePanel();
        return super.onUnbind(intent);
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
