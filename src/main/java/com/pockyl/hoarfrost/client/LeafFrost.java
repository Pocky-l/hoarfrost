package com.pockyl.hoarfrost.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

import com.pockyl.hoarfrost.ClientConfig;
import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.client.compat.DistantHorizonsTint;
import com.pockyl.hoarfrost.client.render.SnowyLeavesModel;
import com.pockyl.hoarfrost.mixin.BlockColorsAccessor;
import com.pockyl.hoarfrost.world.LeafSnow;

import java.util.Map;

/**
 * Snow on the leaves in snowy biomes: the leaves stay green and the faces snow would fall on - the top of the crown and
 * the edges hanging from it - carry a snow cap ({@link SnowyLeavesModel}). Every leaves block, modded or not, gets it.
 *
 * <p>Distant Horizons builds its far land from block colours, not from models: there the leaves of snowy biomes are
 * paled towards white instead, which reads the same from afar.
 */
public final class LeafFrost {
    /** How far distant leaves in snowy biomes pale towards white. */
    private static final float DISTANT_FROST = 0.55F;

    private LeafFrost() {
    }

    private static boolean enabled() {
        return ClientConfig.SPEC.isLoaded() && ClientConfig.SNOWY_LEAVES.get();
    }

    static void onRegisterResolvers(RegisterColorHandlersEvent.ColorResolvers event) {
        event.register(LeafSnow.SNOWY);
    }

    /** Wraps the model of every leaves block state. */
    static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        TextureAtlasSprite top = event.getTextureGetter().apply(new Material(InventoryMenu.BLOCK_ATLAS, Hoarfrost.id("block/leaf_snow_top")));
        TextureAtlasSprite side = event.getTextureGetter().apply(new Material(InventoryMenu.BLOCK_ATLAS, Hoarfrost.id("block/leaf_snow_side")));
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof LeavesBlock)) {
                continue;
            }
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
                BakedModel model = models.get(location);
                if (model != null && !(model instanceof SnowyLeavesModel)) {
                    models.put(location, new SnowyLeavesModel(model, top, side, LeafFrost::enabled));
                }
            }
        }
    }

    /** Wraps the colours of all leaves for Distant Horizons. Registered last, so it wraps what other mods registered. */
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
                if (getter == null || pos == null || color == -1 || !DistantHorizonsTint.handles(getter) || !enabled()) {
                    return color;
                }
                float k = DistantHorizonsTint.snowy(getter, LeafSnow.COLD) * DISTANT_FROST;
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

    /** The setting changed: the chunk meshes hold the old leaves. */
    static void rebuild() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer != null && minecraft.level != null) {
            minecraft.levelRenderer.allChanged();
        }
    }
}
