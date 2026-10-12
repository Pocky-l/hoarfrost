package com.pockyl.hoarfrost.compat;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;

import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The only class that touches Sable, through its companion API. Reached from {@link SubLevelRoofs} once Sable is known
 * to be installed.
 */
final class SableRoofs implements SubLevelRoofs.Survey {
    /** Ticks after which a structure that has not moved is traced again: blocks may have been placed or broken on it. */
    private static final int RETRACE = 20;
    /** Movement (blocks, quaternion components) below which a structure counts as standing still. */
    private static final double STILL = 1.0E-4;

    private final Map<UUID, Footprint> footprints = new HashMap<>();
    private final Set<UUID> seen = new HashSet<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final Vector3d point = new Vector3d();
    private final Vector3d up = new Vector3d();
    private Level level;
    private int ticks;

    private SableRoofs() {
    }

    static SubLevelRoofs.Survey create() {
        return new SableRoofs();
    }

    @Override
    public void survey(Level level, int minX, int minZ, int maxX, int maxZ, Long2ObjectMap<SubLevelRoofs.Roof> out) {
        if (this.level != level) {
            this.level = level;
            footprints.clear();
        }
        ticks++;
        BoundingBox3d area = new BoundingBox3d(minX, level.getMinBuildHeight(), minZ, maxX + 1, level.getMaxBuildHeight(), maxZ + 1);
        seen.clear();
        for (SubLevelAccess sub : SableCompanion.INSTANCE.getAllIntersecting(level, area)) {
            UUID id = sub.getUniqueId();
            seen.add(id);
            BoundingBox3dc box = sub.boundingBox();
            int fromX = Math.max(minX, Mth.floor(box.minX()));
            int fromZ = Math.max(minZ, Mth.floor(box.minZ()));
            int toX = Math.min(maxX, Mth.ceil(box.maxX()) - 1);
            int toZ = Math.min(maxZ, Mth.ceil(box.maxZ()) - 1);
            Footprint footprint = footprints.get(id);
            if (footprint == null || !footprint.still(sub.logicalPose(), fromX, fromZ, toX, toZ) || ticks - footprint.traced >= RETRACE) {
                footprint = trace(level, sub, fromX, fromZ, toX, toZ);
                footprints.put(id, footprint);
            }
            footprint.sub = sub;
            for (int i = 0; i < footprint.keys.size(); i++) {
                long key = footprint.keys.getLong(i);
                SubLevelRoofs.Roof roof = footprint.roofs.get(i);
                SubLevelRoofs.Roof other = out.get(key);
                if (other == null || roof.top() > other.top()) {
                    out.put(key, roof);
                }
            }
        }
        footprints.keySet().retainAll(seen);
    }

    @Override
    public boolean solid(Level level, double x, double y, double z) {
        for (Footprint footprint : footprints.values()) {
            SubLevelAccess sub = footprint.sub;
            if (sub == null || !sub.boundingBox().contains(x, y, z)) {
                continue;
            }
            sub.logicalPose().transformPositionInverse(point.set(x, y, z));
            pos.set(point.x, point.y, point.z);
            BlockState state = level.getBlockState(pos);
            if (state.blocksMotion()) {
                return true;
            }
        }
        return false;
    }

    private Footprint trace(Level level, SubLevelAccess sub, int fromX, int fromZ, int toX, int toZ) {
        BoundingBox3dc box = sub.boundingBox();
        Pose3dc pose = sub.logicalPose();
        Footprint footprint = new Footprint(pose, fromX, fromZ, toX, toZ, ticks);
        // One block at a time along the structure's own vertical, from the top of its bounds through all of it.
        pose.transformNormalInverse(up.set(0.0, 1.0, 0.0));
        int steps = Mth.ceil(box.maxY() - box.minY()) + 1;
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                pose.transformPositionInverse(point.set(x + 0.5, box.maxY(), z + 0.5));
                for (int s = 0; s < steps; s++) {
                    point.sub(up);
                    pos.set(point.x, point.y, point.z);
                    BlockState state = level.getBlockState(pos);
                    if (state.blocksMotion() || !state.getFluidState().isEmpty()) {
                        footprint.keys.add((long) x << 32 | (z & 0xFFFFFFFFL));
                        footprint.roofs.add(new SubLevelRoofs.Roof((float) top(level, pose, state), state));
                        break;
                    }
                }
            }
        }
        return footprint;
    }

    /** World height of the top of the structure's block at {@link #pos}. */
    private double top(Level level, Pose3dc pose, BlockState state) {
        FluidState fluid = state.getFluidState();
        double height = fluid.isEmpty() ? state.getCollisionShape(level, pos).max(Direction.Axis.Y) : fluid.getHeight(level, pos);
        if (!(height > 0.0)) {
            height = 1.0;
        }
        return pose.transformPosition(point.set(pos.getX() + 0.5, pos.getY() + height, pos.getZ() + 0.5)).y;
    }

    /** The traced roofs of one structure, valid while it stands still. */
    private static final class Footprint {
        final LongArrayList keys = new LongArrayList();
        final List<SubLevelRoofs.Roof> roofs = new ArrayList<>();
        final double x;
        final double y;
        final double z;
        final double qx;
        final double qy;
        final double qz;
        final double qw;
        final int fromX;
        final int fromZ;
        final int toX;
        final int toZ;
        final int traced;
        SubLevelAccess sub;

        Footprint(Pose3dc pose, int fromX, int fromZ, int toX, int toZ, int traced) {
            Vector3dc position = pose.position();
            Quaterniondc orientation = pose.orientation();
            this.x = position.x();
            this.y = position.y();
            this.z = position.z();
            this.qx = orientation.x();
            this.qy = orientation.y();
            this.qz = orientation.z();
            this.qw = orientation.w();
            this.fromX = fromX;
            this.fromZ = fromZ;
            this.toX = toX;
            this.toZ = toZ;
            this.traced = traced;
        }

        boolean still(Pose3dc pose, int fromX, int fromZ, int toX, int toZ) {
            Vector3dc position = pose.position();
            Quaterniondc orientation = pose.orientation();
            return fromX == this.fromX && fromZ == this.fromZ && toX == this.toX && toZ == this.toZ
                    && Math.abs(position.x() - x) < STILL && Math.abs(position.y() - y) < STILL && Math.abs(position.z() - z) < STILL
                    && Math.abs(orientation.x() - qx) < STILL && Math.abs(orientation.y() - qy) < STILL
                    && Math.abs(orientation.z() - qz) < STILL && Math.abs(orientation.w() - qw) < STILL;
        }
    }
}
