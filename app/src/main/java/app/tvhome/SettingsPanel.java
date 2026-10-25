package app.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TV Home's own settings: theme, content aware backgrounds, the highlight colour and the home
 * screen options. A plain view, so it can sit in a dialog on the home screen or be drawn over
 * whatever app is running (opened from the quick settings) without leaving that app.
 */
final class SettingsPanel {

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Runnable close;
    /** Called after a look change (theme, content aware, highlight); null when drawn over another app. */
    private final Runnable onLook;
    /** Called after a home screen option changed; null when drawn over another app. */
    private final Runnable onHome;
    private final float density;
    private final FrameLayout host;
    private Theme th;

    SettingsPanel(Context ctx, Runnable close, Runnable onLook, Runnable onHome) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
        this.close = close;
        this.onLook = onLook;
        this.onHome = onHome;
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.host = new FrameLayout(ctx);
    }

    View build() {
        fill(0);
        return host;
    }

    View firstFocus() {
        return host.findFocus();
    }

    private void looked() {
        if (onLook != null) {
            onLook.run();
        } else {
            prefs.edit().putBoolean("dirty", true).apply();
        }
    }

    private void homeChanged() {
        if (onHome != null) {
            onHome.run();
        } else {
            prefs.edit().putBoolean("dirty", true).apply();
        }
        close.run();
    }

    private int dp(int v) {
        return (int) (v * density + 0.5f);
    }

    private void fill(int focusIndex) {
        th = Theme.load(prefs);
        host.removeAllViews();

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(24), dp(24), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(th.sheet);
        bg.setCornerRadii(new float[]{dp(20), dp(20), 0, 0, 0, 0, dp(20), dp(20)});
        panel.setBackground(bg);

        TextView title = new TextView(ctx);
        title.setText("TV Home");
        title.setTextColor(th.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(4), 0, 0, dp(4));
        panel.addView(title);

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        final LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        final List<View> items = new ArrayList<>();

        // Theme.
        list.addView(sectionLabel("Theme"));
        for (int i = 0; i < Theme.MODES.length; i++) {
            final String mode = Theme.MODES[i];
            boolean on = mode.equals(th.mode);
            TextView row = pill(Theme.MODE_NAMES[i] + (on ? "   ✓" : ""));
            row.setOnClickListener(v -> {
                prefs.edit().putString("theme", mode).apply();
                looked();
                fill(items.indexOf(v));
            });
            items.add(row);
            addRow(list, row, 6);
        }
        final boolean awareAllowed = Theme.DARK.equals(th.mode) || Theme.LIGHT.equals(th.mode);
        final boolean awareOn = prefs.getBoolean("aware", false);
        TextView aware = pill("Content aware  ·  " + (awareAllowed
                ? (th.aware ? "On" : "Off") : "Dark and Light only"));
        aware.setOnClickListener(v -> {
            if (awareAllowed) {
                prefs.edit().putBoolean("aware", !awareOn).apply();
                looked();
                fill(items.indexOf(v));
            }
        });
        items.add(aware);
        addRow(list, aware, 6);

        // Highlight colour.
        list.addView(sectionLabel("Highlight"));
        final TextView hlName = new TextView(ctx);
        hlName.setText(Theme.HL_NAMES[th.hlIndex]);
        hlName.setTextColor(th.dim);
        hlName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hlName.setPadding(dp(4), dp(4), 0, 0);
        final int perRow = 5;
        LinearLayout swRow = null;
        for (int i = 0; i < Theme.HL_COLORS.length; i++) {
            if (i % perRow == 0) {
                swRow = new LinearLayout(ctx);
                swRow.setOrientation(LinearLayout.HORIZONTAL);
                list.addView(swRow, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final int index = i;
            Swatch sw = new Swatch(ctx, i, i == th.hlIndex);
            sw.setOnClickListener(v -> {
                prefs.edit().putInt("hl", index).apply();
                looked();
                fill(items.indexOf(v));
            });
            sw.setOnFocusChangeListener((v, f) -> {
                v.invalidate();
                if (f) {
                    hlName.setText(Theme.HL_NAMES[index]);
                }
            });
            items.add(sw);
            swRow.addView(sw, new LinearLayout.LayoutParams(0, dp(58), 1f));
        }
        list.addView(hlName);

        // Home screen.
        list.addView(sectionLabel("Home screen"));
        Set<String> hidden = prefs.getStringSet("hidden", new HashSet<String>());
        if (!hidden.isEmpty()) {
            TextView hiddenRow = pill("Show " + hidden.size() + " hidden apps");
            hiddenRow.setOnClickListener(v -> {
                prefs.edit().remove("hidden").apply();
                homeChanged();
            });
            items.add(hiddenRow);
            addRow(list, hiddenRow, 6);
        }
        final boolean storeOn = prefs.getBoolean("store", true);
        TextView storeRow = pill("Play Store top apps  ·  " + (storeOn ? "On" : "Off"));
        storeRow.setOnClickListener(v -> {
            prefs.edit().putBoolean("store", !storeOn).apply();
            homeChanged();
        });
        items.add(storeRow);
        addRow(list, storeRow, 6);

        final boolean demoOn = prefs.getBoolean("demo", false);
        TextView demoRow = pill("Demo cards  ·  " + (demoOn ? "On" : "Off"));
        demoRow.setOnClickListener(v -> {
            prefs.edit().putBoolean("demo", !demoOn).apply();
            homeChanged();
        });
        items.add(demoRow);
        addRow(list, demoRow, 6);

        TextView orderRow = pill("Reset app order");
        orderRow.setOnClickListener(v -> {
            prefs.edit().remove("order").apply();
            homeChanged();
        });
        items.add(orderRow);
        addRow(list, orderRow, 6);

        TextView done = pill("Done");
        done.setOnClickListener(v -> close.run());
        items.add(done);
        addRow(list, done, 14);

        panel.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        host.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final View target = items.get(Math.max(0, Math.min(focusIndex, items.size() - 1)));
        target.post(target::requestFocus);
    }

    private void addRow(LinearLayout list, TextView row, int gapDp) {
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(gapDp);
        list.addView(row, lp);
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(th.dim);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(16), 0, dp(8));
        return t;
    }

    private TextView pill(String text) {
        final TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(th.text);
        t.setPadding(dp(22), dp(10), dp(22), dp(10));
        t.setFocusable(true);
        t.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(th.pill);
        bg.setCornerRadius(dp(24));
        t.setBackground(bg);
        t.setOnFocusChangeListener((v, focused) -> {
            bg.setColor(focused ? th.focusBg : th.pill);
            t.setTextColor(focused ? th.focusText : th.text);
        });
        return t;
    }

    /** A round colour choice in the highlight picker. */
    private class Swatch extends View {
        private final int index;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean selected;

        Swatch(Context c, int index, boolean selected) {
            super(c);
            this.index = index;
            this.selected = selected;
            setFocusable(true);
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            float r = Math.min(w, h) / 2 - dp(5);
            float cx = w / 2;
            float cy = h / 2;
            int fill;
            p.setStyle(Paint.Style.FILL);
            if (index == 0) {
                // Default follows the theme, so it is half light and half dark.
                fill = 0xFFFFFFFF;
                p.setColor(0xFFFFFFFF);
                cv.drawCircle(cx, cy, r, p);
                p.setColor(0xFF1B2030);
                cv.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), 90, 180, true, p);
            } else if (index == 1) {
                fill = 0xFF000000;
                p.setColor(0xFF000000);
                cv.drawCircle(cx, cy, r, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(0xFFFFFFFF);
                cv.drawCircle(cx, cy, r - dp(1), p);
            } else {
                fill = Theme.HL_COLORS[index];
                p.setColor(fill);
                cv.drawCircle(cx, cy, r, p);
            }
            if (selected) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(3));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setColor(index == 0 ? 0xFF888E9C : Theme.onColor(fill));
                Path tick = new Path();
                tick.moveTo(cx - r * 0.35f, cy + r * 0.02f);
                tick.lineTo(cx - r * 0.08f, cy + r * 0.3f);
                tick.lineTo(cx + r * 0.4f, cy - r * 0.28f);
                cv.drawPath(tick, p);
            }
            if (isFocused()) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(3));
                p.setColor(th.text);
                cv.drawCircle(cx, cy, r + dp(4), p);
            }
        }
    }
}
