package com.pockyl.hoarfrost.client.render;

import com.mojang.blaze3d.systems.RenderSystem;

import java.util.function.IntSupplier;

/**
 * The haze of falling snow as set up for this frame, kept for whoever draws fog after the vanilla fog event: mods that
 * turn the vanilla fog off (Distant Horizons does by default, to show its distant land) would take the snowstorm with
 * it, and Distant Horizons draws its own fog over its distant land.
 */
public final class SnowFog {
    private static final IntSupplier NO_DISTANT_LAND = () -> 0;

    private static boolean terrain;
    private static float near;
    private static float far;
    /** Where the vanilla chunks end and distant land (if any) begins. */
    private static float seam;
    /** The haze this frame, for distant land; 0 without one. */
    private static float strength;
    private static float red;
    private static float green;
    private static float blue;
    private static IntSupplier distantLand = NO_DISTANT_LAND;

    private SnowFog() {
    }

    /**
     * Where the view ends in a haze between clear air and a storm: {@code k} of the storm's haze (0..1) between a view
     * of {@code clear} and one of {@code hazy} blocks. Haze adds up as thickness per block (1 / distance), not as
     * distance: a little of a storm already hides land thousands of blocks deep (Distant Horizons), while mixing the
     * distances themselves kept such land clear until the storm was at full strength.
     */
    public static float hazeDistance(float clear, float hazy, float k) {
        float t = Mth.clamp(k, 0.0F, 1.0F);
        return 1.0F / (1.0F / clear + (1.0F / hazy - 1.0F / clear) * t);
    }

    /** A mod that draws land beyond the vanilla render distance tells how far, in blocks (0 when it draws none now). */
    public static void setDistantLand(IntSupplier blocks) {
        distantLand = blocks;
    }

    /** How far distant land reaches beyond the vanilla chunks, in blocks; 0 without it. */
    public static int distantReach() {
        return distantLand.getAsInt();
    }

    /**
     * The snowy haze of the land this frame (the fog event set it). With distant land its far plane may lie beyond the
     * vanilla chunks ({@code vanillaFar}): the near land then carries only the beginning of the haze.
     */
    public static void setTerrain(float nearPlane, float farPlane, float vanillaFar, float k) {
        terrain = true;
        near = nearPlane;
        far = farPlane;
        seam = vanillaFar;
        strength = k;
    }

    /** No snowy haze this frame. */
    public static void clear() {
        terrain = false;
        strength = 0.0F;
    }

    public static void setColor(float r, float g, float b) {
        red = r;
        green = g;
        blue = b;
    }

    /** After the vanilla fog is set up: puts the snowy haze back if something pushed the fog further away since. */
    public static void afterSetup(boolean terrainMode) {
        if (!terrainMode || !terrain) {
            return;
        }
        terrain = false;
        if (RenderSystem.getShaderFogEnd() > far + 0.5F) {
            RenderSystem.setShaderFogStart(near);
            RenderSystem.setShaderFogEnd(far);
        }
    }

    public static float strength() {
        return strength;
    }

    public static float near() {
        return near;
    }

    public static float far() {
        return far;
    }

    public static float seam() {
        return seam;
    }

    public static float red() {
        return red;
    }

    public static float green() {
        return green;
    }

    public static float blue() {
        return blue;
    }
}
