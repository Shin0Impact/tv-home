package app.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
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
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.view.KeyEvent;
import android.view.Gravity;
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
    /** Opens the "type your city" box; null when drawn over another app (no keyboard box there). */
    private final Runnable askCity;
    private final float density;
    private final Root host;
    /** -1 = the list of settings; otherwise the index of the setting shown in the bottom bar. */
    private int bar = -1;
    private boolean inCats;
    /** The remote button whose app is being picked, or -1. */
    private int pickBtn = -1;
    private int listFocus = 0;
    private Theme th;

    SettingsPanel(Context ctx, Runnable close, Runnable onLook, Runnable onHome, Runnable askCity) {
        this.askCity = askCity;
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
        this.close = close;
        this.onLook = onLook;
        this.onHome = onHome;
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.host = new Root(ctx);
    }

    View build() {
        showList(0);
        return host;
    }

    /** True while a card on the right edge is showing, i.e. it could cover the TV's volume bar. */
    boolean coversRight() {
        return bar < 0;
    }

    /** Back: from the bottom bar to the list. Returns false when the list itself should close. */
    boolean back() {
        if (inCats) {
            int b = pickBtn;
            if (b >= 0) {
                showBar(5, b);
            } else {
                showBar(2, -1);
            }
            return true;
        }
        if (bar >= 0) {
            showList(bar);
            return true;
        }
        return false;
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

    private static final String[] TITLES = {"Theme", "Highlight", "Wallpaper", "Top bar", "Home screen", "Remote buttons"};

    /** Full-screen host: draws the dim itself and turns Up/Down in the bottom bar into "other setting". */
    private class Root extends FrameLayout {
        Root(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (bar >= 0) {
                int k = e.getKeyCode();
                if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                        int next = bar + (k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                        if (next >= 0 && next < TITLES.length) {
                            showBar(next, -1);
                        }
                    }
                    return true;
                }
            }
            return super.dispatchKeyEvent(e);
        }
    }

    private String value(int i) {
        switch (i) {
            case 0:
                return Theme.modeName(th.mode);
            case 1:
                return Theme.HL_NAMES[th.hlIndex];
            case 2: {
                String wall = prefs.getString("wall", "none");
                if (Theme.OLED.equals(th.mode)) {
                    return "Off";
                }
                if (th.aware) {
                    return "Content aware";
                }
                return Wallpaper.source(wall) == null ? "Off" : Wallpaper.name(wall);
            }
            case 3: {
                String city = Weather.city(prefs);
                return city.isEmpty() ? (Weather.fahrenheit(prefs) ? "\u00B0F" : "\u00B0C")
                        : city + " \u00B7 " + (Weather.fahrenheit(prefs) ? "\u00B0F" : "\u00B0C");
            }
            default:
                return "";
        }
    }

    // ------------------------------------------------------------ the list (side panel)

    private void showList(int focusIndex) {
        bar = -1;
        inCats = false;
        pickBtn = -1;
        th = Theme.load(prefs);
        host.removeAllViews();
        host.setBackgroundColor(0x88000000);

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(22), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(th.sheet);
        bg.setCornerRadius(dp(26));
        panel.setBackground(bg);

        TextView title = new TextView(ctx);
        title.setText("TV Home");
        title.setTextColor(th.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(6), 0, 0, dp(18));
        panel.addView(title);

        final List<View> rows = new ArrayList<>();
        for (int i = 0; i < TITLES.length; i++) {
            final int index = i;
            View row = listRow(TITLES[i], value(i));
            row.setOnClickListener(v -> {
                listFocus = index;
                showBar(index, -1);
            });
            rows.add(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
            lp.bottomMargin = dp(8);
            panel.addView(row, lp);
        }
        View space = new View(ctx);
        panel.addView(space, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView done = pill("Done");
        done.setGravity(Gravity.CENTER);
        done.setOnClickListener(v -> close.run());
        panel.addView(done, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Same size and place as the quick menu card.
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(332),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        plp.setMargins(0, dp(14), dp(14), dp(14));
        host.addView(panel, plp);
        final View target = rows.get(Math.max(0, Math.min(focusIndex, rows.size() - 1)));
        target.post(target::requestFocus);
    }

    private View listRow(String name, String value) {
        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), 0, dp(20), 0);
        row.setFocusable(true);
        row.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(th.pill);
        bg.setCornerRadius(dp(18));
        row.setBackground(bg);
        final TextView n = new TextView(ctx);
        n.setText(name);
        n.setTextColor(th.text);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        n.setSingleLine(true);
        row.addView(n, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView v = new TextView(ctx);
        v.setText(value.isEmpty() ? "\u203A" : value + "  \u203A");
        v.setTextColor(th.dim);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setGravity(Gravity.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vlp.leftMargin = dp(12);
        row.addView(v, vlp);
        row.setOnFocusChangeListener((x, focused) -> {
            bg.setColor(focused ? th.focusBg : th.pill);
            n.setTextColor(focused ? th.focusText : th.text);
            v.setTextColor(focused ? th.focusText : th.dim);
        });
        return row;
    }

    // ------------------------------------------------------------ the bottom bar

    /** Opens setting {@code index} as a bar along the bottom; focusPos picks the choice to focus (-1 = the current one). */
    private void showBar(final int index, final int focusPos) {
        bar = index;
        inCats = false;
        pickBtn = -1;
        th = Theme.load(prefs);
        host.removeAllViews();
        host.setBackgroundColor(0x33000000); // light dim, so a theme change stays visible behind the bar

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(40), dp(12), dp(40), dp(14));
        panel.setMinimumHeight(dp(96));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(th.sheet);
        bg.setCornerRadii(new float[]{dp(32), dp(32), dp(32), dp(32), 0, 0, 0, 0});
        panel.setBackground(bg);

        // Left: this setting's name, with the one above and below it, so Up / Down is obvious.
        LinearLayout nav = new LinearLayout(ctx);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.addView(navText(index > 0 ? TITLES[index - 1] : " ", false));
        nav.addView(navText(TITLES[index], true));
        nav.addView(navText(index < TITLES.length - 1 ? TITLES[index + 1] : " ", false));
        panel.addView(nav, new LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout choices = new LinearLayout(ctx);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        choices.setGravity(Gravity.CENTER_VERTICAL);
        choices.setPadding(dp(8), dp(6), dp(8), dp(6));
        choices.setMinimumHeight(dp(64));
        choices.setClipChildren(false);
        choices.setClipToPadding(false);
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.addView(choices);
        panel.addView(hs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final List<View> items = new ArrayList<>();
        int selected = buildChoices(index, choices, items);

        host.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        int want = focusPos >= 0 ? focusPos : selected;
        final View target = items.isEmpty() ? panel
                : items.get(Math.max(0, Math.min(want, items.size() - 1)));
        target.post(target::requestFocus);
    }

    private TextView navText(String text, boolean current) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setSingleLine(true);
        if (current) {
            t.setTextColor(th.text);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        } else {
            t.setTextColor(th.dim);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        }
        return t;
    }

    /** A choice in the bar: a rounded card with a label (and a small sample on top when given). */
    private View choice(String label, int sample, boolean selected, boolean light) {
        final LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(22), 0, dp(22), 0);
        card.setMinimumHeight(dp(52)); // every choice is the same height, so every bar is
        card.setFocusable(true);
        card.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(th.pill);
        bg.setCornerRadius(dp(26));
        // The current choice carries a frame.
        bg.setStroke(dp(2), selected ? Theme.mix(th.pill, th.text, 0.75f) : Color.TRANSPARENT);
        card.setBackground(bg);
        if (sample != 0) {
            View dot = new View(ctx);
            GradientDrawable cb = new GradientDrawable();
            cb.setShape(GradientDrawable.OVAL);
            cb.setColor(sample);
            cb.setStroke(dp(1), 0x66888E9C);
            dot.setBackground(cb);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(20), dp(20));
            clp.rightMargin = dp(10);
            card.addView(dot, clp);
        }
        final TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextColor(th.text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(260));
        card.addView(t);
        card.setOnFocusChangeListener((v, focused) -> {
            bg.setColor(focused ? th.focusBg : th.pill);
            t.setTextColor(focused ? th.focusText : th.text);
        });
        return card;
    }

    private void addChoice(LinearLayout row, List<View> items, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(14);
        row.addView(v, lp);
        items.add(v);
    }

    /** Fills the bar's row for setting {@code index}; returns the position of the current choice. */
    private int buildChoices(final int index, LinearLayout row, final List<View> items) {
        int selected = 0;
        switch (index) {
            case 0: {
                int[] samples = {0xFF000000, 0xFF171B26, 0xFFF1F3F8, 0xFF1B2A4A};
                for (int i = 0; i < Theme.MODES.length; i++) {
                    final String mode = Theme.MODES[i];
                    boolean on = mode.equals(th.mode);
                    if (on) {
                        selected = i;
                    }
                    View c = choice(Theme.MODE_NAMES[i], samples[i], on, false);
                    final int pos = i;
                    c.setOnClickListener(v -> {
                        prefs.edit().putString("theme", mode).apply();
                        looked();
                        showBar(index, pos);
                    });
                    addChoice(row, items, c);
                }
                break;
            }
            case 1: {
                for (int i = 0; i < Theme.HL_COLORS.length; i++) {
                    final int hi = i;
                    Swatch sw = new Swatch(ctx, i, i == th.hlIndex);
                    if (i == th.hlIndex) {
                        selected = i;
                    }
                    sw.setOnClickListener(v -> {
                        prefs.edit().putInt("hl", hi).apply();
                        looked();
                        showBar(index, hi);
                    });
                    sw.setOnFocusChangeListener((v, f) -> v.invalidate());
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(60), dp(60));
                    lp.rightMargin = dp(8);
                    row.addView(sw, lp);
                    items.add(sw);
                }
                break;
            }
            case 2: {
                final String wall = prefs.getString("wall", "none");
                if (Theme.OLED.equals(th.mode)) {
                    addChoice(row, items, choice("OLED stays black", 0, false, false));
                    break;
                }
                final boolean awareOk = Theme.DARK.equals(th.mode) || Theme.LIGHT.equals(th.mode);
                final List<String> values = new ArrayList<>();
                final List<String> labels = new ArrayList<>();
                values.add("none");
                labels.add("Off");
                if (awareOk) {
                    values.add("aware");
                    labels.add("Content aware");
                }
                for (int i = 0; i < Wallpaper.SRC_IDS.length; i++) {
                    values.add("d:" + Wallpaper.SRC_IDS[i]);
                    labels.add(Wallpaper.SRC_NAMES[i].replace(" picture of the day", ""));
                }
                for (int i = 0; i < values.size(); i++) {
                    final String value = values.get(i);
                    boolean on = "aware".equals(value) ? th.aware
                            : "none".equals(value) ? (!th.aware && Wallpaper.source(wall) == null)
                            : value.equals(wall) && !th.aware;
                    if (on) {
                        selected = i;
                    }
                    View c = choice(labels.get(i), 0, on, false);
                    final int pos = i;
                    c.setOnClickListener(v -> {
                        boolean aware = "aware".equals(value);
                        prefs.edit().putBoolean("aware", aware)
                                .putString("wall", aware ? "none" : value).apply();
                        looked();
                        showBar(index, pos);
                    });
                    addChoice(row, items, c);
                }
                final boolean catOn = Wallpaper.isCategory(wall) && !th.aware;
                if (catOn) {
                    selected = items.size();
                }
                View cats = choice("Categories" + (catOn ? " \u00B7 " + Wallpaper.name(wall) : "") + "  \u203A",
                        0, catOn, false);
                cats.setOnClickListener(v -> showCategories(0));
                addChoice(row, items, cats);
                break;
            }
            case 3: {
                final boolean fahr = Weather.fahrenheit(prefs);
                final String city = Weather.city(prefs);
                final View cityChoice = choice("City \u00B7 " + (city.isEmpty() ? "Not set" : city), 0, false, false);
                cityChoice.setOnClickListener(v -> editCity(row, items));
                addChoice(row, items, cityChoice);
                for (int i = 0; i < 2; i++) {
                    final boolean f = i == 1;
                    boolean on = fahr == f;
                    if (on) {
                        selected = items.size();
                    }
                    View c = choice(f ? "\u00B0F" : "\u00B0C", 0, on, false);
                    final int pos = items.size();
                    c.setOnClickListener(v -> {
                        prefs.edit().putString("units", f ? "f" : "c").apply();
                        looked();
                        showBar(index, pos);
                    });
                    addChoice(row, items, c);
                }
                break;
            }
            case 4: {
                final boolean storeOn = prefs.getBoolean("store", true);
                View store = choice("Play Store apps \u00B7 " + (storeOn ? "On" : "Off"), 0, false, false);
                store.setOnClickListener(v -> {
                    prefs.edit().putBoolean("store", !storeOn).apply();
                    homeChanged();
                });
                addChoice(row, items, store);
                final boolean demoOn = prefs.getBoolean("demo", false);
                View demo = choice("Demo cards \u00B7 " + (demoOn ? "On" : "Off"), 0, false, false);
                demo.setOnClickListener(v -> {
                    prefs.edit().putBoolean("demo", !demoOn).apply();
                    homeChanged();
                });
                addChoice(row, items, demo);
                final boolean nfOn = prefs.getBoolean("netflix", true);
                View nf = choice("Netflix cards · " + (nfOn ? "On" : "Off"), 0, false, false);
                nf.setOnClickListener(v -> {
                    prefs.edit().putBoolean("netflix", !nfOn).apply();
                    homeChanged();
                });
                addChoice(row, items, nf);
                View taste = choice("Reset Netflix taste (forget what you opened)", 0, false, false);
                taste.setOnClickListener(v -> {
                    NetflixTop.forgetTaste(prefs);
                    homeChanged();
                });
                addChoice(row, items, taste);
                View credit = choice("Netflix cards use the TMDB API but are not endorsed or certified by TMDB",
                        0, false, false);
                ((TextView) ((LinearLayout) credit).getChildAt(0)).setMaxWidth(dp(1000));
                addChoice(row, items, credit);
                View order = choice("Reset app order", 0, false, false);
                order.setOnClickListener(v -> {
                    prefs.edit().remove("order").apply();
                    homeChanged();
                });
                addChoice(row, items, order);
                break;
            }
            default: {
                for (int i = 0; i < RemoteKeys.NAMES.length; i++) {
                    final int btn = i;
                    View c = choice(RemoteKeys.NAMES[i] + " · "
                            + appName(RemoteKeys.target(prefs, i)), 0, false, false);
                    c.setOnClickListener(v -> showAppPick(btn, 0));
                    addChoice(row, items, c);
                }
                break;
            }
        }
        return selected;
    }

    private String appName(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "TV default";
        }
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "Not installed";
        }
    }

    /** Choose the app a remote button opens: a card the size and shape of the quick menu. */
    private void showAppPick(final int btn, int focusIndex) {
        bar = -1;
        inCats = true;
        pickBtn = btn;
        th = Theme.load(prefs);
        host.removeAllViews();
        host.setBackgroundColor(0x88000000);

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(22), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(th.sheet);
        bg.setCornerRadius(dp(26));
        panel.setBackground(bg);

        TextView title = new TextView(ctx);
        title.setText(RemoteKeys.NAMES[btn] + " button opens");
        title.setTextColor(th.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(6), 0, 0, dp(10));
        panel.addView(title);

        // Every app with a launcher entry, by name.
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        final java.util.Map<String, String> apps = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final java.util.Map<String, String> byName = new java.util.HashMap<>();
        String[] cats = {Intent_LEANBACK, Intent_LAUNCHER};
        for (String cat : cats) {
            android.content.Intent q = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(cat);
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || byName.containsValue(pkg)) {
                    continue;
                }
                String label = String.valueOf(ri.loadLabel(pm));
                while (apps.containsKey(label)) {
                    label = label + " ";
                }
                apps.put(label, pkg);
                byName.put(label, pkg);
            }
        }

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        final String current = RemoteKeys.target(prefs, btn);
        final List<View> rows = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<String> pkgs = new ArrayList<>();
        labels.add("TV default");
        pkgs.add("");
        for (java.util.Map.Entry<String, String> e : apps.entrySet()) {
            labels.add(e.getKey().trim());
            pkgs.add(e.getValue());
        }
        int focus = 0;
        for (int i = 0; i < labels.size(); i++) {
            final String pkg = pkgs.get(i);
            boolean on = pkg.equals(current);
            if (on) {
                focus = i;
            }
            View row = listRow(labels.get(i), on ? "Current" : "");
            if (on) {
                ((GradientDrawable) row.getBackground()).setStroke(dp(2), Theme.mix(th.pill, th.text, 0.75f));
            }
            row.setOnClickListener(v -> {
                prefs.edit().putString("rk_" + btn, pkg).apply();
                showBar(5, btn);
            });
            rows.add(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
            lp.bottomMargin = dp(6);
            list.addView(row, lp);
        }
        panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView back = pill("Back");
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> showBar(5, btn));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        panel.addView(back, blp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(332),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        plp.setMargins(0, dp(14), dp(14), dp(14));
        host.addView(panel, plp);
        final View target = rows.get(Math.max(0, Math.min(focus, rows.size() - 1)));
        target.post(target::requestFocus);
    }

    private static final String Intent_LEANBACK = "android.intent.category.LEANBACK_LAUNCHER";
    private static final String Intent_LAUNCHER = "android.intent.category.LAUNCHER";

    /** The wallpaper categories: a card the size and shape of the quick menu. */
    private void showCategories(int focusIndex) {
        bar = -1;
        inCats = true;
        th = Theme.load(prefs);
        host.removeAllViews();
        host.setBackgroundColor(0x88000000);

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(22), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(th.sheet);
        bg.setCornerRadius(dp(26));
        panel.setBackground(bg);

        TextView title = new TextView(ctx);
        title.setText("Wallpaper categories");
        title.setTextColor(th.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(6), 0, 0, dp(10));
        panel.addView(title);

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        final String wall = prefs.getString("wall", "none");
        final List<View> rows = new ArrayList<>();
        String group = "";
        for (int i = 0; i < Wallpaper.CATS.length; i++) {
            final String[] c = Wallpaper.CATS[i];
            if (!c[2].equals(group)) {
                group = c[2];
                list.addView(sectionLabel(group));
            }
            boolean on = ("d:wh-" + c[0]).equals(wall) && !th.aware;
            View row = listRow(c[1], on ? "Current" : "");
            if (on) {
                ((GradientDrawable) row.getBackground()).setStroke(dp(2), Theme.mix(th.pill, th.text, 0.75f));
            }
            final int pos = rows.size();
            row.setOnClickListener(v -> {
                prefs.edit().putBoolean("aware", false).putString("wall", "d:wh-" + c[0]).apply();
                looked();
                showCategories(pos);
            });
            rows.add(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
            lp.bottomMargin = dp(6);
            list.addView(row, lp);
        }
        panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView back = pill("Back");
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> showBar(2, -1));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        panel.addView(back, blp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(332),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        plp.setMargins(0, dp(14), dp(14), dp(14));
        host.addView(panel, plp);
        final View target = rows.get(Math.max(0, Math.min(focusIndex, rows.size() - 1)));
        target.post(target::requestFocus);
    }

    /** Turns the bar into a small text field for the weather city. */
    private void editCity(final LinearLayout row, final List<View> items) {
        row.removeAllViews();
        items.clear();
        final android.widget.EditText box = new android.widget.EditText(ctx);
        box.setSingleLine(true);
        box.setText(Weather.city(prefs));
        box.setHint("City, for example Haifa");
        box.setHintTextColor(th.dim);
        box.setTextColor(th.text);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        box.setPadding(dp(22), 0, dp(22), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(th.pill);
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(2), th.ring);
        box.setBackground(bg);
        row.addView(box, new LinearLayout.LayoutParams(dp(360), dp(56)));
        final Runnable save = () -> {
            prefs.edit().putString("city", box.getText().toString().trim())
                    .remove("wx_at").remove("wx_for").apply();
            looked();
            showBar(3, 0);
        };
        View ok = choice("Save", 0, false, false);
        ok.setOnClickListener(v -> save.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(14);
        row.addView(ok, lp);
        box.setOnEditorActionListener((v, action, ev) -> {
            save.run();
            return true;
        });
        box.requestFocus();
        box.post(() -> {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(box, 0);
            }
        });
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(th.dim);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(6), dp(12), 0, dp(6));
        return t;
    }

    private TextView pill(String text) {
        final TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(th.text);
        t.setPadding(dp(22), dp(12), dp(22), dp(12));
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
                // The current colour carries a frame.
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(th.dim);
                cv.drawCircle(cx, cy, r + dp(3), p);
            }
            if (isFocused()) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(3));
                p.setColor(th.text);
                cv.drawCircle(cx, cy, r + dp(3), p);
            }
        }
    }
}
