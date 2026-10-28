package de.example.nametaglines.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import de.example.nametaglines.render.NametagRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState; // fix import via IDE if needed
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into rendering of players. The renderer decides what to do: draw the extra lines for the own
 * player and, if enabled, the line above other players.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "submit", at = @At("TAIL"))
    private void nametaglines$submitLines(EntityRenderState state, PoseStack poseStack,
                                          SubmitNodeCollector collector, CameraRenderState camera,
                                          CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState)) return;
        NametagRenderer.capture(state, poseStack, collector, camera);
    }
}
