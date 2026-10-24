package app.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers what apps have played or paused ("Recently watched"). Called from the background media
 * listener, so it works even while TV Home is not on screen. Everything stays on this TV.
 */
final class History {

    private static final int PER_APP = 10;
    private static final int TOTAL = 80;
    private static final Object LOCK = new Object();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static final Pattern VIDEO_LINK =
            Pattern.compile("(?:[?&]v=|youtu\\.be/|/vi/|/embed/|/shorts/)([A-Za-z0-9_-]{11})");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private History() {
    }

    static void recordAsync(final Context ctx, final MediaController c) {
        IO.execute(() -> record(ctx, c));
    }

    static void record(Context ctx, MediaController c) {
        try {
            MediaMetadata md = c.getMetadata();
            String pkg = c.getPackageName();
            if (md == null || pkg == null) {
                return;
            }
            String title = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                    md.getString(MediaMetadata.METADATA_KEY_TITLE));
            if (title == null) {
                return;
            }
            SharedPreferences prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
            storeDebug(prefs, pkg, md);

            String detail = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                    md.getString(MediaMetadata.METADATA_KEY_ARTIST));
            String art = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_ART_URI),
                    md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                    md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI));
            String link = null;
            if (pkg.contains("youtube")) {
                String vid = findVideoId(md);
                if (vid != null) {
                    link = "https://www.youtube.com/watch?v=" + vid;
                    if (art == null) {
                        art = "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";
                    }
                }
            }
            if (art == null) {
                Bitmap b = firstBitmap(md.getBitmap(MediaMetadata.METADATA_KEY_ART),
                        md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
                        md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON));
                if (b != null) {
                    art = saveArt(ctx, b, pkg, title);
                }
            }

            synchronized (LOCK) {
                JSONArray arr = new JSONArray(prefs.getString("history", "[]"));
                List<JSONObject> items = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    items.add(arr.getJSONObject(i));
                }
                JSONObject old = null;
                for (JSONObject o : items) {
                    if (pkg.equals(o.optString("pkg")) && title.equals(o.optString("title"))) {
                        old = o;
                        break;
                    }
                }
                if (art == null && old != null && !old.optString("art").isEmpty()) {
                    art = old.optString("art");
                }
                if (link == null && old != null && !old.optString("link").isEmpty()) {
                    link = old.optString("link");
                }
                if (old != null && items.indexOf(old) == 0
                        && (art == null ? "" : art).equals(old.optString("art"))
                        && (link == null ? "" : link).equals(old.optString("link"))) {
                    return; // already the newest entry
                }
                if (old != null) {
                    items.remove(old);
                }
                JSONObject entry = new JSONObject();
                entry.put("pkg", pkg);
                entry.put("title", title);
                entry.put("sub", detail == null ? "" : detail);
                entry.put("art", art == null ? "" : art);
                entry.put("link", link == null ? "" : link);
                items.add(0, entry);

                Map<String, Integer> perApp = new HashMap<>();
                JSONArray out = new JSONArray();
                for (JSONObject o : items) {
                    String p = o.optString("pkg");
                    int n = perApp.containsKey(p) ? perApp.get(p) : 0;
                    if (n >= PER_APP || out.length() >= TOTAL) {
                        continue;
                    }
                    perApp.put(p, n + 1);
                    out.put(o);
                }
                prefs.edit().putString("history", out.toString()).apply();
            }
        } catch (Exception ignored) {
            // history is a nicety; never let it break anything
        }
    }

    /** What the app reports, shown at the bottom of the quick settings panel while we tune this. */
    private static void storeDebug(SharedPreferences prefs, String pkg, MediaMetadata md) {
        StringBuilder sb = new StringBuilder(pkg.substring(pkg.lastIndexOf('.') + 1)).append(" reports:");
        for (String key : md.keySet()) {
            String v = md.getString(key);
            String shortKey = key.substring(key.lastIndexOf('.') + 1);
            sb.append("\n").append(shortKey).append(" = ")
                    .append(v == null ? "(not text)" : v.length() > 40 ? v.substring(0, 40) + "..." : v);
        }
        prefs.edit().putString("mddebug_" + pkg, sb.toString()).apply();
    }

    private static String findVideoId(MediaMetadata md) {
        String mediaId = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (mediaId != null && VIDEO_ID.matcher(mediaId).matches()) {
            return mediaId;
        }
        for (String key : md.keySet()) {
            String v = md.getString(key);
            if (v == null) {
                continue;
            }
            Matcher m = VIDEO_LINK.matcher(v);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static String saveArt(Context ctx, Bitmap b, String pkg, String title) {
        try {
            File dir = new File(ctx.getCacheDir(), "art");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File f = new File(dir, Integer.toHexString((pkg + "|" + title).hashCode()) + ".jpg");
            Bitmap scaled = b;
            if (b.getWidth() > 960) {
                scaled = Bitmap.createScaledBitmap(b, 960, Math.max(1, b.getHeight() * 960 / b.getWidth()), true);
            }
            FileOutputStream out = new FileOutputStream(f);
            try {
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out);
            } finally {
                out.close();
            }
            return Uri.fromFile(f).toString();
        } catch (Exception e) {
            return null;
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
}
