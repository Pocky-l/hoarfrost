package com.pockyl.hoarfrost.compat;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import com.pockyl.hoarfrost.Hoarfrost;

import org.jetbrains.annotations.Nullable;

/**
 * Roofs of moving block structures from Sable ("sub-levels": airships, flying houses). Their blocks are stored far away
 * in the level and only drawn where the structure is, so heightmaps do not know about them and snow fell straight
 * through. Like Sable's own fix for vanilla rain, every column under a structure is traced from the top of its bounds
 * down along the structure's own vertical to the first block that stops motion or holds a fluid.
 *
 * <p>Without Sable there are no roofs. Only {@link SableRoofs} touches Sable, and only once {@link #loaded()} said it is
 * installed; if its API ever changes under us, the integration switches itself off instead of crashing the game.
 */
public final class SubLevelRoofs {
    public static final String SABLE = "sable";

    private static Boolean loaded;
    private static volatile boolean broken;

    /** Where the snow meets a structure over a column: the exact height and the block it lands on. */
    public record Roof(float top, BlockState state) {
        /** The first whole y above the roof, like a heightmap value. */
        public int height() {
            return Mth.ceil(top - 1.0E-4F);
        }
    }

    /** The Sable side: fills the roofs of an area, tells whether a point is inside a structure's block. */
    interface Survey {
        void survey(Level level, int minX, int minZ, int maxX, int maxZ, Long2ObjectMap<Roof> out);

        boolean solid(Level level, double x, double y, double z);
    }

    private final Long2ObjectOpenHashMap<Roof> roofs = new Long2ObjectOpenHashMap<>();
    @Nullable
    private Survey survey;
    private int lastTick = Integer.MIN_VALUE;

    /** Whether Sable is installed. */
    public static boolean loaded() {
        if (loaded == null) {
            ModList mods = ModList.get();
            loaded = mods != null && mods.isLoaded(SABLE);
        }
        return loaded;
    }

    /** Traces the structures within {@code radius} columns of a point; once per tick, later calls in the tick are free. */
    public void update(Level level, double x, double z, int radius, int tick) {
        if (tick == lastTick) {
            return;
        }
        lastTick = tick;
        roofs.clear();
        if (broken || !loaded()) {
            return;
        }
        try {
            if (survey == null) {
                survey = SableRoofs.create();
            }
            int cx = Mth.floor(x);
            int cz = Mth.floor(z);
            survey.survey(level, cx - radius, cz - radius, cx + radius, cz + radius, roofs);
        } catch (Throwable e) {
            disable(e);
        }
    }

    /** The roof of a structure over the column, or null. */
    @Nullable
    public Roof at(int x, int z) {
        return roofs.isEmpty() ? null : roofs.get((long) x << 32 | (z & 0xFFFFFFFFL));
    }

    /** Whether a point is inside a block of a structure that stops motion. */
    public boolean solid(Level level, double x, double y, double z) {
        if (roofs.isEmpty() || survey == null) {
            return false;
        }
        try {
            return survey.solid(level, x, y, z);
        } catch (Throwable e) {
            disable(e);
            return false;
        }
    }

    private void disable(Throwable e) {
        roofs.clear();
        if (!broken) {
            broken = true;
            Hoarfrost.LOGGER.warn("Sable integration turned off: this version of Sable is not supported", e);
        }
    }
}
