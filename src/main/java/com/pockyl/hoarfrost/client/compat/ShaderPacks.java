package com.pockyl.hoarfrost.client.compat;

import net.neoforged.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Shader packs of <a href="https://modrinth.com/mod/iris">Iris</a> (or its Forge port Oculus), asked through Iris' public
 * API by reflection, so neither is needed to build or run the mod.
 * <p>
 * Flakes and snow cover use vanilla shaders, which Iris swaps for the pack's programs; what changes with a pack is the vertex
 * layout of the meshes (see {@code CoverRenderer}).
 */
public final class ShaderPacks {
    private static final MethodHandle IN_USE;
    private static final MethodHandle PIPELINE;
    private static final MethodHandle GET_PHASE;
    private static final MethodHandle SET_PHASE;
    private static final Object WEATHER_PHASE;

    static {
        MethodHandle inUse = null;
        MethodHandle pipeline = null;
        MethodHandle getPhase = null;
        MethodHandle setPhase = null;
        Object weatherPhase = null;
        if (ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus")) {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object instance = api.getMethod("getInstance").invoke(null);
                inUse = lookup.findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class)).bindTo(instance);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                inUse = null;
            }
            // Internals (not part of the API): without them flakes drawn outside the vanilla weather pass use the pack's
            // particle program.
            try {
                Class<?> manager = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
                Class<?> worldPipeline = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPipeline");
                Class<?> phase = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPhase");
                pipeline = MethodHandles.filterReturnValue(
                        lookup.findStatic(Class.forName("net.irisshaders.iris.Iris"), "getPipelineManager", MethodType.methodType(manager)),
                        lookup.findVirtual(manager, "getPipelineNullable", MethodType.methodType(worldPipeline)))
                        .asType(MethodType.methodType(Object.class));
                getPhase = lookup.findVirtual(worldPipeline, "getPhase", MethodType.methodType(phase))
                        .asType(MethodType.methodType(Object.class, Object.class));
                setPhase = lookup.findVirtual(worldPipeline, "setPhase", MethodType.methodType(void.class, phase))
                        .asType(MethodType.methodType(void.class, Object.class, Object.class));
                weatherPhase = phase.getField("RAIN_SNOW").get(null);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                pipeline = null;
                getPhase = null;
                setPhase = null;
                weatherPhase = null;
            }
        }
        IN_USE = inUse;
        PIPELINE = pipeline;
        GET_PHASE = getPhase;
        SET_PHASE = setPhase;
        WEATHER_PHASE = weatherPhase;
    }

    private ShaderPacks() {
    }

    /** Whether a shader pack is active right now (packs can be switched in game). */
    public static boolean inUse() {
        if (IN_USE == null) {
            return false;
        }
        try {
            return (boolean) IN_USE.invokeExact();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Puts the pack into its weather pass, as if the vanilla weather were being drawn: Iris only does that around the
     * vanilla call, so flakes drawn after it would otherwise get the pack's particle program.
     *
     * @return what to give {@link #endWeatherPhase} afterwards; null when nothing was changed
     */
    public static Object beginWeatherPhase() {
        if (PIPELINE == null || !inUse()) {
            return null;
        }
        try {
            Object pipeline = (Object) PIPELINE.invokeExact();
            if (pipeline == null) {
                return null;
            }
            Object previous = (Object) GET_PHASE.invokeExact(pipeline);
            SET_PHASE.invokeExact(pipeline, WEATHER_PHASE);
            return new Object[] {pipeline, previous};
        } catch (Throwable e) {
            return null;
        }
    }

    /** Puts the pack back into the pass it was in before {@link #beginWeatherPhase}. */
    public static void endWeatherPhase(Object token) {
        if (!(token instanceof Object[] saved) || SET_PHASE == null) {
            return;
        }
        try {
            SET_PHASE.invokeExact(saved[0], saved[1]);
        } catch (Throwable ignored) {
            // The pack resets its pass for the next frame itself.
        }
    }
}
