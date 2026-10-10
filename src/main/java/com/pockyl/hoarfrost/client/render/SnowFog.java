package com.pockyl.hoarfrost.client.render;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * The haze of falling snow as set up for this frame, kept for whoever draws fog after the vanilla fog event: mods that
 * turn the vanilla fog off (Distant Horizons does by default, to show its distant land) would take the snowstorm with
 * it, and Distant Horizons draws its own fog over its distant land.
 */
public final class SnowFog {
    private static boolean terrain;
    private static float near;
    private static float far;
    /** The haze this frame, for distant land; 0 without one. */
    private static float strength;
    private static float red;
    private static float green;
    private static float blue;

    private SnowFog() {
    }

    /** The snowy haze of the land this frame (the fog event set it). */
    public static void setTerrain(float nearPlane, float farPlane, float k) {
        terrain = true;
        near = nearPlane;
        far = farPlane;
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
