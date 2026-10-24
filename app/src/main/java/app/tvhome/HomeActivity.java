package app.tvhome;

import android.animation.ObjectAnimator;
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
    private static final int GRID_COLS = 7;
    private static final int ROTATE_MS = 7000;
    private static final int HISTORY_PER_APP = 4;
    private static final int HISTORY_TOTAL = 40;

    private static final int CARD_COLOR = 0xFF1C2338;
    private static final int PILL_COLOR = 0xFF232B42;
    private static final int TEXT_DIM = 0xFF9AA3B8;
    private static final int INK = 0xFF0A0E1A;
    private static final int ACCENT = 0xFF4C8DFF;

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
    }

    /** One of two stacked views of the hero, so one can fade in over the other with no gap. */
    private static class HeroLayer {
        FrameLayout root;
        ImageView art;
        ImageView side;
        TextView app;
        TextView title;
        TextView sub;
        TextView dots;
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

    private SharedPreferences prefs;
    private float density;
    private int gap;
    private int topW;
    private int gridW;
    private int heroH;
    private ScrollView scroll;
    private LinearLayout grid;
    private View headerView;
    private TextView unhideBtn;
    private App pendingHeroApp;
    private final Runnable heroSwitch = () -> {
        if (pendingHeroApp != null) {
            applyHeroApp(pendingHeroApp);
        }
    };
    private View firstCell;
    private String lastKey = "";
    private Dialog openDialog;

    // Hero views.
    private FrameLayout heroRoot;
    private HeroLayer heroFront;
    private HeroLayer heroBack;
    private Runnable heroFinish;
    private App focusedApp;
    private int heroIdx;

    // Move mode: the app being repositioned, and the app to focus after the next rebuild.
    private String moveApp;
    private String lastFocusPkg;
    /** The home screen while it is showing; the accessibility service uses it for Home-on-Home. */
    static volatile HomeActivity visible;
    private boolean onScreen;
    private String focusAfter;

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            loadPrograms();
            recordHistoryAsync();
            if (focusedApp != null) {
                int before = heroItems.size();
                heroItems = buildHeroItems(focusedApp);
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
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int pad = dp(64);
        gap = dp(16);
        int avail = screenW - 2 * pad;
        topW = (avail - (TOP_ROW - 1) * gap) / TOP_ROW;
        gridW = (avail - (GRID_COLS - 1) * gap) / GRID_COLS;
        heroH = dp(210);
        setContentView(buildRoot(pad));
    }

    @Override
    protected void onResume() {
        super.onResume();
        onScreen = true;
        visible = this;
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
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0A0E1A, 0xFF141B30, 0xFF0A0E1A}));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);

        // The header scrolls with the content (it is the first child of the grid), so it never
        // shows through behind the tiles.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(32), 0, dp(8));
        headerView = header;

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
        settingsBtn.setOnClickListener(v -> showQuickSettings());
        header.addView(settingsBtn, pillParams());

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
    private View makeTile(int w, String text, Drawable banner, Drawable icon, boolean accent) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setClipChildren(false);

        final float radius = dp(16);
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
        ring.setCornerRadius(Math.max(0f, radius - dp(accent ? 4 : 3) / 2f));
        ring.setStroke(dp(accent ? 4 : 3), accent ? ACCENT : Color.WHITE);

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
        final View cell = makeTile(w, a.label, a.banner, a.icon, a.pkg.equals(moveApp));
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
        tiles.put(a.pkg, cell);
        return cell;
    }

    // ------------------------------------------------------------------ hero

    private HeroLayer makeHeroLayer() {
        HeroLayer l = new HeroLayer();
        l.root = new FrameLayout(this);

        l.art = new ImageView(this);
        l.art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        l.root.addView(l.art, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xE6000000, 0x66000000, 0x00000000}));
        l.root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        l.side = new ImageView(this);
        l.side.setClipToOutline(true);
        l.side.setOutlineProvider(rounded(dp(14)));
        FrameLayout.LayoutParams sideLp = new FrameLayout.LayoutParams(dp(300), dp(169),
                Gravity.END | Gravity.CENTER_VERTICAL);
        sideLp.rightMargin = dp(40);
        l.root.addView(l.side, sideLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.BOTTOM);
        text.setPadding(dp(36), dp(24), dp(36), dp(28));

        l.app = new TextView(this);
        l.app.setTextColor(0xFFB6BED2);
        l.app.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        l.app.setLetterSpacing(0.12f);
        text.addView(l.app);

        l.title = new TextView(this);
        l.title.setTextColor(Color.WHITE);
        l.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        l.title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        l.title.setSingleLine(true);
        l.title.setEllipsize(TextUtils.TruncateAt.END);
        l.title.setPadding(0, dp(4), dp(340), 0);
        text.addView(l.title);

        l.sub = new TextView(this);
        l.sub.setTextColor(0xFFC3CADB);
        l.sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        l.sub.setSingleLine(true);
        l.sub.setEllipsize(TextUtils.TruncateAt.END);
        l.sub.setPadding(0, dp(2), dp(340), 0);
        text.addView(l.sub);

        l.root.addView(text, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        l.dots = new TextView(this);
        l.dots.setTextColor(Color.WHITE);
        l.dots.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        FrameLayout.LayoutParams dotsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        dotsLp.rightMargin = dp(24);
        dotsLp.bottomMargin = dp(16);
        l.root.addView(l.dots, dotsLp);
        return l;
    }

    private View buildHero() {
        heroRoot = new FrameLayout(this);
        heroRoot.setFocusable(true);
        heroRoot.setClickable(true);
        final float radius = dp(24);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF26304D, 0xFF121829});
        bg.setCornerRadius(radius);
        heroRoot.setBackground(bg);
        heroRoot.setClipToOutline(true);
        heroRoot.setOutlineProvider(rounded(radius));

        heroFront = makeHeroLayer();
        heroBack = makeHeroLayer();
        heroRoot.addView(heroBack.root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        heroRoot.addView(heroFront.root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        heroBack.root.setAlpha(0f);

        final GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(Math.max(0f, radius - dp(3) / 2f));
        ring.setStroke(dp(3), Color.WHITE);
        heroRoot.setOnFocusChangeListener((v, focused) -> {
            heroRoot.setForeground(focused ? ring : null);
            float s = focused ? 1.02f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(110).start();
            if (focused) {
                ensureVisible(v);
            }
        });
        heroRoot.setOnClickListener(v -> openHeroItem());
        return heroRoot;
    }

    private void openHeroItem() {
        if (heroItems.isEmpty()) {
            return;
        }
        HeroItem it = heroItems.get(Math.min(heroIdx, heroItems.size() - 1));
        boolean opened = false;
        if (it.pending != null) {
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
            try {
                it.controller.getTransportControls().play();
            } catch (Exception ignored) {
                // the app refused; it still opened
            }
        }
    }

    /** Called on every focus change; waits for focus to settle so fast scrolling doesn't flicker. */
    private void setHeroApp(App a) {
        pendingHeroApp = a;
        ui.removeCallbacks(heroSwitch);
        if (focusedApp != null && focusedApp.pkg.equals(a.pkg)) {
            return;
        }
        ui.postDelayed(heroSwitch, 600);
    }

    private void applyHeroApp(App a) {
        if (focusedApp != null && focusedApp.pkg.equals(a.pkg) && !heroItems.isEmpty()) {
            focusedApp = a;
            return;
        }
        focusedApp = a;
        heroItems = buildHeroItems(a);
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
            if (md == null) {
                continue;
            }
            String title = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                    md.getString(MediaMetadata.METADATA_KEY_TITLE));
            if (title == null) {
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
        HeroItem base = new HeroItem();
        base.appLabel = a.label;
        base.title = a.label;
        base.subtitle = "Press OK to open";
        base.banner = a.banner;
        base.icon = a.icon;
        base.intent = a.launch;
        if (out.isEmpty()) {
            out.add(base);
        }
        return out;
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
        return !o.optString("link").isEmpty() || pkg.contains("youtube") || supportsPlaySearch(pkg);
    }

    /**
     * Opens the exact video when we know its link. Otherwise we ask the app to play the title by
     * name, the same request a voice search makes.
     */
    private Intent intentFor(JSONObject o, App a) {
        String link = o.optString("link");
        if (!link.isEmpty()) {
            return new Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    .setPackage(a.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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

    private void fillLayer(HeroLayer l, HeroItem it, int idx, int count) {
        l.app.setText(it.appLabel == null ? "" : it.appLabel.toUpperCase());
        l.title.setText(it.title);
        l.sub.setText(it.subtitle);
        if (it.art != null) {
            l.art.setTag(null);
            l.art.setImageBitmap(it.art);
            l.art.setVisibility(View.VISIBLE);
            l.side.setVisibility(View.GONE);
        } else if (it.artUri != null) {
            l.art.setImageDrawable(null);
            l.art.setVisibility(View.VISIBLE);
            l.side.setVisibility(View.GONE);
            loadImage(it.artUri, l.art);
        } else {
            l.art.setImageDrawable(null);
            l.art.setVisibility(View.GONE);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) l.side.getLayoutParams();
            if (it.banner != null) {
                lp.width = dp(300);
                lp.height = dp(169);
                l.side.setScaleType(ImageView.ScaleType.CENTER_CROP);
                l.side.setImageDrawable(it.banner);
            } else {
                lp.width = dp(120);
                lp.height = dp(120);
                l.side.setScaleType(ImageView.ScaleType.FIT_CENTER);
                l.side.setImageDrawable(it.icon);
            }
            l.side.setLayoutParams(lp);
            l.side.setVisibility(View.VISIBLE);
        }
        StringBuilder dots = new StringBuilder();
        if (count > 1) {
            for (int i = 0; i < count; i++) {
                dots.append(i == idx ? "● " : "○ ");
            }
        }
        l.dots.setText(dots.toString().trim());
    }

    private void showHero(boolean animate) {
        if (heroRoot == null || heroItems.isEmpty()) {
            return;
        }
        final int count = heroItems.size();
        final int idx = Math.min(heroIdx, count - 1);
        final HeroItem it = heroItems.get(idx);

        // Finish any crossfade still running so the two layers are in a known state.
        heroFront.root.animate().cancel();
        heroBack.root.animate().cancel();
        if (heroFinish != null) {
            heroFinish.run();
            heroFinish = null;
        }
        if (!animate) {
            fillLayer(heroFront, it, idx, count);
            heroFront.root.setAlpha(1f);
            heroBack.root.setAlpha(0f);
            return;
        }
        // The new card fades in on top of the old one, so the background never shows through.
        final HeroLayer incoming = heroBack;
        final HeroLayer outgoing = heroFront;
        fillLayer(incoming, it, idx, count);
        incoming.root.setAlpha(0f);
        incoming.root.bringToFront();
        heroFinish = () -> {
            incoming.root.setAlpha(1f);
            outgoing.root.setAlpha(0f);
            heroFront = incoming;
            heroBack = outgoing;
        };
        incoming.root.animate().alpha(1f).setDuration(500).withEndAction(() -> {
            if (heroFinish != null) {
                heroFinish.run();
                heroFinish = null;
            }
        }).start();
    }

    // -------------------------------------------------------------- scrolling

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
        anim.setDuration(190);
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
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heroH);
        heroLp.topMargin = dp(8);
        heroLp.bottomMargin = dp(24);
        grid.addView(heroRoot, heroLp);

        int topCount = Math.min(TOP_ROW, apps.size());
        if (topCount > 0) {
            LinearLayout top = newRow();
            for (int i = 0; i < topCount; i++) {
                View cell = makeAppTile(apps.get(i), topW);
                top.addView(cell, cellParams(topW));
                if (firstCell == null) {
                    firstCell = cell;
                }
            }
            LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            topLp.bottomMargin = dp(18);
            grid.addView(top, topLp);
        }


        if (apps.size() > topCount) {
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
    }

    // ---------------------------------------------------------------- images

    private void loadImage(final String uri, final ImageView target) {
        if (uri == null || uri.isEmpty()) {
            return;
        }
        target.setTag(uri);
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
            ui.post(() -> {
                if (uri.equals(target.getTag())) {
                    target.setImageBitmap(b);
                }
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
                    && uri.getPath().startsWith(getCacheDir().getAbsolutePath())) {
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
        safeStart(a.launch);
    }

    private void showMenu(final App a) {
        final String[] items = {"Move", "Hide from home", "App info"};
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
                    } else {
                        safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + a.pkg)));
                    }
                })
                .create();
        openDialog = d;
        d.show();
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

    /** The quick settings side panel, opened by the remote's Settings button or the Settings pill. */
    private void showQuickSettings() {
        dismissDialog();
        final String[] names = {
                "Network & Internet", "Display", "Sound", "Bluetooth", "Keyboard",
                "Screen saver", "Date & time", "Accounts", "All settings"};
        final String[] actions = {
                Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS,
                Settings.ACTION_SOUND_SETTINGS, Settings.ACTION_BLUETOOTH_SETTINGS,
                Settings.ACTION_INPUT_METHOD_SETTINGS, Settings.ACTION_DREAM_SETTINGS,
                Settings.ACTION_DATE_SETTINGS, Settings.ACTION_SYNC_SETTINGS,
                Settings.ACTION_SETTINGS};

        final Dialog d = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(28), dp(32), dp(28), dp(24));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2121829);
        bg.setCornerRadii(new float[]{dp(24), dp(24), 0, 0, 0, 0, dp(24), dp(24)});
        panel.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("Quick settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, 0, 0, dp(16));
        panel.addView(title);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < names.length; i++) {
            final String action = names[i].equals("Accounts") ? Settings.ACTION_SYNC_SETTINGS : actions[i];
            TextView row = pill(names[i]);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            row.setOnClickListener(v -> {
                d.dismiss();
                safeStart(new Intent(action));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            list.addView(row, lp);
        }
        sv.addView(list);
        panel.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        d.setContentView(panel);
        d.setOnKeyListener((dlg, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_SETTINGS && event.getAction() == KeyEvent.ACTION_UP) {
                dlg.dismiss();
                return true;
            }
            return false;
        });
        openDialog = d;
        d.show();
        Window window = d.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            window.setLayout(dp(380), ViewGroup.LayoutParams.MATCH_PARENT);
            window.setDimAmount(0.55f);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
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
