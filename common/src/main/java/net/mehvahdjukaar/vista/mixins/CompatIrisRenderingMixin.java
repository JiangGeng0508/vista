package net.mehvahdjukaar.vista.mixins;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.mehvahdjukaar.vista.integration.iris.IrisCompat;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(IrisRenderingPipeline.class)
public class CompatIrisRenderingMixin {

    // Feed passes used to skip shadow rendering entirely (a leftover from the mirror era's global
    // state hacks). That leaves the feed sampling a shadow map centered on the PLAYER: pack fog and
    // volumetric light wash the whole feed white whenever the player walks away from the TV, and
    // recover when they come back into coverage. Render shadows for the feed camera like any other
    // pass; each feed pipeline owns its shadow targets, so there is nothing to clobber.
    @WrapWithCondition(method = "renderShadows", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;renderShadows(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;Lnet/minecraft/client/Camera;)V"))
    private boolean vista$renderShadowsForFeedCamera(ShadowRenderer instance, LevelRendererAccessor fullyBufferedMultiBufferSource, Camera camera) {
        return true;
    }

    // A feed pipeline has the same shaderpack block-id layout as the already active main pipeline.
    // Calling allChanged() here destroys the main world's section buffers in the middle of an
    // off-screen pass. The feed then commits an empty frame and stays black while the scheduler
    // waits for the resulting build queue. The main pipeline owns rebuilds during a pack reload;
    // suppress this duplicate request for feed-local pipelines.
    @WrapWithCondition(method = "beginLevelRendering", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;allChanged()V"))
    private boolean vista$skipFirstFrameAllChanged(LevelRenderer instance) {
        if (IrisCompat.isFeedRendering()) {
            return false;
        }
        return true;
    }
}
