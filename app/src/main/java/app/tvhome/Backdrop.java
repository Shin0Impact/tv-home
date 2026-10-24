package app.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The home screen background: a deep gradient with two large soft glows on top, like a modern
 * wallpaper instead of a flat gradient. Colours can be changed while it is showing (the Hero art
 * theme fades them to match the picture in focus).
 */
final class Backdrop extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] base = {0xFF000000, 0xFF000000, 0xFF000000};
    private int blobA;
    private int blobB;

    void set(int[] base, int blobA, int blobB) {
        this.base = base;
        this.blobA = blobA;
        this.blobB = blobB;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        float w = getBounds().width();
        float h = getBounds().height();
        if (w <= 0 || h <= 0) {
            return;
        }
        paint.setShader(new LinearGradient(0, 0, w, h, base, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        float big = Math.max(w, h);
        glow(canvas, w * 0.88f, h * 0.05f, big * 0.62f, blobA);
        glow(canvas, w * 0.06f, h * 1.0f, big * 0.58f, blobB);
        paint.setShader(null);
    }

    private void glow(Canvas canvas, float cx, float cy, float r, int color) {
        if ((color >>> 24) == 0) {
            return;
        }
        paint.setShader(new RadialGradient(cx, cy, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getBounds().width(), getBounds().height(), paint);
    }

    @Override
    public void setAlpha(int alpha) {
        // always opaque
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // not used
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
