package app.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The side panel the remote's Settings / notifications button opens. The TV's system sends the
 * action below when that button is pressed; Google's launcher used to answer it, and now this does.
 * Pressing the button again, Back or Home closes it.
 */
public class PanelActivity extends Activity {

    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        final String[] names = {
                "Network & Internet", "Display", "Sound", "Bluetooth", "Keyboard",
                "Screen saver", "Date & time", "Accounts", "All settings"};
        final String[] actions = {
                Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS,
                Settings.ACTION_SOUND_SETTINGS, Settings.ACTION_BLUETOOTH_SETTINGS,
                Settings.ACTION_INPUT_METHOD_SETTINGS, Settings.ACTION_DREAM_SETTINGS,
                Settings.ACTION_DATE_SETTINGS, Settings.ACTION_SYNC_SETTINGS,
                Settings.ACTION_SETTINGS};

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
            final String action = actions[i];
            TextView row = pill(names[i]);
            row.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show();
                }
                finish();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            list.addView(row, lp);
        }
        sv.addView(list);
        panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView diag = new TextView(this);
        android.content.SharedPreferences sp = getSharedPreferences("home", MODE_PRIVATE);
        StringBuilder info = new StringBuilder(sp.getString("epgdebug", "Apps' rows: not read yet"));
        for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getKey().startsWith("mddebug_") && e.getValue() instanceof String) {
                info.append("\n\n").append((String) e.getValue());
            }
        }
        diag.setText(info.toString());
        diag.setTextColor(0xFF9AA3B8);
        diag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        diag.setPadding(dp(4), dp(12), 0, 0);
        panel.addView(diag);

        setContentView(panel);
        Window window = getWindow();
        window.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        window.setLayout(dp(380), ViewGroup.LayoutParams.MATCH_PARENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.55f);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // The button was pressed again while the panel is open: toggle it closed.
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP
                && (e.getKeyCode() == KeyEvent.KEYCODE_SETTINGS
                || e.getKeyCode() == KeyEvent.KEYCODE_NOTIFICATION)) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private TextView pill(String text) {
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTextColor(Color.WHITE);
        t.setPadding(dp(22), dp(10), dp(22), dp(10));
        t.setFocusable(true);
        t.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(0xFF232B42);
        t.setBackground(bg);
        t.setOnFocusChangeListener((View v, boolean focused) -> {
            bg.setColor(focused ? Color.WHITE : 0xFF232B42);
            t.setTextColor(focused ? 0xFF0A0E1A : Color.WHITE);
        });
        return t;
    }

    private int dp(int value) {
        return (int) (value * density + 0.5f);
    }
}
