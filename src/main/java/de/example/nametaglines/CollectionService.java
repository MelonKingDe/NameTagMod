package de.example.nametaglines;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces <collection:Name>, <skill:name> and <slayer:name> in a line with the amount (collection amount / XP)
 * and leaderboard rank of the own
 * player, fetched from the Elite SkyBlock API. All network access happens on a background thread;
 * the render thread only reads the cache.
 *
 * NOTE: the endpoint paths and JSON field names are configurable (config/nametaglines.json) because
 * they could not be verified. Use "/nametag collection test <name>" to see the raw responses.
 */
public final class CollectionService {
    private enum State { LOADING, OK, NO_RANK, ERROR }

    private static final class Entry {
        volatile State state = State.LOADING;
        volatile long rank = -1;
        volatile double amount = -1;
        volatile long nextRefresh = 0;
        final AtomicBoolean inFlight = new AtomicBoolean(false);
    }

    private record Fetched(int status, String body) {}

    private record Lookup(boolean found, long rank, double amount) {}

    private static final Pattern TOKEN = Pattern.compile("<(collection|skill|slayer):([^>]+)>", Pattern.CASE_INSENSITIVE);
    private static final long ERROR_RETRY_MS = 120_000L;
    private static final long PROFILE_TTL_MS = 600_000L;

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nametaglines-http");
        t.setDaemon(true);
        return t;
    });

    private record ProfileEntry(String id, long fetched) {}

    private static final Map<UUID, ProfileEntry> PROFILES = new ConcurrentHashMap<>();

    private CollectionService() {}

    // ---------------- Public API ----------------

    /** Replaces all <collection:...> and <skill:...> tokens in the given line text. Safe to call every frame. */
    public static String expand(String raw) {
        return expand(raw, null);
    }

    /** Same, but the lookups are done for the given player (null = the own player). */
    public static String expand(String raw, UUID player) {
        if (raw == null || raw.indexOf('<') < 0) return raw;
        Matcher m = TOKEN.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(display(m.group(1).toLowerCase(Locale.ROOT), m.group(2).trim(), player)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Clears all cached results (and the cached profile) so everything is fetched again. */
    public static void refreshAll() {
        CACHE.clear();
        PROFILES.clear();
    }

    /** Debug helper: runs a lookup and reports URLs, HTTP status and a snippet of each response. */
    public static void test(String name, Consumer<String> out) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            out.accept("Join a world first.");
            return;
        }
        UUID uuid = mc.player.getUUID();
        String slug = slug(name);
        NametagConfig cfg = NametagConfig.get();
        EXEC.execute(() -> {
            try {
                out.accept("Player " + uuid + ", leaderboard '" + slug + "'");
                Lookup l = lookup(cfg, uuid, slug, out);
                if (!l.found()) {
                    out.accept("Result: player has no rank on this leaderboard (HTTP 404).");
                } else {
                    out.accept("Result: amount=" + l.amount() + ", rank=" + l.rank());
                }
            } catch (Exception e) {
                out.accept("Failed: " + e);
            }
        });
    }

    // ---------------- Display ----------------

    private static String display(String type, String name, UUID player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "...";
        String slug = type.equals("slayer") ? slayerSlug(name) : slug(name);
        if (slug.isEmpty()) return "?";

        UUID uuid = player != null ? player : mc.player.getUUID();
        if (CACHE.size() > 2000) CACHE.clear(); // keep memory bounded when many players were looked up
        Entry e = CACHE.computeIfAbsent(uuid + "/" + slug, k -> new Entry());
        if (System.currentTimeMillis() >= e.nextRefresh && e.inFlight.compareAndSet(false, true)) {
            EXEC.execute(() -> refresh(e, uuid, slug));
        }

        return switch (e.state) {
            case LOADING -> "...";
            case NO_RANK -> "no rank";
            case ERROR -> "n/a";
            case OK -> format(e, type);
        };
    }

    private static String format(Entry e, String type) {
        String amount = e.amount >= 0
                ? NumberFormat.getIntegerInstance(Locale.US).format(Math.round(e.amount))
                : "?";
        String rank = e.rank >= 0 ? String.valueOf(e.rank) : "?";
        NametagConfig cfg = NametagConfig.get();
        String pattern = switch (type) {
            case "skill" -> cfg.skillFormat;
            case "slayer" -> cfg.slayerFormat;
            default -> cfg.collectionFormat;
        };
        return pattern
                .replace("{amount}", amount)
                .replace("{rank}", rank);
    }

    /**
     * "vampire" -> "vampire-slayer" (the leaderboard name). Also accepts "vampire-slayer" and the boss names
     * (revenant, tarantula, sven, voidgloom, inferno, riftstalker).
     */
    static String slayerSlug(String name) {
        String s = slug(name);
        if (s.isEmpty()) return s;
        if (s.endsWith("-slayer")) s = s.substring(0, s.length() - "-slayer".length());
        s = switch (s) {
            case "revenant", "revenant-horror" -> "zombie";
            case "tarantula", "tarantula-broodfather" -> "spider";
            case "sven", "sven-packmaster" -> "wolf";
            case "voidgloom", "voidgloom-seraph" -> "enderman";
            case "inferno", "inferno-demonlord" -> "blaze";
            case "riftstalker", "riftstalker-bloodfiend", "bloodfiend" -> "vampire";
            default -> s;
        };
        return s + "-slayer";
    }

    /** "Wilted Berberis" -> "wilted-berberis" */
    static String slug(String name) {
        String s = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+|-+$", "");
    }

    // ---------------- Background refresh ----------------

    private static void refresh(Entry e, UUID uuid, String slug) {
        NametagConfig cfg = NametagConfig.get();
        long ttl = Math.max(1, cfg.collectionRefreshMinutes) * 60_000L;
        try {
            Lookup l = lookup(cfg, uuid, slug, s -> {});
            if (l.found()) {
                e.rank = l.rank();
                e.amount = l.amount();
                e.state = State.OK;
            } else {
                e.state = State.NO_RANK;
            }
            e.nextRefresh = System.currentTimeMillis() + ttl;
        } catch (Exception ex) {
            NametagLinesClient.LOGGER.warn("Collection lookup for '{}' failed: {}", slug, ex.toString());
            if (e.state == State.LOADING) e.state = State.ERROR; // keep old values if we already had some
            e.nextRefresh = System.currentTimeMillis() + ERROR_RETRY_MS;
        } finally {
            e.inFlight.set(false);
        }
    }

    private static Lookup lookup(NametagConfig cfg, UUID uuid, String slug, Consumer<String> log) throws Exception {
        String profile = resolveProfile(cfg, uuid, log);

        String url = base(cfg) + cfg.collectionRankPath
                .replace("{lb}", slug)
                .replace("{uuid}", undashed(uuid))
                .replace("{profile}", profile);
        Fetched f = request(url);
        log.accept("GET " + url + " -> HTTP " + f.status());
        log.accept(snippet(f.body()));

        if (f.status() == 404) return new Lookup(false, -1, -1);
        if (f.status() / 100 != 2) throw new IOException("HTTP " + f.status());

        JsonElement root = JsonParser.parseString(f.body());
        Double rank = number(root, "rank", "position");
        Double amount = number(root, "amount", "value", "collection", "score");
        if (rank == null && amount == null) throw new IOException("unexpected response format");
        return new Lookup(true, rank == null ? -1 : rank.longValue(), amount == null ? -1 : amount);
    }

    private static boolean isSelf(UUID uuid) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && uuid.equals(mc.player.getUUID());
    }

    private static String resolveProfile(NametagConfig cfg, UUID uuid, Consumer<String> log) throws Exception {
        // A fixed profile id only applies to the own player; other players always use their selected profile
        if (isSelf(uuid) && cfg.collectionProfileId != null && !cfg.collectionProfileId.isBlank()) {
            return cfg.collectionProfileId.trim();
        }
        long now = System.currentTimeMillis();
        ProfileEntry cached = PROFILES.get(uuid);
        if (cached != null && now - cached.fetched() < PROFILE_TTL_MS) {
            return cached.id();
        }

        String url = base(cfg) + cfg.collectionProfilePath.replace("{uuid}", undashed(uuid));
        Fetched f = request(url);
        log.accept("GET " + url + " -> HTTP " + f.status());
        log.accept(snippet(f.body()));
        if (f.status() / 100 != 2) throw new IOException("profile lookup: HTTP " + f.status());

        String id = string(JsonParser.parseString(f.body()), "profileId", "profile_id", "id");
        if (id == null || id.isBlank()) throw new IOException("profile id not found in response");

        PROFILES.put(uuid, new ProfileEntry(id, now));
        return id;
    }

    // ---------------- HTTP / JSON helpers ----------------

    private static Fetched request(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "NameTagMod/1.0 (by MelonKingDe)")
                .GET()
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return new Fetched(res.statusCode(), res.body());
    }

    private static String base(NametagConfig cfg) {
        String b = cfg.collectionApiBase.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private static String undashed(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    private static String snippet(String body) {
        if (body == null) return "(empty)";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /** Breadth-first search for the first primitive value under one of the given keys (case-insensitive). */
    private static JsonElement find(JsonElement root, String... keys) {
        Deque<JsonElement> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            JsonElement el = queue.poll();
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                for (String key : keys) {
                    for (Map.Entry<String, JsonElement> en : o.entrySet()) {
                        if (en.getKey().equalsIgnoreCase(key) && en.getValue().isJsonPrimitive()) {
                            return en.getValue();
                        }
                    }
                }
                for (Map.Entry<String, JsonElement> en : o.entrySet()) {
                    if (!en.getValue().isJsonPrimitive()) queue.add(en.getValue());
                }
            } else if (el.isJsonArray()) {
                JsonArray arr = el.getAsJsonArray();
                for (JsonElement child : arr) queue.add(child);
            }
        }
        return null;
    }

    private static Double number(JsonElement root, String... keys) {
        JsonElement el = find(root, keys);
        if (el == null) return null;
        try {
            return el.getAsDouble();
        } catch (Exception e) {
            return null;
        }
    }

    private static String string(JsonElement root, String... keys) {
        JsonElement el = find(root, keys);
        return el == null ? null : el.getAsString();
    }
}
