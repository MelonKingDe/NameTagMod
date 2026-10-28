package de.example.nametaglines;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces <youtube:Name>, <twitch:Name> and <tiktok:Name> in a line with the follower/subscriber
 * count of that channel. All network access runs on a background thread; the render thread only
 * reads the cache.
 *
 * Sources:
 *  - YouTube: reads the public channel page (no key needed, can break); with youtubeApiKey it uses the
 *             official YouTube Data API v3 instead
 *  - Twitch:  public follower counter (DecAPI by default, URL configurable)
 *  - TikTok:  reads the public profile page (URL and regex configurable, may be blocked by TikTok)
 */
public final class SocialService {
    private enum State { LOADING, OK, ERROR }

    private static final class Entry {
        volatile State state = State.LOADING;
        volatile long count = -1;
        volatile String errorText = "n/a";
        volatile long nextRefresh = 0;
        final AtomicBoolean inFlight = new AtomicBoolean(false);
    }

    /** Thrown for problems that should show a short message instead of "n/a". */
    private static final class SocialException extends IOException {
        final String shortText;

        SocialException(String message, String shortText) {
            super(message);
            this.shortText = shortText;
        }
    }

    private record Fetched(int status, String body) {}

    private static final Pattern TOKEN = Pattern.compile("<(youtube|twitch|tiktok):([^>]+)>", Pattern.CASE_INSENSITIVE);
    private static final long ERROR_RETRY_MS = 120_000L;
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nametaglines-social");
        t.setDaemon(true);
        return t;
    });

    private SocialService() {}

    // ---------------- Public API ----------------

    /** Replaces all social tokens in the given line text. Safe to call every frame. */
    public static String expand(String raw) {
        if (raw == null || raw.indexOf('<') < 0) return raw;
        Matcher m = TOKEN.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    display(m.group(1).toLowerCase(Locale.ROOT), cleanName(m.group(2)))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static void refreshAll() {
        CACHE.clear();
    }

    /** Debug helper: fetches once and reports the result or the error. */
    public static void test(String platform, String name, Consumer<String> out) {
        String p = platform.toLowerCase(Locale.ROOT);
        String n = cleanName(name);
        EXEC.execute(() -> {
            try {
                out.accept("Fetching " + p + " '" + n + "' ...");
                long count = fetch(p, n, out);
                out.accept("Result: " + count + " -> shown as " + format(count));
            } catch (Exception e) {
                out.accept("Failed: " + e.getMessage());
            }
        });
    }

    // ---------------- Display ----------------

    private static String display(String platform, String name) {
        if (name.isEmpty()) return "?";
        Entry e = CACHE.computeIfAbsent(platform + "/" + name.toLowerCase(Locale.ROOT), k -> new Entry());
        if (System.currentTimeMillis() >= e.nextRefresh && e.inFlight.compareAndSet(false, true)) {
            EXEC.execute(() -> refresh(e, platform, name));
        }
        return switch (e.state) {
            case LOADING -> "...";
            case ERROR -> e.errorText;
            case OK -> format(e.count);
        };
    }

    private static String format(long count) {
        NametagConfig cfg = NametagConfig.get();
        String number;
        if (cfg.socialCompact && count >= 1_000_000L) {
            number = trim(count / 1_000_000.0) + "M";
        } else if (cfg.socialCompact && count >= 1_000L) {
            number = trim(count / 1_000.0) + "K";
        } else {
            number = NumberFormat.getIntegerInstance(Locale.US).format(count);
        }
        return cfg.socialFormat.replace("{count}", number);
    }

    private static String trim(double v) {
        String s = String.format(Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String cleanName(String raw) {
        String n = raw.trim();
        return n.startsWith("@") ? n.substring(1) : n;
    }

    // ---------------- Background refresh ----------------

    private static void refresh(Entry e, String platform, String name) {
        long ttl = Math.max(1, NametagConfig.get().socialRefreshMinutes) * 60_000L;
        try {
            e.count = fetch(platform, name, s -> {});
            e.state = State.OK;
            e.nextRefresh = System.currentTimeMillis() + ttl;
        } catch (SocialException ex) {
            NametagLinesClient.LOGGER.warn("{} lookup for '{}' failed: {}", platform, name, ex.getMessage());
            if (e.state != State.OK) {
                e.state = State.ERROR;
                e.errorText = ex.shortText;
            }
            e.nextRefresh = System.currentTimeMillis() + ERROR_RETRY_MS;
        } catch (Exception ex) {
            NametagLinesClient.LOGGER.warn("{} lookup for '{}' failed: {}", platform, name, ex.toString());
            if (e.state != State.OK) {
                e.state = State.ERROR;
                e.errorText = "n/a";
            }
            e.nextRefresh = System.currentTimeMillis() + ERROR_RETRY_MS;
        } finally {
            e.inFlight.set(false);
        }
    }

    private static long fetch(String platform, String name, Consumer<String> log) throws Exception {
        return switch (platform) {
            case "youtube" -> fetchYoutube(name, log);
            case "twitch" -> fetchTwitch(name, log);
            case "tiktok" -> fetchTiktok(name, log);
            default -> throw new SocialException("unknown platform " + platform, "?");
        };
    }

    // ---------------- Platforms ----------------

    /** With an API key: official API. Without: read the public channel page (no key needed, can break). */
    private static long fetchYoutube(String name, Consumer<String> log) throws Exception {
        String key = NametagConfig.get().youtubeApiKey;
        if (key != null && !key.isBlank()) {
            return fetchYoutubeApi(name, key.trim(), log);
        }
        return fetchYoutubeScrape(name, log);
    }

    private static long fetchYoutubeScrape(String name, Consumer<String> log) throws Exception {
        NametagConfig cfg = NametagConfig.get();
        String url = cfg.youtubeUrl.replace("{name}", enc(name));
        Fetched f = request(url, true);
        log.accept("GET " + url + " -> HTTP " + f.status());
        if (f.status() == 404) throw new SocialException("channel @" + name + " not found", "not found");
        if (f.status() / 100 != 2) throw new SocialException("HTTP " + f.status(), "blocked");

        String body = f.body() == null ? "" : f.body();
        // The page also contains OTHER channels (featured channels, recommendations) with their own
        // "N subscribers" text, often before the channel header. So collect every match and take the one
        // closest to this channel's own handle (@name, not followed by more name characters).
        List<Integer> handlePositions = new ArrayList<>();
        Matcher hm = Pattern.compile("@" + Pattern.quote(name) + "(?![\\w.\\-])", Pattern.CASE_INSENSITIVE)
                .matcher(body);
        while (hm.find()) handlePositions.add(hm.start());

        Matcher m = Pattern.compile(cfg.youtubeRegex).matcher(body);
        String bestText = null;
        int bestDistance = Integer.MAX_VALUE;
        StringBuilder candidates = new StringBuilder();
        int seen = 0;
        while (m.find()) {
            // Without any handle on the page we fall back to the first match
            int distance = handlePositions.isEmpty() ? m.start() : nearest(handlePositions, m.start());
            if (seen++ < 8) candidates.append(m.group(1).trim()).append(" (distance ").append(distance).append(")  ");
            if (distance < bestDistance) {
                bestDistance = distance;
                bestText = m.group(1);
            }
        }
        if (bestText == null) {
            log.accept("no match; page length " + body.length()
                    + (body.contains("consent.youtube.com") ? " (consent page)" : ""));
            throw new SocialException(
                    "subscriber count not found on the page (hidden, blocked or the layout changed)", "n/a");
        }
        log.accept("candidates: " + candidates);
        log.accept("chosen: " + bestText.trim() + " (" + handlePositions.size() + " handle hits on the page)");
        return parseCount(bestText);
    }

    /** Distance from pos to the closest value in the list. */
    private static int nearest(List<Integer> positions, int pos) {
        int best = Integer.MAX_VALUE;
        for (int p : positions) best = Math.min(best, Math.abs(p - pos));
        return best;
    }

    /** "1.23K" -> 1230, "4.5M" -> 4500000, "12,345" -> 12345, "999" -> 999 */
    static long parseCount(String text) throws SocialException {
        String t = text.trim().replace(" ", "").replace(",", "");
        long mult = 1;
        if (!t.isEmpty()) {
            char last = Character.toUpperCase(t.charAt(t.length() - 1));
            if (last == 'K') mult = 1_000L;
            else if (last == 'M') mult = 1_000_000L;
            else if (last == 'B') mult = 1_000_000_000L;
            if (mult > 1) t = t.substring(0, t.length() - 1);
        }
        try {
            return Math.round(Double.parseDouble(t) * mult);
        } catch (NumberFormatException e) {
            throw new SocialException("could not read the number '" + text + "'", "n/a");
        }
    }

    private static long fetchYoutubeApi(String name, String key, Consumer<String> log) throws Exception {
        String url = "https://www.googleapis.com/youtube/v3/channels?part=statistics&forHandle="
                + enc("@" + name) + "&key=" + enc(key.trim());
        Fetched f = request(url, false);
        log.accept("GET https://www.googleapis.com/youtube/v3/channels?part=statistics&forHandle=@" + name
                + "&key=*** -> HTTP " + f.status());
        log.accept(snippet(f.body()));
        if (f.status() == 400 || f.status() == 403) {
            throw new SocialException("YouTube API refused the request (HTTP " + f.status()
                    + "): check the API key and that YouTube Data API v3 is enabled", "bad API key");
        }
        if (f.status() / 100 != 2) throw new IOException("HTTP " + f.status());

        JsonObject root = JsonParser.parseString(f.body()).getAsJsonObject();
        JsonArray items = root.has("items") && root.get("items").isJsonArray() ? root.getAsJsonArray("items") : null;
        if (items == null || items.isEmpty()) {
            throw new SocialException("channel @" + name + " not found", "not found");
        }
        JsonObject stats = items.get(0).getAsJsonObject().getAsJsonObject("statistics");
        if (stats == null) throw new IOException("no statistics in response");
        JsonElement hidden = stats.get("hiddenSubscriberCount");
        if (hidden != null && hidden.isJsonPrimitive() && hidden.getAsBoolean()) {
            throw new SocialException("channel hides its subscriber count", "hidden");
        }
        JsonElement subs = stats.get("subscriberCount");
        if (subs == null) throw new IOException("subscriberCount missing");
        return Long.parseLong(subs.getAsString());
    }

    private static long fetchTwitch(String name, Consumer<String> log) throws Exception {
        String url = NametagConfig.get().twitchUrl.replace("{name}", enc(name));
        Fetched f = request(url, false);
        log.accept("GET " + url + " -> HTTP " + f.status());
        log.accept(snippet(f.body()));
        String body = f.body() == null ? "" : f.body().trim();
        if (f.status() / 100 != 2 && !body.matches("\\d+")) throw new IOException("HTTP " + f.status() + ": " + snippet(body));
        if (!body.matches("\\d+")) {
            throw new SocialException("unexpected response: " + snippet(body), "not found");
        }
        return Long.parseLong(body);
    }

    private static long fetchTiktok(String name, Consumer<String> log) throws Exception {
        NametagConfig cfg = NametagConfig.get();
        String url = cfg.tiktokUrl.replace("{name}", enc(name));
        Fetched f = request(url, true);
        log.accept("GET " + url + " -> HTTP " + f.status());
        if (f.status() / 100 != 2) throw new SocialException("HTTP " + f.status(), "blocked");

        Matcher m = Pattern.compile(cfg.tiktokRegex).matcher(f.body() == null ? "" : f.body());
        if (!m.find()) {
            log.accept(snippet(f.body()));
            throw new SocialException("follower count not found on the page (TikTok may have blocked the request)", "blocked");
        }
        return Long.parseLong(m.group(1));
    }

    // ---------------- Helpers ----------------

    private static Fetched request(String url, boolean browserLike) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", browserLike ? BROWSER_UA : "NameTagMod/1.0 (by MelonKingDe)");
        if (browserLike) {
            b.header("Accept", "text/html,application/xhtml+xml").header("Accept-Language", "en-US,en;q=0.9");
            // Skips the EU cookie consent page that would otherwise replace the YouTube channel page
            if (url.contains("youtube.com")) b.header("Cookie", "CONSENT=YES+cb; SOCS=CAI");
        } else {
            b.header("Accept", "application/json, text/plain");
        }
        HttpResponse<String> res = HTTP.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
        return new Fetched(res.statusCode(), res.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String snippet(String body) {
        if (body == null) return "(empty)";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
