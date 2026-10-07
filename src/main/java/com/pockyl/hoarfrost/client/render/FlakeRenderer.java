package com.pockyl.hoarfrost.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.sim.FlakePool;

/**
 * Writes the flakes and grains of the simulation as camera-facing quads in the particle vertex format, interpolated
 * between ticks. Flakes have their real size - a few millimetres to a few centimetres - and soft shapes: specks, clumps
 * and, in light calm snow, six-armed crystals that turn and tumble as they fall. Far away a flake never gets smaller
 * than about a pixel and a half, so the snowfall does not vanish into the distance; a flake right in front of the eye
 * is out of focus (a soft, faint blob); a flake driven fast across the view (a blizzard) is drawn as a streak, as the
 * eye sees it.
 */
final class FlakeRenderer {
    static final ResourceLocation TEXTURE = Hoarfrost.id("textures/fx/flakes.png");
    private static final int SPRITES = 8;
    private static final int CLUMP = 1;
    private static final int CRYSTAL_FIRST = 2;
    private static final int CRYSTAL_LAST = 4;
    private static final int BLOB = 5;
    private static final int GRAIN = 6;
    private static final int STREAK = 7;
    /** Blocks per unit of a flake's size (the simulation's sizes are in "pixels"): size 1 is about 11 mm. */
    private static final float SCALE = 0.011F;
    /** Smallest size on screen, as a share of the distance: about two pixels at 1080p. */
    private static final float MIN_ANGLE = 0.0024F;
    /** The shapes fill this much of their sprite. */
    private static final float FILL = 0.7F;
    private static final float NEAR = 0.45F;

    private int lastCount;

    int lastCount() {
        return lastCount;
    }

    void render(BufferBuilder builder, FlakePool pool, double camX, double camY, double camZ, float partialTick, Vector3f left, Vector3f up,
            Vector3f look) {
        int count = 0;
        for (int i = 0, high = pool.highWater(); i < high; i++) {
            byte state = pool.state[i];
            if (state == FlakePool.FREE) {
                continue;
            }
            float x = (float) (Mth.lerp(partialTick, pool.prevX[i], pool.x[i]) - camX);
            float y = (float) (Mth.lerp(partialTick, pool.prevY[i], pool.y[i]) - camY);
            float z = (float) (Mth.lerp(partialTick, pool.prevZ[i], pool.z[i]) - camZ);
            float ahead = x * look.x() + y * look.y() + z * look.z();
            if (ahead < 0.05F) {
                continue;
            }
            float distance = Mth.sqrt(x * x + y * y + z * z);
            float alpha = pool.alpha[i] * (state == FlakePool.GRAIN ? 0.85F : 0.95F);
            int sprite = pool.sprite[i];
            if (state == FlakePool.GRAIN) {
                sprite = pool.size[i] > 1.4F ? CLUMP : GRAIN;
            }
            float world = Math.max(pool.size[i] * SCALE, distance * MIN_ANGLE);
            if (state == FlakePool.FALLING && distance < NEAR) {
                // Too close to focus on.
                sprite = BLOB;
                world *= 2.5F;
                alpha *= 0.1F + 0.5F * distance / NEAR;
            }
            // Smaller than its minimum on screen: drawn at the minimum, but fainter, like a speck.
            alpha *= Math.min(1.0F, 0.65F + pool.size[i] * SCALE / Math.max(1.0E-4F, distance * MIN_ANGLE));
            if (alpha < 0.02F) {
                continue;
            }
            float half = world * 0.5F / FILL;
            float halfA = half;
            float ax;
            float ay;
            float az;
            float bx;
            float by;
            float bz;
            float vx = (float) (pool.x[i] - pool.prevX[i]);
            float vy = (float) (pool.y[i] - pool.prevY[i]);
            float vz = (float) (pool.z[i] - pool.prevZ[i]);
            float nx = x / distance;
            float ny = y / distance;
            float nz = z / distance;
            float along = vx * nx + vy * ny + vz * nz;
            float px = vx - nx * along;
            float py = vy - ny * along;
            float pz = vz - nz * along;
            float across = Mth.sqrt(px * px + py * py + pz * pz);
            float bright = 0.9F + 0.1F * ((i * 0x9E3779B9 >>> 24) / 255.0F);
            boolean crystal = sprite >= CRYSTAL_FIRST && sprite <= CRYSTAL_LAST;
            if (state == FlakePool.SETTLED) {
                // Lying on the snow or a block: flat, slightly turned.
                float angle = pool.spin[i];
                ax = Mth.cos(angle);
                ay = 0.0F;
                az = Mth.sin(angle);
                bx = -az;
                by = 0.0F;
                bz = ax;
                y += 0.003F;
            } else if (across > 0.12F && !crystal && sprite != BLOB) {
                // A streak along the motion across the view: the long axis of the streak sprite is u.
                sprite = STREAK;
                ax = px / across;
                ay = py / across;
                az = pz / across;
                bx = ny * az - nz * ay;
                by = nz * ax - nx * az;
                bz = nx * ay - ny * ax;
                halfA = half + across * 0.9F;
                half *= 0.6F;
                alpha *= Math.max(0.5F, 1.0F - across * 0.6F);
            } else if (crystal) {
                float angle = Mth.lerp(partialTick, pool.prevSpin[i], pool.spin[i]);
                float c = Mth.cos(angle);
                float s = Mth.sin(angle);
                ax = left.x() * c + up.x() * s;
                ay = left.y() * c + up.y() * s;
                az = left.z() * c + up.z() * s;
                bx = up.x() * c - left.x() * s;
                by = up.y() * c - left.y() * s;
                bz = up.z() * c - left.z() * s;
                // Tumbling: the crystal turns between face-on and edge-on as it falls.
                halfA = half * (0.3F + 0.7F * Math.abs(Mth.cos(pool.phase[i] * 0.6F)));
                if (Mth.sin(pool.phase[i] * 2.3F + i) > 0.985F) {
                    // Turned to the light for a moment: it glints.
                    bright = 1.0F;
                    alpha = 1.0F;
                }
            } else {
                ax = left.x();
                ay = left.y();
                az = left.z();
                bx = up.x();
                by = up.y();
                bz = up.z();
            }
            float u0 = sprite / (float) SPRITES;
            float u1 = (sprite + 1) / (float) SPRITES;
            int light = pool.light[i];
            int a = (int) (Math.min(1.0F, alpha) * 255.0F);
            int red = (int) (242.0F * bright);
            int green = (int) (247.0F * bright);
            int blue = (int) (255.0F * bright);
            ax *= halfA;
            ay *= halfA;
            az *= halfA;
            bx *= half;
            by *= half;
            bz *= half;
            builder.addVertex(x - ax - bx, y - ay - by, z - az - bz).setUv(u1, 1.0F).setColor(red, green, blue, a).setLight(light);
            builder.addVertex(x - ax + bx, y - ay + by, z - az + bz).setUv(u1, 0.0F).setColor(red, green, blue, a).setLight(light);
            builder.addVertex(x + ax + bx, y + ay + by, z + az + bz).setUv(u0, 0.0F).setColor(red, green, blue, a).setLight(light);
            builder.addVertex(x + ax - bx, y + ay - by, z + az - bz).setUv(u0, 1.0F).setColor(red, green, blue, a).setLight(light);
            count++;
        }
        lastCount = count;
    }
}
