package de.example.nametaglines.gui;

import de.example.nametaglines.NametagConfig;
import de.example.nametaglines.NametagConfig.LineData;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Editor for the extra lines: text, scale (slider), vertical shift (slider) and icons.
 * Changes apply immediately and are saved when the screen closes.
 * If there are more lines than fit on the screen, use the ^ / v buttons to scroll.
 */
public class NametagEditorScreen extends Screen {
    private static final Identifier LOGO =
            Identifier.fromNamespaceAndPath("nametaglines", "textures/gui/logo.png");
    private static final int LOGO_TEX_W = 354;
    private static final int LOGO_TEX_H = 129;

    private static final int TOP = 58;
    private static final int ROW_STEP = 38;

    private final Screen parent;
    /** Index of the first visible line (both columns scroll together). */
    private int scroll = 0;

    public NametagEditorScreen(Screen parent) {
        super(Component.literal("NameTagMod"));
        this.parent = parent;
    }

    private int colW() {
        return Math.min(210, (this.width - 30) / 2);
    }

    /** How many line rows fit between the headers and the bottom buttons. */
    private int visibleRows() {
        return Math.max(1, (this.height - 44 - TOP - 22) / ROW_STEP);
    }

    /** Lines plus the "+ Add line" row (if there is still room for another line). */
    private static int rowsNeeded(List<LineData> lines) {
        return lines.size() + (lines.size() < NametagConfig.MAX_LINES ? 1 : 0);
    }

    @Override
    protected void init() {
        NametagConfig cfg = NametagConfig.get();
        int colW = colW();
        int cx = this.width / 2;
        int leftX = cx - colW - 5;
        int rightX = cx + 5;

        int visible = visibleRows();
        int maxScroll = Math.max(0, Math.max(rowsNeeded(cfg.top), rowsNeeded(cfg.bottom)) - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        buildColumn(leftX, colW, cfg.top, visible);
        buildColumn(rightX, colW, cfg.bottom, visible);

        // Scroll buttons between the two column headers
        if (maxScroll > 0) {
            Button up = Button.builder(Component.literal("^"), btn -> {
                scroll = Math.max(0, scroll - 1);
                this.rebuildWidgets();
            }).bounds(cx - 22, TOP - 14, 20, 12).build();
            up.active = scroll > 0;
            this.addRenderableWidget(up);

            Button down = Button.builder(Component.literal("v"), btn -> {
                scroll = Math.min(maxScroll, scroll + 1);
                this.rebuildWidgets();
            }).bounds(cx + 2, TOP - 14, 20, 12).build();
            down.active = scroll < maxScroll;
            this.addRenderableWidget(down);
        }

        // Top corners: tutorial (left) and line for other players (right)
        this.addRenderableWidget(Button.builder(Component.literal("Tutorial"),
                        btn -> this.minecraft.gui.setScreen(new TutorialScreen(this)))
                .bounds(8, 6, 90, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Other players"),
                        btn -> this.minecraft.gui.setScreen(new PlayerLineScreen(this)))
                .bounds(this.width - 108, 6, 100, 20).build());

        // Bottom row: four buttons
        int total = colW * 2 + 10;
        int bw = (total - 12) / 4;
        int by = this.height - 26;
        this.addRenderableWidget(Button.builder(enabledLabel(cfg), btn -> {
            cfg.enabled = !cfg.enabled;
            btn.setMessage(enabledLabel(cfg));
        }).bounds(leftX, by, bw, 20).build());

        this.addRenderableWidget(Button.builder(ownNameLabel(cfg), btn -> {
            cfg.showOwnName = !cfg.showOwnName;
            btn.setMessage(ownNameLabel(cfg));
        }).bounds(leftX + bw + 4, by, bw, 20).build());

        this.addRenderableWidget(Button.builder(modeLabel(cfg), btn -> {
            cfg.attachToNametag = !cfg.attachToNametag;
            btn.setMessage(modeLabel(cfg));
        }).bounds(leftX + 2 * (bw + 4), by, bw, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose())
                .bounds(leftX + 3 * (bw + 4), by, bw, 20).build());
    }

    private void buildColumn(int x, int colW, List<LineData> lines, int visible) {
        final int iconW = 30;
        final int delW = 22;
        int boxW = colW - iconW - delW - 8;

        int end = Math.min(lines.size(), scroll + visible);
        for (int i = scroll; i < end; i++) {
            final int idx = i;
            final LineData line = lines.get(i);
            int y = TOP + (i - scroll) * ROW_STEP;

            EditBox box = new EditBox(this.font, x, y, boxW, 20, Component.literal("Line " + (i + 1)));
            box.setMaxLength(NametagConfig.MAX_TEXT);
            box.setValue(line.text);
            box.setHint(Component.literal("Line " + (i + 1)));
            box.setResponder(text -> line.text = text);
            this.addRenderableWidget(box);

            // Insert icon at the cursor position (start, end or middle of the text)
            this.addRenderableWidget(Button.builder(Component.literal("Icon"), btn -> {
                String text = line.text;
                int pos = Math.max(0, Math.min(text.length(), box.getCursorPosition()));
                this.minecraft.gui.setScreen(new IconPickerScreen(this, name ->
                        line.text = text.substring(0, pos) + "{icon:" + name + "}" + text.substring(pos)));
            }).bounds(x + boxW + 4, y, iconW, 20).build());

            this.addRenderableWidget(Button.builder(Component.literal("X"), btn -> {
                lines.remove(idx);
                this.rebuildWidgets();
            }).bounds(x + boxW + iconW + 8, y, delW, 20).build());

            // Two sliders under the text box: scale (left) and vertical shift (right)
            int scaleW = (int) (colW * 0.58);
            this.addRenderableWidget(new ScaleSlider(x, y + 21, scaleW, 12, line));
            this.addRenderableWidget(new OffsetSlider(x + scaleW + 4, y + 21, colW - scaleW - 4, 12, line));
        }

        // "+ Add line" sits right after the last line, if that row is visible
        if (lines.size() < NametagConfig.MAX_LINES) {
            int slot = lines.size() - scroll;
            if (slot >= 0 && slot < visible) {
                this.addRenderableWidget(Button.builder(Component.literal("+ Add line"), btn -> {
                    lines.add(new LineData());
                    this.rebuildWidgets();
                }).bounds(x, TOP + slot * ROW_STEP, colW, 20).build());
            }
        }
    }

    private static Component enabledLabel(NametagConfig cfg) {
        return Component.literal("Extra lines: " + (cfg.enabled ? "on" : "off"));
    }

    private static Component modeLabel(NametagConfig cfg) {
        return Component.literal("View: " + (cfg.attachToNametag ? "nametag" : "overlay"));
    }

    private static Component ownNameLabel(NametagConfig cfg) {
        return Component.literal("Own name: " + (cfg.showOwnName ? "on" : "off"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        NametagConfig cfg = NametagConfig.get();
        int cx = this.width / 2;
        int colW = colW();
        int leftX = cx - colW - 5;
        int rightX = cx + 5;

        // Logo image (contains "NameTagMod" and "by MelonKingDe")
        int logoH = 42;
        int logoW = Math.round(logoH * (float) LOGO_TEX_W / LOGO_TEX_H);
        graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO, cx - logoW / 2, 1, 0f, 0f, logoW, logoH,
                LOGO_TEX_W, LOGO_TEX_H, LOGO_TEX_W, LOGO_TEX_H);

        center(graphics, "Above the name (" + cfg.top.size() + "/" + NametagConfig.MAX_LINES + ")",
                leftX + colW / 2, TOP - 12, 0xFFFFFFFF);
        center(graphics, "Below the name (" + cfg.bottom.size() + "/" + NametagConfig.MAX_LINES + ")",
                rightX + colW / 2, TOP - 12, 0xFFFFFFFF);

        center(graphics, "Preview with F5  -  Icons: config/nametaglines/icons", cx, this.height - 38, 0xFF777777);
    }

    private void center(GuiGraphicsExtractor graphics, String text, int cx, int y, int color) {
        graphics.text(this.font, text, cx - this.font.width(text) / 2, y, color, true);
    }

    @Override
    public void removed() {
        NametagConfig.save();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    /** Slider for the vertical shift in pixels: positive = up, negative = down. */
    static class OffsetSlider extends AbstractSliderButton {
        private static final int MIN = -30;
        private static final int MAX = 30;
        private final LineData line;

        OffsetSlider(int x, int y, int width, int height, LineData line) {
            super(x, y, width, height, Component.empty(), toValue(line.offsetY));
            this.line = line;
            updateMessage();
        }

        private static double toValue(float offset) {
            double o = Math.max(MIN, Math.min(MAX, offset));
            return (o - MIN) / (MAX - MIN);
        }

        private int offset() {
            return (int) Math.round(MIN + this.value * (MAX - MIN));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format("Shift: %+d", offset())));
        }

        @Override
        protected void applyValue() {
            this.line.offsetY = offset();
        }
    }

    /** Slider for the line scale (0.25x to 3x, in steps of 0.05). */
    static class ScaleSlider extends AbstractSliderButton {
        private static final double MIN = 0.25;
        private static final double MAX = 3.0;
        private final LineData line;

        ScaleSlider(int x, int y, int width, int height, LineData line) {
            super(x, y, width, height, Component.empty(), toValue(line.scale));
            this.line = line;
            updateMessage();
        }

        private static double toValue(float scale) {
            double s = Math.max(MIN, Math.min(MAX, NametagConfig.clampScale(scale)));
            return (s - MIN) / (MAX - MIN);
        }

        private double scale() {
            return Math.round((MIN + this.value * (MAX - MIN)) * 20.0) / 20.0;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format("Scale: %.2fx", scale())));
        }

        @Override
        protected void applyValue() {
            this.line.scale = (float) scale();
        }
    }
}
