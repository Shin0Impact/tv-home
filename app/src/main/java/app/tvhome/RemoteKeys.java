package app.tvhome;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/**
 * The remote's app buttons. The TV's firmware sends each one as its own key code (found with
 * remote-map.ps1); TV Home catches them and opens whichever app you chose in its settings.
 * A chosen package of "" means "leave it to the TV's own action".
 */
final class RemoteKeys {

    static final String[] NAMES = {"Netflix", "Prime Video", "Guard", "Web", "YouTube", "TCL Home",
            "TCL", "TV", "123", "Info"};
    static final int[] CODES = {4062, 4099, 4140, 4159, 4065, 4020, 4137, 4001, 4056, 165};
    // "" = leave the button to the TV's own action. Info is left alone by default because video apps use it.
    static final String[] DEFAULTS = {
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.tcl.guard",
            "com.tcl.browser",
            "com.google.android.youtube.tv",
            "",
            "",
            "",
            "",
            ""};

    /** Not a package: the button jumps to the previous app, like pressing Home twice. */
    static final String PREVIOUS = "@previous";

    private RemoteKeys() {
    }

    static int indexOf(int keyCode) {
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i] == keyCode) {
                return i;
            }
        }
        return -1;
    }

    static String target(SharedPreferences prefs, int index) {
        return prefs.getString("rk_" + index, DEFAULTS[index]);
    }

    /** The intent that opens {@code pkg}, or null when it is empty or not installed. */
    static Intent launchIntent(PackageManager pm, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        try {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) {
                i = pm.getLaunchIntentForPackage(pkg);
            }
            return i == null ? null : i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (Exception e) {
            return null;
        }
    }
}
