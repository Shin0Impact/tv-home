package app.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.tv.TvContract;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A deliberately small TV home screen in the spirit of tvOS: a big hero card on top that follows the
 * app you highlight (and rotates through what that app has playing), then a row of large app tiles
 * and the rest of your apps. No ads, no accounts, no analytics. The only network use is downloading
 * artwork that an app itself publishes for something it is playing, over HTTPS.
 */
public class HomeActivity extends Activity {

    private static final int TOP_ROW = 5;
    private static final int GRID_COLS = 5;
    private static final int ROTATE_MS = 7000;
    private static final String STORE_PKG = "com.android.vending";
    private static final long STORE_REFRESH_MS = 12L * 3600 * 1000;
    private static final int HISTORY_PER_APP = 4;
    private static final int HISTORY_TOTAL = 40;


    private static class App {
        String pkg;
        String label;
        Intent launch;
        Drawable banner;
        Drawable icon;
    }

    /** One card of the hero: either something playing in an app, or the app itself. */
    private static class HeroItem {
        String appLabel;
        String title;
        String subtitle;
        Bitmap art;
        String artUri;
        Drawable banner;
        Drawable icon;
        PendingIntent pending;
        MediaController controller;
        Intent intent;
        /** True for the plain "open this app" card shown when an app has nothing to offer. */
        boolean base;
    }

    /** One card of the hero carousel. Five of them take turns at the positions -2 .. +2. */
    private static class Slot {
        FrameLayout root;
        ImageView art;
        ImageView side;
        TextView app;
        TextView title;
        TextView sub;
        /** Position relative to the centre card: 0 is the big one, -1 and 1 its neighbours. */
        int rel;
        /** What the pictures currently show, so a refresh does not reload them. */
        String key = "";
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

    /** Moves focus between tiles and nothing else; scrolling is animated by {@link #ensureVisible}. */
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
    private final LruCache<String, Bitmap> imageCache = new LruCache<>(16);
    private final Map<View, ObjectAnimator> scrollAnims = new HashMap<>();
    private final Map<String, View> tiles = new HashMap<>();
    private final List<App> shownApps = new ArrayList<>();
    private List<HeroItem> heroItems = new ArrayList<>();
    /** Watch Next and home-screen row items published by apps, keyed by package. */
    private volatile Map<String, List<HeroItem>> programsByPkg = new HashMap<>();
    /** Top apps read from Google Play's public pages, shown when the Play Store tile is highlighted. */
    private volatile List<JSONObject> storeApps = new ArrayList<>();
    private volatile boolean storeLoading;

    private SharedPreferences prefs;
    private float density;
    private int gap;
    private int topW;
    private int gridW;
    private int heroH;
    private int pad;
    private int cardW;
    private int cardH;
    private Theme th;
    private IconButton wifiBtn;
    private IconButton gearBtn;
    private ScrollView scroll;
    private LinearLayout grid;
    private View headerView;
    private TextView unhideBtn;
    private TextView weatherView;
    private ImageView wallView;
    private View wallScrim;
    private App pendingHeroApp;
    private final Runnable heroSwitch = () -> {
        if (pendingHeroApp != null) {
            applyHeroApp(pendingHeroApp);
        }
    };
    private View firstCell;
    private String lastKey = "";
    private Dialog openDialog;

    // Hero carousel.
    private FrameLayout heroRoot;
    private Slot[] slots;
    private View heroRing;
    private App focusedApp;
    /** The carousel currently shows only a plain app card (no real content). */
    private boolean heroBase;
    private int heroIdx;
    private String stageApp;
    private int stageIdx = -1;
    private int stageN;
    private Backdrop bgDrawable;
    private ValueAnimator tintAnim;
    private int tintColor;

    // Move mode: the app being repositioned, and the app to focus after the next rebuild.
    private String moveApp;
    private String lastFocusPkg;
    /** The home screen while it is showing; the accessibility service uses it for Home-on-Home. */
    static volatile HomeActivity visible;
    /** The running instance even while another app is in front (null once destroyed). */
    static volatile HomeActivity current;
    private boolean onScreen;
    private boolean openSettingsOnResume;
    private String focusAfter;

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            loadPrograms();
            recordHistoryAsync();
            if (focusedApp != null) {
                int before = heroItems.size();
                heroItems = buildHeroItems(focusedApp);
                heroBase = heroItems.size() == 1 && heroItems.get(0).base;
                if (heroItems.size() != before) {
                    heroIdx = 0;
                    showHero(false);
                } else if (heroItems.size() > 1 && !(heroRoot != null && heroRoot.hasFocus())) {
                    heroIdx = (heroIdx + 1) % heroItems.size();
                    showHero(true);
                }
            }
            ui.postDelayed(this, ROTATE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        th = Theme.load(prefs);
        // Opened from the quick settings' TV Home button while this screen was not running.
        openSettingsOnResume = getIntent() != null && getIntent().getBooleanExtra("settings", false);
        try {
            JSONArray cached = new JSONArray(prefs.getString("store_json", "[]"));
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < cached.length(); i++) {
                list.add(cached.getJSONObject(i));
            }
            storeApps = list;
        } catch (Exception ignored) {
            // no cached store apps
        }
        density = getResources().getDisplayMetrics().density;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        pad = dp(64);
        gap = dp(16);
        int avail = screenW - 2 * pad;
        topW = (avail - 2 * dp(20) - (TOP_ROW - 1) * gap) / TOP_ROW;
        gridW = topW; // the same five columns as the first row, so Up/Down stays in its column
        cardW = dp(420);
        cardH = cardW * 9 / 16;
        heroH = cardH;
        setContentView(buildRoot(pad));
    }

    /**
     * Switches on notification access and the remote-key service by itself, so a reinstall needs no
     * adb commands. Works only after this one-time grant:
     * adb shell pm grant app.tvhome android.permission.WRITE_SECURE_SETTINGS
     */
    private void ensureAccess() {
        try {
            if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            android.content.ContentResolver cr = getContentResolver();
            String listener = new ComponentName(this, MediaListener.class).flattenToString();
            String keys = new ComponentName(this, KeyService.class).flattenToString();
            String l = Settings.Secure.getString(cr, "enabled_notification_listeners");
            if (l == null || !l.contains(listener)) {
                Settings.Secure.putString(cr, "enabled_notification_listeners",
                        l == null || l.isEmpty() ? listener : l + ":" + listener);
            }
            String a = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (a == null || !a.contains(keys)) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        a == null || a.isEmpty() ? keys : a + ":" + keys);
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception ignored) {
            // no permission or a locked-down TV: the adb commands still work
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        onScreen = true;
        visible = this;
        ensureAccess();
        updateWeather();
        if (prefs.getBoolean("dirty", false)) {
            // Settings were changed from the quick settings while another app was open.
            prefs.edit().remove("dirty").apply();
            stageApp = null;
            focusedApp = null;
            heroItems = new ArrayList<>();
            heroBase = false;
            rebuildForTheme(false);
        }
        if (openSettingsOnResume) {
            openSettingsOnResume = false;
            if (getIntent() != null) {
                getIntent().removeExtra("settings");
            }
            ui.post(() -> showHomeSettings(0));
        }
        if (wifiBtn != null) {
            wifiBtn.online = isOnline();
            wifiBtn.invalidate();
        }
        if (gearBtn != null) {
            final IconButton g = gearBtn;
            io.execute(() -> {
                final boolean any = MediaListener.count(HomeActivity.this) > 0;
                ui.post(() -> {
                    g.badge = any;
                    g.invalidate();
                });
            });
        }
        refresh(false);
        loadPrograms();
        recordHistoryAsync();
        ui.removeCallbacks(rotate);
        ui.postDelayed(rotate, ROTATE_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        onScreen = false;
        if (visible == this) {
            visible = null;
        }
        ui.removeCallbacks(rotate);
        dismissDialog();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("settings", false)) {
            openSettingsOnResume = true; // from the quick settings panel's TV Home orb
        }
        // Home pressed while already on the home screen: close menus, leave move mode, go to the top.
        dismissDialog();
        if (moveApp != null) {
            moveApp = null;
            rerender();
        }
        if (!onScreen) {
            // Coming back from another app: keep the app that was highlighted last.
            return;
        }
        goTop();
    }

    /** True while Up/Down is held: scrolling then uses a short animation so it keeps up. */
    private boolean fastKeys;

    /**
     * Up/Down between apps keeps the column (five columns everywhere), and Down from the cards
     * goes back to the app you came up from, instead of whichever one is geometrically nearest.
     */
    private boolean moveFocusInColumns(int key) {
        if (moveApp != null || shownApps.isEmpty()) {
            return false;
        }
        if (heroRoot != null && heroRoot.hasFocus()) {
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                View back = lastFocusPkg != null ? tiles.get(lastFocusPkg) : null;
                if (back == null) {
                    back = firstCell;
                }
                if (back != null && back.requestFocus()) {
                    return true;
                }
            }
            return false;
        }
        View f = getCurrentFocus();
        int i = -1;
        for (int k = 0; k < shownApps.size(); k++) {
            if (tiles.get(shownApps.get(k).pkg) == f) {
                i = k;
                break;
            }
        }
        if (i < 0) {
            return false;
        }
        int n = shownApps.size();
        int target = key == KeyEvent.KEYCODE_DPAD_UP ? i - TOP_ROW : i + TOP_ROW;
        if (target < 0) {
            return false; // above the first row: the normal search finds the cards
        }
        if (target >= n) {
            int lastRowStart = ((n - 1) / TOP_ROW) * TOP_ROW;
            if (i >= lastRowStart) {
                return true; // already on the last row
            }
            target = n - 1; // the last row is short: take its last app
        }
        View v = tiles.get(shownApps.get(target).pkg);
        return v != null && v.requestFocus();
    }

    /** Home pressed while already on the home screen: back to the top of the page. */
    void goTop() {
        dismissDialog();
        if (moveApp != null) {
            moveApp = null;
            rerender();
        }
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
        int key = e.getKeyCode();
        if (heroRoot != null && heroRoot.hasFocus() && heroItems.size() > 1
                && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                int n = heroItems.size();
                heroIdx = (heroIdx + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : n - 1)) % n;
                showHero(true);
            }
            return true;
        }
        if (moveApp != null) {
            boolean ours = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                    || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                    || key == KeyEvent.KEYCODE_BACK;
            if (ours) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                            || key == KeyEvent.KEYCODE_BACK) {
                        focusAfter = moveApp;
                        moveApp = null;
                        rerender();
                    } else {
                        moveSelected(key);
                    }
                }
                return true;
            }
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                fastKeys = false;
            } else if (e.getAction() == KeyEvent.ACTION_DOWN) {
                fastKeys = e.getRepeatCount() > 0;
                if (moveFocusInColumns(key)) {
                    return true;
                }
            }
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
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
        if (current == this) {
            current = null;
        }
        io.shutdown();
        net.shutdown();
    }

    private void dismissDialog() {
        if (openDialog != null) {
            try {
                openDialog.dismiss();
            } catch (Exception ignored) {
                // already gone
            }
            openDialog = null;
        }
    }

    // ---------------------------------------------------------------- layout

    private View buildRoot(int pad) {
        FrameLayout root = new FrameLayout(this);
        bgDrawable = new Backdrop();
        bgDrawable.set(new int[]{th.bgA, th.bgB, th.bgC}, th.blobA, th.blobB);
        root.setBackground(bgDrawable);
        // The window's own black background would be one more full-screen layer under ours.
        getWindow().setBackgroundDrawable(null);
        tintColor = 0;

        // Optional picture-of-the-day wallpaper, with a theme-coloured veil so text stays readable.
        wallView = new ImageView(this);
        wallView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallView.setVisibility(View.GONE);
        root.addView(wallView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wallScrim = new View(this);
        FrameLayout.LayoutParams scrimLp;
        if (th.light) {
            // Light: no white film over the picture; only a soft fade at the top for the clock and buttons.
            wallScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xB3FFFFFF, 0x00FFFFFF}));
            scrimLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170));
        } else {
            wallScrim.setBackgroundColor(Theme.COLOUR.equals(th.mode)
                    ? ((th.bgB & 0x00FFFFFF) | 0xAA000000) : 0x8C000000);
            scrimLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        wallScrim.setVisibility(View.GONE);
        root.addView(wallScrim, scrimLp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);

        // The top bar scrolls with the content (it is the first child of the grid), so it never
        // shows through behind the tiles.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(24), 0, dp(4));
        headerView = header;

        weatherView = new TextView(this);
        weatherView.setTextColor(th.dim);
        weatherView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        weatherView.setSingleLine(true);
        weatherView.setIncludeFontPadding(false);
        String wx = Weather.cached(prefs);
        weatherView.setText(wxLabel(wx));
        weatherView.setVisibility(wx.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams wxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wxLp.rightMargin = dp(22);
        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.START);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        time.setTextColor(th.text);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        time.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        clockCol.addView(time);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEE, d MMM");
        date.setFormat24Hour("EEE, d MMM");
        date.setTextColor(th.dim);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        date.setIncludeFontPadding(false);
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.addView(date);
        weatherView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        wxLp.leftMargin = dp(8);
        wxLp.rightMargin = 0;
        dateRow.addView(weatherView, wxLp);
        clockCol.addView(dateRow);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.rightMargin = dp(24);
        header.addView(clockCol, timeLp);

        IconButton mic = new IconButton(this, Icons.MIC);
        mic.setOnClickListener(v -> showSearch(true));
        LinearLayout.LayoutParams micLp = iconParams();
        micLp.leftMargin = 0;
        micLp.rightMargin = dp(10);
        header.addView(mic, micLp);

        SearchBar searchBar = new SearchBar(this);
        searchBar.setOnClickListener(v -> showSearch(false));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        sbLp.rightMargin = dp(22);
        header.addView(searchBar, sbLp);

        unhideBtn = pill("Show hidden");
        unhideBtn.setVisibility(View.GONE);
        unhideBtn.setOnClickListener(v -> {
            prefs.edit().remove("hidden").apply();
            refresh(true);
        });
        header.addView(unhideBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));


        IconButton wifi = new IconButton(this, IconButton.WIFI);
        wifi.setOnClickListener(v -> safeStart(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        wifi.online = isOnline();
        wifiBtn = wifi;
        header.addView(wifi, iconParams());

        IconButton gear = new IconButton(this, IconButton.GEAR);
        gear.setOnClickListener(v -> showQuickSettings());
        gearBtn = gear;
        header.addView(gear, iconParams());

        IconButton sliders = new IconButton(this, IconButton.SLIDERS);
        sliders.setOnClickListener(v -> showHomeSettings(0));
        header.addView(sliders, iconParams());

        scroll = new VScroll(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, 0, 0, dp(48));

        // Side padding lives on the grid (not outside the scroller) so a focused, enlarged tile at
        // the left or right edge is not cut off.
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(pad, 0, pad, 0);
        scroll.addView(grid, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ui.post(() -> {
            loadWallpaper();
            updateWeather();
        });
        return root;
    }

    /** Google TV style search field: a long rounded bar with a magnifier and a hint. */
    private class SearchBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SearchBar(Context c) {
            super(c);
            setFocusable(true);
            setClickable(true);
            setOnFocusChangeListener((v, focused) -> {
                invalidate();
                if (focused) {
                    ensureVisible(v);
                }
            });
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            boolean f = isFocused();
            p.setStyle(Paint.Style.FILL);
            p.setColor(f ? th.focusBg : th.panel); // same material as the first-row panel, so it follows content aware
            cv.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
            int ink = f ? th.focusText : th.text;
            Icons.draw(cv, Icons.SEARCH, dp(26), h / 2, dp(36), ink, f ? th.focusBg : th.panel);
            p.setColor(f ? th.focusText : th.dim);
            p.setTextSize(dp(15));
            p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            Paint.FontMetrics fm = p.getFontMetrics();
            cv.drawText("Search apps, YouTube and the web", dp(48),
                    h / 2 - (fm.ascent + fm.descent) / 2, p);
        }
    }

    private LinearLayout.LayoutParams iconParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.leftMargin = dp(6);
        return lp;
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true; // cannot tell: show it as connected
        }
    }

    /** A round icon button drawn in code: Wi-Fi, settings gear, or sliders. */
    private class IconButton extends View {
        static final int WIFI = Icons.WIFI;
        static final int GEAR = Icons.GEAR;
        static final int SLIDERS = Icons.SLIDERS;

        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean online = true;
        /** A small dot on the icon: there are notifications waiting. */
        boolean badge;

        IconButton(Context c, int kind) {
            super(c);
            this.kind = kind;
            setFocusable(true);
            setClickable(true);
            setOnFocusChangeListener((v, focused) -> {
                invalidate();
                if (focused) {
                    ensureVisible(v);
                }
            });
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            boolean f = isFocused();
            if (f) {
                // Focus = a soft disc and a ring; the icon keeps its own colour so it never vanishes.
                p.setStyle(Paint.Style.FILL);
                p.setColor(th.light ? 0x22000000 : 0x33FFFFFF);
                cv.drawCircle(w / 2, h / 2, Math.min(w, h) / 2 - dp(2), p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(th.ring);
                cv.drawCircle(w / 2, h / 2, Math.min(w, h) / 2 - dp(2), p);
            }
            int color = th.light ? 0xFF111318 : 0xFFFFFFFF;
            if (kind == WIFI && !online) {
                color = th.dim;
            }
            Icons.draw(cv, kind, w / 2, h / 2, Math.min(w, h), color, th.bgA);
            if (badge) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFF4D4D);
                cv.drawCircle(w * 0.74f, h * 0.26f, Math.min(w, h) * 0.1f, p);
            }
        }
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
        t.setTextColor(th.text);
        t.setPadding(dp(22), dp(10), dp(22), dp(10));
        t.setFocusable(true);
        t.setClickable(true);
        final GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, Theme.grad(th.pill));
        bg.setCornerRadius(dp(24));
        t.setBackground(bg);
        t.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                bg.setColors(new int[]{th.focusBg, th.focusBg});
            } else {
                bg.setColors(Theme.grad(th.pill));
            }
            t.setTextColor(focused ? th.focusText : th.text);
        });
        return t;
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false); // a padded row would otherwise cut off the enlarged, focused tile
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
    private View makeTile(int w, String text, Drawable banner, Drawable icon, boolean accent) {
        return makeTile(w, text, banner, icon, accent, true);
    }

    private View makeTile(int w, String text, Drawable banner, Drawable icon, boolean accent,
                          boolean showLabel) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setClipChildren(false);

        final float radius = dp(16);
        final FrameLayout card = new FrameLayout(this);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(th.card);
        cardBg.setCornerRadius(radius);
        card.setBackground(cardBg);
        // Only a full-size banner needs its corners cut; a small centred icon never reaches them,
        // and every rounded clip costs the weak GPU time on every frame.
        if (banner != null) {
            card.setClipToOutline(true);
            card.setOutlineProvider(rounded(radius));
        }

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
        label.setTextColor(th.dim);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(2), dp(8), dp(2), 0);
        if (showLabel) {
            cell.addView(label, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        final GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(Math.max(0f, radius - dp(accent ? 4 : 3) / 2f));
        ring.setStroke(dp(accent ? 4 : 3), accent ? th.accent : th.ring);

        cell.setOnFocusChangeListener((v, focused) -> {
            card.setForeground(focused ? ring : null);
            label.setTextColor(focused ? th.text : th.dim);
            float s = focused ? 1.08f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(110).start();
            if (focused) {
                ensureVisible(v);
            }
        });
        return cell;
    }

    private View makeAppTile(final App a, int w, boolean showLabel) {
        final View cell = makeTile(w, a.label, a.banner, a.icon, a.pkg.equals(moveApp), showLabel);
        final View.OnFocusChangeListener base = cell.getOnFocusChangeListener();
        cell.setOnFocusChangeListener((v, focused) -> {
            base.onFocusChange(v, focused);
            if (focused) {
                lastFocusPkg = a.pkg;
                setHeroApp(a);
            }
        });
        cell.setOnClickListener(v -> launch(a));
        cell.setOnLongClickListener(v -> {
            showMenu(a);
            return true;
        });
        // A small red dot while the app has a notification showing.
        View first = ((ViewGroup) cell).getChildAt(0);
        if (first instanceof FrameLayout) {
            View dot = new View(this);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(0xFFFF4D4D);
            dotBg.setStroke(dp(2), 0xFFFFFFFF);
            dot.setBackground(dotBg);
            dot.setTag("badge");
            dot.setVisibility(View.GONE);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(16), dp(16),
                    Gravity.TOP | Gravity.END);
            dlp.topMargin = dp(8);
            dlp.rightMargin = dp(8);
            ((FrameLayout) first).addView(dot, dlp);
        }
        tiles.put(a.pkg, cell);
        return cell;
    }

    // ------------------------------------------------------------------ hero

    private Slot makeSlot() {
        Slot l = new Slot();
        l.root = new FrameLayout(this);
        final float radius = dp(20);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{th.card, Theme.mix(th.card, 0xFF000000, 0.35f)});
        bg.setCornerRadius(radius);
        l.root.setBackground(bg);
        l.root.setClipToOutline(true);
        l.root.setOutlineProvider(rounded(radius));

        l.art = new ImageView(this);
        l.art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        l.root.addView(l.art, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xDD000000, 0x66000000, 0x00000000}));
        l.root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cardH * 3 / 5, Gravity.BOTTOM));

        l.side = new ImageView(this);
        l.side.setClipToOutline(true);
        l.side.setOutlineProvider(rounded(dp(12)));
        FrameLayout.LayoutParams sideLp = new FrameLayout.LayoutParams(dp(200), dp(112),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sideLp.topMargin = dp(20);
        l.root.addView(l.side, sideLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.BOTTOM);
        text.setPadding(dp(18), dp(12), dp(18), dp(16));

        l.app = new TextView(this);
        l.app.setTextColor(0xFFC3CADB);
        l.app.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        l.app.setLetterSpacing(0.12f);
        l.app.setSingleLine(true);
        text.addView(l.app);

        l.title = new TextView(this);
        l.title.setTextColor(Color.WHITE);
        l.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        l.title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        l.title.setSingleLine(true);
        l.title.setEllipsize(TextUtils.TruncateAt.END);
        l.title.setPadding(0, dp(2), 0, 0);
        text.addView(l.title);

        l.sub = new TextView(this);
        l.sub.setTextColor(0xFFD3D9E6);
        l.sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        l.sub.setSingleLine(true);
        l.sub.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(l.sub);

        l.root.addView(text, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return l;
    }

    private View buildHero() {
        heroRoot = new FrameLayout(this);
        heroRoot.setFocusable(true);
        heroRoot.setClickable(true);
        heroRoot.setClipChildren(false);
        heroRoot.setClipToPadding(false);
        // Fading the whole hero would otherwise draw all five cards into a separate big picture
        // first, on every frame of the slide-in. The cards never overlap, so this is safe.
        heroRoot.setHasOverlappingRendering(false);

        slots = new Slot[5];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = makeSlot();
            slots[i].rel = i - 2;
            heroRoot.addView(slots[i].root, new FrameLayout.LayoutParams(cardW, cardH,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            applyRel(slots[i], false);
        }

        heroRing = new View(this);
        final GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(dp(20));
        ring.setStroke(dp(3), th.ring);
        heroRing.setBackground(ring);
        heroRing.setVisibility(View.INVISIBLE);
        heroRoot.addView(heroRing, new FrameLayout.LayoutParams(cardW, cardH,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        heroRoot.setOnFocusChangeListener((v, focused) -> {
            heroRing.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
            if (focused) {
                ensureVisible(v);
            }
        });
        heroRoot.setOnClickListener(v -> openHeroItem());
        return heroRoot;
    }

    /** Where a card sits for a given relative position: centre, left/right neighbour, or off-screen. */
    private void applyRel(Slot s, boolean animate) {
        final float side = 0.82f;
        float dx1 = cardW * (0.5f + 0.5f * side) + dp(24);
        float dx2 = dx1 + cardW * side + dp(24);
        int a = Math.abs(s.rel);
        float tx = a == 0 ? 0f : (a == 1 ? dx1 : dx2) * Integer.signum(s.rel);
        float sc = a == 0 ? 1f : side;
        int n = heroItems == null ? 0 : heroItems.size();
        boolean visible;
        if (a == 0) {
            visible = n > 0;
        } else if (a == 1) {
            visible = n >= 3 || (n == 2 && s.rel == 1);
        } else {
            visible = false;
        }
        // Side cards are dimmed with a dark tint, not by fading them: fading makes them look washed out on a light background.
        // Fully opaque when shown: any partial alpha on a card makes the GPU draw it offscreen first.
        float al = visible ? 1f : 0f;
        int dim = a == 0 ? Color.TRANSPARENT : 0x33000000;
        s.art.setColorFilter(dim);
        s.side.setColorFilter(dim);
        if (animate) {
            s.root.animate().translationX(tx).scaleX(sc).scaleY(sc).alpha(al)
                    .setDuration(340).setInterpolator(new DecelerateInterpolator()).start();
        } else {
            s.root.animate().cancel();
            s.root.setTranslationX(tx);
            s.root.setScaleX(sc);
            s.root.setScaleY(sc);
            s.root.setAlpha(al);
        }
    }

    private void bindSlot(Slot s, int itemIdx) {
        int n = heroItems.size();
        if (n == 0) {
            return;
        }
        HeroItem it = heroItems.get(((itemIdx % n) + n) % n);
        s.app.setText(it.appLabel == null ? "" : it.appLabel.toUpperCase());
        s.title.setText(it.title);
        s.sub.setText(it.subtitle);
        String key = it.art != null ? "bmp" + System.identityHashCode(it.art)
                : (it.artUri != null ? "uri" + it.artUri : "none" + System.identityHashCode(it.banner));
        if (key.equals(s.key)) {
            return;
        }
        s.key = key;
        if (it.art != null) {
            s.art.setTag(null);
            setArtBitmap(s.art, it.art);
            s.art.setVisibility(View.VISIBLE);
            s.side.setVisibility(View.GONE);
        } else if (it.artUri != null) {
            s.art.setImageDrawable(null);
            s.art.setVisibility(View.VISIBLE);
            s.side.setVisibility(View.GONE);
            loadImage(it.artUri, s.art);
        } else {
            s.art.setTag(null);
            s.art.setImageDrawable(null);
            s.art.setVisibility(View.GONE);
            if (it.banner != null) {
                s.side.setScaleType(ImageView.ScaleType.CENTER_CROP);
                s.side.setImageDrawable(it.banner);
            } else {
                s.side.setScaleType(ImageView.ScaleType.FIT_CENTER);
                s.side.setImageDrawable(it.icon);
            }
            s.side.setVisibility(View.VISIBLE);
        }
    }

    private void showHero(boolean animate) {
        if (heroRoot == null || slots == null || heroItems.isEmpty()) {
            return;
        }
        final int n = heroItems.size();
        heroIdx = Math.min(heroIdx, n - 1);
        final int idx = heroIdx;
        boolean sameApp = focusedApp != null && focusedApp.pkg.equals(stageApp);
        if (animate && sameApp && stageIdx >= 0 && stageN == n && n >= 2 && idx != stageIdx) {
            int delta = ((idx - stageIdx) % n + n) % n;
            int dir = (delta == 1 || (n == 2)) ? 1 : (delta == n - 1 ? -1 : 0);
            if (dir != 0) {
                for (Slot s : slots) {
                    s.rel -= dir;
                    if (Math.abs(s.rel) > 2) {
                        // This card slid out of range: it comes back in at the far side, unseen.
                        s.rel = dir > 0 ? 2 : -2;
                        bindSlot(s, idx + s.rel);
                        applyRel(s, false);
                    }
                }
                for (Slot s : slots) {
                    bindSlot(s, idx + s.rel);
                    applyRel(s, true);
                }
                stageIdx = idx;
                restack();
                updateTint();
                return;
            }
        }
        // A new app, a new list, or a jump of more than one card: set every position directly.
        for (int i = 0; i < slots.length; i++) {
            slots[i].rel = i - 2;
            bindSlot(slots[i], idx + slots[i].rel);
            applyRel(slots[i], false);
        }
        heroRoot.animate().cancel();
        if (animate && !sameApp) {
            // Slide the new app's cards in from the side the focus moved towards, like the timed rotation.
            int from = -1;
            int to = -1;
            for (int i = 0; i < shownApps.size(); i++) {
                String p = shownApps.get(i).pkg;
                if (stageApp != null && p.equals(stageApp)) {
                    from = i;
                }
                if (focusedApp != null && p.equals(focusedApp.pkg)) {
                    to = i;
                }
            }
            int dir = (from >= 0 && to >= 0 && to < from) ? -1 : 1;
            heroRoot.setTranslationX(dir * cardW * 0.55f);
            heroRoot.setAlpha(0f);
            heroRoot.animate().translationX(0f).alpha(1f).setDuration(320)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            heroRoot.setTranslationX(0f);
            heroRoot.setAlpha(1f);
        }
        stageApp = focusedApp == null ? null : focusedApp.pkg;
        stageIdx = idx;
        stageN = n;
        restack();
        updateTint();
    }

    /** Keeps the centre card on top of its neighbours, and the focus ring on top of that. */
    private void restack() {
        for (Slot s : slots) {
            if (s.rel == 0) {
                s.root.bringToFront();
            }
        }
        heroRing.bringToFront();
    }

    /** Hero art theme: lets the background take the colour of the card in focus. */
    private void updateTint() {
        if (!th.aware || heroItems.isEmpty() || bgDrawable == null) {
            return;
        }
        final HeroItem it = heroItems.get(Math.min(heroIdx, heroItems.size() - 1));
        if (it.art != null) {
            tintTo(averageColor(it.art));
        } else if (it.artUri != null) {
            Bitmap cached = imageCache.get(it.artUri);
            if (cached != null) {
                tintTo(averageColor(cached));
            }
            // otherwise the loader calls maybeTint once the picture has arrived
        } else {
            tintTo(0);
        }
    }

    /** A picture finished loading: if it belongs to the card in focus, the background follows it. */
    private void maybeTint(String uri, Bitmap b) {
        if (!th.aware || heroItems.isEmpty()) {
            return;
        }
        HeroItem it = heroItems.get(Math.min(heroIdx, heroItems.size() - 1));
        if (it.art == null && uri.equals(it.artUri)) {
            tintTo(averageColor(b));
        }
    }

    private int averageColor(Bitmap b) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(b, 8, 8, true);
            long r = 0;
            long g = 0;
            long bl = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int c = small.getPixel(x, y);
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    bl += c & 0xFF;
                }
            }
            return 0xFF000000 | ((int) (r / 64) << 16) | ((int) (g / 64) << 8) | (int) (bl / 64);
        } catch (Exception e) {
            return 0;
        }
    }

    private void tintTo(final int color) {
        if (bgDrawable == null || color == tintColor) {
            return;
        }
        final int[] from = tintSet(tintColor);
        final int[] to = tintSet(color);
        tintColor = color;
        if (tintAnim != null) {
            tintAnim.cancel();
        }
        final ArgbEvaluator ev = new ArgbEvaluator();
        tintAnim = ValueAnimator.ofFloat(0f, 1f);
        tintAnim.setDuration(600);
        tintAnim.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            int[] now = new int[5];
            for (int i = 0; i < 5; i++) {
                now[i] = (Integer) ev.evaluate(t, from[i], to[i]);
            }
            bgDrawable.set(new int[]{now[0], now[1], now[2]}, now[3], now[4]);
        });
        tintAnim.start();
    }

    /** Three background colours and two glows: the theme's own, or ones taken from a picture colour. */
    private int[] tintSet(int color) {
        if (color == 0) {
            return new int[]{th.bgA, th.bgB, th.bgC, th.blobA, th.blobB};
        }
        int[] bg = Theme.artBackground(color, th.light);
        int[] blobs = Theme.artBlobs(color);
        return new int[]{bg[0], bg[1], bg[2], blobs[0], blobs[1]};
    }

    private void resumeNow(MediaController mc) {
        try {
            PlaybackState s = mc.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                return;
            }
            mc.getTransportControls().play();
            mc.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
            mc.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
        } catch (Exception ignored) {
            // the app refused
        }
    }

    private void openHeroItem() {
        if (heroItems.isEmpty()) {
            return;
        }
        HeroItem it = heroItems.get(Math.min(heroIdx, heroItems.size() - 1));
        boolean opened = false;
        // Netflix's own "open" shortcut lands on the show's page; bring the app forward and
        // resume the paused session instead.
        boolean skipPending = it.controller != null && it.intent != null
                && it.controller.getPackageName() != null
                && it.controller.getPackageName().contains("netflix");
        if (it.pending != null && !skipPending) {
            try {
                it.pending.send();
                opened = true;
            } catch (Exception ignored) {
                // fall back to launching the app
            }
        }
        if (!opened && it.intent != null) {
            safeStart(it.intent);
        }
        if (it.controller != null) {
            // Something is paused or playing in that app: resume it rather than just opening the app.
            final MediaController mc = it.controller;
            resumeNow(mc);
            // Some apps ignore the request until they are on screen again, so ask once more.
            ui.postDelayed(() -> resumeNow(mc), 1200);
            ui.postDelayed(() -> resumeNow(mc), 2500);
        }
    }

    /** Called on every focus change; waits for focus to settle so fast scrolling doesn't flicker. */
    private void setHeroApp(App a) {
        pendingHeroApp = a;
        ui.removeCallbacks(heroSwitch);
        if (focusedApp != null && focusedApp.pkg.equals(a.pkg)) {
            return;
        }
        ui.postDelayed(heroSwitch, 120);
    }

    private void applyHeroApp(App a) {
        if (STORE_PKG.equals(a.pkg)) {
            maybeLoadStore();
        }
        if (focusedApp != null && focusedApp.pkg.equals(a.pkg) && !heroItems.isEmpty()) {
            focusedApp = a;
            return;
        }
        List<HeroItem> items = buildHeroItems(a);
        boolean plain = items.size() == 1 && items.get(0).base;
        if (plain && focusedApp != null && !heroItems.isEmpty() && !heroBase) {
            // This app has nothing to show: keep the cards of the last app that did.
            return;
        }
        focusedApp = a;
        heroItems = items;
        heroBase = plain;
        heroIdx = 0;
        showHero(heroRoot != null);
        // Restart the timer so the new app's first card is shown for a full interval.
        ui.removeCallbacks(rotate);
        ui.postDelayed(rotate, ROTATE_MS);
    }

    /** What the hero shows for an app: anything it is playing, then the app itself. */
    private List<HeroItem> buildHeroItems(App a) {
        List<HeroItem> out = new ArrayList<>();
        for (MediaController c : sessions()) {
            if (!a.pkg.equals(c.getPackageName())) {
                continue;
            }
            MediaMetadata md = c.getMetadata();
            String title = md == null ? null : firstNonEmpty(
                    md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                    md.getString(MediaMetadata.METADATA_KEY_TITLE));
            if (title == null) {
                // The app doesn't say what is on (Netflix, for example), but it may still report
                // that something is paused or playing and how far in: offer to resume that.
                PlaybackState st = c.getPlaybackState();
                if (st != null && (st.getState() == PlaybackState.STATE_PAUSED
                        || st.getState() == PlaybackState.STATE_PLAYING)) {
                    // Paused apps often blank their metadata (YouTube does): the newest remembered
                    // title for this app is almost certainly what is paused, so show that.
                    JSONObject last = null;
                    try {
                        JSONArray hist = new JSONArray(prefs.getString("history", "[]"));
                        for (int i = 0; i < hist.length() && last == null; i++) {
                            JSONObject o = hist.getJSONObject(i);
                            if (a.pkg.equals(o.optString("pkg")) && !o.optString("title").isEmpty()) {
                                last = o;
                            }
                        }
                    } catch (Exception ignored) {
                        // no history
                    }
                    if (last != null) {
                        HeroItem it = new HeroItem();
                        it.appLabel = a.label;
                        it.title = last.optString("title");
                        String sub = last.optString("sub");
                        it.subtitle = (st.getState() == PlaybackState.STATE_PLAYING ? "Now playing" : "Paused, press OK to resume")
                                + (sub.isEmpty() ? "" : "  ·  " + sub);
                        String art = last.optString("art");
                        it.artUri = art.isEmpty() ? null : art;
                        it.banner = a.banner;
                        it.icon = a.icon;
                        it.pending = c.getSessionActivity();
                        it.controller = c;
                        it.intent = a.launch;
                        out.add(it);
                        continue;
                    }
                }
                continue;
            }
            HeroItem it = new HeroItem();
            it.appLabel = a.label;
            it.title = title;
            PlaybackState ps = c.getPlaybackState();
            boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
            String detail = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                    md.getString(MediaMetadata.METADATA_KEY_ARTIST));
            it.subtitle = (playing ? "Now playing" : "Paused, press OK to resume")
                    + (detail != null ? "  ·  " + detail : "");
            it.art = firstBitmap(md.getBitmap(MediaMetadata.METADATA_KEY_ART),
                    md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
                    md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON));
            it.artUri = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_ART_URI),
                    md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                    md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI));
            // Prefer the picture saved when this title was playing: the live one is often blank or
            // missing while paused.
            String saved = rememberedArt(a.pkg, title);
            if (saved != null) {
                it.art = null;
                it.artUri = saved;
            }
            it.banner = a.banner;
            it.icon = a.icon;
            it.pending = c.getSessionActivity();
            it.controller = c;
            it.intent = a.launch;
            out.add(it);
        }
        // Titles remembered from earlier playback in this app (live ones above take priority).
        Set<String> seen = new HashSet<>();
        for (HeroItem h : out) {
            seen.add(h.title);
        }
        try {
            JSONArray hist = new JSONArray(prefs.getString("history", "[]"));
            for (int i = 0; i < hist.length(); i++) {
                JSONObject o = hist.getJSONObject(i);
                if (!a.pkg.equals(o.optString("pkg")) || seen.contains(o.optString("title"))
                        || !canOpen(o)) {
                    continue; // only remembered titles that open a specific video
                }
                HeroItem h = new HeroItem();
                h.appLabel = a.label;
                h.title = o.optString("title");
                String sub = o.optString("sub");
                h.subtitle = "Recently played" + (sub.isEmpty() ? "" : "  ·  " + sub);
                String art = o.optString("art");
                h.artUri = art.isEmpty() ? null : art;
                h.banner = a.banner;
                h.icon = a.icon;
                h.intent = intentFor(o, a);
                out.add(h);
                seen.add(h.title);
            }
        } catch (Exception ignored) {
            // unreadable history: skip it
        }
        List<HeroItem> published = programsByPkg.get(a.pkg);
        if (published != null) {
            for (HeroItem p : published) {
                p.appLabel = a.label;
                p.banner = a.banner;
                p.icon = a.icon;
                out.add(p);
            }
        }
        if (STORE_PKG.equals(a.pkg) && prefs.getBoolean("store", true)) {
            int rank = 0;
            for (JSONObject o : storeApps) {
                rank++;
                HeroItem h = new HeroItem();
                h.appLabel = a.label;
                h.title = o.optString("title");
                String desc = o.optString("desc");
                h.subtitle = "Top app #" + rank + (desc.isEmpty() ? "" : "  \u00B7  " + desc);
                String art = o.optString("art");
                h.artUri = art.isEmpty() ? null : art;
                h.banner = a.banner;
                h.icon = a.icon;
                h.intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + o.optString("pkg"))).setPackage(STORE_PKG);
                out.add(h);
            }
        }
        if (out.isEmpty() && prefs.getBoolean("demo", false)) {
            // For trying out the carousel: a few sample cards for any app.
            String[] names = {"Sample card one", "Sample card two", "Sample card three", "Sample card four"};
            for (String n : names) {
                HeroItem d = new HeroItem();
                d.appLabel = a.label;
                d.title = n;
                d.subtitle = "Demo, turn off in TV Home settings";
                d.banner = a.banner;
                d.icon = a.icon;
                d.intent = a.launch;
                out.add(d);
            }
        }
        HeroItem base = new HeroItem();
        base.appLabel = a.label;
        base.title = a.label;
        base.subtitle = "Press OK to open";
        base.banner = a.banner;
        base.icon = a.icon;
        base.intent = a.launch;
        base.base = true;
        if (out.isEmpty()) {
            out.add(base);
        }
        return out;
    }

    /** Fetches the Play Store's public top-apps page, then each app's page for its name and picture. */
    private void maybeLoadStore() {
        if (!prefs.getBoolean("store", true) || storeLoading || net.isShutdown()) {
            return;
        }
        if (!storeApps.isEmpty()
                && System.currentTimeMillis() - prefs.getLong("store_at", 0) < STORE_REFRESH_MS) {
            return;
        }
        storeLoading = true;
        net.execute(() -> {
            String status = "no page";
            try {
                java.util.Locale loc = java.util.Locale.getDefault();
                String country = loc.getCountry().isEmpty() ? "US" : loc.getCountry();
                String query = "hl=" + loc.getLanguage() + "&gl=" + country;
                String page = fetchText("https://play.google.com/store/apps/top?" + query);
                if (page != null) {
                    Set<String> ids = new LinkedHashSet<>();
                    Matcher m = Pattern.compile(
                            "/store/apps/details\\?id=([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)").matcher(page);
                    while (m.find() && ids.size() < 10) {
                        if (!STORE_PKG.equals(m.group(1))) {
                            ids.add(m.group(1));
                        }
                    }
                    JSONArray out = new JSONArray();
                    for (String id : ids) {
                        String html = fetchText("https://play.google.com/store/apps/details?id=" + id + "&" + query);
                        if (html == null) {
                            continue;
                        }
                        String title = meta(html, "og:title");
                        if (title == null) {
                            continue;
                        }
                        title = title.replaceAll("\\s+[-\u2013\u2014]\\s+.*Google Play$", "");
                        JSONObject o = new JSONObject();
                        o.put("pkg", id);
                        o.put("title", title);
                        String img = meta(html, "og:image");
                        o.put("art", img == null ? "" : img);
                        String desc = meta(html, "og:description");
                        if (desc != null && desc.length() > 70) {
                            desc = desc.substring(0, 67) + "...";
                        }
                        o.put("desc", desc == null ? "" : desc.replace('\n', ' '));
                        out.put(o);
                    }
                    status = ids.size() + " links, " + out.length() + " cards";
                    if (out.length() > 0) {
                        List<JSONObject> list = new ArrayList<>();
                        for (int i = 0; i < out.length(); i++) {
                            list.add(out.getJSONObject(i));
                        }
                        storeApps = list;
                        prefs.edit().putString("store_json", out.toString())
                                .putLong("store_at", System.currentTimeMillis()).apply();
                    }
                }
            } catch (Exception e) {
                status = "failed: " + e.getClass().getSimpleName();
            }
            prefs.edit().putString("storedebug", "Play Store top apps: " + status).apply();
            storeLoading = false;
            ui.post(() -> {
                // If the Play Store tile is the one highlighted, show the new cards now.
                if (STORE_PKG.equals(lastFocusPkg) && !storeApps.isEmpty()) {
                    for (App x : shownApps) {
                        if (STORE_PKG.equals(x.pkg)) {
                            focusedApp = null;
                            applyHeroApp(x);
                        }
                    }
                }
            });
        });
    }

    private String fetchText(String url) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            if (!url.startsWith("https://")) {
                return null;
            }
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            in = conn.getInputStream();
            byte[] data = readAll(in, 4 * 1024 * 1024);
            return data == null ? null : new String(data, "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException ignored) {
                // closing
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String meta(String html, String prop) {
        Matcher m = Pattern.compile("<meta[^>]*?(?:property|name)=\"" + prop
                + "\"[^>]*?content=\"([^\"]*)\"").matcher(html);
        if (!m.find()) {
            m = Pattern.compile("<meta[^>]*?content=\"([^\"]*)\"[^>]*?(?:property|name)=\"" + prop
                    + "\"").matcher(html);
            if (!m.find()) {
                return null;
            }
        }
        return m.group(1).replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">");
    }

    private String rememberedArt(String pkg, String title) {
        try {
            JSONArray hist = new JSONArray(prefs.getString("history", "[]"));
            for (int i = 0; i < hist.length(); i++) {
                JSONObject o = hist.getJSONObject(i);
                if (pkg.equals(o.optString("pkg")) && title.equals(o.optString("title"))
                        && !o.optString("art").isEmpty()) {
                    return o.optString("art");
                }
            }
        } catch (Exception ignored) {
            // nothing remembered
        }
        return null;
    }

    /**
     * Reads Watch Next and the home-screen rows apps publish (Android TV's TvContract) on a
     * background thread. Anything the system does not let us see simply yields nothing.
     */
    private void loadPrograms() {
        if (io.isShutdown()) {
            return;
        }
        io.execute(() -> {
            Map<String, List<HeroItem>> map = new HashMap<>();
            StringBuilder dbg = new StringBuilder();
            dbg.append("Watch Next: ").append(readPrograms(map, TvContract.WatchNextPrograms.CONTENT_URI,
                    "Continue watching"));
            dbg.append("\nHome rows: ").append(readPrograms(map, TvContract.PreviewPrograms.CONTENT_URI,
                    "Recommended"));
            int total = 0;
            for (List<HeroItem> l : map.values()) {
                total += l.size();
            }
            dbg.append("\nCards from apps: ").append(total);
            prefs.edit().putString("epgdebug", dbg.toString()).apply();
            programsByPkg = map;
        });
    }

    private void recordHistoryAsync() {
        if (!io.isShutdown()) {
            io.execute(this::recordHistory);
        }
    }

    /** Also records whatever is active right now (the background listener normally has it already). */
    private void recordHistory() {
        for (MediaController c : sessions()) {
            History.record(this, c);
        }
    }

    /** Returns a short status for the diagnostics line in the quick settings panel. */
    private String readPrograms(Map<String, List<HeroItem>> map, Uri uri, String kind) {
        Cursor c = null;
        int rows = 0;
        try {
            c = getContentResolver().query(uri,
                    new String[]{
                            TvContract.PreviewPrograms.COLUMN_TITLE,
                            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
                            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                            TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
                            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION},
                    null, null, null);
            if (c == null) {
                return "no access";
            }
            while (c.moveToNext()) {
                rows++;
                String pkg = c.getString(4);
                String intentUri = c.getString(3);
                String title = c.getString(0);
                if (pkg == null || intentUri == null || title == null) {
                    continue;
                }
                List<HeroItem> list = map.get(pkg);
                if (list == null) {
                    list = new ArrayList<>();
                    map.put(pkg, list);
                }
                if (list.size() >= 6) {
                    continue;
                }
                HeroItem it = new HeroItem();
                it.title = title;
                String detail = c.getString(5);
                it.subtitle = kind + (detail != null && !detail.isEmpty() ? "  ·  " + detail : "");
                it.artUri = firstNonEmpty(c.getString(2), c.getString(1));
                try {
                    it.intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
                } catch (URISyntaxException e) {
                    continue;
                }
                list.add(it);
            }
        } catch (Exception e) {
            // Not readable on this device: nothing to show from this source.
            return "blocked (" + e.getClass().getSimpleName() + ")";
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return rows + " found";
    }

    private final Map<String, Boolean> playSearchSupport = new HashMap<>();

    /** Whether the app answers "play this by name" (the request a voice search makes). */
    private boolean supportsPlaySearch(String pkg) {
        Boolean known = playSearchSupport.get(pkg);
        if (known == null) {
            Intent probe = new Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").setPackage(pkg);
            known = getPackageManager().resolveActivity(probe, 0) != null;
            playSearchSupport.put(pkg, known);
        }
        return known;
    }

    /** True when pressing OK on this remembered title can lead somewhere specific, not just the app. */
    private boolean canOpen(JSONObject o) {
        String pkg = o.optString("pkg");
        return !o.optString("link").isEmpty() || pkg.contains("youtube") || supportsPlaySearch(pkg)
                || !o.optString("art").isEmpty(); // has a poster: show it, OK at least opens the app
    }

    /**
     * Opens the exact video when we know its link. Otherwise we ask the app to play the title by
     * name, the same request a voice search makes.
     */
    private Intent intentFor(JSONObject o, App a) {
        String link = o.optString("link");
        if (!link.isEmpty()) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    .setPackage(a.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Only if the app really handles that kind of link; otherwise try the other ways.
            if (getPackageManager().resolveActivity(view, 0) != null) {
                return view;
            }
        }
        String title = o.optString("title");
        if (supportsPlaySearch(a.pkg)) {
            return new Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH")
                    .setPackage(a.pkg)
                    .putExtra("query", title)
                    .putExtra("android.intent.extra.focus", "vnd.android.cursor.item/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if (a.pkg.contains("youtube")) {
            return new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(title)))
                    .setPackage(a.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return a.launch;
    }

    private List<JSONObject> recentHistory(int max) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("history", "[]"));
            for (int i = 0; i < arr.length() && out.size() < max; i++) {
                out.add(arr.getJSONObject(i));
            }
        } catch (Exception ignored) {
            // unreadable history: show nothing
        }
        return out;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, dp(4), 0, dp(10));
        return t;
    }

    private List<MediaController> sessions() {
        try {
            MediaSessionManager m = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = m.getActiveSessions(new ComponentName(this, MediaListener.class));
            return list == null ? new ArrayList<MediaController>() : list;
        } catch (Exception e) {
            return new ArrayList<>(); // notification access not granted yet
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static Bitmap firstBitmap(Bitmap... values) {
        for (Bitmap b : values) {
            if (b != null) {
                return b;
            }
        }
        return null;
    }

    /** Brings the focused tile into view with a short eased animation instead of an instant jump. */
    private void ensureVisible(View cell) {
        if (cell.getHeight() == 0 || scroll == null || scroll.getChildCount() == 0) {
            return;
        }
        if (cell == heroRoot || cell.getParent() == headerView) {
            animateScroll(scroll, "scrollY", 0); // same position as pressing Home
            return;
        }
        View content = scroll.getChildAt(0);
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
        anim.setDuration(fastKeys ? 70 : 190);
        anim.setInterpolator(new DecelerateInterpolator());
        scrollAnims.put(scroller, anim);
        anim.start();
    }

    // ------------------------------------------------------------------ data

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
            key.append('|').append(prefs.getString("order", ""));
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
        final List<String> order = listPref("order");
        Collections.sort(list, (x, y) -> {
            int px = order.indexOf(x.pkg);
            int py = order.indexOf(y.pkg);
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

    private void addWatchedShelf(List<App> apps) {
        List<JSONObject> hist = recentHistory(14);
        if (hist.isEmpty()) {
            return;
        }
        Map<String, App> byPkg = new HashMap<>();
        for (App a : apps) {
            byPkg.put(a.pkg, a);
        }
        HScroll hs = new HScroll(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(0, dp(10), 0, dp(10));
        LinearLayout rowView = newRow();
        int shown = 0;
        for (JSONObject o : hist) {
            final App src = byPkg.get(o.optString("pkg"));
            if (src == null || !canOpen(o)) {
                continue; // hidden/uninstalled app, or nothing that opens a specific video
            }
            final Intent open = intentFor(o, src);
            View cell = makeTile(topW, o.optString("title"), null,
                    src.banner != null ? src.banner : src.icon, false);
            String art = o.optString("art");
            if (!art.isEmpty()) {
                FrameLayout card = (FrameLayout) ((LinearLayout) cell).getChildAt(0);
                ImageView img = (ImageView) card.getChildAt(0);
                img.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setImageDrawable(null);
                loadImage(art, img);
            }
            cell.setOnClickListener(v -> safeStart(open));
            rowView.addView(cell, cellParams(topW));
            shown++;
        }
        if (shown == 0) {
            return;
        }
        grid.addView(sectionTitle("Recently watched"), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hs.addView(rowView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        grid.addView(hs, lp);
    }

    private void render(List<App> apps) {
        // Hold the page height while it is rebuilt so the scroll position does not jump to the top.
        grid.setMinimumHeight(grid.getHeight());
        grid.removeAllViews();
        tiles.clear();
        shownApps.clear();
        shownApps.addAll(apps);
        firstCell = null;
        scrollAnims.clear();

        grid.addView(headerView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (heroRoot == null) {
            buildHero();
        }
        // The carousel is as wide as the screen so the neighbouring cards can peek in from the sides.
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heroH);
        heroLp.topMargin = dp(8);
        heroLp.bottomMargin = dp(22);
        heroLp.leftMargin = -pad;
        heroLp.rightMargin = -pad;
        grid.addView(heroRoot, heroLp);

        int topCount = Math.min(TOP_ROW, apps.size());
        if (topCount > 0) {
            // The first row sits on a rounded panel.
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setClipChildren(false);
            panel.setClipToPadding(false);
            panel.setPadding(dp(20), dp(18), dp(20), dp(18));
            GradientDrawable panelBg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, Theme.grad(th.panel));
            panelBg.setCornerRadius(dp(28));
            panel.setBackground(panelBg);

            LinearLayout top = newRow();
            for (int i = 0; i < topCount; i++) {
                View cell = makeAppTile(apps.get(i), topW, false); // the first row shows no names
                LinearLayout.LayoutParams cp = cellParams(topW);
                if (i == topCount - 1) {
                    cp.rightMargin = 0;
                }
                top.addView(cell, cp);
                if (firstCell == null) {
                    firstCell = cell;
                }
            }
            panel.addView(top, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            topLp.bottomMargin = dp(22);
            grid.addView(panel, topLp);
        }


        if (apps.size() > topCount) {
            LinearLayout row = null;
            for (int i = topCount; i < apps.size(); i++) {
                if ((i - topCount) % GRID_COLS == 0) {
                    row = newRow();
                    row.setPadding(dp(20), 0, dp(20), 0); // lines the columns up with the first row's panel
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowLp.bottomMargin = dp(18);
                    grid.addView(row, rowLp);
                }
                row.addView(makeAppTile(apps.get(i), gridW, true), cellParams(gridW));
            }
        }

        Set<String> hidden = prefs.getStringSet("hidden", new HashSet<String>());
        unhideBtn.setText("Show " + hidden.size() + " hidden");
        unhideBtn.setVisibility(hidden.isEmpty() ? View.GONE : View.VISIBLE);

        // Keep the hero on the same app across a rebuild.
        if (focusedApp != null) {
            for (App a : apps) {
                if (a.pkg.equals(focusedApp.pkg)) {
                    focusedApp = a;
                }
            }
        }
        if (focusedApp == null && !apps.isEmpty()) {
            applyHeroApp(apps.get(0));
        } else if (stageApp == null) {
            showHero(false); // the screen was rebuilt (a new theme): draw the cards again
        }

        String want = focusAfter != null ? focusAfter : lastFocusPkg;
        View target = want != null ? tiles.get(want) : null;
        focusAfter = null;
        if (target != null) {
            target.setScaleX(1.08f); // already enlarged: no "pop" on every step of a move
            target.setScaleY(1.08f);
            target.requestFocus();
            final View t = target;
            grid.post(() -> ensureVisible(t));
        } else {
            scroll.scrollTo(0, 0);
            if (firstCell != null) {
                firstCell.requestFocus();
            }
        }
        grid.post(() -> grid.setMinimumHeight(0));
        updateBadges();
    }

    // ---------------------------------------------------------------- images

    /**
     * Every picture fills the card. Tall ones (movie posters) are cut to a wide strip taken from
     * the upper part, where the title and faces usually are.
     */
    private void setArtBitmap(ImageView v, Bitmap b) {
        v.setPadding(0, 0, 0, 0);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int wideH = b.getWidth() * 9 / 16;
        if (b.getHeight() > wideH * 11 / 10) {
            try {
                int y = (int) ((b.getHeight() - wideH) * 0.22f);
                b = Bitmap.createBitmap(b, 0, y, b.getWidth(), wideH);
            } catch (Exception ignored) {
                // show it uncropped
            }
        }
        v.setImageBitmap(b);
    }

    private void loadImage(final String uri, final ImageView target) {
        if (uri == null || uri.isEmpty()) {
            return;
        }
        target.setTag(uri);
        Bitmap cached = imageCache.get(uri);
        if (cached != null) {
            setArtBitmap(target, cached);
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
            ui.post(() -> {
                if (uri.equals(target.getTag())) {
                    setArtBitmap(target, b);
                }
                maybeTint(uri, b);
            });
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
            } else if ("file".equals(scheme) && uri.getPath() != null
                    && (uri.getPath().startsWith(getFilesDir().getAbsolutePath())
                    || uri.getPath().startsWith(getCacheDir().getAbsolutePath()))) {
                in = new FileInputStream(uri.getPath()); // only our own artwork cache
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
            while (bounds.outWidth / (sample * 2) >= 1280) {
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
        SearchDialog.recordUse(prefs, a.pkg);
        safeStart(a.launch);
    }

    private void showMenu(final App a) {
        showMenu(a, false);
    }

    /** With armed set, the last row reads a red "Are you sure?" and the next click uninstalls. */
    private void showMenu(final App a, final boolean armed) {
        final String[] items = {"Move", "Hide from home", "App info", "Force stop",
                armed ? "Are you sure?" : "Uninstall"};
        dismissDialog();
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(a.label)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        moveApp = a.pkg;
                        focusAfter = a.pkg;
                        Toast.makeText(this, "Use the arrows to move it, press OK to place it",
                                Toast.LENGTH_LONG).show();
                        refresh(true);
                    } else if (which == 1) {
                        Set<String> hidden = new HashSet<>(
                                prefs.getStringSet("hidden", new HashSet<String>()));
                        hidden.add(a.pkg);
                        prefs.edit().putStringSet("hidden", hidden).apply();
                        refresh(true);
                    } else if (which == 2) {
                        safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + a.pkg)));
                    } else if (which == 3) {
                        // Apps cannot truly force-stop each other, but this ends the app's background
                        // processes and frees its memory, which is what matters on this TV.
                        try {
                            ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE))
                                    .killBackgroundProcesses(a.pkg);
                            Toast.makeText(this, a.label + " stopped", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(this, "Could not stop " + a.label, Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        boolean system = false;
                        try {
                            system = (getPackageManager().getApplicationInfo(a.pkg, 0).flags
                                    & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                        } catch (Exception ignored) {
                            // treat as a normal app
                        }
                        if (system) {
                            Toast.makeText(this, "That is a system app: hide it from home instead",
                                    Toast.LENGTH_LONG).show();
                        } else if (!armed) {
                            showMenu(a, true);
                        } else {
                            safeStart(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
                        }
                    }
                })
                .create();
        openDialog = d;
        d.show();
        if (armed) {
            // Red warning row, already selected so one more OK confirms.
            final android.widget.ListView lv = d.getListView();
            lv.setSelection(4);
            lv.post(() -> {
                View row = lv.getChildAt(4 - lv.getFirstVisiblePosition());
                if (row instanceof TextView) {
                    ((TextView) row).setTextColor(0xFFFF5252);
                }
            });
        }
    }

    /** Moves the selected app one step in the given direction, then rebuilds the screen. */
    private void moveSelected(int keyCode) {
        List<String> cur = new ArrayList<>();
        for (App a : shownApps) {
            cur.add(a.pkg);
        }
        int n = cur.size();
        int i = cur.indexOf(moveApp);
        if (i < 0) {
            return;
        }
        int r = i < TOP_ROW ? 0 : 1 + (i - TOP_ROW) / GRID_COLS;
        int c = i < TOP_ROW ? i : (i - TOP_ROW) % GRID_COLS;
        int j = i;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                j = i - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                j = i + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (r == 1) {
                    j = Math.min(c, TOP_ROW - 1);
                } else if (r > 1) {
                    j = TOP_ROW + GRID_COLS * (r - 2) + c;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                j = r == 0 ? TOP_ROW + c : TOP_ROW + GRID_COLS * r + c;
                break;
            default:
                break;
        }
        j = Math.max(0, Math.min(j, n - 1));
        if (j == i) {
            return;
        }
        cur.remove(i);
        cur.add(j, moveApp);
        saveListPref("order", cur);
        // Rebuild from what is already in memory: no package scan, no image loading, so it is quick.
        Map<String, App> byPkg = new HashMap<>();
        for (App a : shownApps) {
            byPkg.put(a.pkg, a);
        }
        List<App> next = new ArrayList<>();
        for (String p : cur) {
            next.add(byPkg.get(p));
        }
        focusAfter = moveApp;
        render(next);
    }

    private void rerender() {
        render(new ArrayList<>(shownApps));
    }

    /** Search box (voice, apps, YouTube, web). */
    private void showSearch(boolean voice) {
        dismissDialog();
        if (voice) {
            SearchDialog.voice(this);
            return;
        }
        List<String> labels = new ArrayList<>();
        List<String> pkgs = new ArrayList<>();
        for (App a : shownApps) {
            labels.add(a.label);
            pkgs.add(a.pkg);
        }
        openDialog = SearchDialog.show(this, th, labels, pkgs, pkg -> {
            for (App a : shownApps) {
                if (a.pkg.equals(pkg)) {
                    launch(a);
                }
            }
        });
    }

    /** Asks for the city used by the weather line. */
    private void showCityDialog() {
        final android.widget.EditText box = new android.widget.EditText(this);
        box.setSingleLine(true);
        box.setText(Weather.city(prefs));
        box.setHint("For example: Haifa");
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Weather city")
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    prefs.edit().putString("city", box.getText().toString().trim())
                            .remove("wx_at").remove("wx_for").apply();
                    updateWeather();
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.show();
        box.requestFocus();
    }

    /** Shows the saved weather text and refreshes it in the background when it is old. */
    private static String wxLabel(String t) {
        return t.isEmpty() ? "" : "·  " + t;
    }

    void updateWeather() {
        if (weatherView == null) {
            return;
        }
        String t = Weather.cached(prefs);
        weatherView.setText(wxLabel(t));
        weatherView.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
        Weather.refresh(prefs, () -> ui.post(() -> {
            if (weatherView != null) {
                String now = Weather.cached(prefs);
                weatherView.setText(wxLabel(now));
                weatherView.setVisibility(now.isEmpty() ? View.GONE : View.VISIBLE);
            }
        }));
    }

    /** Puts the daily picture behind everything (and downloads today's once a day). */
    private void loadWallpaper() {
        final ImageView wv = wallView;
        final View veil = wallScrim;
        if (wv == null || veil == null) {
            return;
        }
        final String src = th.picture ? Wallpaper.source(th.wall) : null;
        if (src == null) {
            wv.setImageDrawable(null);
            wv.setColorFilter(null);
            wv.setVisibility(View.GONE);
            veil.setVisibility(View.GONE);
            if (wv.getParent() instanceof View && bgDrawable != null) {
                ((View) wv.getParent()).setBackground(bgDrawable);
            }
            return;
        }
        if (net.isShutdown()) {
            return;
        }
        net.execute(() -> {
            File first = Wallpaper.latest(this, src);
            if (first != null) {
                showWallpaper(wv, veil, decodeWall(first));
            }
            File fresh = Wallpaper.ensure(this, src);
            if (fresh != null && !fresh.equals(first)) {
                showWallpaper(wv, veil, decodeWall(fresh));
            }
        });
    }

    private void showWallpaper(final ImageView wv, final View veil, final Bitmap b) {
        if (b == null) {
            return;
        }
        ui.post(() -> {
            if (wv != wallView) {
                return; // the screen was rebuilt meanwhile
            }
            wv.setImageBitmap(b);
            wv.setVisibility(View.VISIBLE);
            // The picture covers the whole screen, so the gradient under it is wasted work: drop it.
            if (wv.getParent() instanceof View) {
                ((View) wv.getParent()).setBackground(null);
            }
            if (th.light) {
                // Light: only a soft fade at the top, drawn by the small veil view.
                wv.setColorFilter(null);
                veil.setVisibility(View.VISIBLE);
            } else {
                // Dark/colour: darken the picture itself instead of laying a second full-screen
                // layer over it (one pass less for the GPU on every frame).
                boolean colour = Theme.COLOUR.equals(th.mode);
                float a = colour ? 0xAA / 255f : 0x8C / 255f;
                int mul = Math.round((1f - a) * 255f);
                int c = colour ? th.bgB : 0;
                int add = ((Math.round(((c >> 16) & 0xFF) * a) & 0xFF) << 16)
                        | ((Math.round(((c >> 8) & 0xFF) * a) & 0xFF) << 8)
                        | (Math.round((c & 0xFF) * a) & 0xFF);
                wv.setColorFilter(new android.graphics.LightingColorFilter(
                        (mul << 16) | (mul << 8) | mul, add));
                veil.setVisibility(View.GONE);
            }
        });
    }

    private Bitmap decodeWall(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 1920) {
                sample *= 2;
            }
            BitmapFactory.Options real = new BitmapFactory.Options();
            real.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), real);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Shows or hides the red dot on each app tile depending on its notifications. */
    void updateBadges() {
        if (io.isShutdown()) {
            return;
        }
        io.execute(() -> {
            final Set<String> pkgs = new HashSet<>();
            if (MediaListener.connected()) {
                for (MediaListener.Note n : MediaListener.notes(this)) {
                    if (n.pkg != null) {
                        pkgs.add(n.pkg);
                    }
                }
            }
            ui.post(() -> {
                for (Map.Entry<String, View> e : tiles.entrySet()) {
                    View dot = e.getValue().findViewWithTag("badge");
                    if (dot != null) {
                        dot.setVisibility(pkgs.contains(e.getKey()) ? View.VISIBLE : View.GONE);
                    }
                }
            });
        });
    }

    /** The quick settings panel (orbs and notifications), opened by the remote's Settings button or the gear. */
    private void showQuickSettings() {
        dismissDialog();
        safeStart(new Intent(this, PanelActivity.class));
    }

    /** Rebuilds the whole screen with the colours of the chosen theme. */
    private void applyTheme() {
        rebuildForTheme(true);
    }

    /** Applies the chosen theme without any animation (used while another app is on screen). */
    void applyThemeQuietly() {
        ui.post(() -> rebuildForTheme(false));
    }

    /**
     * Rebuilds the screen with the colours of the chosen theme. The new screen is built underneath
     * the old one, and the old one is faded out, so nothing flashes.
     */
    private void rebuildForTheme(boolean fade) {
        th = Theme.load(prefs);
        if (tintAnim != null) {
            tintAnim.cancel();
        }
        final int keepY = scroll != null ? scroll.getScrollY() : 0;
        final ViewGroup content = (ViewGroup) findViewById(android.R.id.content);
        final View oldRoot = fade && content != null && content.getChildCount() > 0
                ? content.getChildAt(0) : null;
        heroRoot = null;
        slots = null;
        heroRing = null;
        stageApp = null;
        stageIdx = -1;
        stageN = 0;
        firstCell = null;
        tiles.clear();
        scrollAnims.clear();
        View fresh = buildRoot(pad);
        if (oldRoot != null) {
            content.addView(fresh, 0, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            setContentView(fresh);
        }
        if (shownApps.isEmpty()) {
            refresh(true);
        } else {
            // Rebuild from what is already in memory (no package scan, no image loading), all at
            // once, so nothing heavy happens while the fade is running.
            render(new ArrayList<>(shownApps));
        }
        // Put the new screen at the same scroll position as the old one, before its first frame, so
        // nothing jumps while the theme changes.
        if (keepY > 0) {
            final View sc = scroll;
            sc.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (sc.getHeight() == 0 || grid.getHeight() == 0) {
                        return true;
                    }
                    sc.getViewTreeObserver().removeOnPreDrawListener(this);
                    scrollAnims.clear();
                    sc.scrollTo(0, keepY);
                    return true;
                }
            });
        }
        if (oldRoot != null) {
            // Fade it as one flat layer on the graphics chip, so the fade stays smooth on this weak TV.
            // The fade starts after the new screen has been drawn once.
            oldRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            oldRoot.animate().setStartDelay(60).alpha(0f).setDuration(300)
                    .setInterpolator(new android.view.animation.LinearInterpolator())
                    .withEndAction(() -> {
                        oldRoot.setLayerType(View.LAYER_TYPE_NONE, null);
                        content.removeView(oldRoot);
                    }).start();
        }
    }

    /** TV Home's own settings, shown in a side dialog (the same view the quick settings can draw over an app). */
    private void showHomeSettings(final int focusRow) {
        dismissDialog();
        final Dialog d = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        final SettingsPanel sp = new SettingsPanel(this, d::dismiss, this::applyTheme, () -> {
            stageApp = null;
            focusedApp = null;
            heroItems = new ArrayList<>();
            heroBase = false;
            refresh(true);
        }, this::showCityDialog);
        d.setContentView(sp.build());
        d.setOnKeyListener((dialog, keyCode, ev) -> keyCode == android.view.KeyEvent.KEYCODE_BACK
                && ev.getAction() == android.view.KeyEvent.ACTION_UP && sp.back());
        openDialog = d;
        d.show();
        Window window = d.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
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
