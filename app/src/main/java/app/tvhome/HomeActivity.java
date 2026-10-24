package app.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
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
 * A deliberately small TV home screen in the spirit of tvOS:
 * a row of large app tiles, a "Continue watching" shelf fed by the standard Android TV
 * Watch Next list (hidden when empty), and the rest of your apps below.
 * No ads, no accounts, no analytics. The only network use is downloading poster images that
 * installed apps themselves list in Watch Next, over HTTPS.
 */
public class HomeActivity extends Activity {

    private static final int TOP_ROW = 5;
    private static final int GRID_COLS = 7;
    private static final int SHELF_VISIBLE = 4;
    private static final int MAX_PROGRAMS = 10;

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

    private static class Program {
        String title;
        String image;
        Intent intent;
    }

    /** ScrollView that only moves focus between tiles and never nudges the bare padding. */
    private static class VScroll extends ScrollView {
        VScroll(Context c) {
            super(c);
        }

        @Override
        public boolean arrowScroll(int direction) {
            return moveFocusOnly(this, direction);
        }
    }

    /** Same idea for horizontal shelves. */
    private static class HScroll extends HorizontalScrollView {
        HScroll(Context c) {
            super(c);
        }

        @Override
        public boolean arrowScroll(int direction) {
            return moveFocusOnly(this, direction);
        }
    }

    private static boolean moveFocusOnly(ViewGroup self, int direction) {
        View current = self.findFocus();
        if (current == self) {
            current = null;
        }
        View next = FocusFinder.getInstance().findNextFocus(self, current, direction);
        return next != null && next.requestFocus(direction);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> imageCache = new LruCache<>(24);

    private SharedPreferences prefs;
    private float density;
    private int gap;
    private int topW;
    private int gridW;
    private int shelfW;
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
        gap = dp(16);
        int avail = screenW - 2 * pad;
        topW = (avail - (TOP_ROW - 1) * gap) / TOP_ROW;
        gridW = (avail - (GRID_COLS - 1) * gap) / GRID_COLS;
        shelfW = (avail - (SHELF_VISIBLE - 1) * gap) / SHELF_VISIBLE;
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
        net.shutdown();
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

        // Content: top row, shelf, grid.
        scroll = new VScroll(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, dp(20), 0, dp(48));

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

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, dp(24), 0, dp(10));
        return t;
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        return row;
    }

    private LinearLayout.LayoutParams cellParams(int w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = gap;
        return lp;
    }

    /**
     * One focusable tile: a rounded 16:9 card with either a banner, an icon, or a downloaded image,
     * and a one-line label underneath.
     */
    private View makeTile(int w, String text, Drawable banner, Drawable icon, String imageUri) {
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
        if (banner != null) {
            img.setImageDrawable(banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (icon != null) {
            img.setImageDrawable(icon);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = Math.min(dp(64), w / 3);
            card.addView(img, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        } else {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            loadImage(imageUri, img);
        }
        cell.addView(card, new LinearLayout.LayoutParams(w, w * 9 / 16));

        final TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(TEXT_DIM);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(2), dp(8), dp(2), 0);
        cell.addView(label, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        return cell;
    }

    private View makeAppTile(final App a, int w) {
        View cell = makeTile(w, a.label, a.banner, a.icon, null);
        cell.setOnClickListener(v -> safeStart(a.launch));
        cell.setOnLongClickListener(v -> {
            showMenu(a);
            return true;
        });
        return cell;
    }

    // ------------------------------------------------------------------ data

    /** Re-reads apps and Watch Next on a background thread; only rebuilds the UI if something changed. */
    private void refresh(final boolean force) {
        if (io.isShutdown()) {
            return;
        }
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            final List<Program> programs = queryPrograms();
            StringBuilder key = new StringBuilder();
            for (App a : queryApps(pm, false)) {
                key.append(a.pkg).append(',');
            }
            key.append('|').append(prefs.getString("pinned", "")).append('|');
            for (Program p : programs) {
                key.append(p.title).append(p.image).append(';');
            }
            final String k = key.toString();
            if (!force && k.equals(lastKey)) {
                return;
            }
            final List<App> full = queryApps(pm, true);
            lastKey = k;
            ui.post(() -> render(full, programs));
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

    /**
     * Reads the standard Android TV "Watch Next" list. Needs READ_EPG_DATA to be granted
     * (see README); without it this simply returns nothing and the shelf stays hidden.
     */
    private List<Program> queryPrograms() {
        List<Program> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    TvContract.WatchNextPrograms.CONTENT_URI,
                    new String[]{
                            TvContract.WatchNextPrograms.COLUMN_TITLE,
                            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                            TvContract.WatchNextPrograms.COLUMN_INTENT_URI},
                    null, null,
                    TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC");
            if (c == null) {
                return out;
            }
            while (c.moveToNext() && out.size() < MAX_PROGRAMS) {
                String intentUri = c.getString(3);
                if (intentUri == null) {
                    continue;
                }
                Program p = new Program();
                p.title = c.getString(0) == null ? "" : c.getString(0);
                String thumb = c.getString(1);
                p.image = (thumb != null && !thumb.isEmpty()) ? thumb : c.getString(2);
                try {
                    p.intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
                } catch (URISyntaxException e) {
                    continue;
                }
                out.add(p);
            }
        } catch (Exception e) {
            // Permission not granted or provider unavailable: no shelf.
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out;
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

    private void render(List<App> apps, List<Program> programs) {
        grid.removeAllViews();
        firstCell = null;

        // Row 1: the first few apps (pinned ones first), large.
        int topCount = Math.min(TOP_ROW, apps.size());
        LinearLayout top = newRow();
        for (int i = 0; i < topCount; i++) {
            View cell = makeAppTile(apps.get(i), topW);
            top.addView(cell, cellParams(topW));
            if (firstCell == null) {
                firstCell = cell;
            }
        }
        grid.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Shelf: Continue watching.
        if (!programs.isEmpty()) {
            grid.addView(sectionTitle("Continue watching"));
            HScroll hs = new HScroll(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
            hs.setClipChildren(false);
            hs.setClipToPadding(false);
            hs.setPadding(0, dp(10), 0, dp(10));
            LinearLayout shelf = newRow();
            for (final Program p : programs) {
                View cell = makeTile(shelfW, p.title, null, null, p.image);
                cell.setOnClickListener(v -> safeStart(p.intent));
                shelf.addView(cell, cellParams(shelfW));
            }
            hs.addView(shelf, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            grid.addView(hs, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // The rest of the apps, smaller.
        if (apps.size() > topCount) {
            grid.addView(sectionTitle("More apps"));
            LinearLayout row = null;
            for (int i = topCount; i < apps.size(); i++) {
                if ((i - topCount) % GRID_COLS == 0) {
                    row = newRow();
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowLp.bottomMargin = dp(18);
                    grid.addView(row, rowLp);
                }
                row.addView(makeAppTile(apps.get(i), gridW), cellParams(gridW));
            }
        }

        Set<String> hidden = prefs.getStringSet("hidden", new HashSet<String>());
        unhideBtn.setText("Show " + hidden.size() + " hidden");
        unhideBtn.setVisibility(hidden.isEmpty() ? View.GONE : View.VISIBLE);
        if (firstCell != null) {
            firstCell.requestFocus();
        }
    }

    // ---------------------------------------------------------------- images

    private void loadImage(final String uri, final ImageView target) {
        if (uri == null || uri.isEmpty()) {
            return;
        }
        Bitmap cached = imageCache.get(uri);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        if (net.isShutdown()) {
            return;
        }
        net.execute(() -> {
            final Bitmap b = fetch(uri);
            if (b == null) {
                return;
            }
            imageCache.put(uri, b);
            ui.post(() -> target.setImageBitmap(b));
        });
    }

    /** Only https and content:// are ever opened; plain http is refused. */
    private Bitmap fetch(String uriStr) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            Uri uri = Uri.parse(uriStr);
            String scheme = uri.getScheme();
            if ("https".equals(scheme)) {
                conn = (HttpURLConnection) new URL(uriStr).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);
                in = conn.getInputStream();
            } else if ("content".equals(scheme) || "android.resource".equals(scheme)) {
                in = getContentResolver().openInputStream(uri);
            } else {
                return null;
            }
            byte[] data = readAll(in, 3 * 1024 * 1024);
            if (data == null) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 640) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException ignored) {
                // nothing to do
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
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
