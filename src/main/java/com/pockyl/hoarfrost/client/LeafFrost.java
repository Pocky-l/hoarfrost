package com.pockyl.hoarfrost.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

import com.pockyl.hoarfrost.ClientConfig;
import com.pockyl.hoarfrost.mixin.BlockColorsAccessor;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;

/**
 * White leaves under snow, as in Bedrock Edition: the leaves of snowy crowns lose their green tint (near the camera a
 * white snowy face is drawn over them as well, see {@code CoverRenderer}, since a tint alone cannot make a dark texture
 * white). The leaves' own colour handlers are wrapped, so every tree, modded or not, keeps its colour
 * underneath; the frost goes through vanilla block colours and so works with Sodium and shader packs.
 *
 * <p>How much snow lies on each column's crown is kept per chunk in steps; chunk meshes are rebuilt only when a step
 * changes. The colours are read on the chunk building threads, hence the concurrent map.
 */
public final class LeafFrost {
    /** Steps of frost; a change of step rebuilds the chunk's meshes. */
    public static final int STEPS = 4;
    private static final int FROST = 0xFFFFFF;
    /** Share of the leaf colour the frost takes at the top of a fully snowed crown. */
    private static final float MOST = 1.0F;

    /** Per chunk: the frost step and the height of the top of the crown of each column. */
    private static final Map<Long, Entry> CHUNKS = new ConcurrentHashMap<>();

    private record Entry(byte[] steps, short[] tops) {
    }

    private LeafFrost() {
    }

    private static boolean enabled() {
        return ClientConfig.SPEC.isLoaded() && ClientConfig.SNOWY_LEAVES.get();
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
                return pos == null || color == -1 ? color : frost(color, pos);
            }, block);
        }
    }

    private static int frost(int color, BlockPos pos) {
        if (!enabled()) {
            return color;
        }
        Entry entry = CHUNKS.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (entry == null) {
            return color;
        }
        int column = (pos.getZ() & 15) << 4 | pos.getX() & 15;
        int step = entry.steps[column];
        if (step <= 0) {
            return color;
        }
        // Thickest on top of the crown, a sprinkle on the leaves lower down.
        float below = entry.tops[column] - (pos.getY() + 1.0F);
        float k = MOST * step / STEPS * Math.clamp(1.0F - below * 0.04F, 0.6F, 1.0F);
        int r = color >> 16 & 0xFF;
        int g = color >> 8 & 0xFF;
        int b = color & 0xFF;
        r += (int) (((FROST >> 16 & 0xFF) - r) * k);
        g += (int) (((FROST >> 8 & 0xFF) - g) * k);
        b += (int) (((FROST & 0xFF) - b) * k);
        return color & 0xFF000000 | r << 16 | g << 8 | b;
    }

    /**
     * Sets the frost of a chunk; its meshes are rebuilt when it changed.
     *
     * @param steps frost step per column, 0..{@link #STEPS}
     * @param tops  height of the top of the crown per column
     */
    static void set(int chunkX, int chunkZ, byte[] steps, short[] tops) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        Entry old = CHUNKS.get(key);
        if (old != null && Arrays.equals(old.steps, steps)) {
            return;
        }
        boolean any = false;
        for (byte step : steps) {
            any |= step > 0;
        }
        if (!any && old == null) {
            return;
        }
        if (any) {
            CHUNKS.put(key, new Entry(steps.clone(), tops.clone()));
        } else {
            CHUNKS.remove(key);
        }
        dirty(chunkX, chunkZ, old, steps, tops);
    }

    /** Rebuilds the sections the changed crowns are in. */
    private static void dirty(int chunkX, int chunkZ, Entry old, byte[] steps, short[] tops) {
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int c = 0; c < 256; c++) {
            boolean changed = old == null ? steps[c] > 0 : old.steps[c] != steps[c];
            if (changed) {
                int top = old != null && old.steps[c] > 0 ? Math.max(tops[c], old.tops[c]) : tops[c];
                low = Math.min(low, top - 12);
                high = Math.max(high, top);
            }
        }
        if (low > high) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer == null) {
            return;
        }
        for (int section = low >> 4; section <= high >> 4; section++) {
            minecraft.levelRenderer.setSectionDirty(chunkX, section, chunkZ);
        }
    }

    /** Forgets chunks the client no longer has. */
    static void prune(LongPredicate loaded) {
        CHUNKS.keySet().removeIf(key -> !loaded.test(key));
    }

    static void clear() {
        CHUNKS.clear();
    }
}
