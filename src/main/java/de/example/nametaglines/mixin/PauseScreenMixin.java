package de.example.nametaglines.mixin;

import de.example.nametaglines.gui.NametagEditorScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a "NameTagMod" button to the game menu (ESC). */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void nametaglines$addButton(CallbackInfo ci) {
        int w = 204;
        int h = 20;
        int x = this.width / 2 - w / 2;

        // Place the button below the lowest widget of the menu
        int lowest = 0;
        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget widget) {
                lowest = Math.max(lowest, widget.getBottom());
            }
        }
        int y = lowest + 4;

        // No room below (or nothing found): put a smaller button into the top-left corner
        if (lowest == 0 || y + h > this.height - 4) {
            x = 8;
            y = 8;
            w = 110;
        }

        this.addRenderableWidget(Button.builder(Component.literal("NameTagMod"),
                        btn -> this.minecraft.gui.setScreen(new NametagEditorScreen(this)))
                .bounds(x, y, w, h).build());
    }
}
