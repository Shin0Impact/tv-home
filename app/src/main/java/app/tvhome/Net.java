package app.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Tiny HTTPS helpers for the weather and wallpaper downloads. Plain http is refused. */
final class Net {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private Net() {
    }

    /** The page as text, or null. */
    static String text(String url) {
        byte[] data = bytes(url, 2 * 1024 * 1024);
        try {
            return data == null ? null : new String(data, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** Saves the file at url into dest (only if it fits max bytes). */
    static boolean download(String url, File dest, int max) {
        byte[] data = bytes(url, max);
        if (data == null || data.length < 1024) {
            return false;
        }
        try {
            File dir = dest.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File tmp = new File(dest.getPath() + ".part");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            return tmp.renameTo(dest);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] bytes(String url, int max) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            if (!url.startsWith("https://")) {
                return null;
            }
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", UA);
            in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) {
                    return null;
                }
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
                // closing
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
