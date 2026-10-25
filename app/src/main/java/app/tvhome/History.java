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
            if (pkg == null) {
                return;
            }
            SharedPreferences prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
            String title = md == null ? null : firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                    md.getString(MediaMetadata.METADATA_KEY_TITLE));
            if (title == null) {
                // Apps like Netflix never say what is playing; remember only that something was
                // paused or playing, and how far in, so the card survives the app closing.
                android.media.session.PlaybackState st = c.getPlaybackState();
                if (st != null && (st.getState() == android.media.session.PlaybackState.STATE_PAUSED
                        || st.getState() == android.media.session.PlaybackState.STATE_PLAYING)) {
                    prefs.edit().putString("resume_" + pkg,
                            st.getPosition() + "|" + System.currentTimeMillis()).apply();
                }
                return;
            }

            String detail = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                    md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    md.getString(MediaMetadata.METADATA_KEY_ALBUM));
            String art = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_ART_URI),
                    md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                    md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI));
            // Work out a link to the exact video from whatever the app sends, whatever app it is:
            // a playable address, a YouTube link or id anywhere in the metadata, or a YouTube
            // thumbnail address together with the media id.
            String link = findLink(md, art);
            if (art == null && link != null && link.contains("youtube.com/watch?v=")) {
                art = "https://i.ytimg.com/vi/" + link.substring(link.indexOf("v=") + 2) + "/hqdefault.jpg";
            }
            String bmp = "none";
            if (art == null) {
                Bitmap b = firstBitmap(md.getBitmap(MediaMetadata.METADATA_KEY_ART),
                        md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
                        md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON));
                if (b != null) {
                    bmp = b.getWidth() + "x" + b.getHeight();
                    art = saveArt(ctx, b, pkg, title);
                }
            }
            storeDebug(prefs, pkg, md);
            prefs.edit().putString("mddebug_" + pkg,
                    prefs.getString("mddebug_" + pkg, "") + "\n>> picture: bitmap " + bmp
                            + ", saved " + (art == null ? "NO" : "yes")).apply();

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
        StringBuilder sb = new StringBuilder(pkg).append(" reports:");
        for (String key : md.keySet()) {
            String v = md.getString(key);
            String shortKey = key.substring(key.lastIndexOf('.') + 1);
            sb.append("\n").append(shortKey).append(" = ")
                    .append(v == null ? "(not text)" : v.length() > 40 ? v.substring(0, 40) + "..." : v);
        }
        prefs.edit().putString("mddebug_" + pkg, sb.toString()).apply();
    }

    private static final Pattern HTTP_LINK = Pattern.compile("^https?://\\S+$");

    /** The best link to this exact item that the metadata gives away, or null. */
    private static String findLink(MediaMetadata md, String art) {
        String vid = findVideoId(md);
        boolean ytArt = art != null && (art.contains("ytimg.com") || art.contains("youtube.com"));
        if (vid != null && (ytArt || hasYoutubeText(md))) {
            return "https://www.youtube.com/watch?v=" + vid;
        }
        String uri = md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
        if (uri != null && HTTP_LINK.matcher(uri).matches() && !uri.contains("googlevideo.com")) {
            return uri;
        }
        return null;
    }

    private static boolean hasYoutubeText(MediaMetadata md) {
        for (String key : md.keySet()) {
            String v = md.getString(key);
            if (v != null && (v.contains("youtube.com") || v.contains("youtu.be") || v.contains("ytimg.com"))) {
                return true;
            }
        }
        return false;
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

    /** True when the picture is (nearly) one flat colour, such as the black frame apps send while paused. */
    private static boolean isBlank(Bitmap b) {
        int min = 255;
        int max = 0;
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 16; x++) {
                int p = b.getPixel(Math.min(b.getWidth() - 1, x * b.getWidth() / 16 + b.getWidth() / 32),
                        Math.min(b.getHeight() - 1, y * b.getHeight() / 9 + b.getHeight() / 18));
                int lum = (((p >> 16) & 0xFF) * 3 + ((p >> 8) & 0xFF) * 6 + (p & 0xFF)) / 10;
                min = Math.min(min, lum);
                max = Math.max(max, lum);
            }
        }
        return max - min < 14;
    }

    private static String saveArt(Context ctx, Bitmap b, String pkg, String title) {
        try {
            boolean blank = false;
            try {
                blank = isBlank(b);
            } catch (Exception ignored) {
                // cannot sample this bitmap: assume it is fine
            }
            if (blank) {
                // Keep whatever good picture was saved earlier for this title.
                File old = new File(new File(ctx.getCacheDir(), "art"),
                        Integer.toHexString((pkg + "|" + title).hashCode()) + ".jpg");
                return old.exists() ? Uri.fromFile(old).toString() : null;
            }
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
