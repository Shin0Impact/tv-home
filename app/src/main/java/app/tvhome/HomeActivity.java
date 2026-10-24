package app.tvhome;

import android.animation.ObjectAnimator;
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
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A deliberately small TV home screen in the spirit of tvOS:
 * a big carousel on top (Continue watching from Android TV's Watch Next list, or your recently used
 * apps), a row of large app tiles, and the rest of your apps below.
 * No ads, no accounts, no analytics. The only network use is downloading poster images that
 * installed apps themselves list in Watch Next, over HTTPS.
 */
public class HomeActivity extends Activity {

    private static final int TOP_ROW = 5;
    private static final int GRID_COLS = 7;
    private static final int MAX_HERO = 10;
    private static final int MAX_RECENT = 10;

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
        String pkg;
        String image;
        Intent intent;
    }

    /** One card in the big carousel. */
    private static class Hero {
        String title;
        String subtitle;
        Drawable banner;
        Drawable icon;
        String imageUri;
        Intent intent;
    }

    private static class VScroll extends ScrollView {
        VScroll(Context c) {
            super(c);
        }

        @Override
        public boolean arrowScroll(int direction) {
            return moveFocusOnly(this, direction);
        }
    }

    private static class HScroll extends HorizontalScrollView {
        HScroll(Context c) {
            super(c);
        }

        @Override
        public boolean arrowScroll(int direction) {
            return moveFocusOnly(this, direction);
        }
    }

    /**
     * Moves focus between tiles and nothing else. Scrolling is done by {@link #ensureVisible}, which
     * animates smoothly. Returning false at the edges lets focus move on to the header buttons.
     */
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
    private final Map<View, ObjectAnimator> scrollAnims = new HashMap<>();

    private SharedPreferences prefs;
    private float density;
    private int gap;
    private int topW;
    private int gridW;
    private int heroW;
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
        // About two and a quarter cards visible, so the next one peeks in and hints at the carousel.
        heroW = (int) ((avail - 2 * gap) / 2.25f);
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
        animateScroll(scroll, "scrollY", 0);
        if (firstCell != null) {
            firstCell.requestFocus();
        }
    }

    @Override
    public void onBackPressed() {
        // This is the home screen, there is nowhere to go back to.
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            int key = e.getKeyCode();
            if (key == KeyEvent.KEYCODE_SETTINGS) {
                showQuickSettings();
                return true;
            }
            if (key == KeyEvent.KEYCODE_PROFILE_SWITCH) {
                safeStart(new Intent(Settings.ACTION_SYNC_SETTINGS));
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
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
        column.setPadding(pad, dp(32), pad, 0);
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
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockBox.addView(time);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(TEXT_DIM);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
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

        // Content: carousel, app row, grid.
        scroll = new VScroll(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, dp(12), 0, dp(48));

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
        t.setPadding(0, dp(20), 0, dp(8));
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

    private ViewOutlineProvider rounded(final float radius) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };
    }

    /** A focusable tile: a rounded 16:9 card (banner or icon) with a one-line label underneath. */
    private View makeTile(int w, String text, Drawable banner, Drawable icon) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setClipChildren(false);
        cell.setRevealOnFocusHint(false);

        final float radius = dp(12);
        final FrameLayout card = new FrameLayout(this);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(CARD_COLOR);
        cardBg.setCornerRadius(radius);
        card.setBackground(cardBg);
        card.setClipToOutline(true);
        card.setOutlineProvider(rounded(radius));

        ImageView img = new ImageView(this);
        if (banner != null) {
            img.setImageDrawable(banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            img.setImageDrawable(icon);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = Math.min(dp(64), w / 3);
            card.addView(img, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
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
            if (focused) {
                ensureVisible(v);
            }
        });
        return cell;
    }

    private View makeAppTile(final App a, int w) {
        View cell = makeTile(w, a.label, a.banner, a.icon);
        cell.setOnClickListener(v -> launch(a));
        cell.setOnLongClickListener(v -> {
            showMenu(a);
            return true;
        });
        return cell;
    }

    /** A big carousel card with the title over a dark gradient at the bottom. */
    private View makeHeroCard(int w, Hero item) {
        final int h = w * 9 / 16;
        final FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        card.setRevealOnFocusHint(false);

        final float radius = dp(18);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF26304D, 0xFF161C30});
        bg.setCornerRadius(radius);
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setOutlineProvider(rounded(radius));

        ImageView img = new ImageView(this);
        if (item.banner != null) {
            img.setImageDrawable(item.banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (item.icon != null) {
            img.setImageDrawable(item.icon);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = dp(96);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s, Gravity.CENTER);
            lp.bottomMargin = dp(24);
            card.addView(img, lp);
        } else {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            loadImage(item.imageUri, img);
        }

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xDD000000}));
        card.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h * 50 / 100, Gravity.BOTTOM));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(22), 0, dp(22), dp(18));
        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title);
        if (item.subtitle != null && !item.subtitle.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(item.subtitle);
            sub.setTextColor(0xFFC3CADB);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(sub);
        }
        card.addView(text, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        final GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(radius);
        ring.setStroke(dp(3), Color.WHITE);
        card.setOnFocusChangeListener((v, focused) -> {
            card.setForeground(focused ? ring : null);
            float s = focused ? 1.04f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(110).start();
            if (focused) {
                ensureVisible(v);
            }
        });
        final Intent intent = item.intent;
        card.setOnClickListener(v -> safeStart(intent));
        return card;
    }

    // -------------------------------------------------------------- scrolling

    /**
     * Scrolls just enough to bring the focused tile (and a margin around it) into view, with a short
     * eased animation. The stock behaviour jumps instantly, which felt steppy.
     */
    private void ensureVisible(View cell) {
        if (cell.getHeight() == 0 || scroll == null || scroll.getChildCount() == 0) {
            return; // not laid out yet
        }
        View content = scroll.getChildAt(0);

        // Position of the tile inside the scrolling content, and the horizontal scroller it sits in.
        int top = 0;
        int left = 0;
        HScroll hs = null;
        boolean foundHs = false;
        View w = cell;
        while (true) {
            top += w.getTop();
            if (!foundHs) {
                left += w.getLeft();
            }
            ViewParent pp = w.getParent();
            if (!(pp instanceof View)) {
                break;
            }
            View p = (View) pp;
            if (p instanceof HScroll && !foundHs) {
                hs = (HScroll) p;
                foundHs = true;
            }
            if (p == scroll) {
                break;
            }
            w = p;
        }

        // Vertical.
        int topInScroll = top + scroll.getPaddingTop();
        int bottomInScroll = topInScroll + cell.getHeight();
        int viewTop = scroll.getScrollY();
        int viewH = scroll.getHeight();
        int maxY = Math.max(0, content.getHeight() + scroll.getPaddingTop()
                + scroll.getPaddingBottom() - viewH);
        int targetY = viewTop;
        if (topInScroll - dp(80) < viewTop) {
            targetY = topInScroll - dp(80);
        } else if (bottomInScroll + dp(48) > viewTop + viewH) {
            targetY = bottomInScroll + dp(48) - viewH;
        }
        targetY = Math.max(0, Math.min(targetY, maxY));
        if (targetY != viewTop) {
            animateScroll(scroll, "scrollY", targetY);
        }

        // Horizontal (inside a carousel).
        if (hs != null && hs.getChildCount() > 0) {
            int viewL = hs.getScrollX();
            int viewW = hs.getWidth();
            int maxX = Math.max(0, hs.getChildAt(0).getWidth() - viewW);
            int targetX = viewL;
            if (left - dp(8) < viewL) {
                targetX = left - dp(8);
            } else if (left + cell.getWidth() + dp(8) > viewL + viewW) {
                targetX = left + cell.getWidth() + dp(8) - viewW;
            }
            targetX = Math.max(0, Math.min(targetX, maxX));
            if (targetX != viewL) {
                animateScroll(hs, "scrollX", targetX);
            }
        }
    }

    private void animateScroll(View scroller, String property, int to) {
        ObjectAnimator old = scrollAnims.get(scroller);
        if (old != null) {
            old.cancel();
        }
        ObjectAnimator anim = ObjectAnimator.ofInt(scroller, property, to);
        anim.setDuration(190);
        anim.setInterpolator(new DecelerateInterpolator());
        scrollAnims.put(scroller, anim);
        anim.start();
    }

    // ------------------------------------------------------------------ data

    /** Re-reads apps and Watch Next on a background thread; only rebuilds the UI if something changed. */
    private void refresh(final boolean force) {
        if (io.isShutdown()) {
            return;
        }
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            final List<Program> programs = queryPrograms(pm);
            StringBuilder key = new StringBuilder();
            for (App a : queryApps(pm, false)) {
                key.append(a.pkg).append(',');
            }
            key.append('|').append(prefs.getString("pinned", ""));
            key.append('|').append(prefs.getString("recent", "")).append('|');
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
        final List<String> pinned = listPref("pinned");
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
     * (see README); without it this simply returns nothing.
     */
    private List<Program> queryPrograms(PackageManager pm) {
        List<Program> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    TvContract.WatchNextPrograms.CONTENT_URI,
                    new String[]{
                            TvContract.WatchNextPrograms.COLUMN_TITLE,
                            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME},
                    null, null,
                    TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC");
            if (c == null) {
                return out;
            }
            while (c.moveToNext() && out.size() < MAX_HERO) {
                String intentUri = c.getString(3);
                if (intentUri == null) {
                    continue;
                }
                Program p = new Program();
                p.title = c.getString(0) == null ? "" : c.getString(0);
                String thumb = c.getString(1);
                p.image = (thumb != null && !thumb.isEmpty()) ? thumb : c.getString(2);
                p.pkg = c.getString(4);
                try {
                    p.intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
                } catch (URISyntaxException e) {
                    continue;
                }
                out.add(p);
            }
        } catch (Exception e) {
            // Permission not granted or provider unavailable: no Watch Next data.
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out;
    }

    private List<String> listPref(String key) {
        List<String> out = new ArrayList<>();
        for (String s : prefs.getString(key, "").split(",")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private void saveListPref(String key, List<String> list) {
        prefs.edit().putString(key, TextUtils.join(",", list)).apply();
    }

    private void recordRecent(String pkg) {
        List<String> recent = listPref("recent");
        recent.remove(pkg);
        recent.add(0, pkg);
        while (recent.size() > MAX_RECENT) {
            recent.remove(recent.size() - 1);
        }
        saveListPref("recent", recent);
    }

    private void render(List<App> apps, List<Program> programs) {
        grid.removeAllViews();
        firstCell = null;
        scrollAnims.clear();

        Map<String, App> byPkg = new HashMap<>();
        for (App a : apps) {
            byPkg.put(a.pkg, a);
        }
        PackageManager pm = getPackageManager();

        // Big carousel: Watch Next if there is any, otherwise recently used apps.
        List<Hero> heroes = new ArrayList<>();
        String heroTitle = "Continue watching";
        for (Program p : programs) {
            Hero h = new Hero();
            h.title = p.title;
            h.imageUri = p.image;
            h.intent = p.intent;
            App src = p.pkg == null ? null : byPkg.get(p.pkg);
            h.subtitle = src != null ? src.label : "";
            heroes.add(h);
        }
        if (heroes.isEmpty()) {
            heroTitle = "Recently used";
            for (String pkg : listPref("recent")) {
                App a = byPkg.get(pkg);
                if (a == null || heroes.size() >= MAX_HERO) {
                    continue;
                }
                Hero h = new Hero();
                h.title = a.label;
                h.subtitle = "Open app";
                h.banner = a.banner;
                h.icon = a.icon;
                h.intent = a.launch;
                heroes.add(h);
            }
        }
        if (!heroes.isEmpty()) {
            grid.addView(sectionTitle(heroTitle));
            HScroll hs = new HScroll(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
            hs.setClipChildren(false);
            hs.setClipToPadding(false);
            hs.setPadding(0, dp(10), 0, dp(10));
            LinearLayout rowView = newRow();
            for (final Hero h : heroes) {
                View card = makeHeroCard(heroW, h);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(heroW, heroW * 9 / 16);
                lp.rightMargin = gap;
                rowView.addView(card, lp);
            }
            hs.addView(rowView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            grid.addView(hs, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // Row of large app tiles (pinned apps first).
        int topCount = Math.min(TOP_ROW, apps.size());
        if (topCount > 0) {
            grid.addView(sectionTitle("Apps"));
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
        scroll.scrollTo(0, 0);
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
            while (bounds.outWidth / (sample * 2) >= 960) {
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

    private void launch(App a) {
        recordRecent(a.pkg);
        safeStart(a.launch);
    }

    private void showMenu(final App a) {
        final boolean isPinned = listPref("pinned").contains(a.pkg);
        final String[] items = {isPinned ? "Unpin" : "Pin to front", "Hide from home", "App info"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(a.label)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        List<String> pinned = listPref("pinned");
                        pinned.remove(a.pkg);
                        if (!isPinned) {
                            pinned.add(0, a.pkg);
                        }
                        saveListPref("pinned", pinned);
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

    /** A simple side panel, opened by the remote's Settings button while TV Home is on screen. */
    private void showQuickSettings() {
        final String[] names = {
                "Network & Internet", "Display", "Sound", "Bluetooth", "Keyboard", "Accounts", "All settings"};
        final String[] actions = {
                Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS,
                Settings.ACTION_SOUND_SETTINGS, Settings.ACTION_BLUETOOTH_SETTINGS,
                Settings.ACTION_INPUT_METHOD_SETTINGS, Settings.ACTION_SYNC_SETTINGS,
                Settings.ACTION_SETTINGS};
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Quick settings")
                .setItems(names, (d, which) -> safeStart(new Intent(actions[which])))
                .create();
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            window.setLayout(dp(360), ViewGroup.LayoutParams.MATCH_PARENT);
        }
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
