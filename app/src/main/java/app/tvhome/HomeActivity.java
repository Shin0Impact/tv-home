package app.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A deliberately small TV home screen: a dark background, a clock, and a grid of your apps.
 * No ads, no recommendations, no network access, no background work. The only animation is a
 * short scale on the focused tile.
 */
public class HomeActivity extends Activity {

    private static final int COLUMNS = 6;
    private static final int CARD_COLOR = 0xFF1C2338;
    private static final int PILL_COLOR = 0xFF232B42;
    private static final int TEXT_DIM = 0xFF9AA3B8;
    private static final int INK = 0xFF0A0E1A;

    private static class App {
        String pkg;
        String label;
        Intent launch;
        Drawable banner;
        Drawable icon;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private float density;
    private int tileW;
    private int tileH;
    private ScrollView scroll;
    private LinearLayout grid;
    private TextView unhideBtn;
    private View firstCell;
    private String lastKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int pad = dp(64);
        tileW = (screenW - 2 * pad - (COLUMNS - 1) * dp(16)) / COLUMNS;
        tileH = tileW * 9 / 16;
        setContentView(buildRoot(pad));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already on the home screen: jump back to the top.
        scroll.smoothScrollTo(0, 0);
        if (firstCell != null) {
            firstCell.requestFocus();
        }
    }

    @Override
    public void onBackPressed() {
        // This is the home screen, there is nowhere to go back to.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ---------------------------------------------------------------- layout

    private View buildRoot(int pad) {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0A0E1A, 0xFF141B30, 0xFF0A0E1A}));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, dp(36), pad, 0);
        column.setClipChildren(false);
        column.setClipToPadding(false);

        // Header: clock + date on the left, pills on the right.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockBox.addView(time);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(TEXT_DIM);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        clockBox.addView(date);

        header.addView(clockBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        unhideBtn = pill("Show hidden");
        unhideBtn.setVisibility(View.GONE);
        unhideBtn.setOnClickListener(v -> {
            prefs.edit().remove("hidden").apply();
            refresh(true);
        });
        header.addView(unhideBtn, pillParams());

        TextView settingsBtn = pill("Settings");
        settingsBtn.setOnClickListener(v -> safeStart(new Intent(Settings.ACTION_SETTINGS)));
        header.addView(settingsBtn, pillParams());

        // App grid.
        scroll = new ScrollView(this) {
            @Override
            public boolean arrowScroll(int direction) {
                // Only ever move focus between tiles. The stock behaviour nudges the view by a few
                // pixels when there is no tile left in that direction, which is the "hitch" at the
                // very top and bottom. Returning false lets focus move on to the header buttons.
                View current = findFocus();
                if (current == this) {
                    current = null;
                }
                View next = FocusFinder.getInstance().findNextFocus(this, current, direction);
                return next != null && next.requestFocus(direction);
            }
        };
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, dp(28), 0, dp(48));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        scroll.addView(grid, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        return lp;
    }

    private TextView pill(String text) {
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(Color.WHITE);
        t.setPadding(dp(22), dp(10), dp(22), dp(10));
        t.setFocusable(true);
        t.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(PILL_COLOR);
        t.setBackground(bg);
        t.setOnFocusChangeListener((v, focused) -> {
            bg.setColor(focused ? Color.WHITE : PILL_COLOR);
            t.setTextColor(focused ? INK : Color.WHITE);
        });
        return t;
    }

    private View makeCell(final App a) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setClipChildren(false);

        final float radius = dp(12);
        final FrameLayout card = new FrameLayout(this);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(CARD_COLOR);
        cardBg.setCornerRadius(radius);
        card.setBackground(cardBg);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });

        ImageView img = new ImageView(this);
        if (a.banner != null) {
            img.setImageDrawable(a.banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            img.setImageDrawable(a.icon);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        }
        cell.addView(card, new LinearLayout.LayoutParams(tileW, tileH));

        final TextView label = new TextView(this);
        label.setText(a.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(TEXT_DIM);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(2), dp(8), dp(2), 0);
        cell.addView(label, new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT));

        final GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(radius);
        ring.setStroke(dp(3), Color.WHITE);

        cell.setOnFocusChangeListener((v, focused) -> {
            card.setForeground(focused ? ring : null);
            label.setTextColor(focused ? Color.WHITE : TEXT_DIM);
            float s = focused ? 1.08f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(110).start();
        });
        cell.setOnClickListener(v -> safeStart(a.launch));
        cell.setOnLongClickListener(v -> {
            showMenu(a);
            return true;
        });
        return cell;
    }

    // ------------------------------------------------------------------ data

    /** Re-reads the app list on a background thread; only rebuilds the UI if something changed. */
    private void refresh(final boolean force) {
        if (io.isShutdown()) {
            return;
        }
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            StringBuilder key = new StringBuilder();
            for (App a : queryApps(pm, false)) {
                key.append(a.pkg).append(',');
            }
            key.append('|').append(prefs.getString("pinned", ""));
            final String k = key.toString();
            if (!force && k.equals(lastKey)) {
                return;
            }
            final List<App> full = queryApps(pm, true);
            lastKey = k;
            ui.post(() -> render(full));
        });
    }

    private List<App> queryApps(PackageManager pm, boolean withImages) {
        Set<String> hidden = prefs.getStringSet("hidden", new HashSet<String>());
        Map<String, App> map = new LinkedHashMap<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String category : categories) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || map.containsKey(pkg) || hidden.contains(pkg)) {
                    continue;
                }
                App a = new App();
                a.pkg = pkg;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.launch = new Intent(Intent.ACTION_MAIN)
                        .addCategory(category)
                        .setClassName(pkg, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if (withImages) {
                    a.banner = ri.activityInfo.loadBanner(pm);
                    if (a.banner == null) {
                        a.icon = ri.loadIcon(pm);
                    }
                }
                map.put(pkg, a);
            }
        }
        List<App> list = new ArrayList<>(map.values());
        final List<String> pinned = pinnedList();
        Collections.sort(list, (x, y) -> {
            int px = pinned.indexOf(x.pkg);
            int py = pinned.indexOf(y.pkg);
            if (px >= 0 && py >= 0) {
                return px - py;
            }
            if (px >= 0) {
                return -1;
            }
            if (py >= 0) {
                return 1;
            }
            return x.label.compareToIgnoreCase(y.label);
        });
        return list;
    }

    private List<String> pinnedList() {
        List<String> out = new ArrayList<>();
        for (String s : prefs.getString("pinned", "").split(",")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private void render(List<App> apps) {
        grid.removeAllViews();
        firstCell = null;
        LinearLayout row = null;
        int gap = dp(16);
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = dp(20);
                grid.addView(row, rowLp);
            }
            View cell = makeCell(apps.get(i));
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                    tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
            cellLp.rightMargin = gap;
            row.addView(cell, cellLp);
            if (firstCell == null) {
                firstCell = cell;
            }
        }
        Set<String> hidden = prefs.getStringSet("hidden", new HashSet<String>());
        unhideBtn.setText("Show " + hidden.size() + " hidden");
        unhideBtn.setVisibility(hidden.isEmpty() ? View.GONE : View.VISIBLE);
        if (firstCell != null) {
            firstCell.requestFocus();
        }
    }

    // --------------------------------------------------------------- actions

    private void showMenu(final App a) {
        final String[] items = {"Move to front", "Hide from home", "App info"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(a.label)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        List<String> pinned = pinnedList();
                        pinned.remove(a.pkg);
                        pinned.add(0, a.pkg);
                        prefs.edit().putString("pinned", TextUtils.join(",", pinned)).apply();
                        refresh(true);
                    } else if (which == 1) {
                        Set<String> hidden = new HashSet<>(
                                prefs.getStringSet("hidden", new HashSet<String>()));
                        hidden.add(a.pkg);
                        prefs.edit().putStringSet("hidden", hidden).apply();
                        refresh(true);
                    } else {
                        safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + a.pkg)));
                    }
                })
                .show();
    }

    private void safeStart(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return (int) (value * density + 0.5f);
    }
}
