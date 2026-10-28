package de.example.nametaglines;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** JSON config: config/nametaglines.json */
public class NametagConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("nametaglines.json");

    public static final float MIN_SCALE = 0.25f;
    public static final float MAX_SCALE = 4.0f;

    /** Maximum number of lines per side (above / below). */
    public static final int MAX_LINES = 8;
    /** Maximum number of characters per line. */
    public static final int MAX_TEXT = 256;

    /** One line: text with &-color codes and {icon:name} placeholders, plus its scale. */
    public static class LineData {
        public String text = "";
        public float scale = 1.0f;
        /** Vertical shift in pixels: positive = up, negative = down. Does not move other lines. */
        public float offsetY = 0.0f;

        public LineData() {}

        public LineData(String text) {
            this.text = text;
        }
    }

    public boolean enabled = true;
    /** Also render your own name in the middle (vanilla does not show it for yourself). */
    public boolean showOwnName = true;
    /**
     * true  = lines are part of the world nametag (follow bobbing, FOV effects, perspective exactly,
     *         but water/glass/item frames can dim or cover them).
     * false = lines are a screen overlay (always fully visible, but only approximate perspective).
     */
    public boolean attachToNametag = true;
    /** Lines from top to bottom. */
    public List<LineData> top = new ArrayList<>();
    public List<LineData> bottom = new ArrayList<>();

    // --- <collection:Name> lookup (Elite SkyBlock API) ---
    public String collectionApiBase = "https://api.eliteskyblock.com";
    /** Path to find the selected profile of a player. {uuid} = player UUID without dashes. */
    public String collectionProfilePath = "/profile/{uuid}/selected";
    /** Path to read a player's rank on a leaderboard. {lb} = leaderboard slug, {profile} = profile id. */
    public String collectionRankPath = "/leaderboard/rank/{lb}/{uuid}/{profile}";
    /** Fixed profile id. Empty = automatically use the selected profile. */
    public String collectionProfileId = "";
    /** Display format. Placeholders: {amount}, {rank}. &-codes allowed. */
    public String collectionFormat = "{amount} (#{rank})";
    public int collectionRefreshMinutes = 5;
    /** Display format for <skill:name>. Placeholders: {amount} (XP), {rank}. */
    public String skillFormat = "{amount} XP (#{rank})";
    /** Display format for <slayer:name>. Placeholders: {amount} (slayer XP), {rank}. */
    public String slayerFormat = "{amount} XP (#{rank})";

    // --- <youtube:Name>, <twitch:Name>, <tiktok:Name> follower counts ---
    /** Optional YouTube Data API v3 key. Without it the channel page is read instead (can break). Keep it private. */
    public String youtubeApiKey = "";
    /** Channel page used when no API key is set. {name} = channel handle without @. */
    public String youtubeUrl = "https://www.youtube.com/@{name}?hl=en";
    /** Finds the subscriber text on that page (group 1), e.g. "1.23K" in "1.23K subscribers". */
    public String youtubeRegex = "([\\d.,]+\\s?[KkMmBb]?)\\s+subscribers";
    /** Public follower counter for Twitch. {name} = channel name. Must return just a number. */
    public String twitchUrl = "https://decapi.me/twitch/followcount/{name}";
    /** TikTok profile page and the regex that finds the follower count (group 1). */
    public String tiktokUrl = "https://www.tiktok.com/@{name}";
    public String tiktokRegex = "\"followerCount\":(\\d+)";
    /** Display format. {count} = number. */
    public String socialFormat = "{count}";
    /** true = 1.2K / 3.4M instead of 1,234 / 3,400,000. */
    public boolean socialCompact = false;
    public int socialRefreshMinutes = 10;

    // --- one line above the nametags of OTHER players near you ---
    /** Off by default: lookups for other players send their UUIDs to the Elite API. */
    public boolean othersEnabled = false;
    /** Template: {player} = name, <skill:..> / <collection:..> are looked up for each player. */
    public LineData othersLine = new LineData();
    public int othersMaxDistance = 24;
    public int othersMaxPlayers = 8;

    private static NametagConfig instance = new NametagConfig();

    public static NametagConfig get() {
        return instance;
    }

    public static float clampScale(float s) {
        if (Float.isNaN(s) || s <= 0f) return 1.0f;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
    }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                NametagConfig loaded = GSON.fromJson(Files.readString(FILE), NametagConfig.class);
                if (loaded != null) {
                    if (loaded.top == null) loaded.top = new ArrayList<>();
                    if (loaded.bottom == null) loaded.bottom = new ArrayList<>();
                    if (loaded.collectionApiBase == null || loaded.collectionApiBase.isBlank()) loaded.collectionApiBase = "https://api.eliteskyblock.com";
                    if (loaded.collectionProfilePath == null || loaded.collectionProfilePath.isBlank()) loaded.collectionProfilePath = "/profile/{uuid}/selected";
                    if (loaded.collectionRankPath == null || loaded.collectionRankPath.isBlank()) loaded.collectionRankPath = "/leaderboard/rank/{lb}/{uuid}/{profile}";
                    if (loaded.collectionProfileId == null) loaded.collectionProfileId = "";
                    if (loaded.collectionFormat == null || loaded.collectionFormat.isBlank()) loaded.collectionFormat = "{amount} (#{rank})";
                    if (loaded.skillFormat == null || loaded.skillFormat.isBlank()) loaded.skillFormat = "{amount} XP (#{rank})";
                    if (loaded.slayerFormat == null || loaded.slayerFormat.isBlank()) loaded.slayerFormat = "{amount} XP (#{rank})";
                    if (loaded.youtubeApiKey == null) loaded.youtubeApiKey = "";
                    if (loaded.youtubeUrl == null || loaded.youtubeUrl.isBlank()) loaded.youtubeUrl = "https://www.youtube.com/@{name}?hl=en";
                    if (loaded.youtubeRegex == null || loaded.youtubeRegex.isBlank()) loaded.youtubeRegex = "([\\d.,]+\\s?[KkMmBb]?)\\s+subscribers";
                    if (loaded.twitchUrl == null || loaded.twitchUrl.isBlank()) loaded.twitchUrl = "https://decapi.me/twitch/followcount/{name}";
                    if (loaded.tiktokUrl == null || loaded.tiktokUrl.isBlank()) loaded.tiktokUrl = "https://www.tiktok.com/@{name}";
                    if (loaded.tiktokRegex == null || loaded.tiktokRegex.isBlank()) loaded.tiktokRegex = "\"followerCount\":(\\d+)";
                    if (loaded.socialFormat == null || loaded.socialFormat.isBlank()) loaded.socialFormat = "{count}";
                    if (loaded.socialRefreshMinutes < 1) loaded.socialRefreshMinutes = 10;
                    if (loaded.othersLine == null) loaded.othersLine = new LineData(); else normalize(loaded.othersLine);
                    loaded.othersMaxDistance = Math.max(4, Math.min(64, loaded.othersMaxDistance <= 0 ? 24 : loaded.othersMaxDistance));
                    loaded.othersMaxPlayers = Math.max(1, Math.min(20, loaded.othersMaxPlayers <= 0 ? 8 : loaded.othersMaxPlayers));
                    if (loaded.collectionRefreshMinutes < 1) loaded.collectionRefreshMinutes = 5;
                    loaded.top.removeIf(l -> l == null);
                    loaded.bottom.removeIf(l -> l == null);
                    for (LineData l : loaded.top) normalize(l);
                    for (LineData l : loaded.bottom) normalize(l);
                    instance = loaded;
                }
            } else {
                save();
            }
        } catch (Exception e) {
            NametagLinesClient.LOGGER.error("Could not load config", e);
        }
    }

    private static void normalize(LineData l) {
        if (l.text == null) l.text = "";
        l.scale = clampScale(l.scale);
        if (Float.isNaN(l.offsetY)) l.offsetY = 0f;
    }

    public static void save() {
        try {
            Files.writeString(FILE, GSON.toJson(instance));
        } catch (IOException e) {
            NametagLinesClient.LOGGER.error("Could not save config", e);
        }
    }
}
