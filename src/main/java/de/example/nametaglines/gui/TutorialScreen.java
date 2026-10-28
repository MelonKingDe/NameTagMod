package de.example.nametaglines.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Explains every feature of the mod and everything that can be written into a line.
 * The text is wrapped to the screen width and split into pages automatically.
 */
public class TutorialScreen extends Screen {
    private static final int LINE_H = 11;
    private static final int COLOR_TITLE = 0xFF55FF55;
    private static final int COLOR_HEADING = 0xFFFFAA00;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_CODE = 0xFF55FFFF;

    private record Line(String text, int color, boolean heading) {}

    /** Content: kind 'h' = heading, 'p' = paragraph, 'c' = example / code. */
    private static final String[][] CONTENT = {
            {"h", "1. The basics"},
            {"p", "The editor adds extra text lines above and below your own nametag. Use '+ Add line' in the left column for lines above your name and in the right column for lines below it. X removes a line."},
            {"p", "Each side holds up to 8 lines with up to 256 characters each. If they do not all fit on the screen, scroll with the ^ and v buttons between the column titles."},
            {"p", "Every line has two sliders: Scale (0.25x to 3x) changes its size. Shift moves only that line up (+) or down (-) in pixels; the other lines stay where they are."},
            {"p", "Buttons at the bottom: 'Extra lines' switches everything on or off. 'Own name' shows your own name in the middle (vanilla hides it). 'View' switches the display mode."},
            {"p", "View nametag: the lines are part of the world nametag and follow all camera effects, but water, glass and item frames can cover them. View overlay: the lines are drawn on the screen and are always fully visible, but they hide with F1 and follow camera effects only roughly."},
            {"p", "Only you see your lines. Look at yourself in third person (F5) to check them."},

            {"h", "2. Colors and formats"},
            {"p", "Write & followed by a code: &0 to &9 and &a to &f are colors. &l bold, &o italic, &n underline, &m strikethrough, &k obfuscated, &r resets everything."},
            {"p", "&z makes the text a moving rainbow (chroma). You can combine it with &l, &o and so on. A color code or &r ends it."},
            {"c", "Example:  &6Gold &lBold &zRainbow &rPlain"},

            {"h", "3. Icons (PNG and GIF)"},
            {"p", "Put PNG or GIF files into .minecraft/config/nametaglines/icons. GIFs are animated."},
            {"p", "Click the Icon button next to a line and pick an icon. It is inserted at the cursor as {icon:name}, so you can place it before, after or in the middle of the text. You can use several icons in one line."},
            {"p", "The name is the file name in lower case without the extension (other characters become _). Click Reload in the picker after adding files. Keep GIFs small, for example 32x32 or 64x64."},
            {"c", "Example:  {icon:crown} &6King {icon:crown}"},

            {"h", "4. Collections, skills and slayers"},
            {"p", "Elite SkyBlock leaderboards (eliteskyblock.com). The name becomes the leaderboard address, e.g. 'Wilted Berberis' -> wilted-berberis."},
            {"c", "<collection:Wilted Berberis>  ->  12,345 (#42)"},
            {"c", "<skill:social>  ->  1,234,567 XP (#42)"},
            {"c", "<slayer:vampire>  ->  250,000 XP (#42)"},
            {"p", "Slayers: zombie, spider, wolf, enderman, blaze and vampire (leaderboards like vampire-slayer)."},
            {"p", "While loading it shows ..., 'no rank' if you are not on the leaderboard and 'n/a' on errors. Data is cached for a few minutes and uses your selected SkyBlock profile (change it with /nametag collection profile). Formats and API addresses are in config/nametaglines.json. Problems? Use /nametag collection test <name>."},

            {"h", "5. YouTube, Twitch and TikTok"},
            {"c", "<youtube:MelonKingDe>   <twitch:Name>   <tiktok:Name>"},
            {"p", "Shows the subscribers or followers of that channel. YouTube and TikTok read the public channel / profile page, so no key is needed, but they can be blocked and YouTube shows rounded numbers like 1.23K. An optional API key (youtubeApiKey in the config) makes YouTube more reliable. Twitch uses a public counter. Test with /nametag social test youtube <name>."},

            {"h", "6. Other players"},
            {"p", "The 'Other players' button (top right) sets one line that appears above the nametags of other players near you. It can contain {player} (their name) and <skill:...>, <collection:...> and <slayer:...>, which are looked up for each player. Colors, icons, scale and shift work as well."},
            {"c", "Example:  &7{player} &8| &f<skill:social>"},
            {"p", "Set a maximum distance and a maximum number of players to limit the lookups. Only real players are used, not NPCs. Their UUIDs are sent to the Elite API. You can turn the line off at any time."},

            {"h", "7. Keys and commands"},
            {"p", "Open the editor with K (changeable in Controls), with the NameTagMod button in the ESC menu or with /nametag gui."},
            {"p", "Commands: /nametag above|below add, set, scale, offset, remove, clear. Also /nametag list, toggle, ownname, mode, icons, collection, social and others. All settings are saved in config/nametaglines.json."},
    };

    private final Screen parent;
    private List<List<Line>> pages = new ArrayList<>();
    private int page = 0;

    public TutorialScreen(Screen parent) {
        super(Component.literal("Tutorial"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int maxW = textWidth();
        List<Line> all = new ArrayList<>();
        boolean first = true;
        for (String[] entry : CONTENT) {
            String kind = entry[0];
            if (kind.equals("h")) {
                if (!first) all.add(new Line("", COLOR_TEXT, false)); // blank line before a heading
                all.add(new Line(entry[1], COLOR_HEADING, true));
            } else if (kind.equals("c")) {
                wrap(all, entry[1], maxW - 10, COLOR_CODE, "  ");
            } else {
                wrap(all, entry[1], maxW, COLOR_TEXT, "");
            }
            first = false;
        }

        int perPage = Math.max(6, (this.height - 34 - 44) / LINE_H);
        pages = paginate(all, perPage);
        page = Math.max(0, Math.min(page, pages.size() - 1));

        int cx = this.width / 2;
        int by = this.height - 26;
        Button prev = Button.builder(Component.literal("<"), btn -> {
            page--;
            this.rebuildWidgets();
        }).bounds(cx - 100, by, 30, 20).build();
        prev.active = page > 0;
        this.addRenderableWidget(prev);

        this.addRenderableWidget(Button.builder(Component.literal("Back"), btn -> onClose())
                .bounds(cx - 65, by, 130, 20).build());

        Button next = Button.builder(Component.literal(">"), btn -> {
            page++;
            this.rebuildWidgets();
        }).bounds(cx + 70, by, 30, 20).build();
        next.active = page < pages.size() - 1;
        this.addRenderableWidget(next);
    }

    private int textWidth() {
        return Math.min(380, this.width - 30);
    }

    /** Word-wraps text to maxW pixels and appends the lines. */
    private void wrap(List<Line> out, String text, int maxW, int color, String indent) {
        StringBuilder current = new StringBuilder(indent);
        for (String word : text.split(" ")) {
            String candidate = current.length() == indent.length() ? current + word : current + " " + word;
            if (this.font.width(candidate) > maxW && current.length() > indent.length()) {
                out.add(new Line(current.toString(), color, false));
                current = new StringBuilder(indent).append(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > indent.length()) out.add(new Line(current.toString(), color, false));
    }

    /** Splits the lines into pages; a heading never ends up alone at the bottom of a page. */
    private static List<List<Line>> paginate(List<Line> all, int perPage) {
        List<List<Line>> result = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        for (Line line : all) {
            boolean needNewPage = current.size() >= perPage
                    || (line.heading() && !current.isEmpty() && current.size() > perPage - 3);
            if (needNewPage) {
                result.add(current);
                current = new ArrayList<>();
            }
            // no blank line at the top of a page
            if (current.isEmpty() && line.text().isEmpty()) continue;
            current.add(line);
        }
        if (!current.isEmpty()) result.add(current);
        if (result.isEmpty()) result.add(new ArrayList<>());
        return result;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int cx = this.width / 2;
        center(graphics, "NameTagMod - Tutorial", cx, 10, COLOR_TITLE);

        int x = cx - textWidth() / 2;
        int y = 30;
        for (Line line : pages.get(page)) {
            if (!line.text().isEmpty()) {
                graphics.text(this.font, line.text(), x, y, line.color(), true);
            }
            y += LINE_H;
        }

        center(graphics, "Page " + (page + 1) + " / " + pages.size(), cx, this.height - 38, 0xFF777777);
    }

    private void center(GuiGraphicsExtractor graphics, String text, int cx, int y, int color) {
        graphics.text(this.font, text, cx - this.font.width(text) / 2, y, color, true);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
