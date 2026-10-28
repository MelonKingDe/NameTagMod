package de.example.nametaglines.gui;

import de.example.nametaglines.NametagConfig;
import de.example.nametaglines.NametagConfig.LineData;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * Settings for the one line that is shown above the nametags of OTHER players near you.
 * {player} is replaced by their name; skill/collection tokens are looked up for each player.
 */
public class PlayerLineScreen extends Screen {
    private static final int START_Y = 58;

    private final Screen parent;

    public PlayerLineScreen(Screen parent) {
        super(Component.literal("Other players"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        NametagConfig cfg = NametagConfig.get();
        LineData line = cfg.othersLine;

        int w = Math.min(300, this.width - 20);
        int x = this.width / 2 - w / 2;
        int y = START_Y;

        // On / off
        this.addRenderableWidget(Button.builder(enabledLabel(cfg), btn -> {
            cfg.othersEnabled = !cfg.othersEnabled;
            btn.setMessage(enabledLabel(cfg));
        }).bounds(x, y, w, 20).build());
        y += 26;

        // Text + icon button
        EditBox box = new EditBox(this.font, x, y, w - 34, 20, Component.literal("Text"));
        box.setMaxLength(NametagConfig.MAX_TEXT);
        box.setValue(line.text);
        box.setHint(Component.literal("{player} <skill:social> &6"));
        box.setResponder(text -> line.text = text);
        this.addRenderableWidget(box);

        this.addRenderableWidget(Button.builder(Component.literal("Icon"), btn -> {
            String text = line.text;
            int pos = Math.max(0, Math.min(text.length(), box.getCursorPosition()));
            this.minecraft.gui.setScreen(new IconPickerScreen(this, name ->
                    line.text = text.substring(0, pos) + "{icon:" + name + "}" + text.substring(pos)));
        }).bounds(x + w - 30, y, 30, 20).build());
        y += 26;

        // Scale + shift
        int scaleW = (int) (w * 0.58);
        this.addRenderableWidget(new NametagEditorScreen.ScaleSlider(x, y, scaleW, 14, line));
        this.addRenderableWidget(new NametagEditorScreen.OffsetSlider(x + scaleW + 4, y, w - scaleW - 4, 14, line));
        y += 22;

        // Limits so that not too many lookups happen
        this.addRenderableWidget(new IntSlider(x, y, w, 20, "Max distance (blocks)", 4, 64,
                cfg.othersMaxDistance, v -> cfg.othersMaxDistance = v));
        y += 24;
        this.addRenderableWidget(new IntSlider(x, y, w, 20, "Max players", 1, 20,
                cfg.othersMaxPlayers, v -> cfg.othersMaxPlayers = v));

        this.addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose())
                .bounds(this.width / 2 - 75, this.height - 26, 150, 20).build());
    }

    private static Component enabledLabel(NametagConfig cfg) {
        return Component.literal("Line above other players: " + (cfg.othersEnabled ? "on" : "off"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int cx = this.width / 2;
        center(graphics, "Other players", cx, 10, 0xFFFFFFFF);
        center(graphics, "One line above the nametags of players near you.", cx, 24, 0xFFAAAAAA);
        center(graphics, "{player} = their name. Skills / collections are", cx, 34, 0xFFAAAAAA);
        center(graphics, "looked up for each player.", cx, 44, 0xFFAAAAAA);

        int hy = START_Y + 26 + 26 + 22 + 24 + 24 + 6;
        center(graphics, "Their UUIDs are sent to the Elite API for lookups.", cx, hy, 0xFF777777);
        center(graphics, "Use Shift (+) to raise the line if it overlaps text.", cx, hy + 11, 0xFF777777);
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

    /** Slider for a whole number between min and max. */
    private static class IntSlider extends AbstractSliderButton {
        private final String label;
        private final int min;
        private final int max;
        private final IntConsumer onChange;

        IntSlider(int x, int y, int width, int height, String label, int min, int max, int current,
                  IntConsumer onChange) {
            super(x, y, width, height, Component.empty(),
                    (double) (Math.max(min, Math.min(max, current)) - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.onChange = onChange;
            updateMessage();
        }

        private int value() {
            return (int) Math.round(this.min + this.value * (this.max - this.min));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(this.label + ": " + value()));
        }

        @Override
        protected void applyValue() {
            this.onChange.accept(value());
        }
    }
}
