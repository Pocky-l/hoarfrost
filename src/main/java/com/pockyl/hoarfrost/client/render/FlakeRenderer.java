package com.pockyl.hoarfrost.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.sim.Flakes;

/**
 * Draws the flakes and powder of the simulation in the weather pass, as camera-facing quads in the particle vertex format
 * with the vanilla particle shader (which shader packs swap for their weather program), interpolated between ticks.
 *
 * <ul>
 *   <li>Flakes are a little larger than life with soft shapes: specks and round flakes, six-armed crystals that turn and
 *   tumble (edge-on, face-on) and glint now and then, fluffy clumps of crystals, hard grains in blizzards.</li>
 *   <li>A flake too small on screen to show its shape is drawn as a soft speck; far away a flake never gets smaller than
 *   a couple of pixels and fades instead, so the snowfall does not vanish into the distance.</li>
 *   <li>A flake right in front of the eye is out of focus: a large, faint, soft disc.</li>
 *   <li>A flake driven fast across the view (a blizzard) is drawn as a streak along its path, as the eye sees it.</li>
 * </ul>
 */
public final class FlakeRenderer implements AutoCloseable {
    private static final ResourceLocation TEXTURE = Hoarfrost.id("textures/fx/flakes.png");
    private static final int SPRITES = 16;
    private static final int STREAK = 8;
    private static final int BOKEH = 9;
    /** Blocks per centimetre of a flake: drawn about twice life size, so flakes read on screen as they do to the eye. */
    private static final float CM = 0.022F;
    /** Smallest size on screen, as a share of the distance: about three pixels at 1080p. */
    private static final float MIN_ANGLE = 0.003F;
    /** Shapes are only drawn above this size on screen (share of the distance); below it a soft speck reads the same. */
    private static final float DETAIL_ANGLE = 0.007F;
    /** The shapes fill this much of their sprite. */
    private static final float FILL = 0.72F;
    private static final float FOCUS = 0.5F;
    /** Speed across the view (blocks per tick) from which a flake is seen as a streak: only in strong wind. */
    private static final float STREAK_SPEED = 0.14F;

    private final ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 21);
    private int lastCount;

    public int lastCount() {
        return lastCount;
    }

    public void render(Flakes flakes, LightTexture lightTexture, Camera camera, float partialTick, double camX, double camY, double camZ) {
        if (flakes.count() == 0) {
            lastCount = 0;
            return;
        }
        lightTexture.turnOnLightLayer();
        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        Minecraft.getInstance().getTextureManager().getTexture(TEXTURE).setFilter(true, false);
        RenderSystem.setShaderTexture(0, TEXTURE);
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        write(builder, flakes, camX, camY, camZ, partialTick, camera.getLeftVector(), camera.getUpVector(), camera.getLookVector(),
                viewCone());
        MeshData mesh = builder.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        lightTexture.turnOffLightLayer();
    }

    /** Cosine of the half angle of a cone around the view direction that holds the whole screen, with a margin. */
    private static float viewCone() {
        Minecraft minecraft = Minecraft.getInstance();
        // The field of view widens when sprinting or flying fast: allow for it.
        double vertical = Math.toRadians(minecraft.options.fov().get() * 1.3);
        double aspect = (double) minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getHeight());
        double diagonal = Math.atan(Math.tan(vertical * 0.5) * Math.sqrt(1.0 + aspect * aspect));
        return (float) Math.cos(Math.min(diagonal + 0.15, Math.PI * 0.5));
    }

    private void write(BufferBuilder builder, Flakes flakes, double camX, double camY, double camZ, float partialTick, Vector3f left,
            Vector3f up, Vector3f look, float view) {
        int count = 0;
        // Clouds first: they are behind and around the flakes.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0, high = flakes.highWater(); i < high; i++) {
                byte kind = flakes.kind[i];
                if (kind == Flakes.FREE || flakes.alpha[i] <= 0.01F || (kind == Flakes.CLOUD) != (pass == 0)) {
                    continue;
                }
                float x = (float) (Mth.lerp(partialTick, flakes.prevX[i], flakes.x[i]) - camX);
                float y = (float) (Mth.lerp(partialTick, flakes.prevY[i], flakes.y[i]) - camY);
                float z = (float) (Mth.lerp(partialTick, flakes.prevZ[i], flakes.z[i]) - camZ);
                float ahead = x * look.x() + y * look.y() + z * look.z();
                float distance = Mth.sqrt(x * x + y * y + z * z);
                if (kind == Flakes.CLOUD) {
                    // Large: may reach into view from behind or beside the camera.
                    if (ahead < -flakes.size[i]) {
                        continue;
                    }
                } else if (ahead < 0.05F || ahead < distance * view && distance > 1.0F) {
                    continue;
                }
                float size = kind == Flakes.CLOUD ? flakes.size[i] : flakes.size[i] * CM;
                float alpha = flakes.alpha[i] * switch (kind) {
                    case Flakes.FAR -> 0.8F;
                    case Flakes.STREAM -> 0.7F;
                    case Flakes.POWDER -> flakes.shape[i] == Flakes.PUFF ? 0.42F : 0.9F;
                    // A cloud is thin where you stand in it: it only shows as a cloud from a little way off.
                    case Flakes.CLOUD -> 0.3F * smoothstep(flakes.size[i] * 0.3F, flakes.size[i] * 1.1F, distance);
                    default -> 0.92F;
                };
                int sprite = flakes.shape[i];
                boolean shaped = sprite == Flakes.CRYSTAL || sprite == Flakes.CRYSTAL_ALT || sprite == Flakes.CLUMP || sprite == Flakes.CLUMP_ALT;
                if (shaped && size < distance * DETAIL_ANGLE) {
                    sprite = Flakes.ROUND;
                    shaped = false;
                }
                float world = Math.max(size, distance * MIN_ANGLE);
                // Drawn at its least size on screen, a tiny flake is a fainter speck.
                alpha *= Math.min(1.0F, 0.6F + size / Math.max(1.0E-4F, distance * MIN_ANGLE));
                if (kind != Flakes.FAR && kind != Flakes.CLOUD && distance < FOCUS && sprite != Flakes.PUFF) {
                    // Too close to focus on.
                    sprite = BOKEH;
                    shaped = false;
                    world *= 2.6F;
                    alpha *= 0.2F + 0.4F * distance / FOCUS;
                }
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
                float nx = x / distance;
                float ny = y / distance;
                float nz = z / distance;
                float vx = (float) (flakes.x[i] - flakes.prevX[i]);
                float vy = (float) (flakes.y[i] - flakes.prevY[i]);
                float vz = (float) (flakes.z[i] - flakes.prevZ[i]);
                float along = vx * nx + vy * ny + vz * nz;
                float px = vx - nx * along;
                float py = vy - ny * along;
                float pz = vz - nz * along;
                float across = Mth.sqrt(px * px + py * py + pz * pz);
                float bright = 0.9F + 0.1F * ((i * 0x9E3779B9 >>> 24) / 255.0F);
                // The eye smears a flake driven fast across the view (a gale), crossing its own size many times a tick.
                float smear = across / Math.max(world, 1.0E-3F);
                if (across > STREAK_SPEED && smear > 4.0F && !shaped && sprite != BOKEH && sprite != Flakes.PUFF && sprite != Flakes.VEIL) {
                    sprite = STREAK;
                    ax = px / across;
                    ay = py / across;
                    az = pz / across;
                    bx = ny * az - nz * ay;
                    by = nz * ax - nx * az;
                    bz = nx * ay - ny * ax;
                    halfA = half + across * 0.75F;
                    half *= 0.7F;
                    alpha *= Math.max(0.45F, 1.0F - (smear - 4.0F) * 0.04F);
                } else if (shaped || sprite == Flakes.GRAIN || sprite == Flakes.PUFF || sprite == Flakes.VEIL) {
                    float angle = Mth.lerp(partialTick, flakes.prevSpin[i], flakes.spin[i]);
                    float c = Mth.cos(angle);
                    float s = Mth.sin(angle);
                    ax = left.x() * c + up.x() * s;
                    ay = left.y() * c + up.y() * s;
                    az = left.z() * c + up.z() * s;
                    bx = up.x() * c - left.x() * s;
                    by = up.y() * c - left.y() * s;
                    bz = up.z() * c - left.z() * s;
                    if (sprite == Flakes.CRYSTAL || sprite == Flakes.CRYSTAL_ALT) {
                        // Tumbling: the crystal turns between face-on and edge-on as it falls, and glints when it faces the light.
                        float tumble = Mth.cos(flakes.phase[i] * 0.6F);
                        halfA = half * (0.25F + 0.75F * Math.abs(tumble));
                        if (tumble > 0.97F) {
                            bright = 1.0F;
                            alpha = Math.min(1.0F, alpha * 1.25F);
                        }
                    } else if (shaped) {
                        halfA = half * (0.75F + 0.25F * Math.abs(Mth.cos(flakes.phase[i] * 0.4F)));
                    } else if (kind == Flakes.CLOUD) {
                        // Wider than high, and reaching down no further than the ground below it.
                        halfA = half * 1.3F;
                        half = Math.min(half, Math.max(0.3F, (float) (y + camY - flakes.phase[i]) * 0.85F));
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
                int light = flakes.light[i];
                int a = (int) (Math.min(1.0F, alpha) * 255.0F);
                int red = (int) (240.0F * bright);
                int green = (int) (246.0F * bright);
                int blue = (int) (255.0F * bright);
                ax *= halfA;
                ay *= halfA;
                az *= halfA;
                bx *= half;
                by *= half;
                bz *= half;
                // Counter-clockwise seen from the camera, as shader packs expect.
                builder.addVertex(x - ax - bx, y - ay - by, z - az - bz).setUv(u1, 1.0F).setColor(red, green, blue, a).setLight(light);
                builder.addVertex(x - ax + bx, y - ay + by, z - az + bz).setUv(u1, 0.0F).setColor(red, green, blue, a).setLight(light);
                builder.addVertex(x + ax + bx, y + ay + by, z + az + bz).setUv(u0, 0.0F).setColor(red, green, blue, a).setLight(light);
                builder.addVertex(x + ax - bx, y + ay - by, z + az - bz).setUv(u0, 1.0F).setColor(red, green, blue, a).setLight(light);
                count++;
            }
        }
        lastCount = count;
    }

    private static float smoothstep(float from, float to, float value) {
        float t = Math.clamp((value - from) / (to - from), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    @Override
    public void close() {
        bytes.close();
    }
}
