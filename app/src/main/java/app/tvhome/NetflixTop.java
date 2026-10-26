package app.tvhome;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Popular on Netflix" cards for the Netflix tile. The list comes from TMDB (themoviedb.org),
 * filtered to titles Netflix streams in the user's country. This product uses the TMDB API but is
 * not endorsed or certified by TMDB. The API key is never part of the app or the repository: it is
 * saved on the TV (preference "tmdb_key") by set-tmdb.ps1.
 */
final class NetflixTop {

    static final String PKG = "com.netflix.ninja";
    static final long REFRESH_MS = 12L * 3600 * 1000;
    private static final int COUNT = 10;
    /** Bumped when the way the list is chosen changes, so an older saved list is not shown. */
    private static final int VERSION = 2;
    private static final String IMG = "https://image.tmdb.org/t/p/w780";

    private NetflixTop() {
    }

    static List<JSONObject> cached(SharedPreferences p) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("nf_json", "[]"));
            for (int i = 0; i < a.length(); i++) {
                out.add(a.getJSONObject(i));
            }
        } catch (Exception ignored) {
            // nothing cached
        }
        return out;
    }

    static boolean fresh(SharedPreferences p) {
        return p.getInt("nf_ver", 0) == VERSION
                && !p.getString("nf_json", "[]").equals("[]")
                && System.currentTimeMillis() - p.getLong("nf_at", 0) < REFRESH_MS
                && p.getString("nf_cc", "").equals(country(p, false));
    }

    /**
     * The country whose Netflix catalogue to ask about: the country of the weather city, else the
     * TV's own region, else US. The lookup of the city's country happens at most once per city.
     */
    static String country(SharedPreferences p, boolean mayLookUp) {
        String city = Weather.city(p);
        if (!city.isEmpty()) {
            if (city.equals(p.getString("wx_cc_for", ""))) {
                String cc = p.getString("wx_cc", "");
                if (!cc.isEmpty()) {
                    return cc;
                }
            } else if (mayLookUp) {
                try {
                    String json = Net.text("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                            + URLEncoder.encode(city, "UTF-8"));
                    JSONArray res = json == null ? null : new JSONObject(json).optJSONArray("results");
                    if (res != null && res.length() > 0) {
                        String cc = res.getJSONObject(0).optString("country_code", "");
                        if (!cc.isEmpty()) {
                            p.edit().putString("wx_cc", cc).putString("wx_cc_for", city).apply();
                            return cc;
                        }
                    }
                } catch (Exception ignored) {
                    // fall through to the TV's region
                }
            }
        }
        String region = Locale.getDefault().getCountry();
        return region.isEmpty() ? "US" : region;
    }

    /** Downloads the list (blocking) and saves it. Returns a short status for diagnosis. */
    static String refresh(SharedPreferences p) {
        String key = p.getString("tmdb_key", "");
        if (key.isEmpty()) {
            return "no TMDB key set";
        }
        try {
            String cc = country(p, true);
            String lang = Locale.getDefault().toLanguageTag();
            List<JSONObject> movies = fetch("movie", key, cc, lang);
            List<JSONObject> shows = fetch("tv", key, cc, lang);
            JSONArray out = new JSONArray();
            for (int i = 0; out.length() < COUNT && (i < movies.size() || i < shows.size()); i++) {
                if (i < movies.size() && out.length() < COUNT) {
                    out.put(movies.get(i));
                }
                if (i < shows.size() && out.length() < COUNT) {
                    out.put(shows.get(i));
                }
            }
            if (out.length() == 0) {
                return "TMDB gave no titles for " + cc;
            }
            p.edit().putString("nf_json", out.toString()).putLong("nf_at", System.currentTimeMillis())
                    .putString("nf_cc", cc).putInt("nf_ver", VERSION).apply();
            return out.length() + " titles for " + cc;
        } catch (Exception e) {
            return "failed: " + e.getClass().getSimpleName();
        }
    }

    private static List<JSONObject> fetch(String type, String key, String cc, String lang) throws Exception {
        // New and popular: released (or, for series, aired) in roughly the last six months.
        java.util.Calendar since = java.util.Calendar.getInstance();
        since.add(java.util.Calendar.DAY_OF_YEAR, -180);
        String from = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(since.getTime());
        String url = "https://api.themoviedb.org/3/discover/" + type + "?api_key=" + URLEncoder.encode(key, "UTF-8")
                + "&with_watch_providers=8&watch_monetization_types=flatrate&watch_region=" + cc
                + ("movie".equals(type) ? "&primary_release_date.gte=" : "&air_date.gte=") + from
                + "&sort_by=popularity.desc&include_adult=false&language=" + lang;
        String json = Net.text(url);
        List<JSONObject> out = new ArrayList<>();
        if (json == null) {
            return out;
        }
        JSONArray res = new JSONObject(json).optJSONArray("results");
        if (res == null) {
            return out;
        }
        for (int i = 0; i < res.length() && out.size() < COUNT; i++) {
            JSONObject r = res.getJSONObject(i);
            String back = r.optString("backdrop_path", "");
            if (back.isEmpty() || "null".equals(back)) {
                continue; // a card needs a wide picture
            }
            String title = r.optString("movie".equals(type) ? "title" : "name", "");
            if (title.isEmpty()) {
                continue;
            }
            String date = r.optString("movie".equals(type) ? "release_date" : "first_air_date", "");
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("kind", "movie".equals(type) ? "Movie" : "Series");
            o.put("year", date.length() >= 4 ? date.substring(0, 4) : "");
            o.put("art", IMG + back);
            out.add(o);
        }
        return out;
    }

    /** Opens Netflix's search for the title, which lists that title first. */
    static Intent openIntent(String title) {
        try {
            return new Intent(Intent.ACTION_VIEW,
                    Uri.parse("nflx://www.netflix.com/search?q=" + URLEncoder.encode(title, "UTF-8")))
                    .setPackage(PKG);
        } catch (Exception e) {
            return new Intent(Intent.ACTION_VIEW, Uri.parse("nflx://www.netflix.com/")).setPackage(PKG);
        }
    }
}
