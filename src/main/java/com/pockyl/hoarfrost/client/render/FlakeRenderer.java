package com.pockyl.hoarfrost.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.sim.FlakePool;

/**
 * Writes the flakes and grains of the simulation as camera-facing quads in the particle vertex format, interpolated
 * between ticks. Flakes are pixel art like vanilla's snow: single pixels, small crosses and, for large flakes, stars
 * that turn slowly as they tumble. A flake right in front of the eye is out of focus (a soft, faint blob); a flake
 * driven fast across the view (a blizzard) is drawn as a short streak, as the eye sees it.
 */
final class FlakeRenderer {
    static final ResourceLocation TEXTURE = Hoarfrost.id("textures/fx/flakes.png");
    private static final int SPRITES = 8;
    /** Size of the shape inside each 8x8 sprite, in texels. */
    private static final float[] SHAPE = {1.0F, 2.0F, 3.0F, 5.0F, 7.0F, 4.0F, 1.0F, 1.0F};
    private static final int BLOB = 5;
    private static final int GRAIN = 6;
    private static final float NEAR = 1.2F;

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
            float alpha = pool.alpha[i] * (state == FlakePool.GRAIN ? 0.8F : 0.92F);
            int sprite = pool.sprite[i];
            float size = pool.size[i];
            if (state == FlakePool.GRAIN) {
                sprite = GRAIN;
            } else if (state == FlakePool.FALLING && distance < NEAR) {
                // Too close to focus on.
                sprite = BLOB;
                size *= 1.6F;
                alpha *= 0.12F + 0.5F * distance / NEAR;
            }
            if (alpha < 0.02F) {
                continue;
            }
            float half = 4.0F * size / SHAPE[sprite] / 16.0F;
            float ax;
            float ay;
            float az;
            float bx;
            float by;
            float bz;
            float halfA = half;
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
            if (state != FlakePool.SETTLED && across > 0.09F && sprite <= 2) {
                // A streak along the motion across the view.
                ax = px / across;
                ay = py / across;
                az = pz / across;
                bx = ny * az - nz * ay;
                by = nz * ax - nx * az;
                bz = nx * ay - ny * ax;
                halfA = half + across * 1.1F;
                alpha *= Math.max(0.55F, 1.0F - across * 0.8F);
            } else if (sprite == 3 || sprite == 4) {
                float angle = Mth.lerp(partialTick, pool.prevSpin[i], pool.spin[i]);
                float c = Mth.cos(angle);
                float s = Mth.sin(angle);
                ax = left.x() * c + up.x() * s;
                ay = left.y() * c + up.y() * s;
                az = left.z() * c + up.z() * s;
                bx = up.x() * c - left.x() * s;
                by = up.y() * c - left.y() * s;
                bz = up.z() * c - left.z() * s;
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
            ax *= halfA;
            ay *= halfA;
            az *= halfA;
            bx *= half;
            by *= half;
            bz *= half;
            builder.addVertex(x - ax - bx, y - ay - by, z - az - bz).setUv(u1, 1.0F).setColor(242, 247, 255, a).setLight(light);
            builder.addVertex(x - ax + bx, y - ay + by, z - az + bz).setUv(u1, 0.0F).setColor(242, 247, 255, a).setLight(light);
            builder.addVertex(x + ax + bx, y + ay + by, z + az + bz).setUv(u0, 0.0F).setColor(242, 247, 255, a).setLight(light);
            builder.addVertex(x + ax - bx, y + ay - by, z + az - bz).setUv(u0, 1.0F).setColor(242, 247, 255, a).setLight(light);
            count++;
        }
        lastCount = count;
    }
}
