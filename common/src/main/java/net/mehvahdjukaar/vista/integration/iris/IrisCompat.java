package net.mehvahdjukaar.vista.integration.iris;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.BlendModeStorage;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.ShaderRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.mehvahdjukaar.moonlight.api.platform.configs.ConfigBuilder;
import net.mehvahdjukaar.vista.integration.CompatHandler;
import net.mehvahdjukaar.vista.VistaMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.lwjgl.opengl.ARBClearTexture;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class IrisCompat {

    // true while Vista is rendering a camera pass (should work)
    private static final WorldRenderingPipeline VISTA_PIPELINE = createFeedPipeline();
    private static final ThreadLocal<Boolean> VISTA_RENDERING = ThreadLocal.withInitial(() -> false);
    private static Supplier<Boolean> irisShaderPacksOff;

    // Iris's frame counter and timer advance once per game frame, but feeds run slower (10Hz by
    // default), so shader pack TAA jitter would skip samples between observations and never resolve.
    // These feed-local clocks advance one step per feed render instead. See the two Compat mixins.
    private static int feedFrameCounter = 0;
    private static float feedFrameTimeCounter = 0F;
    private static float feedLastFrameTime = 1F / 60F;
    private static long feedLastFrameNanos = 0L;

    public static boolean isFeedRendering() {
        return VISTA_RENDERING.get();
    }

    // Whether an Iris shaderpack pipeline is currently driving world rendering. Used to gate the
    // custom CRT screen shader, which is not safe under an active pack.
    public static boolean hasActiveShaderPack() {
        return CompatHandler.IRIS
                && Iris.getPipelineManager().getPipelineNullable() instanceof ShaderRenderingPipeline;
    }

    // A bare static Iris flips at the head and return of renderLevel, so nesting a second one inside
    // the main pass leaves it stuck false. See the call site in VistaLevelRenderer#renderLevel.
    public static boolean isIrisRenderingLevel() {
        return ImmediateState.isRenderingLevel;
    }

    public static void setIrisRenderingLevel(boolean renderingLevel) {
        ImmediateState.isRenderingLevel = renderingLevel;
    }

    public static int getFeedFrameCounter() {
        return feedFrameCounter;
    }

    public static float getFeedFrameTimeCounter() {
        return feedFrameTimeCounter;
    }

    public static float getFeedLastFrameTime() {
        return feedLastFrameTime;
    }

    private static void advanceFeedClocks() {
        feedFrameCounter = (feedFrameCounter + 1) % 720720;
        long now = System.nanoTime();
        if (feedLastFrameNanos != 0L) {
            float dt = (float) ((now - feedLastFrameNanos) / 1_000_000_000.0);
            feedLastFrameTime = dt;
            feedFrameTimeCounter += dt;
            if (feedFrameTimeCounter >= 3600F) {
                feedFrameTimeCounter = 0F;
            }
        }
        feedLastFrameNanos = now;
    }

    // Iris detects a recreated render target through version counters that only increment in
    // destroyBuffers, so a brand new RenderTarget starts at 0 just like the old one did. Resizing a TV
    // swaps in exactly that, and Iris keeps its gbuffers on the old (possibly freed) depth texture.
    //
    // Feed pipelines can be shared by same-size canvases, so the bump has to happen on EVERY feed
    // render (not only when the canvas instance changes) and has to be globally monotonic. A plain
    // +1 gives two freshly created canvases the same version (1), and the pipeline then skips the
    // depth re-attach for the second canvas, leaving its depth/gbuffer attachments pointing at the
    // first TV's buffers -- one TV's picture then bleeds into the other until the pipeline is
    // reloaded.
    private static final AtomicInteger FEED_CANVAS_VERSION = new AtomicInteger(0);

    public static void onFeedCanvasBound(RenderTarget canvas, String texturePath) {
        CURRENT_FEED_TEXTURE.set(texturePath);
        bumpIrisVersionCounters(canvas);
    }

    private static void bumpIrisVersionCounters(RenderTarget canvas) {
        if (DEPTH_BUFFER_VERSION_FIELD == null && COLOR_BUFFER_VERSION_FIELD == null) return;
        int version = FEED_CANVAS_VERSION.updateAndGet(v -> v == Integer.MAX_VALUE ? 1 : v + 1);
        try {
            if (DEPTH_BUFFER_VERSION_FIELD != null) {
                DEPTH_BUFFER_VERSION_FIELD.setInt(canvas, version);
            }
            if (COLOR_BUFFER_VERSION_FIELD != null) {
                COLOR_BUFFER_VERSION_FIELD.setInt(canvas, version);
            }
        } catch (IllegalAccessException e) {
            VistaMod.LOGGER.warn("Failed to bump Iris render-target version counters", e);
        }
    }

    // The dimension key the feed pass renders under. One pipeline per canvas (TV texture) keeps
    // each camera's temporal buffers (TAA history, SSR, ...) isolated. Same-size TVs sharing one
    // pipeline alternate canvases every render and each composite blends the other camera's previous
    // frame into its own -- the cross-camera ghosting this whole file is trying to avoid. Pipeline
    // creation is expensive, so the number of issued per-canvas keys is capped; past the cap feeds
    // fall back to sharing a pipeline per canvas size, which is handled by onFeedPipelineBound's
    // full clear below.
    public static final int MAX_FEED_PIPELINES = 6;
    private static final Set<String> ISSUED_FEED_KEYS = new HashSet<>();

    @Nullable
    public static String feedPipelineKey() {
        String texturePath = CURRENT_FEED_TEXTURE.get();
        if (texturePath == null) return null;
        if (ISSUED_FEED_KEYS.contains(texturePath)) return texturePath;
        if (ISSUED_FEED_KEYS.size() >= MAX_FEED_PIPELINES) {
            // "<w>x<h>": the shared per-size key used before canvas isolation.
            return texturePath.substring(texturePath.lastIndexOf('_') + 1);
        }
        ISSUED_FEED_KEYS.add(texturePath);
        return texturePath;
    }

    // Iris's full clear follows the pack's attachment clear colors. In BSL/Bliss that includes a
    // white gbuffer clear that becomes visible in an off-screen feed after a shaderpack reload.
    // Each feed repopulates its gbuffers, while clearTemporalHistory resets the few buffers that
    // legitimately persist between frames, so suppress Iris's feed-local full clear.

    public static void onFeedPipelineBound(WorldRenderingPipeline pipeline, RenderTarget canvas) {
        RenderTarget last = LAST_FEED_CANVAS.put(pipeline, canvas);
        if (last != null && last != canvas) {
            VistaMod.LOGGER.info("[VistaFeed] pipeline canvas switch #{} -> #{}: clearing temporal buffers",
                    System.identityHashCode(last), System.identityHashCode(canvas));
            // The unconditional clear below handles the shared pipeline; this is only diagnostic.

        }
        // The feed renders at 10Hz by default, so between two frames a moving entity jumps by a
        // large pixel distance. BSL/Bliss TAA has no per-entity motion vectors; it reprojects only
        // the camera, so its neighbourhood clamp cannot reject that jump and persistent buffers
        // carry prior camera frames into the current composite. Clearing only the known temporal
        // buffers before each feed render prevents them from contaminating the next composite.



        clearTemporalHistory(pipeline);
        suppressFullClear(pipeline);
    }

    // Main render thread only, keyed weakly so pipelines destroyed on a pack reload don't leak.
    private static final Map<WorldRenderingPipeline, RenderTarget> LAST_FEED_CANVAS = new WeakHashMap<>();

    private static void suppressFullClear(WorldRenderingPipeline pipeline) {
        // Toggling shaderpacks off mid-session makes preparePipeline hand back a bare
        // VanillaRenderingPipeline for the feed dimension, which has no renderTargets field.
        if (!(pipeline instanceof IrisRenderingPipeline)) return;
        if (PIPELINE_RENDER_TARGETS_FIELD == null || FULL_CLEAR_REQUIRED_FIELD == null) return;
        try {
            Object renderTargets = PIPELINE_RENDER_TARGETS_FIELD.get(pipeline);
            if (renderTargets != null) {
                FULL_CLEAR_REQUIRED_FIELD.setBoolean(renderTargets, false);
            }
        } catch (ReflectiveOperationException e) {
            VistaMod.LOGGER.warn("Failed to suppress Iris full clear for feed", e);
        }
    }

    // BSL stores TAA/exposure history in colortex2, Bliss uses colortex5, and both retain
    // colored-light history in colortex9. Clear only these ping-pong pairs: full-clearing the
    // pack's gbuffers, including colortex1, clears that attachment to white and washes the
    // composite out, so it is never used for feed pipelines.
    private static final int[] TEMPORAL_BUFFER_INDICES = {2, 5, 9};
    private static void clearTemporalHistory(WorldRenderingPipeline pipeline) {
        if (!(pipeline instanceof IrisRenderingPipeline)) return;
        if (PIPELINE_RENDER_TARGETS_FIELD == null) return;
        try {
            Object renderTargets = PIPELINE_RENDER_TARGETS_FIELD.get(pipeline);
            if (renderTargets == null) return;
            for (int index : TEMPORAL_BUFFER_INDICES) {
                Object temporalTarget = renderTargets.getClass().getMethod("get", int.class).invoke(renderTargets, index);
                if (temporalTarget == null) continue;
                clearIrisTexture(temporalTarget, "getMainTexture");
                clearIrisTexture(temporalTarget, "getAltTexture");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            VistaMod.LOGGER.warn("Failed to clear Iris TAA history for feed", e);
        }
    }

    private static void clearIrisTexture(Object irisRenderTarget, String getterName)
            throws ReflectiveOperationException {
        int texture = (Integer) irisRenderTarget.getClass().getMethod(getterName).invoke(irisRenderTarget);
        if (texture != 0) {
            // data == null fills the texture with zeroes; format/type just describe the incoming
            // data, so RGBA/UNSIGNED_BYTE is valid for the pack's float colour targets too.
            ARBClearTexture.glClearTexImage(texture, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (int[]) null);
        }
    }

    // The feed pipeline's first beginLevelRendering registers the pack's block-id map and is due
    // to rebuild every section so meshes carry it. That rebuild is deferred out of the feed pass
    // (tearing down the geometry being drawn wrecks it) and runs once the outermost feed finishes.
    public static void scheduleWorldRebuild() {
        PENDING_WORLD_REBUILD = true;
    }

    private static boolean PENDING_WORLD_REBUILD;

    public static void runPendingWorldRebuild(boolean outermostFeedFinished) {
        if (!outermostFeedFinished || !PENDING_WORLD_REBUILD) return;
        PENDING_WORLD_REBUILD = false;
        LevelRenderer levelRenderer = Minecraft.getInstance().levelRenderer;
        if (levelRenderer != null) {
            VistaMod.LOGGER.info("[VistaFeed] running deferred world rebuild for block id init");
            levelRenderer.allChanged();
        }
    }

    @Nullable
    private static Field lookupField(Class<?> clazz, String name) {
        try {
            Field f = clazz.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    @Nullable
    private static final Field DEPTH_BUFFER_VERSION_FIELD = lookupField(RenderTarget.class, "iris$depthBufferVersion");
    @Nullable
    private static final Field COLOR_BUFFER_VERSION_FIELD = lookupField(RenderTarget.class, "iris$colorBufferVersion");
    // IrisRenderingPipeline.renderTargets and RenderTargets.fullClearRequired: arming the flag makes
    // the next beginLevelRendering run its full clear passes over every colortex, history included.
    @Nullable
    private static final Field PIPELINE_RENDER_TARGETS_FIELD = lookupField(IrisRenderingPipeline.class, "renderTargets");
    @Nullable
    private static final Field FULL_CLEAR_REQUIRED_FIELD = lookupField(RenderTargets.class, "fullClearRequired");

    // VanillaRenderingPipeline's constructor is not inert: it rewrites WorldRenderingSettings as if a
    // pack had just unloaded, and each setter arms Iris's reload flag. It happens to land on untouched
    // defaults today only because addConfigs touches the class at startup before any pack exists. Load
    // it later and it would reset the vertex format under a live pack, forcing a terrain rebuild
    // mid-frame. Hence the snapshot and restore.
    //
    // Fields are copied directly rather than through the getters: those name Sodium and Minecraft
    // types, and the Iris artifact on the NeoForge compile classpath still carries Fabric mappings,
    // so they're unusable from common. Walking the fields also covers the reload flag itself.
    private static WorldRenderingPipeline createFeedPipeline() {
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        Map<Field, Object> snapshot = new LinkedHashMap<>();
        try {
            for (Field field : WorldRenderingSettings.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                snapshot.put(field, field.get(settings));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            VistaMod.LOGGER.warn("Failed to snapshot Iris world rendering settings", e);
            snapshot.clear();
        }

        WorldRenderingPipeline pipeline = new VanillaRenderingPipeline();

        try {
            for (Map.Entry<Field, Object> entry : snapshot.entrySet()) {
                entry.getKey().set(settings, entry.getValue());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            VistaMod.LOGGER.warn("Failed to restore Iris world rendering settings", e);
        }
        return pipeline;
    }

    @Nullable
    public static WorldRenderingPipeline getModifiedPipeline() {
        return VISTA_RENDERING.get() && irisShaderPacksOff.get() ? VISTA_PIPELINE : null;
    }

    // Whether the feed pass gets its own IrisRenderingPipeline. Sharing one means its render targets
    // get resized between the feed canvas and the main framebuffer every frame, and RenderTargets
    // reallocates every gbuffer on a size change, so it flickers and costs a lot.
    public static boolean shouldSwapDimensionForFeed() {
        return VISTA_RENDERING.get() && !irisShaderPacksOff.get();
    }

    private static final ThreadLocal<String> CURRENT_FEED_TEXTURE = new ThreadLocal<>();

    public static Runnable decorateRendererWithoutShaderPacks(Runnable renderTask) {
        return () -> {
            LevelRenderer lr = Minecraft.getInstance().levelRenderer;
            PipelineManager pm = Iris.getPipelineManager();
            boolean oldShadowActive = ShadowRenderer.ACTIVE;
            boolean oldVistaRendering = VISTA_RENDERING.get();
            OldRenderState oldState = OldRenderState.loadFrom(CapturedRenderingState.INSTANCE);

            WorldRenderingPipeline oldLrPipeline = getCurrentPipeline(lr);
            // Iris's own MixinLevelRenderer only restores the LevelRenderer-side copy, so this
            // singleton has to be saved too or anything reading the pipeline manager between the feed
            // render and the next main renderLevel gets the stub VanillaRenderingPipeline.
            WorldRenderingPipeline oldPmPipeline = getPipelineManagerPipeline(pm);

            try {
                ShadowRenderer.ACTIVE = false;
                VISTA_RENDERING.set(true);
                releaseIrisStateLocks();
                // Only the outermost pass is one feed frame. Recursive mirrors nest through here, and
                // bumping the global jitter clock per level lands right back on skipped samples.
                if (!oldVistaRendering) advanceFeedClocks();
                renderTask.run();
            } finally {
                ShadowRenderer.ACTIVE = oldShadowActive;
                VISTA_RENDERING.set(oldVistaRendering);
                oldState.saveTo(CapturedRenderingState.INSTANCE);
                setCurrentPipeline(lr, oldLrPipeline);
                setPipelineManagerPipeline(pm, oldPmPipeline);
            }
        };
    }

    // A pack program that overrode blend or the color/depth mask leaves Iris's global storages locked,
    // recording every later GlStateManager call instead of applying it until an Iris program hands the
    // state back. Feeds run on a VanillaRenderingPipeline where no Iris program ever applies, so
    // nothing releases the lock and the whole nested render draws with the pack's frozen state. That's
    // what makes blended geometry come out opaque in a TV or mirror.
    //
    // Releasing applies the right state, not a stale one: the locked calls were deferred into the
    // storages and hold what the last vanilla caller asked for. No need to re-lock either, the next
    // pack program re-applies its override anyway.
    private static void releaseIrisStateLocks() {
        BlendModeStorage.restoreBlend();
        DepthColorStorage.unlockDepthColor();
    }

    private static void setCurrentPipeline(LevelRenderer lr, WorldRenderingPipeline oldPipeline) {
        try {
            VANILLA_PIPELINE_FIELD.set(lr, oldPipeline);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static WorldRenderingPipeline getCurrentPipeline(LevelRenderer lr) {
        try {
            return (WorldRenderingPipeline) VANILLA_PIPELINE_FIELD.get(lr);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Field VANILLA_PIPELINE_FIELD = Arrays.stream(LevelRenderer.class.getDeclaredFields())
            .filter(f -> f.getType().equals(WorldRenderingPipeline.class))
            .findFirst()
            .map(p -> {
                p.setAccessible(true);
                return p;
            })
            .orElseThrow(() -> new RuntimeException("Failed to find vanilla pipeline field!"));

    private static final Field PIPELINE_MANAGER_PIPELINE_FIELD = Arrays.stream(PipelineManager.class.getDeclaredFields())
            .filter(f -> f.getType().equals(WorldRenderingPipeline.class))
            .findFirst()
            .map(p -> {
                p.setAccessible(true);
                return p;
            })
            .orElseThrow(() -> new RuntimeException("Failed to find PipelineManager.pipeline field!"));

    private static WorldRenderingPipeline getPipelineManagerPipeline(PipelineManager pm) {
        try {
            return (WorldRenderingPipeline) PIPELINE_MANAGER_PIPELINE_FIELD.get(pm);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setPipelineManagerPipeline(PipelineManager pm, WorldRenderingPipeline pipeline) {
        try {
            PIPELINE_MANAGER_PIPELINE_FIELD.set(pm, pipeline);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean shouldSkipBobbing() {
        return VISTA_RENDERING.get();
    }

    public static boolean shouldShushDHCompat() {
        return VISTA_RENDERING.get();
    }

    private record OldRenderState(
            Matrix4fc gbufferModelView,
            Matrix4fc gbufferProjection,
            Vector3d fogColor,
            float fogDensity,
            float darknessLightFactor,
            float tickDelta,
            float realTickDelta,
            int currentRenderedBlockEntity,
            int currentRenderedEntity,
            int currentRenderedItem,
            float currentAlphaTest,
            float cloudTime) {

        public void saveTo(CapturedRenderingState state) {
            state.setGbufferModelView(gbufferModelView);
            state.setGbufferProjection((Matrix4f) gbufferProjection);
            state.setFogColor((float) fogColor.x, (float) fogColor.y, (float) fogColor.z);
            state.setFogDensity(fogDensity);
            state.setDarknessLightFactor(darknessLightFactor);
            state.setTickDelta(tickDelta);
            state.setRealTickDelta(realTickDelta);
            state.setCurrentBlockEntity(currentRenderedBlockEntity);
            state.setCurrentEntity(currentRenderedEntity);
            state.setCurrentRenderedItem(currentRenderedItem);
            state.setCurrentAlphaTest(currentAlphaTest);
            state.setCloudTime(cloudTime);
        }

        public static OldRenderState loadFrom(CapturedRenderingState state) {
            return new OldRenderState(
                    new Matrix4f(state.getGbufferModelView()),
                    new Matrix4f(state.getGbufferProjection()),
                    new Vector3d(state.getFogColor()),
                    state.getFogDensity(), state.getDarknessLightFactor(), state.getTickDelta(),
                    state.getRealTickDelta(), state.getCurrentRenderedBlockEntity(), state.getCurrentRenderedEntity(),
                    state.getCurrentRenderedItem(), state.getCurrentAlphaTest(), state.getCloudTime());
        }
    }

    public static void addConfigs(ConfigBuilder builder) {
        irisShaderPacksOff = builder
                .comment("Attempts to disable iris shaders in the live feed view")
                .define("iris_off_hack", true);
    }
}
