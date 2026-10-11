package com.pockyl.hoarfrost.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

import com.pockyl.hoarfrost.ClientConfig;
import com.pockyl.hoarfrost.client.compat.DistantHorizonsTint;
import com.pockyl.hoarfrost.mixin.BlockColorsAccessor;

/**
 * White leaves in snowy biomes, as in Bedrock Edition: the leaves keep their own texture and lose their green tint for
 * white. Like Bedrock, a biome counts as snowy below a base temperature of 0.15.
 *
 * <p>The leaves' own colour handlers are wrapped, so every tree, modded or not, keeps its colour elsewhere. Whether a
 * biome is snowy is a colour resolver, so vanilla biome blending (and Sodium's) fades the frost over the biome border,
 * and it works with shader packs.
 */
public final class LeafFrost {
    /** Bedrock's limit: leaves in biomes colder than this turn white. */
    private static final float COLD = 0.15F;

    /** 0xFF where the biome is snowy, 0 elsewhere; blended over the biome border like grass colours. */
    private static final ColorResolver SNOWY = (biome, x, z) -> biome.getBaseTemperature() < COLD ? 0xFF : 0;

    private LeafFrost() {
    }

    private static boolean enabled() {
        return ClientConfig.SPEC.isLoaded() && ClientConfig.SNOWY_LEAVES.get();
    }

    static void onRegisterResolvers(RegisterColorHandlersEvent.ColorResolvers event) {
        event.register(SNOWY);
    }

    /** Wraps the colours of all leaves. Registered last, so it wraps what other mods registered. */
    static void onRegisterColors(RegisterColorHandlersEvent.Block event) {
        BlockColorsAccessor accessor = (BlockColorsAccessor) event.getBlockColors();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof LeavesBlock)) {
                continue;
            }
            BlockColor original = accessor.hoarfrost$colors().get(block);
            if (original == null) {
                continue;
            }
            event.register((state, getter, pos, tint) -> {
                int color = original.getColor(state, getter, pos, tint);
                if (getter == null || pos == null || color == -1 || !enabled()) {
                    return color;
                }
                float k = DistantHorizonsTint.handles(getter)
                        ? DistantHorizonsTint.snowy(getter, COLD)
                        : (getter.getBlockTint(pos, SNOWY) & 0xFF) / 255.0F;
                return k <= 0.0F ? color : whiten(color, k);
            }, block);
        }
    }

    private static int whiten(int color, float k) {
        int r = color >> 16 & 0xFF;
        int g = color >> 8 & 0xFF;
        int b = color & 0xFF;
        r += (int) ((0xFF - r) * k);
        g += (int) ((0xFF - g) * k);
        b += (int) ((0xFF - b) * k);
        return color & 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** The setting changed: the chunk meshes hold the old leaf colours. */
    static void rebuild() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer != null && minecraft.level != null) {
            minecraft.levelRenderer.allChanged();
        }
    }
}
