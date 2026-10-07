package com.pockyl.hoarfrost.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.sim.Terrain;
import com.pockyl.hoarfrost.weather.Noise;

/**
 * The snowfall beyond the simulated flakes: rings of curtains around the camera, textured with a sheet of tiny flakes
 * that falls and drifts with the wind. Each ring is coarser and higher than the one inside it, so the curtains give
 * the falling snow its depth, and denser bands of snow travel through them with the wind; they fade into the fog.
 * Curtains stand on the land the snow reaches (they never hang in caves) and only where it snows.
 */
final class SnowCurtains {
    static final ResourceLocation TEXTURE = Hoarfrost.id("textures/fx/snow_curtain.png");
    private static final float SEGMENT = 3.0F;
    private static final float FADE = 2.5F;
    private static final int BAND_SEED = 0x5C17;

    private int lastQuads;

    int lastQuads() {
        return lastQuads;
    }

    /**
     * @param inner radius of the simulated flakes; the curtains start just outside
     * @param strength 0..1, the curtains' opacity for this snowfall
     * @param storm 0..1, how stormy the wind is: in a blizzard the curtains are dense and the flakes in them streak
     *        sideways with the wind
     */
    void render(BufferBuilder builder, Terrain terrain, double camX, double camY, double camZ, float inner, int rings, float strength,
            double driftX, double driftZ, double fallen, float windX, float windZ, float fallSpeed, float storm) {
        lastQuads = 0;
        if (strength <= 0.01F || rings <= 0) {
            return;
        }
        for (int ring = 0; ring < rings; ring++) {
            float radius = inner + 2.0F + ring * (5.0F + ring * 2.5F);
            float scale = 1.0F + radius / 40.0F;
            float tileW = 6.0F * scale * (1.0F + 3.0F * storm);
            float tileH = 12.0F * scale;
            float top = (float) (camY + 26.0 + radius * 0.25);
            float alpha = strength * (0.55F - 0.04F * ring) * (1.0F + 0.6F * storm);
            int segments = Math.max(12, Mth.ceil(Mth.TWO_PI * radius / SEGMENT));
            float step = Mth.TWO_PI / segments;
            float offset = ring * 0.37F;
            for (int s = 0; s < segments; s++) {
                float a0 = s * step + offset;
                float a1 = a0 + step;
                float c0 = Mth.cos(a0);
                float s0 = Mth.sin(a0);
                float c1 = Mth.cos(a1);
                float s1 = Mth.sin(a1);
                double x0 = camX + c0 * radius;
                double z0 = camZ + s0 * radius;
                double x1 = camX + c1 * radius;
                double z1 = camZ + s1 * radius;
                int mx = Mth.floor((x0 + x1) * 0.5);
                int mz = Mth.floor((z0 + z1) * 0.5);
                if (!terrain.isLoaded(mx, mz) || !terrain.snowy(mx, mz)) {
                    continue;
                }
                float ground = Math.max(terrain.height(mx, mz), (float) camY - 20.0F);
                if (ground + FADE >= top) {
                    continue;
                }
                // Bands of denser snow travelling with the wind.
                double bandX = (x0 + x1) * 0.5 - driftX;
                double bandZ = (z0 + z1) * 0.5 - driftZ;
                float band = 0.45F + 0.75F * Noise.value(bandX / 22.0, bandZ / 22.0, BAND_SEED);
                int a = (int) (Mth.clamp(alpha * band, 0.0F, 1.0F) * 255.0F);
                if (a < 3) {
                    continue;
                }
                // Texture coordinates: along the ring, falling down and drifting sideways with the wind; the
                // sheet slants with the wind like the flakes in it.
                float tangentX = -Mth.sin(a0 + step * 0.5F);
                float tangentZ = Mth.cos(a0 + step * 0.5F);
                float sideways = windX * tangentX + windZ * tangentZ;
                float slant = fallSpeed > 1.0E-3F ? Mth.clamp(sideways / fallSpeed, -4.0F, 4.0F) : 0.0F;
                double drift = driftX * tangentX + driftZ * tangentZ;
                float u0 = (float) ((s * SEGMENT - drift) / tileW + ring * 0.31);
                float u1 = u0 + SEGMENT / tileW;
                float vScroll = (float) (-fallen / tileH + ring * 0.53);
                float vTop = (float) (top - camY);
                float vGround = (float) (ground - camY);
                float vFade = vGround + FADE;
                float rx0 = (float) (x0 - camX);
                float rz0 = (float) (z0 - camZ);
                float rx1 = (float) (x1 - camX);
                float rz1 = (float) (z1 - camZ);
                // Upper part: full strength. Lower part: fades in from the ground.
                // Lit like the open ground there: daylight by day, dark at night, lamplight near a village.
                int light = terrain.lightAt(mx + 0.5, ground + 0.5, mz + 0.5);
                quad(builder, rx0, rz0, rx1, rz1, vFade, vTop, u0, u1, vScroll, tileH, tileW, slant, a, a, light);
                quad(builder, rx0, rz0, rx1, rz1, vGround, vFade, u0, u1, vScroll, tileH, tileW, slant, 0, a, light);
                lastQuads += 2;
            }
        }
    }

    private static void quad(BufferBuilder builder, float x0, float z0, float x1, float z1, float bottom, float top, float u0, float u1,
            float scroll, float tileH, float tileW, float slant, int alphaBottom, int alphaTop, int light) {
        // Texture v grows downwards; a slanted sheet shifts u with height.
        float vb = -bottom / tileH + scroll;
        float vt = -top / tileH + scroll;
        float ub = bottom * slant / tileW;
        float ut = top * slant / tileW;
        builder.addVertex(x0, top, z0).setUv(u0 + ut, vt).setColor(255, 255, 255, alphaTop).setLight(light);
        builder.addVertex(x0, bottom, z0).setUv(u0 + ub, vb).setColor(255, 255, 255, alphaBottom).setLight(light);
        builder.addVertex(x1, bottom, z1).setUv(u1 + ub, vb).setColor(255, 255, 255, alphaBottom).setLight(light);
        builder.addVertex(x1, top, z1).setUv(u1 + ut, vt).setColor(255, 255, 255, alphaTop).setLight(light);
    }
}
