package app.tvhome;

import android.content.SharedPreferences;

/**
 * The colours of the home screen. Four looks (OLED, Dark, Light, Colour), a separate highlight
 * colour that works in all of them, and a "content aware" switch that tints the background with
 * the picture of the card in focus (Dark and Light only). Everything is chosen in TV Home settings
 * and kept in the "home" preferences: "theme", "hl" (highlight), "aware".
 */
final class Theme {

    static final String OLED = "oled";
    static final String DARK = "dark";
    static final String LIGHT = "light";
    static final String COLOUR = "colour";

    static final String[] MODES = {OLED, DARK, LIGHT, COLOUR};
    static final String[] MODE_NAMES = {"OLED", "Dark", "Light", "Colour"};

    /** Highlight choices. 0 follows the theme (white on dark, black on light), 1 is pure white. */
    static final String[] HL_NAMES = {"Default", "OLED", "Blue", "Purple", "Green", "Red", "Orange", "Teal", "Pink"};
    static final int[] HL_COLORS = {0, 0xFFFFFFFF, 0xFF4C8DFF, 0xFF8E5CFF, 0xFF2FBF71, 0xFFE5484D,
            0xFFFF8A3D, 0xFF19B8A6, 0xFFFF5FA2};

    String mode;
    boolean light;
    /** Background follows the picture in focus. */
    boolean aware;
    int hlIndex;
    /** The "wall" preference and whether a downloaded picture is the background. */
    String wall = "none";
    boolean picture;
    /** Three background colours (top-left, middle, bottom-right of the gradient). */
    int bgA;
    int bgB;
    int bgC;
    /** Rounded panel behind the first row of apps. */
    int panel;
    /** Side sheets (quick settings, TV Home settings). */
    int sheet;
    /** Tile background while its picture is loading. */
    int card;
    /** Buttons and rows. */
    int pill;
    int text;
    int dim;
    /** Focus ring colour (the highlight). */
    int ring;
    /** A focused button: background and its text. */
    int focusBg;
    int focusText;
    /** Switched-on buttons and the tile being moved. */
    int accent;
    /** Optional soft glows over the background (unused now, kept at zero). */
    int blobA;
    int blobB;

    private Theme() {
    }

    static Theme load(SharedPreferences p) {
        String mode = p.getString("theme", DARK);
        boolean aware;
        if ("gray".equals(mode)) {
            mode = DARK;
        }
        if ("art".equals(mode)) {
            mode = DARK;
            aware = !p.contains("aware") || p.getBoolean("aware", true);
        } else {
            aware = p.getBoolean("aware", false);
        }
        if (!OLED.equals(mode) && !LIGHT.equals(mode) && !COLOUR.equals(mode)) {
            mode = DARK;
        }
        int hlIndex = Math.max(0, Math.min(p.getInt("hl", 0), HL_COLORS.length - 1));

        Theme t = new Theme();
        t.mode = mode;
        t.light = LIGHT.equals(mode);
        t.wall = p.getString("wall", "none");
        t.picture = Wallpaper.source(t.wall) != null && !OLED.equals(mode);
        t.aware = aware && (DARK.equals(mode) || LIGHT.equals(mode)) && !t.picture;
        t.hlIndex = hlIndex;

        // Highlight.
        int blue = HL_COLORS[2];
        if (hlIndex == 0) {
            t.ring = t.light ? 0xFF14171F : 0xFFFFFFFF;
            t.focusBg = t.ring;
            t.focusText = t.light ? 0xFFFFFFFF : 0xFF0A0E1A;
            t.accent = blue;
        } else if (hlIndex == 1) {
            // Pure white on dark themes, pure black on Light, so it always shows.
            t.ring = t.light ? 0xFF000000 : 0xFFFFFFFF;
            t.focusBg = t.ring;
            t.focusText = t.light ? 0xFFFFFFFF : 0xFF000000;
            t.accent = blue;
        } else {
            t.ring = HL_COLORS[hlIndex];
            t.focusBg = t.ring;
            t.focusText = onColor(t.ring);
            t.accent = t.ring;
        }

        if (LIGHT.equals(mode)) {
            t.bgA = 0xFFE3E9F5;
            t.bgB = 0xFFF4F1EE;
            t.bgC = 0xFFE1E7F1;
            t.panel = 0x29141E3C; // a soft darker band, so white app banners stand out
            t.sheet = 0xF5F4F6FA;
            t.card = 0xFFD5DAE4;
            t.pill = 0xFFFFFFFF;
            t.text = 0xFF14171F;
            t.dim = 0xFF5B6475;
        } else if (OLED.equals(mode)) {
            t.bgA = 0xFF000000;
            t.bgB = 0xFF000000;
            t.bgC = 0xFF000000;
            t.panel = 0x1CFFFFFF;
            t.sheet = 0xFA000000;
            t.card = 0xFF101114;
            t.pill = 0xFF17181C;
            t.text = 0xFFFFFFFF;
            t.dim = 0xFF8B92A1;
        } else if (COLOUR.equals(mode)) {
            // A subtle tint of the highlight colour (blue when the highlight is Default or OLED).
            int c = hlIndex >= 2 ? HL_COLORS[hlIndex] : blue;
            t.bgA = mix(c, 0xFF000000, 0.78f);
            t.bgB = mix(c, 0xFF000000, 0.86f);
            t.bgC = mix(c, 0xFF000000, 0.92f);
            t.panel = 0x24FFFFFF;
            t.sheet = (mix(c, 0xFF000000, 0.88f) & 0x00FFFFFF) | 0xF2000000;
            t.card = mix(c, 0xFF000000, 0.75f);
            t.pill = (mix(c, 0xFF000000, 0.68f) & 0x00FFFFFF) | 0xCC000000;
            t.text = 0xFFFFFFFF;
            t.dim = 0xFFC9D0DE;
        } else {
            t.bgA = 0xFF0A0F20;
            t.bgB = 0xFF1B2242;
            t.bgC = 0xFF0B1224;
            t.panel = 0x26FFFFFF;
            t.sheet = 0xF2121829;
            t.card = 0xFF1C2338;
            t.pill = 0xFF232B42;
            t.text = 0xFFFFFFFF;
            t.dim = 0xFF9AA3B8;
        }
        // A built-in wallpaper replaces the plain background of the Dark and Light themes.
        int[] built = Wallpaper.builtin(Wallpaper.builtinIndex(t.wall), t.light);
        if (built != null && (DARK.equals(mode) || LIGHT.equals(mode))) {
            t.bgA = built[0];
            t.bgB = built[1];
            t.bgC = built[2];
            t.blobA = built[3];
            t.blobB = built[4];
        }
        // A highlight too close to the button colour would vanish (white on white): fall back to the text colour.
        if (Math.abs(lum(t.focusBg) - lum(t.pill)) < 40) {
            t.focusBg = t.text;
            t.ring = t.text;
            t.focusText = onColor(t.text);
        }
        return t;
    }

    private static int lum(int c) {
        return (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
    }

    /** Background colours for content-aware mode, from one colour taken from the picture. */
    static int[] artBackground(int color, boolean light) {
        if (light) {
            return new int[]{
                    mix(color, 0xFFFFFFFF, 0.78f),
                    mix(color, 0xFFFFFFFF, 0.88f),
                    mix(color, 0xFFF5F5F8, 0.92f)};
        }
        return new int[]{
                mix(color, 0xFF000000, 0.45f),
                mix(color, 0xFF000000, 0.68f),
                mix(color, 0xFF05070D, 0.85f)};
    }

    static int[] artBlobs(int color) {
        return new int[]{0, 0};
    }

    /** Black or white, whichever reads better on top of colour c. */
    static int onColor(int c) {
        int lum = (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
        return lum > 150 ? 0xFF111318 : 0xFFFFFFFF;
    }

    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Moves a colour toward another by t, keeping its own transparency. */
    static int tone(int c, int target, float t) {
        return (c & 0xFF000000) | (mix(c, target, t) & 0x00FFFFFF);
    }

    /** Kept for the buttons: flat colour (no gradient). */
    static int[] grad(int c) {
        return new int[]{c, c};
    }

    static String modeName(String mode) {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equals(mode)) {
                return MODE_NAMES[i];
            }
        }
        return MODE_NAMES[1];
    }
}
