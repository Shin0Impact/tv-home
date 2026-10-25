package app.tvhome;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** The TV's physical inputs (HDMI 1, 2, 3, ...), for the Source list in the quick settings. */
final class Inputs {

    private Inputs() {
    }

    /** Fills ids and names with the physical inputs. Works from any context (also the overlay). */
    static void list(Context ctx, List<String> ids, List<String> names) {
        try {
            TvInputManager tv = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            for (TvInputInfo info : new ArrayList<>(tv.getTvInputList())) {
                if (!info.isPassthroughInput()) {
                    continue; // only the physical inputs, not the tuner or app channels
                }
                CharSequence label = info.loadCustomLabel(ctx);
                if (label == null || label.length() == 0) {
                    label = info.loadLabel(ctx);
                }
                ids.add(info.getId());
                names.add(label == null ? info.getId() : label.toString());
            }
        } catch (Exception ignored) {
            // leave the lists as they are
        }
    }

    static void switchTo(Context ctx, String id) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(ctx, "Can't switch to that input", Toast.LENGTH_SHORT).show();
        }
    }
}
