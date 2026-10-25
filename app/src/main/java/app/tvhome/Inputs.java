package app.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** The TV's inputs (HDMI 1, 2, 3, ...), listed in a small dialog; choosing one switches to it. */
final class Inputs {

    private Inputs() {
    }

    /** Shows the list and returns the dialog (so the caller can close it), or null if there are none. */
    static AlertDialog show(final Activity act) {
        final List<String> ids = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        try {
            TvInputManager tv = (TvInputManager) act.getSystemService(Context.TV_INPUT_SERVICE);
            for (TvInputInfo info : tv.getTvInputList()) {
                if (!info.isPassthroughInput()) {
                    continue; // only the physical inputs, not the tuner or app channels
                }
                CharSequence label = info.loadCustomLabel(act);
                if (label == null || label.length() == 0) {
                    label = info.loadLabel(act);
                }
                ids.add(info.getId());
                names.add(label == null ? info.getId() : label.toString());
            }
        } catch (Exception ignored) {
            // fall through to the message below
        }
        if (names.isEmpty()) {
            Toast.makeText(act, "No inputs found", Toast.LENGTH_SHORT).show();
            return null;
        }
        AlertDialog d = new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Inputs")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    try {
                        Intent go = new Intent(Intent.ACTION_VIEW,
                                TvContract.buildChannelUriForPassthroughInput(ids.get(which)));
                        go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        act.startActivity(go);
                    } catch (Exception e) {
                        Toast.makeText(act, "Can't switch to that input", Toast.LENGTH_SHORT).show();
                    }
                })
                .create();
        d.show();
        return d;
    }
}
