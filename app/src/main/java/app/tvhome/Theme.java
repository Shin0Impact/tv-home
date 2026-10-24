package app.tvhome;

import android.content.SharedPreferences;

/**
 * The colours of the home screen. Four looks: Dark, Light, Colour (a tint you pick) and Hero art
 * (the background follows the picture of the card in focus). Chosen in TV Home settings and kept in
 * the "home" preferences.
 */
final class Theme {

    static final String DARK = "dark";
    static final String LIGHT = "light";
    static final String COLOUR = "colour";
    static final String ART = "art";

    static final String[] MODES = {DARK, LIGHT, COLOUR, ART};
    static final String[] MODE_NAMES = {"Dark", "Light", "Colour", "Hero art"};

    static final int[] ACCENTS = {0xFF4C8DFF, 0xFF8E5CFF, 0xFF2FBF71, 0xFFE5484D, 0xFFFF8A3D};
    static final String[] ACCENT_NAMES = {"Blue", "Purple", "Green", "Red", "Orange"};

    final String mode;
    final boolean light;
    /** Three background colours (top-left, middle, bottom-right of the gradient). */
    final int bgA;
    final int bgB;
    final int bgC;
    /** Rounded panel behind the first row of apps. */
    final int panel;
    /** Side sheets (quick settings, TV Home settings). */
    final int sheet;
    /** Tile background while its picture is loading. */
    final int card;
    /** Buttons and rows. */
    final int pill;
    final int text;
    final int dim;
    /** Focus ring colour. */
    final int ring;
    /** A focused button: background and its text. */
    final int focusBg;
    final int focusText;
    /** The tile being moved. */
    final int accent;

    private Theme(String mode, boolean light, int bgA, int bgB, int bgC, int panel, int sheet,
                  int card, int pill, int text, int dim, int ring, int focusBg, int focusText,
                  int accent) {
        this.mode = mode;
        this.light = light;
        this.bgA = bgA;
        this.bgB = bgB;
        this.bgC = bgC;
        this.panel = panel;
        this.sheet = sheet;
        this.card = card;
        this.pill = pill;
        this.text = text;
        this.dim = dim;
        this.ring = ring;
        this.focusBg = focusBg;
        this.focusText = focusText;
        this.accent = accent;
    }

    static Theme load(SharedPreferences p) {
        String mode = p.getString("theme", DARK);
        int accentIdx = Math.max(0, Math.min(p.getInt("accent", 0), ACCENTS.length - 1));
        int accent = ACCENTS[accentIdx];
        if (LIGHT.equals(mode)) {
            return new Theme(LIGHT, true,
                    0xFFE8ECF3, 0xFFF3F0EC, 0xFFE6EAF2,
                    0xB3FFFFFF, 0xF5F4F6FA, 0xFFD5DAE4, 0xFFFFFFFF,
                    0xFF14171F, 0xFF5B6475, 0xFF14171F, 0xFF14171F, 0xFFFFFFFF, accent);
        }
        if (COLOUR.equals(mode)) {
            return new Theme(COLOUR, false,
                    mix(accent, 0xFF000000, 0.55f), mix(accent, 0xFF000000, 0.72f),
                    mix(accent, 0xFF000000, 0.86f),
                    0x2EFFFFFF, mix(accent, 0xFF000000, 0.80f) & 0x00FFFFFF | 0xF2000000,
                    mix(accent, 0xFF000000, 0.6f), mix(accent, 0xFF000000, 0.5f) & 0x00FFFFFF | 0x99000000,
                    0xFFFFFFFF, 0xFFD3D9E6, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF0A0E1A, 0xFFFFFFFF);
        }
        boolean art = ART.equals(mode);
        return new Theme(art ? ART : DARK, false,
                0xFF0A0E1A, 0xFF141B30, 0xFF0A0E1A,
                0x24FFFFFF, 0xF2121829, 0xFF1C2338, 0xFF232B42,
                0xFFFFFFFF, 0xFF9AA3B8, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF0A0E1A, accent);
    }

    /** Background colours for the Hero art theme, derived from one colour taken from the picture. */
    static int[] artBackground(int color) {
        return new int[]{
                mix(color, 0xFF000000, 0.45f),
                mix(color, 0xFF000000, 0.68f),
                mix(color, 0xFF05070D, 0.85f)};
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

    static String modeName(String mode) {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equals(mode)) {
                return MODE_NAMES[i];
            }
        }
        return MODE_NAMES[0];
    }

    static String nextMode(String mode) {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equals(mode)) {
                return MODES[(i + 1) % MODES.length];
            }
        }
        return MODES[1];
    }
}
