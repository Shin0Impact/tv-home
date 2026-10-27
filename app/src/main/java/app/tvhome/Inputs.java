package app.tvhome;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The TV's physical inputs (HDMI 1, 2, 3, ...), for the Source list in the quick settings. */
final class Inputs {

    private Inputs() {
    }

    /** Fills ids and names with the physical inputs. Works from any context (also the overlay). */
    static void list(Context ctx, List<String> ids, List<String> names) {
        try {
            TvInputManager tv = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            List<TvInputInfo> found = new ArrayList<>();
            for (TvInputInfo info : new ArrayList<>(tv.getTvInputList())) {
                if (info.isPassthroughInput()) { // only the physical inputs, not the tuner or app channels
                    found.add(info);
                }
            }
            // Android hands them over in no particular order: HDMI first, then by port (the number the
            // TV's hardware gave each input, e.g. ".../HW15" is HDMI 1), then by name.
            final Context c = ctx;
            Collections.sort(found, (a, b) -> {
                int ra = a.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1;
                int rb = b.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1;
                if (ra != rb) {
                    return ra - rb;
                }
                int na = hardwareNumber(a.getId());
                int nb = hardwareNumber(b.getId());
                if (na != nb) {
                    return na - nb;
                }
                return String.valueOf(a.loadLabel(c)).compareToIgnoreCase(String.valueOf(b.loadLabel(c)));
            });
            for (TvInputInfo info : found) {
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

    /** The number after "HW" at the end of an input id ("com.tcl.tvinput/.TvPassThroughService/HW15" gives 15). */
    private static int hardwareNumber(String id) {
        int i = id.lastIndexOf("/HW");
        if (i >= 0) {
            try {
                return Integer.parseInt(id.substring(i + 3));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return Integer.MAX_VALUE;
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
