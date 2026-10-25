package app.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Search from the home screen: "Voice search" first, then a typed box that finds your apps and
 * offers to search YouTube or the web for the same words.
 */
final class SearchDialog {

    interface Launcher {
        void open(String pkg);
    }

    private SearchDialog() {
    }

    static AlertDialog show(final Activity act, final List<String> labels, final List<String> pkgs,
                            final Launcher launcher) {
        final float density = act.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * density);
        box.setPadding(p, p / 2, p, 0);

        final EditText input = new EditText(act);
        input.setHint("Type to search apps, YouTube or the web");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final List<String> rows = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(act,
                android.R.layout.simple_list_item_1, rows);
        ListView list = new ListView(act);
        list.setAdapter(adapter);
        box.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (300 * density)));

        final AlertDialog[] self = new AlertDialog[1];
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            String q = input.getText().toString().trim();
            rows.clear();
            actions.clear();
            rows.add("Voice search");
            actions.add(() -> {
                voice(act);
                self[0].dismiss();
            });
            if (!q.isEmpty()) {
                String low = q.toLowerCase();
                for (int i = 0; i < labels.size() && rows.size() < 7; i++) {
                    if (labels.get(i).toLowerCase().contains(low)) {
                        final String pkg = pkgs.get(i);
                        rows.add("Open  " + labels.get(i));
                        actions.add(() -> {
                            self[0].dismiss();
                            launcher.open(pkg);
                        });
                    }
                }
                final String query = q;
                rows.add("Search YouTube for \"" + q + "\"");
                actions.add(() -> {
                    self[0].dismiss();
                    view(act, "https://www.youtube.com/results?search_query=" + Uri.encode(query));
                });
                rows.add("Search the web for \"" + q + "\"");
                actions.add(() -> {
                    self[0].dismiss();
                    view(act, "https://www.google.com/search?q=" + Uri.encode(query));
                });
            }
            adapter.notifyDataSetChanged();
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
        list.setOnItemClickListener((parent, v, pos, id) -> {
            if (pos >= 0 && pos < actions.size()) {
                actions.get(pos).run();
            }
        });
        input.setOnEditorActionListener((v, action, ev) -> {
            if (actions.size() > 1) {
                actions.get(1).run();
            }
            return true;
        });
        rebuild[0].run();

        AlertDialog d = new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Search")
                .setView(box)
                .create();
        self[0] = d;
        d.show();
        return d;
    }

    private static void voice(Activity act) {
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
}
