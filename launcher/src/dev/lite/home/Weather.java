package dev.lite.home;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Current weather from Open-Meteo (open-meteo.com): free, open source, no account or API key.
 * This is the launcher's only network use, and it is off when the Weather setting is off.
 *
 * Hardening:
 *  - HTTPS only, system CAs only (res/xml/network_security.xml forbids cleartext app-wide)
 *  - fixed hosts, no redirects followed
 *  - responses capped at 64 KB; values range-checked before display
 *  - coordinates rounded to ~10 km before they leave the device
 *  - nothing else is sent: no IDs, no cookies, a generic User-Agent
 */
final class Weather {

    static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, FOG = 3, DRIZZLE = 4, RAIN = 5, SNOW = 6, STORM = 7;
    static final long STALE_MS = 30 * 60 * 1000L;
    private static final int MAX_BYTES = 64 * 1024;

    int code;          // WMO weather code, now
    int dayCode = -1;  // WMO weather code for the day as a whole (the forecast), -1 if unknown
    boolean day;
    double temp, max, min; // °C
    long fetchedAt;

    // ---- display ---------------------------------------------------------------------------

    int kind() {
        if (code == 0) return CLEAR;
        if (code == 1 || code == 2) return PARTLY;
        if (code == 3) return CLOUDY;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 57) return DRIZZLE;
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SNOW;
        if (code >= 95) return STORM;
        return CLOUDY;
    }

    String condition() { return describe(code, day); }

    /** "Rain expected" when the day's forecast is wetter or stormier than right now, else null. */
    String outlook() {
        if (dayCode < 0) return null;
        Weather d = new Weather();
        d.code = dayCode;
        int now = kind(), later = d.kind();
        boolean worse = (later == RAIN || later == DRIZZLE || later == SNOW || later == STORM) && later != now;
        return worse ? describe(dayCode, true) + " expected" : null;
    }

    private static String describe(int code, boolean day) {
        switch (code) {
            case 0: return day ? "Sunny" : "Clear";
            case 1: return day ? "Mostly sunny" : "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: return "Light snow";
            case 73: return "Snow";
            case 75: return "Heavy snow";
            case 77: return "Snow grains";
            case 80: return "Light showers";
            case 81: return "Showers";
            case 82: return "Heavy showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm with hail";
            default: return "Cloudy";
        }
    }

    static String deg(double celsius, boolean fahrenheit) {
        double v = fahrenheit ? celsius * 9 / 5 + 32 : celsius;
        return Math.round(v) + "°";
    }

    boolean stale() { return System.currentTimeMillis() - fetchedAt > STALE_MS; }

    // ---- cache -----------------------------------------------------------------------------

    static Weather cached(SharedPreferences p) {
        String json = p.getString("wx_json", null);
        if (json == null) return null;
        try {
            Weather w = parse(json);
            w.fetchedAt = p.getLong("wx_at", 0);
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- location: from the system time zone, no permission needed --------------------------

    /**
     * Where to get weather for: the city in the TV's time zone (Europe/Warsaw -> Warsaw). Looked up
     * once per time zone and cached. Returns {lat, lon} or null (e.g. time zone "UTC").
     * Call off the main thread: the first call for a time zone does one geocoding request.
     */
    static double[] location(SharedPreferences p) {
        String tz = java.util.TimeZone.getDefault().getID();
        if (tz.equals(p.getString("wx_tz", null)) && p.contains(Prefs.LAT)) {
            return new double[]{Double.parseDouble(p.getString(Prefs.LAT, "0")), Double.parseDouble(p.getString(Prefs.LON, "0"))};
        }
        int slash = tz.lastIndexOf('/');
        if (slash < 0 || tz.startsWith("Etc/")) return null;
        String[] r = geocode(tz.substring(slash + 1).replace('_', ' '));
        if (r == null) return null;
        p.edit().putString("wx_tz", tz).putString(Prefs.CITY, r[0]).putString(Prefs.LAT, r[1]).putString(Prefs.LON, r[2])
                .putString("wx_country", r[3]).remove("wx_json").remove("wx_at").apply();
        return new double[]{Double.parseDouble(r[1]), Double.parseDouble(r[2])};
    }

    /** °F only where it's the everyday unit, judged by the time zone's country (not the UI language). */
    static boolean fahrenheit(SharedPreferences p) {
        String cc = p.getString("wx_country", "");
        return cc.equals("US") || cc.equals("LR") || cc.equals("MM") || cc.equals("BS") || cc.equals("BZ")
                || cc.equals("KY") || cc.equals("PW") || cc.equals("FM") || cc.equals("MH");
    }

    // ---- network (call off the main thread) ------------------------------------------------

    /** Fetches current weather and stores it in the cache. Returns null on any failure. */
    static Weather fetch(SharedPreferences p, double lat, double lon) {
        try {
            String json = get(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code,is_day"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
                    round(lat), round(lon)));
            Weather w = parse(json);
            w.fetchedAt = System.currentTimeMillis();
            p.edit().putString("wx_json", json).putLong("wx_at", w.fetchedAt).apply();
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    /** City search: returns {"Warsaw, Poland", lat, lon, "PL"} or null. */
    static String[] geocode(String name) {
        try {
            String json = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="
                    + URLEncoder.encode(name.trim(), "UTF-8"));
            JSONArray r = new JSONObject(json).optJSONArray("results");
            if (r == null || r.length() == 0) return null;
            JSONObject c = r.getJSONObject(0);
            String place = c.getString("name") + (c.has("country") ? ", " + c.getString("country") : "");
            return new String[]{place, String.valueOf(c.getDouble("latitude")), String.valueOf(c.getDouble("longitude")),
                    c.optString("country_code", "")};
        } catch (Exception e) {
            return null;
        }
    }

    /** ~10 km: enough for city weather, too coarse to locate a home. */
    private static double round(double deg) { return Math.round(deg * 10) / 10.0; }

    private static Weather parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONObject c = o.getJSONObject("current");
        JSONObject d = o.getJSONObject("daily");
        Weather w = new Weather();
        w.temp = c.getDouble("temperature_2m");
        w.code = c.getInt("weather_code");
        w.day = c.optInt("is_day", 1) == 1;
        w.max = d.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = d.getJSONArray("temperature_2m_min").getDouble(0);
        JSONArray dc = d.optJSONArray("weather_code");
        if (dc != null && dc.length() > 0) w.dayCode = dc.getInt(0);
        if (!sane(w.temp) || !sane(w.max) || !sane(w.min) || w.code < 0 || w.code > 99) {
            throw new IllegalArgumentException("implausible weather data");
        }
        return w;
    }

    private static boolean sane(double celsius) {
        return !Double.isNaN(celsius) && celsius > -90 && celsius < 60;
    }

    private static String get(String url) throws Exception {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol())) throw new SecurityException("https only");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "LiteHome");
        if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new SecurityException("response too large");
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
