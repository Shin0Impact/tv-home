package app.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.app.SearchManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.Log;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Search from the home screen, in the look of the search bar on top: a long rounded field with the
 * results listed under it as rounded rows (your apps, then YouTube and the web). The microphone
 * button on the home screen starts voice search directly.
 */
final class SearchDialog {

    interface Launcher {
        void open(String pkg);
    }

    private SearchDialog() {
    }

    static Dialog show(final Activity act, final Theme th, final List<String> labels,
                       final List<String> pkgs, final Launcher launcher) {
        final float density = act.getResources().getDisplayMetrics().density;
        final Dialog d = new Dialog(act, android.R.style.Theme_Translucent_NoTitleBar);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(density, 48), dp(density, 28), dp(density, 48), dp(density, 20));
        GradientDrawable sheet = new GradientDrawable();
        sheet.setColor(th.sheet);
                box.setBackground(sheet);

        // Every app on the home screen that can take a search (a standard search, Google's app
        // search action, or "play from search") gets its own row, so this list builds itself.
        final Map<String, String[]> searchable = searchableApps(act, pkgs);
        Log.i("TVHomeSearch", "searchable apps: " + java.util.Arrays.deepToString(searchable.entrySet().toArray()));
        final SharedPreferences usePrefs = act.getSharedPreferences("home", Activity.MODE_PRIVATE);

        // The field.
        final EditText input = new EditText(act);
        input.setHint("Search apps, YouTube and the web");
        input.setHintTextColor(th.dim);
        input.setTextColor(th.text);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(dp(density, 24), 0, dp(density, 24), 0);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(th.panel);
        fieldBg.setCornerRadius(dp(density, 28));
        fieldBg.setStroke(dp(density, 2), th.ring);
        input.setBackground(fieldBg);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(density, 52)));

        final LinearLayout results = new LinearLayout(act);
        results.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(results);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rlp.topMargin = dp(density, 14);
        box.addView(sv, rlp);

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            String q = input.getText().toString().trim();
            results.removeAllViews();
            if (q.isEmpty()) {
                return;
            }
            String low = q.toLowerCase();
            int shown = 0;
            final List<Integer> order = byUse(usePrefs, pkgs);
            for (int oi = 0; oi < order.size() && shown < 4; oi++) {
                int i = order.get(oi);
                if (labels.get(i).toLowerCase().contains(low)) {
                    final String pkg = pkgs.get(i);
                    results.addView(row(act, th, density, "Open  " + labels.get(i), () -> {
                        d.dismiss();
                        launcher.open(pkg);
                    }));
                    shown++;
                }
            }
            final String query = q;
            final Intent gs = googleSearch(act, query);
            if (gs != null) {
                results.addView(row(act, th, density, "Google TV search for \"" + q + "\"", () -> {
                    d.dismiss();
                    try {
                        act.startActivity(gs);
                    } catch (Exception e) {
                        Log.w("TVHomeSearch", "google search failed: " + e);
                        Toast.makeText(act, "Google search couldn't open", Toast.LENGTH_SHORT).show();
                    }
                }));
            }
            int apps = 0;
            for (int oi = 0; oi < order.size() && apps < 8; oi++) {
                int i = order.get(oi);
                final String pkg = pkgs.get(i);
                final String[] action = searchable.get(pkg);
                if (action == null) {
                    continue;
                }
                apps++;
                results.addView(row(act, th, density,
                        "Search " + labels.get(i) + " for \"" + q + "\"", () -> {
                    d.dismiss();
                    searchIn(act, pkg, action, query);
                }));
            }
            results.addView(row(act, th, density, "Search YouTube for \"" + q + "\"", () -> {
                d.dismiss();
                view(act, "https://www.youtube.com/results?search_query=" + Uri.encode(query));
            }));
            results.addView(row(act, th, density, "Search the web for \"" + q + "\"", () -> {
                d.dismiss();
                view(act, "https://www.google.com/search?q=" + Uri.encode(query));
            }));
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                // not needed
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                rebuild[0].run();
            }

            @Override
            public void afterTextChanged(Editable s) {
                // not needed
            }
        });
        // Enter on the keyboard runs the first result (an app if one matches, else YouTube).
        input.setOnEditorActionListener((v, action, ev) -> {
            if (results.getChildCount() > 0) {
                results.getChildAt(0).performClick();
            }
            return true;
        });

        d.setContentView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.TOP | Gravity.START);
            w.getDecorView().setPadding(0, 0, 0, 0);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        d.show();
        input.requestFocus();
        return d;
    }

    private static View row(Activity act, final Theme th, float density, String text, final Runnable go) {
        final TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(th.text);
        t.setSingleLine(true);
        t.setPadding(dp(density, 24), dp(density, 12), dp(density, 24), dp(density, 12));
        t.setFocusable(true);
        t.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(th.pill);
        bg.setCornerRadius(dp(density, 24));
        t.setBackground(bg);
        t.setOnFocusChangeListener((v, focused) -> {
            bg.setColor(focused ? th.focusBg : th.pill);
            t.setTextColor(focused ? th.focusText : th.text);
        });
        t.setOnClickListener(v -> go.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(density, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private static final String[] SEARCH_ACTIONS = {
            Intent.ACTION_SEARCH,
            "com.google.android.gms.actions.SEARCH_ACTION",
            "android.media.action.MEDIA_PLAY_FROM_SEARCH"};

    /**
     * Apps whose own search can take the typed words as a link (the same kind of link that makes
     * "Search YouTube" work). %s is replaced by the words. Add a line to teach it another app.
     */
    private static final String[][] LINKS = {
            {"org.smarttube.stable", "https://www.youtube.com/results?search_query=%s"},
            {"com.teamsmart.videomanager.tv", "https://www.youtube.com/results?search_query=%s"},
            {"com.google.android.youtube.tv", "https://www.youtube.com/results?search_query=%s"}};

    /**
     * package -> how to search in it, for the apps in {@code pkgs}: {"link", template} for the
     * known links above, else {"act", action, activity} for an app that declares a search action.
     */
    private static Map<String, String[]> searchableApps(Activity act, List<String> pkgs) {
        Map<String, String[]> found = new HashMap<>();
        for (String[] l : LINKS) {
            if (pkgs.contains(l[0])) {
                found.put(l[0], new String[]{"link", l[1]});
            }
        }
        PackageManager pm = act.getPackageManager();
        for (String action : SEARCH_ACTIONS) {
            try {
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(action), 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkgs.contains(pkg) && !pkg.equals(act.getPackageName())
                            && ri.activityInfo.exported && !found.containsKey(pkg)) {
                        found.put(pkg, new String[]{"act", action, ri.activityInfo.name});
                    }
                }
            } catch (Exception ignored) {
                // skip this kind of search
            }
        }
        return found;
    }

    /** Remembers that an app was opened (how often, and when last). */
    static void recordUse(SharedPreferences p, String pkg) {
        p.edit().putInt("uses_" + pkg, p.getInt("uses_" + pkg, 0) + 1)
                .putLong("last_" + pkg, System.currentTimeMillis()).apply();
    }

    /** Indexes into pkgs: the last used app, then the 3 most used, then the rest; nobody twice. */
    private static List<Integer> byUse(SharedPreferences p, List<String> pkgs) {
        List<Integer> out = new ArrayList<>();
        int last = -1;
        long best = 0;
        for (int i = 0; i < pkgs.size(); i++) {
            long t = p.getLong("last_" + pkgs.get(i), 0);
            if (t > best) {
                best = t;
                last = i;
            }
        }
        if (last >= 0) {
            out.add(last);
        }
        for (int n = 0; n < 3; n++) {
            int top = -1;
            int most = 0;
            for (int i = 0; i < pkgs.size(); i++) {
                int u = p.getInt("uses_" + pkgs.get(i), 0);
                if (u > most && !out.contains(i)) {
                    most = u;
                    top = i;
                }
            }
            if (top >= 0) {
                out.add(top);
            }
        }
        for (int i = 0; i < pkgs.size(); i++) {
            if (!out.contains(i)) {
                out.add(i);
            }
        }
        return out;
    }

    private static void searchIn(Activity act, String pkg, String[] how, String query) {
        recordUse(act.getSharedPreferences("home", Activity.MODE_PRIVATE), pkg);
        Log.i("TVHomeSearch", "search in " + pkg + " how=" + java.util.Arrays.toString(how) + " query=" + query);
        Intent i;
        if ("link".equals(how[0])) {
            String q = how[1].startsWith("spotify:") ? query : Uri.encode(query);
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(how[1].replace("%s", q))).setPackage(pkg);
        } else {
            // Started by its exact screen, so it does not matter how the app filed the action.
            i = new Intent(how[1]).setClassName(pkg, how[2])
                    .putExtra(SearchManager.QUERY, query)
                    .putExtra("query", query);
            if (how[1].endsWith("MEDIA_PLAY_FROM_SEARCH")) {
                i.putExtra("android.intent.extra.focus", "vnd.android.cursor.item/*");
                i.putExtra("android.intent.extra.title", query);
            }
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.i("TVHomeSearch", "intent " + i.toUri(0) + " extras=" + i.getExtras());
        try {
            act.startActivity(i);
        } catch (Exception e) {
            Log.w("TVHomeSearch", "failed: " + e);
            Toast.makeText(act, "That app couldn't search", Toast.LENGTH_SHORT).show();
        }
    }

    /** Google TV's own search (rows of results from your streaming apps), started with the words typed. */
    private static Intent googleSearch(Activity act, String query) {
        try {
            Intent probe = new Intent("android.search.action.GLOBAL_SEARCH").setPackage("com.google.android.katniss");
            List<ResolveInfo> found = act.getPackageManager().queryIntentActivities(probe, 0);
            if (found.isEmpty()) {
                return null;
            }
            ResolveInfo ri = found.get(0);
            return new Intent("android.search.action.GLOBAL_SEARCH")
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    .putExtra(SearchManager.QUERY, query)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (Exception e) {
            return null;
        }
    }

    static void voice(Activity act) {
        String[] tries = {Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, "android.intent.action.VOICE_ASSIST"};
        for (String action : tries) {
            try {
                act.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
                // try the next one
            }
        }
        Toast.makeText(act, "Voice search isn't available", Toast.LENGTH_SHORT).show();
    }

    private static void view(Activity act, String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(act, "Nothing can open that", Toast.LENGTH_SHORT).show();
        }
    }

    private static int dp(float density, int v) {
        return (int) (v * density + 0.5f);
    }
}
