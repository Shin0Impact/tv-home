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
        if ((key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN
                || key == KeyEvent.KEYCODE_VOLUME_MUTE) && overlay != null) {
            // The TV draws its volume bar below our window: step aside for a few seconds so it shows.
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                final View v = overlay;
                v.setAlpha(0f);
                v.removeCallbacks(showAgain);
                showAgainView = v;
                v.postDelayed(showAgain, 3000);
            }
            return false;
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
    private View showAgainView;
    private final Runnable showAgain = () -> {
        if (showAgainView != null) {
            showAgainView.setAlpha(1f);
        }
    };

    /**
     * Shows the quick settings as a window drawn over whatever is on screen. Unlike opening an
     * activity this does not pause the video underneath. If Android refuses the window, falls back
     * to the activity version.
     */
    private SettingsPanel settings;

    private void togglePanel() {
        if (overlay != null) {
            closePanel();
            return;
        }
        if (HomeActivity.visible != null) {
            // On the home screen nothing is playing: a normal screen is fine, and the volume bar shows above it.
            try {
                startActivity(new Intent(this, PanelActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
                // fall through to the overlay
            }
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
                            // In TV Home settings, Back first leaves the bottom bar.
                            if (code == KeyEvent.KEYCODE_BACK && settings != null && settings.back()) {
                                return true;
                            }
                            closePanel();
                        }
                        return true;
                    }
                    return super.dispatchKeyEvent(e);
                }
            };
            final FrameLayout box = holder;
            // The window is only as wide as the menu, so it never covers the volume bar or the media
            // controls in the middle of the screen. (It widens when TV Home settings open.)
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    (int) (360 * density + 0.5f), ViewGroup.LayoutParams.MATCH_PARENT,
                    android.provider.Settings.canDrawOverlays(this)
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.END;
            final View panel = new QuickPanel(this, this::closePanel, () -> {
                // Settings open in this same window, over the app, instead of going to the home screen.
                box.removeAllViews();
                box.setBackgroundColor(0); // the settings draw their own dim
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                try {
                    wm.updateViewLayout(box, lp);
                } catch (Exception ignored) {
                    // keep the narrow window
                }
                SettingsPanel sp = new SettingsPanel(this, this::closePanel, () -> {
                    // Change the home screen right away, even while it is behind another app.
                    HomeActivity home = HomeActivity.current;
                    if (home != null) {
                        home.applyThemeQuietly();
                    }
                }, null, null);
                settings = sp;
                box.addView(sp.build(), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                box.requestFocus();
            }).build();
            holder.addView(panel, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            try {
                wm.addView(holder, lp);
            } catch (Exception first) {
                // The lower layer was refused: use the accessibility layer (it sits above the volume bar).
                android.util.Log.w("TVHomeKey", "app overlay refused: " + first);
                lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
                wm.addView(holder, lp);
            }
            android.util.Log.i("TVHomeKey", "panel type " + lp.type + " (2038 = app overlay, 2032 = accessibility)"
                    + " canDrawOverlays=" + android.provider.Settings.canDrawOverlays(this));
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
        settings = null;
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
