package com.pockyl.hoarfrost.sim;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collision and outline boxes of block states as flat arrays {@code [minX, minY, minZ, maxX, maxY, maxZ, ...]} in
 * block-local coordinates, so a point test against slabs, stairs or fences costs a few comparisons and no allocation.
 */
final class ShapeCache {
    private static final double[] EMPTY = new double[0];

    private final Map<BlockState, double[]> cache = new IdentityHashMap<>();
    private final Map<BlockState, double[]> outlines = new IdentityHashMap<>();

    double[] boxes(BlockState state, BlockGetter level, BlockPos pos) {
        if (state.getBlock().hasDynamicShape() || state.hasOffsetFunction()) {
            return toArray(state.getCollisionShape(level, pos));
        }
        double[] boxes = cache.get(state);
        if (boxes == null) {
            boxes = toArray(state.getCollisionShape(level, pos));
            cache.put(state, boxes);
        }
        return boxes;
    }

    /** The boxes of the block as it is drawn (its outline), e.g. the visible top of a snow layer. */
    double[] outline(BlockState state, BlockGetter level, BlockPos pos) {
        if (state.getBlock().hasDynamicShape() || state.hasOffsetFunction()) {
            return toArray(state.getShape(level, pos));
        }
        double[] boxes = outlines.get(state);
        if (boxes == null) {
            boxes = toArray(state.getShape(level, pos));
            outlines.put(state, boxes);
        }
        return boxes;
    }

    private static double[] toArray(VoxelShape shape) {
        if (shape.isEmpty()) {
            return EMPTY;
        }
        List<AABB> list = shape.toAabbs();
        double[] boxes = new double[list.size() * 6];
        for (int i = 0; i < list.size(); i++) {
            AABB box = list.get(i);
            boxes[i * 6] = box.minX;
            boxes[i * 6 + 1] = box.minY;
            boxes[i * 6 + 2] = box.minZ;
            boxes[i * 6 + 3] = box.maxX;
            boxes[i * 6 + 4] = box.maxY;
            boxes[i * 6 + 5] = box.maxZ;
        }
        return boxes;
    }
}
