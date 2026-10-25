package app.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Wallpapers. The preference "wall" is "none", "b0".."b5" (a built-in design drawn in code) or
 * "d:bing" / "d:nasa" / "d:wiki" / "d:picsum" (a picture of the day downloaded once a day and kept
 * in the app's cache). OLED always stays pure black.
 */
final class Wallpaper {

    static final String[] BUILTIN_NAMES = {"Aurora", "Dusk", "Ocean", "Forest", "Ember", "Mist"};

    static final String[] SRC_IDS = {"bing", "nasa", "wiki", "picsum"};
    static final String[] SRC_NAMES = {"Bing daily", "NASA picture of the day", "Wikipedia picture of the day",
            "Random photo"};

    /** Dark designs: three gradient colours, then two soft glows. */
    private static final int[][] DARK = {
            {0xFF08122A, 0xFF16264F, 0xFF0A1A2E, 0x665B6CFF, 0x4419C2B0},
            {0xFF1A0F2E, 0xFF3B1F4F, 0xFF150C22, 0x66FF6FA5, 0x445B6CFF},
            {0xFF04141F, 0xFF0B3347, 0xFF06202B, 0x5519B8D4, 0x3340E0A0},
            {0xFF0A1A12, 0xFF174030, 0xFF0B1F18, 0x5540C070, 0x33B0D060},
            {0xFF1F0E0A, 0xFF4A2014, 0xFF1A0C08, 0x66FF8A3D, 0x44FF4D6D},
            {0xFF15171B, 0xFF272B33, 0xFF121418, 0x338FA3C7, 0x22C79FB5}};

    /** Light designs. */
    private static final int[][] LIGHT = {
            {0xFFDCE6FA, 0xFFEDE8FA, 0xFFD8F0F0, 0x805B6CFF, 0x6019C2B0},
            {0xFFF6E3EE, 0xFFE9DDF5, 0xFFFBE9DC, 0x80FF6FA5, 0x605B6CFF},
            {0xFFD9F0F7, 0xFFE3F4EF, 0xFFD2E7F5, 0x8019B8D4, 0x6040E0A0},
            {0xFFE2F3E6, 0xFFEFF5DD, 0xFFD9EEE2, 0x8040C070, 0x60B0D060},
            {0xFFFBE8DA, 0xFFF9E1E4, 0xFFFFF0D8, 0x80FF8A3D, 0x60FF4D6D},
            {0xFFE6E8EC, 0xFFF1F2F5, 0xFFE0E3E8, 0x668FA3C7, 0x44C79FB5}};

    private Wallpaper() {
    }

    /** The five colours of built-in design idx (0..5), for a light or dark theme; null if idx is bad. */
    static int[] builtin(int idx, boolean light) {
        if (idx < 0 || idx >= DARK.length) {
            return null;
        }
        return light ? LIGHT[idx] : DARK[idx];
    }

    static int builtinIndex(String wall) {
        if (wall != null && wall.length() == 2 && wall.charAt(0) == 'b') {
            return wall.charAt(1) - '0';
        }
        return -1;
    }

    static String source(String wall) {
        return wall != null && wall.startsWith("d:") ? wall.substring(2) : null;
    }

    static String name(String wall) {
        int b = builtinIndex(wall);
        if (b >= 0 && b < BUILTIN_NAMES.length) {
            return BUILTIN_NAMES[b];
        }
        String s = source(wall);
        for (int i = 0; s != null && i < SRC_IDS.length; i++) {
            if (SRC_IDS[i].equals(s)) {
                return SRC_NAMES[i];
            }
        }
        return "Off";
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private static File dir(Context ctx) {
        return new File(ctx.getCacheDir(), "wall");
    }

    /** Today's picture for this source if we already have it, else the newest older one, else null. */
    static File latest(Context ctx, String src) {
        File today = new File(dir(ctx), src + "-" + today() + ".jpg");
        if (today.exists()) {
            return today;
        }
        File[] all = dir(ctx).listFiles();
        File best = null;
        if (all != null) {
            for (File f : all) {
                if (f.getName().startsWith(src + "-") && f.getName().endsWith(".jpg")
                        && (best == null || f.getName().compareTo(best.getName()) > 0)) {
                    best = f;
                }
            }
        }
        return best;
    }

    /** Downloads today's picture if it is missing (call off the main thread). Returns the file to show. */
    static File ensure(Context ctx, String src) {
        File want = new File(dir(ctx), src + "-" + today() + ".jpg");
        if (want.exists()) {
            return want;
        }
        String url = pictureUrl(src);
        if (url != null && Net.download(url, want, 14 * 1024 * 1024)) {
            File[] all = dir(ctx).listFiles(); // keep only the newest
            if (all != null) {
                for (File f : all) {
                    if (!f.equals(want)) {
                        f.delete(); // yesterday's picture, and any left from other sources
                    }
                }
            }
            return want;
        }
        return latest(ctx, src);
    }

    private static String pictureUrl(String src) {
        try {
            if ("bing".equals(src)) {
                String json = Net.text("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US");
                if (json == null) {
                    return null;
                }
                String u = new JSONObject(json).getJSONArray("images").getJSONObject(0).getString("url");
                return u.startsWith("http") ? u : "https://www.bing.com" + u;
            }
            if ("nasa".equals(src)) {
                String json = Net.text("https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY");
                if (json == null) {
                    return null;
                }
                JSONObject o = new JSONObject(json);
                return "image".equals(o.optString("media_type")) ? o.optString("url", null) : null;
            }
            if ("wiki".equals(src)) {
                String d = new SimpleDateFormat("yyyy/MM/dd", Locale.US).format(new Date());
                String json = Net.text("https://en.wikipedia.org/api/rest_v1/feed/featured/" + d);
                if (json == null) {
                    return null;
                }
                JSONObject img = new JSONObject(json).getJSONObject("image");
                JSONObject big = img.optJSONObject("image");
                return big != null ? big.getString("source") : img.getJSONObject("thumbnail").getString("source");
            }
            if ("picsum".equals(src)) {
                return "https://picsum.photos/seed/" + today() + "/1920/1080";
            }
        } catch (Exception ignored) {
            // no picture today
        }
        return null;
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
    }
}
