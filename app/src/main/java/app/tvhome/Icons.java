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

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF R = new RectF();
    private static final Path PATH = new Path();
    private static final Path PATH2 = new Path();

    private Icons() {
    }

    /**
     * Draws icon {@code kind} inside a square of side {@code s} centred on (cx, cy). {@code knobBg} is
     * the colour behind the icon, used for the knobs of the sliders icon.
     */
    static void draw(Canvas cv, int kind, float cx, float cy, float s, int color, int knobBg) {
        final float ox = cx - s / 2;
        final float oy = cy - s / 2;
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
            default:
                break;
        }
    }
}
