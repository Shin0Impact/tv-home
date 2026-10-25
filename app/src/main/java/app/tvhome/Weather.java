package app.tvhome;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

/**
 * The little weather line in the top bar. The city is typed once in TV Home settings. Uses Open-Meteo
 * (free, no account, no key): one lookup finds the city's coordinates, another reads the temperature.
 * Only the city name you typed is sent.
 */
final class Weather {

    private static final long MAX_AGE = 30 * 60 * 1000L;

    private Weather() {
    }

    static String city(SharedPreferences p) {
        return p.getString("city", "").trim();
    }

    static boolean fahrenheit(SharedPreferences p) {
        return "f".equals(p.getString("units", "c"));
    }

    /** The last text we got for the current city and units, or "". */
    static String cached(SharedPreferences p) {
        if (city(p).isEmpty() || !key(p).equals(p.getString("wx_for", ""))) {
            return "";
        }
        return p.getString("wx_text", "");
    }

    private static String key(SharedPreferences p) {
        return city(p) + "|" + (fahrenheit(p) ? "f" : "c");
    }

    /** Fetches in the background if the saved value is old; calls done (on that thread) when it changed. */
    static void refresh(final SharedPreferences p, final Runnable done) {
        if (city(p).isEmpty()) {
            return;
        }
        if (key(p).equals(p.getString("wx_for", ""))
                && System.currentTimeMillis() - p.getLong("wx_at", 0) < MAX_AGE) {
            return;
        }
        new Thread(() -> {
            try {
                String city = city(p);
                String geo = p.getString("wx_geo", "");
                String[] g = geo.split("\\|");
                double lat;
                double lon;
                String name;
                if (g.length == 4 && g[0].equals(city)) {
                    lat = Double.parseDouble(g[1]);
                    lon = Double.parseDouble(g[2]);
                    name = g[3];
                } else {
                    String json = Net.text("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                            + URLEncoder.encode(city, "UTF-8"));
                    if (json == null) {
                        return;
                    }
                    JSONArray res = new JSONObject(json).optJSONArray("results");
                    if (res == null || res.length() == 0) {
                        p.edit().putString("wx_text", "City not found").putString("wx_for", key(p))
                                .putLong("wx_at", System.currentTimeMillis()).apply();
                        done.run();
                        return;
                    }
                    JSONObject r = res.getJSONObject(0);
                    lat = r.getDouble("latitude");
                    lon = r.getDouble("longitude");
                    name = r.optString("name", city);
                    p.edit().putString("wx_geo", city + "|" + lat + "|" + lon + "|" + name.replace("|", " ")).apply();
                }
                String json = Net.text("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                        + "&longitude=" + lon + "&current=temperature_2m,weather_code"
                        + "&temperature_unit=" + (fahrenheit(p) ? "fahrenheit" : "celsius"));
                if (json == null) {
                    return;
                }
                JSONObject cur = new JSONObject(json).getJSONObject("current");
                long t = Math.round(cur.getDouble("temperature_2m"));
                String text = name + "  " + t + "°  " + describe(cur.optInt("weather_code", -1));
                p.edit().putString("wx_text", text).putString("wx_for", key(p))
                        .putLong("wx_at", System.currentTimeMillis()).apply();
                done.run();
            } catch (Exception ignored) {
                // no weather this time; the old text stays
            }
        }).start();
    }

    private static String describe(int code) {
        if (code == 0) {
            return "Clear";
        }
        if (code <= 2) {
            return "Partly cloudy";
        }
        if (code == 3) {
            return "Cloudy";
        }
        if (code == 45 || code == 48) {
            return "Fog";
        }
        if (code >= 51 && code <= 57) {
            return "Drizzle";
        }
        if (code >= 61 && code <= 67) {
            return "Rain";
        }
        if (code >= 71 && code <= 77) {
            return "Snow";
        }
        if (code >= 80 && code <= 82) {
            return "Showers";
        }
        if (code == 85 || code == 86) {
            return "Snow showers";
        }
        if (code >= 95) {
            return "Thunderstorm";
        }
        return "";
    }
}
