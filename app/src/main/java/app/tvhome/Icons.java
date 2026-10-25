package app.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** The small icons used in the top bar and the quick settings orbs, drawn in code (no image files). */
final class Icons {

    static final int WIFI = 0;
    static final int GEAR = 1;
    static final int SLIDERS = 2;
    static final int BLUETOOTH = 3;
    static final int SOUND = 4;
    static final int DISPLAY = 5;
    static final int KEYBOARD = 6;
    static final int MOON = 7;
    static final int PERSON = 8;
    static final int CLOCK = 9;
    static final int CAST = 10;
    static final int SEARCH = 11;
    static final int INPUT = 12;
    static final int MIC = 13;

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF R = new RectF();
    private static final Path PATH = new Path();
    private static final Path PATH2 = new Path();

    /** Google Material icon outlines (24x24 grid), parsed once. */
    private static final String[][] MATERIAL = {
        {"WIFI", "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z"},
        {"GEAR", "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"},
        {"SLIDERS", "M3,17v2h6v-2L3,17zM3,5v2h10L13,5L3,5zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2L3,11v2h4v2h2L9,9L7,9zM21,13v-2L11,11v2h10zM15,9h2L17,7h4L21,5h-4L17,3h-2v6z"},
        {"BLUETOOTH", "M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41L11,22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59L13,5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z"},
        {"SOUND", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"},
        {"DISPLAY", "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12z"},
        {"KEYBOARD", "M20,5L4,5c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2L22,7c0,-1.1 -0.9,-2 -2,-2zM11,8h2v2h-2L11,8zM11,11h2v2h-2v-2zM8,8h2v2L8,10L8,8zM8,11h2v2L8,13v-2zM7,13L5,13v-2h2v2zM7,10L5,10L5,8h2v2zM16,17L8,17v-2h8v2zM16,13h-2v-2h2v2zM16,10h-2L14,8h2v2zM19,13h-2v-2h2v2zM19,10h-2L17,8h2v2z"},
        {"MOON", "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9 9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36 -0.98,1.37 -2.58,2.26 -4.4,2.26 -2.98,0 -5.4,-2.42 -5.4,-5.4 0,-1.81 0.89,-3.42 2.26,-4.4C12.92,3.04 12.46,3 12,3z"},
        {"PERSON", "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"},
        {"CLOCK", "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z"},
        {"SEARCH", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"},
        {"INPUT", "M21,3.01H3c-1.1,0 -2,0.9 -2,2V9h2V4.99h18v14.03H3V15H1v4.01c0,1.1 0.9,1.99 2,1.99h18c1.1,0 2,-0.89 2,-1.99v-14c0,-1.1 -0.9,-1.99 -2,-1.99zM11,16l4,-4 -4,-4v3H1v2h10v3z"},
        {"MIC", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"}
    };

    private static final java.util.Map<Integer, Path> CACHE = new java.util.HashMap<>();

    private static Path material(int kind) {
        Path cached = CACHE.get(kind);
        if (cached != null) {
            return cached;
        }
        String name = null;
        switch (kind) {
            case WIFI: name = "WIFI"; break;
            case GEAR: name = "GEAR"; break;
            case SLIDERS: name = "SLIDERS"; break;
            case BLUETOOTH: name = "BLUETOOTH"; break;
            case SOUND: name = "SOUND"; break;
            case DISPLAY: name = "DISPLAY"; break;
            case KEYBOARD: name = "KEYBOARD"; break;
            case MOON: name = "MOON"; break;
            case PERSON: name = "PERSON"; break;
            case CLOCK: name = "CLOCK"; break;
            case SEARCH: name = "SEARCH"; break;
            case INPUT: name = "INPUT"; break;
            case MIC: name = "MIC"; break;
            default: return null;
        }
        for (String[] m : MATERIAL) {
            if (m[0].equals(name)) {
                Path p = parse(m[1]);
                CACHE.put(kind, p);
                return p;
            }
        }
        return null;
    }

    /** A small SVG path reader: M L H V C S Z, upper and lower case (all the icons need). */
    private static Path parse(String d) {
        Path p = new Path();
        int i = 0;
        int n = d.length();
        char cmd = 'M';
        float x = 0, y = 0, sx = 0, sy = 0, cx2 = 0, cy2 = 0;
        float[] a = new float[6];
        while (i < n) {
            char c = d.charAt(i);
            if (c == ' ' || c == ',') {
                i++;
                continue;
            }
            if (Character.isLetter(c)) {
                cmd = c;
                i++;
                if (cmd == 'z' || cmd == 'Z') {
                    p.close();
                    x = sx;
                    y = sy;
                }
                continue;
            }
            int need = "mMlLtT".indexOf(cmd) >= 0 ? 2 : "hHvV".indexOf(cmd) >= 0 ? 1
                    : "sSqQ".indexOf(cmd) >= 0 ? 4 : 6;
            for (int k = 0; k < need; k++) {
                while (i < n && (d.charAt(i) == ' ' || d.charAt(i) == ',')) {
                    i++;
                }
                int j = i;
                if (j < n && (d.charAt(j) == '-' || d.charAt(j) == '+')) {
                    j++;
                }
                boolean dot = false;
                while (j < n) {
                    char ch = d.charAt(j);
                    if (ch >= '0' && ch <= '9') {
                        j++;
                    } else if (ch == '.' && !dot) {
                        dot = true;
                        j++;
                    } else {
                        break;
                    }
                }
                a[k] = Float.parseFloat(d.substring(i, j));
                i = j;
            }
            boolean rel = Character.isLowerCase(cmd);
            switch (Character.toUpperCase(cmd)) {
                case 'M':
                    x = rel ? x + a[0] : a[0];
                    y = rel ? y + a[1] : a[1];
                    p.moveTo(x, y);
                    sx = x;
                    sy = y;
                    cmd = rel ? 'l' : 'L';
                    break;
                case 'L':
                    x = rel ? x + a[0] : a[0];
                    y = rel ? y + a[1] : a[1];
                    p.lineTo(x, y);
                    break;
                case 'H':
                    x = rel ? x + a[0] : a[0];
                    p.lineTo(x, y);
                    break;
                case 'V':
                    y = rel ? y + a[0] : a[0];
                    p.lineTo(x, y);
                    break;
                case 'C': {
                    float x1 = rel ? x + a[0] : a[0];
                    float y1 = rel ? y + a[1] : a[1];
                    cx2 = rel ? x + a[2] : a[2];
                    cy2 = rel ? y + a[3] : a[3];
                    float ex = rel ? x + a[4] : a[4];
                    float ey = rel ? y + a[5] : a[5];
                    p.cubicTo(x1, y1, cx2, cy2, ex, ey);
                    x = ex;
                    y = ey;
                    break;
                }
                case 'S': {
                    float x1 = 2 * x - cx2;
                    float y1 = 2 * y - cy2;
                    cx2 = rel ? x + a[0] : a[0];
                    cy2 = rel ? y + a[1] : a[1];
                    float ex = rel ? x + a[2] : a[2];
                    float ey = rel ? y + a[3] : a[3];
                    p.cubicTo(x1, y1, cx2, cy2, ex, ey);
                    x = ex;
                    y = ey;
                    break;
                }
                default:
                    break;
            }
        }
        return p;
    }

    private static final android.graphics.Matrix MX = new android.graphics.Matrix();
    private static final Path TMP = new Path();

    private Icons() {
    }

    /**
     * Draws icon {@code kind} inside a square of side {@code s} centred on (cx, cy). {@code knobBg} is
     * the colour behind the icon, used for the knobs of the sliders icon.
     */
    static void draw(Canvas cv, int kind, float cx, float cy, float s, int color, int knobBg) {
        final float ox = cx - s / 2;
        final float oy = cy - s / 2;
        Path mat = material(kind);
        if (mat != null) {
            // Google's Material outline icons, filled with the icon colour. Drawn smaller
            // than the box: glyphs fill most of the 24 grid.
            float scale = s * 0.7f / 24f;
            MX.setScale(scale, scale);
            MX.postTranslate(cx - 12 * scale, cy - 12 * scale);
            TMP.reset();
            mat.transform(MX, TMP);
            P.setStyle(Paint.Style.FILL);
            P.setColor(color);
            cv.drawPath(TMP, P);
            return;
        }
        P.setColor(color);
        P.setStrokeCap(Paint.Cap.ROUND);
        P.setStrokeJoin(Paint.Join.ROUND);
        switch (kind) {
            case WIFI: {
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(s * 0.07f);
                float by = cy + s * 0.2f;
                for (int i = 1; i <= 3; i++) {
                    float rad = s * 0.13f * i;
                    R.set(cx - rad, by - rad, cx + rad, by + rad);
                    cv.drawArc(R, 225, 90, false, P);
                }
                P.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx, by, s * 0.045f, P);
                break;
            }
            case GEAR: {
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(s * 0.09f);
                cv.drawCircle(cx, cy, s * 0.17f, P);
                P.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 8; i++) {
                    cv.save();
                    cv.rotate(i * 45f, cx, cy);
                    R.set(cx - s * 0.055f, cy - s * 0.31f, cx + s * 0.055f, cy - s * 0.2f);
                    cv.drawRoundRect(R, s * 0.03f, s * 0.03f, P);
                    cv.restore();
                }
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(s * 0.07f);
                cv.drawCircle(cx, cy, s * 0.235f, P);
                break;
            }
            case SLIDERS: {
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(s * 0.06f);
                float[] ys = {cy - s * 0.2f, cy, cy + s * 0.2f};
                float[] knobs = {cx + s * 0.1f, cx - s * 0.1f, cx + s * 0.04f};
                for (int i = 0; i < 3; i++) {
                    cv.drawLine(cx - s * 0.3f, ys[i], cx + s * 0.3f, ys[i], P);
                }
                for (int i = 0; i < 3; i++) {
                    P.setStyle(Paint.Style.FILL);
                    P.setColor(knobBg);
                    cv.drawCircle(knobs[i], ys[i], s * 0.085f, P);
                    P.setColor(color);
                    P.setStyle(Paint.Style.STROKE);
                    P.setStrokeWidth(s * 0.055f);
                    cv.drawCircle(knobs[i], ys[i], s * 0.075f, P);
                }
                break;
            }
            case BLUETOOTH: {
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(s * 0.07f);
                PATH.reset();
                PATH.moveTo(ox + s * 0.3f, oy + s * 0.32f);
                PATH.lineTo(ox + s * 0.7f, oy + s * 0.68f);
                PATH.lineTo(ox + s * 0.5f, oy + s * 0.85f);
                PATH.lineTo(ox + s * 0.5f, oy + s * 0.15f);
                PATH.lineTo(ox + s * 0.7f, oy + s * 0.32f);
                PATH.lineTo(ox + s * 0.3f, oy + s * 0.68f);
                cv.drawPath(PATH, P);
                break;
            }
            case SOUND: {
                P.setStyle(Paint.Style.FILL);
                PATH.reset();
                PATH.moveTo(ox + s * 0.2f, oy + s * 0.42f);
                PATH.lineTo(ox + s * 0.33f, oy + s * 0.42f);
                PATH.lineTo(ox + s * 0.48f, oy + s * 0.29f);
                PATH.lineTo(ox + s * 0.48f, oy + s * 0.71f);
                PATH.lineTo(ox + s * 0.33f, oy + s * 0.58f);
                PATH.lineTo(ox + s * 0.2f, oy + s * 0.58f);
                PATH.close();
                cv.drawPath(PATH, P);
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(s * 0.06f);
                for (int i = 1; i <= 2; i++) {
                    float rad = s * (0.1f + 0.12f * i);
                    R.set(ox + s * 0.48f - rad, cy - rad, ox + s * 0.48f + rad, cy + rad);
                    cv.drawArc(R, -45, 90, false, P);
                }
                break;
            }
            case DISPLAY: {
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(s * 0.06f);
                R.set(ox + s * 0.18f, oy + s * 0.27f, ox + s * 0.82f, oy + s * 0.65f);
                cv.drawRoundRect(R, s * 0.05f, s * 0.05f, P);
                cv.drawLine(cx, oy + s * 0.65f, cx, oy + s * 0.75f, P);
                cv.drawLine(cx - s * 0.12f, oy + s * 0.75f, cx + s * 0.12f, oy + s * 0.75f, P);
                break;
            }
            case KEYBOARD: {
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(s * 0.06f);
                R.set(ox + s * 0.14f, oy + s * 0.3f, ox + s * 0.86f, oy + s * 0.7f);
                cv.drawRoundRect(R, s * 0.06f, s * 0.06f, P);
                P.setStyle(Paint.Style.FILL);
                float[] xs = {0.3f, 0.43f, 0.57f, 0.7f};
                for (float x : xs) {
                    cv.drawCircle(ox + s * x, oy + s * 0.43f, s * 0.03f, P);
                }
                P.setStyle(Paint.Style.STROKE);
                cv.drawLine(ox + s * 0.33f, oy + s * 0.58f, ox + s * 0.67f, oy + s * 0.58f, P);
                break;
            }
            case MOON: {
                P.setStyle(Paint.Style.FILL);
                PATH.reset();
                PATH.addCircle(ox + s * 0.48f, oy + s * 0.5f, s * 0.26f, Path.Direction.CW);
                PATH2.reset();
                PATH2.addCircle(ox + s * 0.6f, oy + s * 0.42f, s * 0.22f, Path.Direction.CW);
                PATH.op(PATH2, Path.Op.DIFFERENCE);
                cv.drawPath(PATH, P);
                break;
            }
            case PERSON: {
                P.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx, oy + s * 0.36f, s * 0.12f, P);
                R.set(ox + s * 0.26f, oy + s * 0.56f, ox + s * 0.74f, oy + s * 1.0f);
                cv.drawArc(R, 180, 180, true, P);
                break;
            }
            case CLOCK: {
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(s * 0.06f);
                cv.drawCircle(cx, cy, s * 0.27f, P);
                cv.drawLine(cx, cy, cx, cy - s * 0.15f, P);
                cv.drawLine(cx, cy, cx + s * 0.11f, cy + s * 0.06f, P);
                break;
            }
            case CAST: {
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(s * 0.06f);
                PATH.reset();
                PATH.moveTo(ox + s * 0.5f, oy + s * 0.3f);
                PATH.lineTo(ox + s * 0.8f, oy + s * 0.3f);
                PATH.lineTo(ox + s * 0.8f, oy + s * 0.7f);
                PATH.lineTo(ox + s * 0.5f, oy + s * 0.7f);
                cv.drawPath(PATH, P);
                PATH.reset();
                PATH.moveTo(ox + s * 0.2f, oy + s * 0.3f);
                PATH.lineTo(ox + s * 0.5f, oy + s * 0.3f);
                cv.drawPath(PATH, P);
                float bx = ox + s * 0.2f;
                float by = oy + s * 0.7f;
                for (int n = 1; n <= 3; n++) {
                    float rad = s * 0.1f * n;
                    R.set(bx - rad, by - rad, bx + rad, by + rad);
                    cv.drawArc(R, -90, 90, false, P);
                }
                P.setStyle(Paint.Style.FILL);
                cv.drawCircle(bx, by, s * 0.03f, P);
                break;
            }
            case SEARCH: {
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(s * 0.07f);
                cv.drawCircle(cx - s * 0.05f, cy - s * 0.05f, s * 0.2f, P);
                cv.drawLine(cx + s * 0.1f, cy + s * 0.1f, cx + s * 0.28f, cy + s * 0.28f, P);
                break;
            }
            case INPUT: {
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(s * 0.06f);
                R.set(ox + s * 0.3f, oy + s * 0.22f, ox + s * 0.8f, oy + s * 0.78f);
                cv.drawRoundRect(R, s * 0.05f, s * 0.05f, P);
                cv.drawLine(ox + s * 0.14f, cy, ox + s * 0.56f, cy, P);
                cv.drawLine(ox + s * 0.44f, cy - s * 0.1f, ox + s * 0.56f, cy, P);
                cv.drawLine(ox + s * 0.44f, cy + s * 0.1f, ox + s * 0.56f, cy, P);
                break;
            }
            case MIC: {
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(s * 0.07f);
                R.set(cx - s * 0.1f, cy - s * 0.3f, cx + s * 0.1f, cy + s * 0.05f);
                cv.drawRoundRect(R, s * 0.1f, s * 0.1f, P);
                R.set(cx - s * 0.2f, cy - s * 0.15f, cx + s * 0.2f, cy + s * 0.2f);
                cv.drawArc(R, 0, 180, false, P);
                cv.drawLine(cx, cy + s * 0.2f, cx, cy + s * 0.3f, P);
                cv.drawLine(cx - s * 0.1f, cy + s * 0.3f, cx + s * 0.1f, cy + s * 0.3f, P);
                break;
            }
            default:
                break;
        }
    }
}
