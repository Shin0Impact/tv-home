package app.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The quick settings panel: round buttons for the usual settings and the notifications that are
 * showing. Built as a plain view so it can be shown either as a window of its own (over a playing
 * video, without pausing it) or inside PanelActivity.
 */
final class QuickPanel {

    private final Context ctx;
    private final Runnable close;
    private final float density;
    private Theme th;
    private LinearLayout notesBox;
    private TextView clearBtn;

    QuickPanel(Context ctx, Runnable close) {
        this.ctx = ctx;
        this.close = close;
        this.density = ctx.getResources().getDisplayMetrics().density;
    }

    View build() {
        SharedPreferences sp = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
        th = Theme.load(sp);

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(14));
        // A floating rounded card with a hairline edge, not a slab glued to the screen edge.
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, Theme.grad(th.sheet));
        bg.setCornerRadii(new float[]{dp(20), dp(20), 0, 0, 0, 0, dp(20), dp(20)});
        panel.setBackground(bg);

        android.widget.TextClock clock = new android.widget.TextClock(ctx);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(th.text);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setPadding(dp(4), 0, 0, 0);
        panel.addView(clock);
        android.widget.TextClock date = new android.widget.TextClock(ctx);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(th.dim);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        date.setPadding(dp(4), 0, 0, dp(12));
        panel.addView(date);

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setClipChildren(false);
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        // Room above the first row so a focused (enlarged) circle is never cut off.
        content.setPadding(0, 0, 0, dp(4));

        boolean online = isOnline();
        final Object[][] orbs = {
                {"Wi-Fi", "", Icons.WIFI, Settings.ACTION_WIFI_SETTINGS, online},
                {"Bluetooth", "", Icons.BLUETOOTH, Settings.ACTION_BLUETOOTH_SETTINGS, false},
                {"Sound", "", Icons.SOUND, Settings.ACTION_SOUND_SETTINGS, false},
                {"Display", "", Icons.DISPLAY, Settings.ACTION_DISPLAY_SETTINGS, false},
                {"Screen share", "", Icons.CAST, "app.tvhome.SHARE", false},
                {"Keyboard", "", Icons.KEYBOARD, Settings.ACTION_INPUT_METHOD_SETTINGS, false},
                {"Screen saver", "", Icons.MOON, "app.tvhome.SCREENSAVER", false},
                {"Date & time", "", Icons.CLOCK, Settings.ACTION_DATE_SETTINGS, false},
                {"Accounts", "", Icons.PERSON, Settings.ACTION_SYNC_SETTINGS, false},
                {"All settings", "", Icons.GEAR, Settings.ACTION_SETTINGS, false},
                {"TV Home", "", Icons.SLIDERS, "app.tvhome.OPEN_SETTINGS", false}};
        final int perRow = 5;
        LinearLayout row = null;
        for (int i = 0; i < orbs.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(6);
                content.addView(row, rlp);
            }
            Object[] o = orbs[i];
            View cell = orb((String) o[0], (String) o[1], (Integer) o[2], (Boolean) o[4]);
            final String action = (String) o[3];
            cell.setOnClickListener(v -> openAction(action));
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        // Notifications.
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(14), 0, dp(10));
        TextView nt = new TextView(ctx);
        nt.setText("Notifications");
        nt.setTextColor(th.text);
        nt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        nt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(nt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        clearBtn = pill("Clear all");
        clearBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        clearBtn.setPadding(dp(16), dp(6), dp(16), dp(6));
        clearBtn.setOnClickListener(v -> {
            MediaListener.dismissAll();
            fillNotes();
        });
        head.addView(clearBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(head);

        notesBox = new LinearLayout(ctx);
        notesBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(notesBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fillNotes();

        // What the apps report to the hero cards; only here while the carousel is being tuned.
        TextView diag = new TextView(ctx);
        StringBuilder info = new StringBuilder(sp.getString("epgdebug", "Apps' rows: not read yet"));
        if (sp.contains("storedebug")) {
            info.append("\n").append(sp.getString("storedebug", ""));
        }
        for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getKey().startsWith("mddebug_") && e.getValue() instanceof String) {
                info.append("\n\n").append((String) e.getValue());
            }
        }
        diag.setText(info.toString());
        diag.setTextColor(th.dim);
        diag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        diag.setPadding(dp(4), dp(8), 0, 0);
        diag.setVisibility(View.GONE);
        final TextView diagBtn = pill("Diagnostics");
        diagBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        diagBtn.setOnClickListener(v -> diag.setVisibility(
                diag.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(16);
        content.addView(diagBtn, dlp);
        content.addView(diag);

        sv.addView(content);
        panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        android.widget.FrameLayout frame = new android.widget.FrameLayout(ctx);
        frame.setPadding(0, 0, 0, 0);
        frame.setClipChildren(false);
        frame.addView(panel, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private void openAction(String action) {
        try {
            if ("app.tvhome.SCREENSAVER".equals(action)) {
                // Start the screen saver right now (what the Settings screen only lets you pick).
                try {
                    ctx.startActivity(new Intent(Intent.ACTION_MAIN)
                            .setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    ctx.startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            } else if ("app.tvhome.SHARE".equals(action)) {
                // The TV's own receiver: some phones and tablets only see the TV while this is open.
                // Google Cast needs nothing from here; the tablet's Cast button reaches it any time.
                try {
                    ctx.startActivity(new Intent(Intent.ACTION_MAIN)
                            .setClassName("com.tcl.miracast", "com.tcl.miracast.MainActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    ctx.startActivity(new Intent(Intent.ACTION_MAIN)
                            .setClassName("com.tcl.miracast", "com.tcl.miracast.DeviceListActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            } else if ("app.tvhome.OPEN_SETTINGS".equals(action)) {
                ctx.startActivity(new Intent(ctx, HomeActivity.class)
                        .putExtra("settings", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                ctx.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (Exception e) {
            Toast.makeText(ctx, "Can't open that", Toast.LENGTH_SHORT).show();
        }
        close.run();
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    /** Lists the notifications that are showing now. OK opens one, holding OK dismisses it. */
    private void fillNotes() {
        notesBox.removeAllViews();
        if (!MediaListener.connected()) {
            addNote("Notification access is off", "Turn it on for TV Home to see notifications here.");
            clearBtn.setVisibility(View.GONE);
            return;
        }
        List<MediaListener.Note> notes = MediaListener.notes(ctx);
        clearBtn.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
        if (notes.isEmpty()) {
            addNote("No notifications", "You're all caught up.");
            return;
        }
        for (final MediaListener.Note n : notes) {
            View card = noteCard(n);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            lp.leftMargin = dp(2);
            lp.rightMargin = dp(2);
            notesBox.addView(card, lp);
        }
    }

    private void addNote(String title, String body) {
        TextView t = new TextView(ctx);
        t.setText(title + "\n" + body);
        t.setTextColor(th.dim);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(dp(4), dp(4), dp(4), dp(8));
        notesBox.addView(t);
    }

    private View noteCard(final MediaListener.Note n) {
        final LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setFocusable(true);
        card.setClickable(true);
        final GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, Theme.grad(th.pill));
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);

        // The app's own small icon, on a round coloured chip so a white icon is always visible.
        ImageView icon = new ImageView(ctx);
        GradientDrawable chip = new GradientDrawable();
        chip.setShape(GradientDrawable.OVAL);
        chip.setColor(th.accent);
        icon.setBackground(chip);
        icon.setPadding(dp(6), dp(6), dp(6), dp(6));
        if (n.icon != null) {
            icon.setImageDrawable(n.icon);
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(28), dp(28));
        ilp.rightMargin = dp(12);
        card.addView(icon, ilp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        final TextView app = new TextView(ctx);
        app.setText(n.label);
        app.setTextColor(th.dim);
        app.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        app.setSingleLine(true);
        col.addView(app);
        final TextView head = new TextView(ctx);
        head.setText(n.title == null ? "" : n.title);
        head.setTextColor(th.text);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.setSingleLine(true);
        head.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(head);
        final TextView body = new TextView(ctx);
        body.setText(n.text == null ? "" : n.text);
        body.setTextColor(th.dim);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        body.setMaxLines(2);
        body.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(body);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                bg.setColors(new int[]{th.focusBg, th.focusBg});
            } else {
                bg.setColors(Theme.grad(th.pill));
            }
            app.setTextColor(focused ? th.focusText : th.dim);
            head.setTextColor(focused ? th.focusText : th.text);
            body.setTextColor(focused ? th.focusText : th.dim);
        });
        card.setOnClickListener(v -> {
            if (n.intent != null) {
                try {
                    n.intent.send();
                } catch (Exception e) {
                    Toast.makeText(ctx, "Can't open that", Toast.LENGTH_SHORT).show();
                }
                close.run();
            }
        });
        card.setOnLongClickListener(v -> {
            MediaListener.dismiss(n.key);
            notesBox.removeView(card);
            if (notesBox.getChildCount() == 0) {
                fillNotes();
            }
            return true;
        });
        return card;
    }

    /** One round button with a label under it. */
    private View orb(String label, String status, final int icon, final boolean on) {
        final LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setPadding(0, dp(6), 0, dp(6));
        cell.setClipChildren(false);
        cell.setClipToPadding(false);

        final View disc = new View(ctx) {
            @Override
            protected void onDraw(Canvas cv) {
                float w = getWidth();
                float h = getHeight();
                boolean f = cell.isFocused();
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.FILL);
                int fill = on ? th.accent : th.pill;
                p.setColor(fill);
                float r = Math.min(w, h) / 2 - dp(4);
                cv.drawCircle(w / 2, h / 2, r, p);
                if (f) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(3));
                    p.setColor(th.ring);
                    cv.drawCircle(w / 2, h / 2, r + dp(2), p);
                }
                int ink = Theme.onColor(fill);
                Icons.draw(cv, icon, w / 2, h / 2, r * 2f * 0.9f, ink, fill);
            }
        };
        cell.addView(disc, new LinearLayout.LayoutParams(dp(44), dp(44)));

        final TextView name = new TextView(ctx);
        name.setText(label);
        name.setTextColor(th.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, dp(4), 0, 0);
        cell.addView(name);

        // Always present (blank when there is no status) so every label lines up.
        TextView st = new TextView(ctx);
        st.setText(status.isEmpty() ? " " : status);
        st.setTextColor(th.dim);
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        st.setGravity(Gravity.CENTER);
        st.setSingleLine(true);
        cell.addView(st);

        cell.setOnFocusChangeListener((v, focused) -> {
            disc.invalidate();

        });
        return cell;
    }

    private TextView pill(String text) {
        final TextView t = new TextView(ctx);
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
        t.setOnFocusChangeListener((View v, boolean focused) -> {
            if (focused) {
                bg.setColors(new int[]{th.focusBg, th.focusBg});
            } else {
                bg.setColors(Theme.grad(th.pill));
            }
            t.setTextColor(focused ? th.focusText : th.text);
        });
        return t;
    }

    private int dp(int value) {
        return (int) (value * density + 0.5f);
    }
}
