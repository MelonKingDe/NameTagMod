package de.example.nametaglines.gui;

import de.example.nametaglines.IconManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Pick an icon from config/nametaglines/icons. */
public class IconPickerScreen extends Screen {
    private static final int COLS = 3;
    private static final int ROWS = 5;
    private static final int BTN_W = 112;
    private static final int BTN_H = 20;
    private static final int GAP = 4;
    private static final int START_Y = 40;
    private static final int ICON_BOX = 16;

    private record Placed(IconManager.Icon icon, int x, int y) {}

    private final Screen parent;
    private final Consumer<String> onPick;
    private final List<Placed> placed = new ArrayList<>();
    private int page = 0;

    public IconPickerScreen(Screen parent, Consumer<String> onPick) {
        super(Component.literal("Select icon"));
        this.parent = parent;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        placed.clear();
        List<IconManager.Icon> icons = new ArrayList<>(IconManager.all());

        int perPage = COLS * ROWS;
        int pages = Math.max(1, (icons.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));

        int totalW = COLS * BTN_W + (COLS - 1) * GAP;
        int startX = this.width / 2 - totalW / 2;

        for (int k = 0; k < perPage; k++) {
            int index = page * perPage + k;
            if (index >= icons.size()) break;
            IconManager.Icon icon = icons.get(index);
            int x = startX + (k % COLS) * (BTN_W + GAP);
            int y = START_Y + (k / COLS) * (BTN_H + GAP);

            String label = "    " + this.font.plainSubstrByWidth(icon.name(), BTN_W - 30);
            this.addRenderableWidget(Button.builder(Component.literal(label), btn -> {
                onPick.accept(icon.name());
                onClose();
            }).bounds(x, y, BTN_W, BTN_H).build());
            placed.add(new Placed(icon, x + 3, y + 2));
        }

        // Bottom row: < | Reload | Back | >
        int by = this.height - 26;
        int cx = this.width / 2;
        Button prev = Button.builder(Component.literal("<"), btn -> {
            page--;
            this.rebuildWidgets();
        }).bounds(cx - 130, by, 30, 20).build();
        prev.active = page > 0;
        this.addRenderableWidget(prev);

        this.addRenderableWidget(Button.builder(Component.literal("Reload"), btn -> {
            IconManager.reload();
            page = 0;
            this.rebuildWidgets();
        }).bounds(cx - 96, by, 90, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), btn -> onClose())
                .bounds(cx + 6, by, 90, 20).build());

        final int lastPage = pages - 1;
        Button next = Button.builder(Component.literal(">"), btn -> {
            page++;
            this.rebuildWidgets();
        }).bounds(cx + 100, by, 30, 20).build();
        next.active = page < lastPage;
        this.addRenderableWidget(next);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        center(graphics, "Select icon", this.width / 2, 12, 0xFFFFFFFF);

        // Draw icons onto the buttons (scaled to fit 16x16)
        for (Placed p : placed) {
            IconManager.Icon icon = p.icon();
            float s = Math.min(ICON_BOX / (float) icon.width(), ICON_BOX / (float) icon.height());
            int w = Math.max(1, Math.round(icon.width() * s));
            int h = Math.max(1, Math.round(icon.height() * s));
            int x = p.x() + (ICON_BOX - w) / 2;
            int y = p.y() + (ICON_BOX - h) / 2;
            graphics.blit(RenderPipelines.GUI_TEXTURED, icon.id(), x, y, 0f, (float) (icon.currentFrame() * icon.height()), w, h,
                    icon.width(), icon.height(), icon.width(), icon.sheetHeight());
        }

        if (placed.isEmpty()) {
            center(graphics, "No icons found.", this.width / 2, 60, 0xFFFFAAAA);
            center(graphics, "Put PNG or GIF files in this folder and click 'Reload':", this.width / 2, 76, 0xFFAAAAAA);
            center(graphics, "config/nametaglines/icons", this.width / 2, 90, 0xFFFFFFFF);
        }
    }

    private void center(GuiGraphicsExtractor graphics, String text, int cx, int y, int color) {
        graphics.text(this.font, text, cx - this.font.width(text) / 2, y, color, true);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
