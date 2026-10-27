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
    /** How many titles of each kind are fetched, to choose the ten cards from. */
    private static final int CANDIDATES = 20;
    /** Bumped when the way the list is chosen changes, so an older saved list is not shown. */
    private static final int VERSION = 4;
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
            JSONArray out = rank(p, movies, shows);
            if (out.length() == 0) {
                return "TMDB gave no titles for " + cc;
            }
            // Netflix's own page for each title, so OK can open the show itself (remembered per title).
            int linked = 0;
            JSONObject urls;
            try {
                urls = new JSONObject(p.getString("nf_urls", "{}"));
            } catch (Exception e) {
                urls = new JSONObject();
            }
            for (int i = 0; i < out.length(); i++) {
                JSONObject o = out.getJSONObject(i);
                String id = o.optString("kind") + ":" + o.optInt("id", 0) + ":" + cc;
                String link = urls.optString(id, "");
                if (link.isEmpty()) {
                    link = netflixUrl(o.optString("title"), o.optString("kind"), o.optString("year"), cc);
                    if (!link.isEmpty()) {
                        urls.put(id, link);
                    }
                }
                o.put("url", link);
                if (!link.isEmpty()) {
                    linked++;
                }
            }
            p.edit().putString("nf_urls", urls.toString()).apply();
            p.edit().putString("nf_json", out.toString()).putLong("nf_at", System.currentTimeMillis())
                    .putString("nf_cc", cc).putInt("nf_ver", VERSION).apply();
            return out.length() + " titles for " + cc + ", " + linked + " with a Netflix link";
        } catch (Exception e) {
            return "failed: " + e.getClass().getSimpleName();
        }
    }

    // ------------------------------------------------------------ learning from what you open

    /**
     * What you opened lately: how often each genre and each kind (movie / series) came up, and the
     * titles themselves. Kept only on the TV, in the preference "nf_taste".
     */
    private static JSONObject taste(SharedPreferences p) {
        try {
            return new JSONObject(p.getString("nf_taste", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Remembers that this card was opened, and makes the next visit to the Netflix tile reload the list. */
    static void learn(SharedPreferences p, JSONObject title) {
        try {
            JSONObject t = taste(p);
            JSONObject g = t.optJSONObject("g") == null ? new JSONObject() : t.getJSONObject("g");
            JSONObject k = t.optJSONObject("k") == null ? new JSONObject() : t.getJSONObject("k");
            JSONArray seen = t.optJSONArray("seen") == null ? new JSONArray() : t.getJSONArray("seen");
            JSONArray genres = title.optJSONArray("genres");
            for (int i = 0; genres != null && i < genres.length(); i++) {
                String id = String.valueOf(genres.getInt(i));
                g.put(id, g.optInt(id, 0) + 1);
            }
            String kind = title.optString("kind");
            k.put(kind, k.optInt(kind, 0) + 1);
            String mark = kind + ":" + title.optInt("id", 0);
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, seen.length() - 39); i < seen.length(); i++) {
                if (!mark.equals(seen.getString(i))) {
                    keep.put(seen.getString(i));
                }
            }
            keep.put(mark);
            // Old habits fade: when the counts get large, halve them.
            int total = 0;
            java.util.Iterator<String> it = g.keys();
            while (it.hasNext()) {
                total += g.optInt(it.next(), 0);
            }
            if (total > 60) {
                JSONObject half = new JSONObject();
                it = g.keys();
                while (it.hasNext()) {
                    String id = it.next();
                    int v = g.optInt(id, 0) / 2;
                    if (v > 0) {
                        half.put(id, v);
                    }
                }
                g = half;
            }
            t.put("g", g).put("k", k).put("seen", keep);
            p.edit().putString("nf_taste", t.toString()).remove("nf_at").apply();
        } catch (Exception ignored) {
            // no learning this time
        }
    }

    static void forgetTaste(SharedPreferences p) {
        p.edit().remove("nf_taste").remove("nf_at").apply();
    }

    /**
     * Picks the ten cards: TMDB's popularity order, nudged towards the genres and the kind of title
     * you open most. Titles you already opened are left out.
     */
    private static JSONArray rank(SharedPreferences p, List<JSONObject> movies, List<JSONObject> shows)
            throws Exception {
        JSONObject t = taste(p);
        JSONObject g = t.optJSONObject("g") == null ? new JSONObject() : t.getJSONObject("g");
        JSONObject k = t.optJSONObject("k") == null ? new JSONObject() : t.getJSONObject("k");
        JSONArray seenArr = t.optJSONArray("seen") == null ? new JSONArray() : t.getJSONArray("seen");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < seenArr.length(); i++) {
            seen.add(seenArr.getString(i));
        }
        int maxG = 1;
        java.util.Iterator<String> it = g.keys();
        while (it.hasNext()) {
            maxG = Math.max(maxG, g.optInt(it.next(), 0));
        }
        int maxK = 1;
        it = k.keys();
        while (it.hasNext()) {
            maxK = Math.max(maxK, k.optInt(it.next(), 0));
        }
        final List<JSONObject> all = new ArrayList<>();
        final java.util.Map<JSONObject, Double> score = new java.util.HashMap<>();
        for (int pass = 0; pass < 2; pass++) {
            List<JSONObject> list = pass == 0 ? movies : shows;
            for (int i = 0; i < list.size(); i++) {
                JSONObject o = list.get(i);
                if (seen.contains(o.optString("kind") + ":" + o.optInt("id", 0))) {
                    continue;
                }
                double base = 1.0 - (double) i / Math.max(1, list.size());
                double affinity = 0;
                JSONArray genres = o.optJSONArray("genres");
                if (genres != null && genres.length() > 0) {
                    double sum = 0;
                    for (int j = 0; j < genres.length(); j++) {
                        sum += (double) g.optInt(String.valueOf(genres.getInt(j)), 0) / maxG;
                    }
                    affinity = sum / genres.length();
                }
                double kindPull = (double) k.optInt(o.optString("kind"), 0) / maxK;
                score.put(o, base + 0.9 * affinity + 0.3 * kindPull);
                all.add(o);
            }
        }
        java.util.Collections.sort(all, (a, b) -> Double.compare(score.get(b), score.get(a)));
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.size() && out.length() < COUNT; i++) {
            out.put(all.get(i));
        }
        return out;
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
        for (int i = 0; i < res.length() && out.size() < CANDIDATES; i++) {
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
            o.put("id", r.optInt("id", 0));
            JSONArray genres = r.optJSONArray("genre_ids");
            o.put("genres", genres == null ? new JSONArray() : genres);
            out.add(o);
        }
        return out;
    }

    /**
     * Asks JustWatch (an unofficial lookup that may change or stop working) for Netflix's own page
     * for a title in this country. Returns "" when nothing matches; the card then just opens Netflix.
     */
    private static String netflixUrl(String title, String kind, String year, String cc) {
        try {
            String query = "query Q($f: TitleFilter!, $c: Country!, $l: Language!) { popularTitles(country: $c, "
                    + "filter: $f, first: 5) { edges { node { objectType content(country: $c, language: $l) "
                    + "{ title originalReleaseYear } offers(country: $c, platform: WEB) "
                    + "{ package { shortName } standardWebURL } } } } }";
            JSONObject filter = new JSONObject().put("searchQuery", title);
            JSONObject vars = new JSONObject().put("f", filter).put("c", cc).put("l", "en");
            String body = new JSONObject().put("query", query).put("variables", vars).toString();
            String json = Net.postJson("https://apis.justwatch.com/graphql", body);
            if (json == null) {
                return "";
            }
            JSONArray edges = new JSONObject(json).getJSONObject("data").getJSONObject("popularTitles")
                    .getJSONArray("edges");
            String want = "Movie".equals(kind) ? "MOVIE" : "SHOW";
            int wantYear = year.isEmpty() ? -1 : Integer.parseInt(year);
            for (int i = 0; i < edges.length(); i++) {
                JSONObject node = edges.getJSONObject(i).getJSONObject("node");
                if (!want.equals(node.optString("objectType"))) {
                    continue;
                }
                int y = node.getJSONObject("content").optInt("originalReleaseYear", -1);
                if (wantYear > 0 && y > 0 && Math.abs(y - wantYear) > 1) {
                    continue; // for a series, TMDB's year is the latest season: allow some difference below
                }
                String link = netflixOffer(node);
                if (!link.isEmpty()) {
                    return link;
                }
            }
            // A series whose newest season is recent has an older first year: take the first Netflix match.
            for (int i = 0; i < edges.length(); i++) {
                JSONObject node = edges.getJSONObject(i).getJSONObject("node");
                if (want.equals(node.optString("objectType"))) {
                    String link = netflixOffer(node);
                    if (!link.isEmpty()) {
                        return link;
                    }
                }
            }
        } catch (Exception ignored) {
            // no link for this title
        }
        return "";
    }

    private static String netflixOffer(JSONObject node) {
        JSONArray offers = node.optJSONArray("offers");
        if (offers == null) {
            return "";
        }
        for (int i = 0; i < offers.length(); i++) {
            JSONObject o = offers.optJSONObject(i);
            if (o != null && "nfx".equals(o.optJSONObject("package") == null ? ""
                    : o.optJSONObject("package").optString("shortName"))) {
                String u = o.optString("standardWebURL");
                if (u.startsWith("https://www.netflix.com/title/")) {
                    return u;
                }
            }
        }
        return "";
    }

    /**
     * Opens the title's Netflix page, skipping Netflix's profile picker (the source extra is what
     * makes Netflix treat it as a trusted deep link). Without a link, just opens Netflix.
     */
    static Intent openIntent(String url, Intent fallback) {
        if (url == null || !url.startsWith("https://www.netflix.com/title/")) {
            return fallback;
        }
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(PKG).putExtra("source", "30");
    }
}
