package de.example.nametaglines.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.example.nametaglines.CollectionService;
import de.example.nametaglines.IconManager;
import de.example.nametaglines.NametagConfig;
import de.example.nametaglines.SocialService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;   // fix import via IDE if needed
import net.minecraft.client.renderer.rendertype.RenderTypes;  // fix import via IDE if needed
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState; // fix import via IDE if needed
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;


import org.joml.Matrix3x2fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draws the name + extra lines (with scale, icons and chroma text) above the own player, and one
 * optional line above the nametags of other players near you.
 * Mode 1 (default): submitted into the world as part of the nametag, so it follows every view effect.
 * Mode 2: HUD overlay (projected to the screen), so nothing (water, glass, item frames) can cover or dim it.
 * Coordinate system of a line: 1 unit = 1 font pixel, y grows downwards, x=0 is the center.
 */
public final class NametagRenderer {
    private static final Pattern ICON_TOKEN = Pattern.compile("\\{icon:([a-z0-9_\\-]+)}");

    private static final float LINE_STEP = 10f;   // line height at scale 1
    private static final float ICON_HEIGHT = 9f;  // icon height at scale 1
    private static final float ICON_Y = -0.5f;

    /** All valid &-codes: 0-9, a-f (colors), k-o (formats), r (reset), z (chroma). */
    private static final String CODES = "0123456789abcdefklmnorz";
    /** RGB values of the 16 vanilla colors for &0 - &f. */
    private static final int[] COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private static final long CHROMA_PERIOD_MS = 2500L; // time for one full color cycle
    private static final float CHROMA_SPREAD = 0.04f;   // hue shift per character

    private sealed interface Segment permits TextSeg, IconSeg {
        float width();
    }

    private record TextSeg(FormattedCharSequence text, float width) implements Segment {}

    private record IconSeg(IconManager.Icon icon, float width) implements Segment {}

    /** Anchor of another player's nametag (overlay mode). */
    private record OtherAnchor(Vector3f view, UUID uuid, String name) {}

    /** Camera-space position of the own nametag anchor, recorded during the entity pass (overlay mode). */
    private static Vector3f pendingView;
    /** Same for other players, keyed by UUID so it stays small even if the HUD is hidden (F1). */
    private static final Map<UUID, OtherAnchor> pendingOthers = new LinkedHashMap<>();

    /** Players near you that get the "other players" line (refreshed twice per second). */
    private static Set<UUID> nearby = Set.of();
    private static long nearbyUpdated = 0;

    private NametagRenderer() {}

    // ---------------- Entry point from the entity pass ----------------

    public static void capture(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                               CameraRenderState camera) {
        Minecraft mc = Minecraft.getInstance();
        NametagConfig cfg = NametagConfig.get();
        if (!cfg.enabled || mc.player == null || mc.level == null) return;
        if (!(state instanceof AvatarRenderState avatar)) return;

        // ----- own player -----
        if (avatar.id == mc.player.getId()) {
            if (cfg.attachToNametag) {
                pendingView = null;
                submitWorld(state, poseStack, collector, camera);
            } else {
                pendingView = anchorView(state, poseStack, camera);
            }
            return;
        }

        // ----- other players -----
        if (!cfg.othersEnabled || cfg.othersLine.text.isEmpty()) return;
        Entity entity = mc.level.getEntity(avatar.id);
        if (!(entity instanceof Player other)) return;
        UUID uuid = other.getUUID();
        if (!isNearby(uuid)) return;
        String name = other.getName().getString();

        if (cfg.attachToNametag) {
            submitOtherWorld(state, poseStack, collector, camera, uuid, name);
        } else {
            Vector3f view = anchorView(state, poseStack, camera);
            if (view != null) pendingOthers.put(uuid, new OtherAnchor(view, uuid, name));
        }
    }

    /** Nearest real players within the configured distance (NPCs have non-random UUIDs and are skipped). */
    private static boolean isNearby(UUID uuid) {
        long now = System.currentTimeMillis();
        if (now - nearbyUpdated > 500) {
            nearbyUpdated = now;
            nearby = computeNearby();
        }
        return nearby.contains(uuid);
    }

    private static Set<UUID> computeNearby() {
        Minecraft mc = Minecraft.getInstance();
        NametagConfig cfg = NametagConfig.get();
        if (mc.player == null || mc.level == null) return Set.of();

        double maxSq = (double) cfg.othersMaxDistance * cfg.othersMaxDistance;
        List<Player> list = new ArrayList<>();
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.getUUID().version() != 4) continue;
            if (p.distanceToSqr(mc.player) > maxSq) continue;
            list.add(p);
        }
        list.sort(Comparator.comparingDouble(p -> p.distanceToSqr(mc.player)));

        Set<UUID> out = new HashSet<>();
        for (int i = 0; i < Math.min(list.size(), cfg.othersMaxPlayers); i++) {
            out.add(list.get(i).getUUID());
        }
        return out;
    }

    /** Anchor of the nametag in camera space (the camera looks along -Z). */
    private static Vector3f anchorView(EntityRenderState state, PoseStack poseStack, CameraRenderState camera) {
        Vec3 attachment = state.nameTagAttachment;
        if (attachment == null) return null;
        // Anchor in camera-relative world coordinates (same point the vanilla nametag is drawn at)
        Vector3f world = poseStack.last().pose().transformPosition(
                (float) attachment.x, (float) attachment.y + 0.5f, (float) attachment.z, new Vector3f());
        return new Quaternionf(camera.orientation).invert().transform(world);
    }

    // ---------------- World mode (attached to the nametag) ----------------

    private static void submitWorld(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                    CameraRenderState camera) {
        NametagConfig cfg = NametagConfig.get();
        Vec3 attachment = state.nameTagAttachment;
        Minecraft mc = Minecraft.getInstance();
        if (attachment == null || mc.player == null) return;
        Font font = mc.font;

        poseStack.pushPose();
        // Billboard transformation like the vanilla nametag
        poseStack.translate(attachment.x, attachment.y + 0.5, attachment.z);
        poseStack.mulPose(camera.orientation);
        poseStack.scale(0.025F, -0.025F, 0.025F);

        // Middle line: own name
        if (cfg.showOwnName) {
            FormattedCharSequence seq = mc.player.getDisplayName().getVisualOrderText();
            List<Segment> name = new ArrayList<>();
            name.add(new TextSeg(seq, font.width(seq)));
            submitLine(state, poseStack, collector, 0f, 1f, name);
        }

        // Above: stack upwards, starting with the bottom-most line
        float y = 0f;
        List<NametagConfig.LineData> top = cfg.top;
        for (int i = top.size() - 1; i >= 0; i--) {
            float scale = NametagConfig.clampScale(top.get(i).scale);
            y -= LINE_STEP * scale;
            submitLine(state, poseStack, collector, y - top.get(i).offsetY, scale, parse(top.get(i).text, font));
        }

        // Below: stack downwards, starting under the name
        y = LINE_STEP;
        for (NametagConfig.LineData line : cfg.bottom) {
            float scale = NametagConfig.clampScale(line.scale);
            submitLine(state, poseStack, collector, y - line.offsetY, scale, parse(line.text, font));
            y += LINE_STEP * scale;
        }

        poseStack.popPose();
    }

    /** The single "other players" line, drawn above the vanilla nametag of another player. */
    private static void submitOtherWorld(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                         CameraRenderState camera, UUID uuid, String name) {
        Vec3 attachment = state.nameTagAttachment;
        if (attachment == null) return;
        NametagConfig.LineData line = NametagConfig.get().othersLine;
        float scale = NametagConfig.clampScale(line.scale);

        poseStack.pushPose();
        poseStack.translate(attachment.x, attachment.y + 0.5, attachment.z);
        poseStack.mulPose(camera.orientation);
        poseStack.scale(0.025F, -0.025F, 0.025F);
        submitLine(state, poseStack, collector, -LINE_STEP * scale - line.offsetY, scale,
                parse(line.text, Minecraft.getInstance().font, uuid, name));
        poseStack.popPose();
    }

    private static void submitLine(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                   float yTop, float scale, List<Segment> segments) {
        if (segments.isEmpty()) return;

        float total = 0f;
        for (Segment s : segments) total += s.width();

        boolean discrete = state.isDiscrete;
        Font.DisplayMode mode = discrete ? Font.DisplayMode.NORMAL : Font.DisplayMode.SEE_THROUGH;
        int light = state.lightCoords;

        poseStack.pushPose();
        poseStack.translate(0f, yTop, 0f);
        poseStack.scale(scale, scale, 1f);

        float x = -total / 2f;
        for (Segment seg : segments) {
            if (seg instanceof TextSeg t) {
                // Parameters: x, y, text, shadow, mode, light, color, background, outline
                collector.submitText(poseStack, x, 0f, t.text(), true, mode, light, 0xFFFFFFFF, 0, 0);
            } else if (seg instanceof IconSeg ic) {
                submitIcon(collector, poseStack, ic, x, discrete, light);
            }
            x += seg.width();
        }
        poseStack.popPose();
    }

    private static void submitIcon(SubmitNodeCollector collector, PoseStack poseStack, IconSeg seg,
                                   float x, boolean discrete, int light) {
        final float x0 = x;
        final float x1 = x + seg.width();
        final float y0 = ICON_Y;
        final float y1 = ICON_Y + ICON_HEIGHT;

        RenderType type = discrete
                ? RenderTypes.text(seg.icon().id())
                : RenderTypes.textSeeThrough(seg.icon().id());

        // Animated icons: all frames are stacked vertically, so a frame is a slice of the V range
        IconManager.Icon icon = seg.icon();
        final float v0 = icon.currentFrame() / (float) icon.frames();
        final float v1 = (icon.currentFrame() + 1) / (float) icon.frames();

        // Vertex order and format like text glyphs: position, color, UV, light
        collector.submitCustomGeometry(poseStack, type, (pose, consumer) -> {
            consumer.addVertex(pose, x0, y0, 0f).setColor(-1).setUv(0f, v0).setLight(light);
            consumer.addVertex(pose, x0, y1, 0f).setColor(-1).setUv(0f, v1).setLight(light);
            consumer.addVertex(pose, x1, y1, 0f).setColor(-1).setUv(1f, v1).setLight(light);
            consumer.addVertex(pose, x1, y0, 0f).setColor(-1).setUv(1f, v0).setLight(light);
        });
    }

    // ---------------- Overlay mode (screen projection) ----------------

    /** Called every frame by the HUD element: projects the anchors to the screen and draws the lines. */
    public static void draw(GuiGraphicsExtractor graphics) {
        Vector3f own = pendingView;
        pendingView = null;
        List<OtherAnchor> others = new ArrayList<>(pendingOthers.values());
        pendingOthers.clear();

        NametagConfig cfg = NametagConfig.get();
        Minecraft mc = Minecraft.getInstance();
        if (cfg.attachToNametag || !cfg.enabled || mc.player == null) return;

        Font font = mc.font;
        Matrix3x2fStack pose = graphics.pose();

        // ----- own lines -----
        float[] p = own == null ? null : project(mc, own);
        if (p != null) {
            pose.pushMatrix();
            pose.translate(p[0], p[1]);
            pose.scale(p[2], p[2]);

            // Middle line: own name
            if (cfg.showOwnName) {
                FormattedCharSequence seq = mc.player.getDisplayName().getVisualOrderText();
                List<Segment> name = new ArrayList<>();
                name.add(new TextSeg(seq, font.width(seq)));
                drawLine(graphics, font, 0f, 1f, name);
            }

            // Above: stack upwards, starting with the bottom-most line
            float y = 0f;
            List<NametagConfig.LineData> top = cfg.top;
            for (int i = top.size() - 1; i >= 0; i--) {
                float scale = NametagConfig.clampScale(top.get(i).scale);
                y -= LINE_STEP * scale;
                drawLine(graphics, font, y - top.get(i).offsetY, scale, parse(top.get(i).text, font));
            }

            // Below: stack downwards, starting under the name
            y = LINE_STEP;
            for (NametagConfig.LineData line : cfg.bottom) {
                float scale = NametagConfig.clampScale(line.scale);
                drawLine(graphics, font, y - line.offsetY, scale, parse(line.text, font));
                y += LINE_STEP * scale;
            }
            pose.popMatrix();
        }

        // ----- line above other players -----
        if (cfg.othersEnabled) {
            NametagConfig.LineData line = cfg.othersLine;
            float scale = NametagConfig.clampScale(line.scale);
            for (OtherAnchor a : others) {
                float[] q = project(mc, a.view());
                if (q == null) continue;
                pose.pushMatrix();
                pose.translate(q[0], q[1]);
                pose.scale(q[2], q[2]);
                drawLine(graphics, font, -LINE_STEP * scale - line.offsetY, scale,
                        parse(line.text, font, a.uuid(), a.name()));
                pose.popMatrix();
            }
        }
    }

    /** Camera-space point -> {screenX, screenY, fontScale} in GUI pixels, or null if behind the camera. */
    private static float[] project(Minecraft mc, Vector3f v) {
        float depth = -v.z;
        if (depth < 0.1f) return null;

        float guiW = mc.getWindow().getGuiScaledWidth();
        float guiH = mc.getWindow().getGuiScaledHeight();
        // FOV option is the vertical field of view in degrees
        float tanHalf = (float) Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
        float pxPerUnit = (guiH / 2f) / tanHalf / depth;   // GUI pixels per world unit at this depth

        return new float[]{guiW / 2f + v.x * pxPerUnit, guiH / 2f - v.y * pxPerUnit, 0.025f * pxPerUnit};
    }

    private static void drawLine(GuiGraphicsExtractor graphics, Font font, float yTop, float scale,
                                 List<Segment> segments) {
        if (segments.isEmpty()) return;

        float total = 0f;
        for (Segment s : segments) total += s.width();

        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(0f, yTop);
        pose.scale(scale, scale);

        float x = -total / 2f;
        for (Segment seg : segments) {
            if (seg instanceof TextSeg t) {
                pose.pushMatrix();
                pose.translate(x, 0f);
                graphics.text(font, toComponent(t.text()), 0, 0, 0xFFFFFFFF, true);
                pose.popMatrix();
            } else if (seg instanceof IconSeg ic) {
                drawIcon(graphics, ic, x);
            }
            x += seg.width();
        }
        pose.popMatrix();
    }

    private static void drawIcon(GuiGraphicsExtractor graphics, IconSeg seg, float x) {
        IconManager.Icon icon = seg.icon();
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, ICON_Y);
        pose.scale(seg.width() / icon.width(), ICON_HEIGHT / icon.height());
        // Animated icons: pick the current frame out of the stacked sprite sheet
        graphics.blit(RenderPipelines.GUI_TEXTURED, icon.id(), 0, 0, 0f, (float) (icon.currentFrame() * icon.height()),
                icon.width(), icon.height(), icon.width(), icon.height(), icon.width(), icon.sheetHeight());
        pose.popMatrix();
    }

    /** Converts the styled character sequence (with animated chroma colors) to a Component. */
    private static Component toComponent(FormattedCharSequence seq) {
        MutableComponent out = Component.empty();
        seq.accept((index, style, codePoint) -> {
            out.append(Component.literal(new String(Character.toChars(codePoint))).withStyle(style));
            return true;
        });
        return out;
    }

    // ---------------- Parsing ----------------

    /** Formatting state while walking through a line; carries over icons. */
    private static final class StyleState {
        int color = -1;          // -1 = default color
        boolean chroma;          // &z
        boolean bold, italic, underlined, strikethrough, obfuscated;

        /** True if the character after '&' is a known formatting code (vanilla codes or z). */
        boolean isCode(char c) {
            return CODES.indexOf(Character.toLowerCase(c)) >= 0;
        }

        void apply(char code) {
            code = Character.toLowerCase(code);
            if (code == 'z') {
                chroma = true;
                color = -1;
                return;
            }
            int colorIndex = "0123456789abcdef".indexOf(code);
            if (colorIndex >= 0) {
                // A color code resets other formats (like vanilla)
                reset();
                color = COLORS[colorIndex];
                return;
            }
            switch (code) {
                case 'r' -> reset();
                case 'l' -> bold = true;
                case 'o' -> italic = true;
                case 'n' -> underlined = true;
                case 'm' -> strikethrough = true;
                case 'k' -> obfuscated = true;
                default -> { }
            }
        }

        void reset() {
            color = -1;
            chroma = false;
            bold = italic = underlined = strikethrough = obfuscated = false;
        }

        Style toStyle() {
            Style s = Style.EMPTY;
            if (color >= 0) s = s.withColor(TextColor.fromRgb(color));
            if (bold) s = s.withBold(true);
            if (italic) s = s.withItalic(true);
            if (underlined) s = s.withUnderlined(true);
            if (strikethrough) s = s.withStrikethrough(true);
            if (obfuscated) s = s.withObfuscated(true);
            return s;
        }
    }

    /** Parses a line for the own player. */
    private static List<Segment> parse(String raw, Font font) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return new ArrayList<>();
        return parse(raw, font, mc.player.getUUID(), mc.player.getName().getString());
    }

    /** Parses a line for the given player: {player} and the lookup tokens refer to that player. */
    private static List<Segment> parse(String raw, Font font, UUID player, String playerName) {
        List<Segment> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        raw = raw.replace("{player}", playerName);
        raw = CollectionService.expand(raw, player); // <collection:Name> / <skill:name> -> "amount (#rank)"
        raw = SocialService.expand(raw);             // <youtube:Name> / <twitch:Name> / <tiktok:Name> -> count

        StyleState state = new StyleState();
        int[] charCounter = {0}; // keeps the rainbow continuous across icons

        Matcher m = ICON_TOKEN.matcher(raw);
        int last = 0;
        while (m.find()) {
            addText(out, font, raw.substring(last, m.start()), state, charCounter);
            IconManager.Icon icon = IconManager.get(m.group(1));
            if (icon != null) {
                out.add(new IconSeg(icon, ICON_HEIGHT * icon.width() / (float) icon.height()));
                charCounter[0]++;
            } else {
                addText(out, font, m.group(), state, charCounter); // show unknown icons as plain text
            }
            last = m.end();
        }
        addText(out, font, raw.substring(last), state, charCounter);
        return out;
    }

    /** Parses &-codes in a text part and appends it as one styled segment. */
    private static void addText(List<Segment> out, Font font, String part, StyleState state, int[] charCounter) {
        if (part.isEmpty()) return;

        int[] codePoints = new int[part.length()];
        Style[] styles = new Style[part.length()];
        boolean[] chroma = new boolean[part.length()];
        int n = 0;

        for (int i = 0; i < part.length(); ) {
            int cp = part.codePointAt(i);
            if (cp == '&' && i + 1 < part.length() && state.isCode(part.charAt(i + 1))) {
                state.apply(part.charAt(i + 1));
                i += 2;
                continue;
            }
            codePoints[n] = cp;
            styles[n] = state.toStyle();
            chroma[n] = state.chroma;
            n++;
            i += Character.charCount(cp);
        }
        if (n == 0) return;

        final int count = n;
        final int offset = charCounter[0];
        charCounter[0] += n;

        // The color of chroma characters is calculated while drawing, so it animates
        FormattedCharSequence seq = sink -> {
            float base = (System.currentTimeMillis() % CHROMA_PERIOD_MS) / (float) CHROMA_PERIOD_MS;
            for (int i = 0; i < count; i++) {
                Style style = styles[i];
                if (chroma[i]) {
                    float hue = base - (offset + i) * CHROMA_SPREAD;
                    hue -= (float) Math.floor(hue);
                    style = style.withColor(TextColor.fromRgb(Mth.hsvToRgb(hue, 0.9f, 1.0f)));
                }
                if (!sink.accept(i, style, codePoints[i])) return false;
            }
            return true;
        };
        out.add(new TextSeg(seq, font.width(seq)));
    }
}
