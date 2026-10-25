package app.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.List;

/**
 * The picture on a folder tile: up to four of its apps as small pictures in a 2 x 2 arrangement.
 * It draws copies of the apps' own pictures, so it never disturbs the tiles that show the originals.
 */
final class FolderIcon extends Drawable {

    private final List<Drawable> kids;
    private final List<Boolean> wide;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF cell = new RectF();

    /** kids and wide are the same length; wide marks a banner (fills its cell) versus a small icon. */
    FolderIcon(List<Drawable> kids, List<Boolean> wide) {
        this.kids = kids;
        this.wide = wide;
    }

    @Override
    public int getIntrinsicWidth() {
        return 320;
    }

    @Override
    public int getIntrinsicHeight() {
        return 180;
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        float pad = h * 0.13f;
        float gap = h * 0.07f;
        float cw = (w - 2 * pad - gap) / 2f;
        float ch = (h - 2 * pad - gap) / 2f;
        float radius = ch * 0.22f;
        int n = Math.min(4, kids.size());
        for (int i = 0; i < n; i++) {
            float left = b.left + pad + (i % 2) * (cw + gap);
            float top = b.top + pad + (i / 2) * (ch + gap);
            cell.set(left, top, left + cw, top + ch);
            paint.setColor(0x26FFFFFF);
            c.drawRoundRect(cell, radius, radius, paint);
            Drawable d = kids.get(i);
            if (d == null) {
                continue;
            }
            if (wide.get(i)) {
                c.save();
                clip.reset();
                clip.addRoundRect(cell, radius, radius, Path.Direction.CW);
                c.clipPath(clip);
                d.setBounds((int) left, (int) top, (int) (left + cw), (int) (top + ch));
                d.draw(c);
                c.restore();
            } else {
                float s = ch * 0.78f;
                float l = left + (cw - s) / 2f;
                float t = top + (ch - s) / 2f;
                d.setBounds((int) l, (int) t, (int) (l + s), (int) (t + s));
                d.draw(c);
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        // not used
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // not used
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
