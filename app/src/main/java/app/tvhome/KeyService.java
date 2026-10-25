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
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            android.util.Log.i("TVHomeKey", "onKeyEvent key=" + key + " menuOpen=" + (overlay != null));
        }
        if (key == KeyEvent.KEYCODE_HOME && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            // Pressing Home twice quickly jumps back to the app you were just in.
            long now = android.os.SystemClock.uptimeMillis();
            if (lastApp != null && now - lastHomeAt < 700) {
                lastHomeAt = 0;
                if (overlay != null) {
                    closePanel();
                }
                openApp(lastApp);
                return true;
            }
            lastHomeAt = now;
            if (curApp != null) {
                lastApp = curApp;
            }
        }
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
                android.util.Log.i("TVHomeKey", "volume key: hiding menu");
                hideForVolume();
            }
            return false;
        }
        if (overlay != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            android.util.Log.i("TVHomeKey", "key while menu open: " + key);
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

    /** Hides the menu for three seconds so the TV's volume bar can be seen. */
    private void hideForVolume() {
        final View v = overlay;
        if (v == null) {
            return;
        }
        v.setAlpha(0f);
        v.setVisibility(View.INVISIBLE);
        v.removeCallbacks(showAgain);
        showAgainView = v;
        v.postDelayed(showAgain, 3000);
    }

    /** Fires whenever the volume changes, even when the remote's volume keys never reach this app. */
    private final android.content.BroadcastReceiver volumeReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            android.util.Log.i("TVHomeKey", "volume changed broadcast: hiding menu");
            hideForVolume();
        }
    };
    private boolean volumeWatching;

    private View overlay;
    private View showAgainView;
    private final Runnable showAgain = () -> {
        if (showAgainView != null) {
            showAgainView.setVisibility(View.VISIBLE);
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
            lastVol = -1;
            poller.removeCallbacks(pollVolume);
            poller.post(pollVolume);
            if (!volumeWatching) {
                try {
                    registerReceiver(volumeReceiver,
                            new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
                    volumeWatching = true;
                } catch (Exception e) {
                    android.util.Log.w("TVHomeKey", "volume watch failed: " + e);
                }
            }
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
        poller.removeCallbacks(pollVolume);
        if (volumeWatching) {
            try {
                unregisterReceiver(volumeReceiver);
            } catch (Exception ignored) {
                // not registered
            }
            volumeWatching = false;
        }
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

    /** The app on screen right now, and the one that was open when Home was last pressed. */
    private String curApp;
    private String lastApp;
    private long lastHomeAt;

    private void openApp(String pkg) {
        try {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) {
                i = getPackageManager().getLaunchIntentForPackage(pkg);
            }
            if (i != null) {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (Exception ignored) {
            // nothing to do
        }
    }

    // While the menu is open, check the volume a few times a second: if it changed, step aside.
    private final android.os.Handler poller = new android.os.Handler(android.os.Looper.getMainLooper());
    private int lastVol = -1;
    private boolean lastMute;
    private final Runnable pollVolume = new Runnable() {
        @Override
        public void run() {
            if (overlay == null) {
                return;
            }
            try {
                android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                int v = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                boolean m = am.isStreamMute(android.media.AudioManager.STREAM_MUSIC);
                if (lastVol >= 0 && (v != lastVol || m != lastMute)) {
                    android.util.Log.i("TVHomeKey", "volume poll: " + lastVol + " -> " + v + " mute=" + m);
                    hideForVolume();
                }
                lastVol = v;
                lastMute = m;
            } catch (Exception ignored) {
                // try again next time
            }
            poller.postDelayed(this, 250);
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null) {
            return;
        }
        String pkg = event.getPackageName().toString();
        if (overlay != null) {
            android.util.Log.i("TVHomeKey", "window event while menu open: " + pkg + " / " + event.getClassName());
            if (pkg.startsWith("com.android.systemui") || pkg.equals("android")) {
                hideForVolume();
            }
        }
        if (pkg.equals(getPackageName()) || pkg.equals("android")
                || pkg.startsWith("com.android.systemui") || pkg.contains("inputmethod")) {
            return;
        }
        // Only remember apps that can actually be launched from home.
        try {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) {
                i = getPackageManager().getLaunchIntentForPackage(pkg);
            }
            if (i != null) {
                curApp = pkg;
            }
        } catch (Exception ignored) {
            // keep the previous one
        }
    }

    @Override
    public void onInterrupt() {
        // Intentionally empty.
    }
}
