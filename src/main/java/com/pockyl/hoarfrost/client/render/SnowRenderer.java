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
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;

import com.pockyl.hoarfrost.sim.SnowSimulation;

/**
 * Draws the snow of a simulation: the falling flakes in the weather pass (with the vanilla particle shader, which shader
 * packs swap for their weather program), and the snow lying on the world.
 */
public final class SnowRenderer implements AutoCloseable {
    private final SnowSimulation simulation;
    private final FlakeRenderer flakes = new FlakeRenderer();
    private final CoverRenderer cover;
    private final ByteBufferBuilder flakeBytes = new ByteBufferBuilder(1 << 20);

    public SnowRenderer(SnowSimulation simulation) {
        this.simulation = simulation;
        this.cover = new CoverRenderer(simulation.cover(), simulation.terrain());
    }

    public int flakesDrawn() {
        return flakes.lastCount();
    }

    public int coverQuads() {
        return cover.lastQuads();
    }

    public void renderSnowfall(LightTexture lightTexture, Camera camera, float partialTick, double camX, double camY, double camZ) {
        if (simulation.pool().count() == 0) {
            return;
        }
        lightTexture.turnOnLightLayer();
        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        Minecraft.getInstance().getTextureManager().getTexture(FlakeRenderer.TEXTURE).setFilter(true, false);
        RenderSystem.setShaderTexture(0, FlakeRenderer.TEXTURE);
        BufferBuilder builder = new BufferBuilder(flakeBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        flakes.render(builder, simulation.pool(), camX, camY, camZ, partialTick, camera.getLeftVector(), camera.getUpVector(),
                camera.getLookVector(), viewCone());
        draw(builder);

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

    private static void draw(BufferBuilder builder) {
        MeshData mesh = builder.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
    }

    public void renderCover(Camera camera, Frustum frustum, Matrix4f modelView, Matrix4f projection, double radius) {
        cover.render(camera, frustum, modelView, projection, radius);
    }

    @Override
    public void close() {
        cover.close();
        flakeBytes.close();
    }
}
