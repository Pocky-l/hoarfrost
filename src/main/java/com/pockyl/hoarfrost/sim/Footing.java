package com.pockyl.hoarfrost.sim;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a foot meets under the snow decides the print it leaves:
 * <ul>
 *   <li>soft ground (soil, grass, sand): a boot-shaped dent with a packed floor and a rim of snow pushed up around it;</li>
 *   <li>deep snow (snow blocks and layers under the cover, or a deep drift): a deeper hole, the foot dragging a furrow
 *       behind it, more snow heaped in front;</li>
 *   <li>hard blocks (stone, planks): a crisp, shallow print with steep walls - the sole stops at the block;</li>
 *   <li>ice: the foot slips - a long narrow scuff that sweeps the thin snow off down to the ice and pushes it into a little
 *       heap at the end, longer the faster the step.</li>
 * </ul>
 * The shape of a print is an oval along the step, reaching {@code front} ahead of the foot, {@code back} behind it and
 * {@code side} to each side.
 */
public enum Footing {
    // front, back, side, wall sharpness, rim height, rim width, rim only ahead, share of the snow, press, press per weight, packing
    SOFT(1.45F, 1.45F, 0.9F, 2.0F, 0.22F, 0.6F, 0.0F, 0.85F, 0.06F, 0.05F, 0.12F),
    DEEP(1.5F, 2.3F, 0.95F, 2.5F, 0.3F, 0.7F, 0.35F, 0.9F, 0.12F, 0.08F, 0.15F),
    HARD(1.4F, 1.4F, 0.85F, 6.0F, 0.12F, 0.35F, 0.0F, 0.75F, 0.035F, 0.02F, 0.1F),
    ICE(2.4F, 1.1F, 0.6F, 3.0F, 0.4F, 0.8F, 1.0F, 1.0F, 1.0F, 0.0F, 0.03F);

    private static final Footing[] VALUES = values();
    /** Snow deeper than this takes a foot like a drift, whatever lies under it. */
    private static final float DEEP_SNOW = 0.2F;

    public final float front;
    public final float back;
    public final float side;
    /** Exponent of the dent's profile: 2 is a soft bowl, higher numbers give a flat floor and steep walls. */
    public final float sharpness;
    /** Height of the rim of pushed-up snow, as a share of the dent's depth. */
    public final float rim;
    /** Width of the rim beyond the dent's edge, as a share of its size. */
    public final float rimWidth;
    /** 0: the rim goes all around; 1: only ahead of the foot (snow shoved forward). */
    public final float rimAhead;
    /** Most of the snow's depth a print presses down (1 sweeps it off). */
    public final float share;
    private final float press;
    private final float pressPerWeight;
    /** How much one step treads the snow. */
    public final float packing;

    Footing(float front, float back, float side, float sharpness, float rim, float rimWidth, float rimAhead, float share, float press,
            float pressPerWeight, float packing) {
        this.front = front;
        this.back = back;
        this.side = side;
        this.sharpness = sharpness;
        this.rim = rim;
        this.rimWidth = rimWidth;
        this.rimAhead = rimAhead;
        this.share = share;
        this.press = press;
        this.pressPerWeight = pressPerWeight;
        this.packing = packing;
    }

    public static Footing of(int ordinal) {
        return VALUES[Mth.clamp(ordinal, 0, VALUES.length - 1)];
    }

    /** The footing on the block whose top is at {@code base}, under {@code depth} of snow. */
    public static Footing at(BlockGetter level, int x, float base, int z, float depth) {
        BlockPos pos = new BlockPos(x, Mth.floor(base - 0.01F), z);
        BlockState state = level.getBlockState(pos);
        if (state.is(BlockTags.ICE)) {
            return ICE;
        }
        if (depth > DEEP_SNOW || state.getBlock() instanceof SnowLayerBlock || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW)) {
            return DEEP;
        }
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE) || state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return HARD;
        }
        return SOFT;
    }

    /** How deep a print presses into {@code depth} of snow. */
    public float press(float depth, float weight) {
        return Math.min(depth * share, press + pressPerWeight * weight);
    }

    /**
     * The print's effect on the snow surface at a point: how much it is lowered (negative where the rim raises it).
     *
     * @param along  distance ahead of the foot along the step (negative behind), in units of the foot's radius
     * @param across distance to the side, in the same units
     * @param stretch how much longer than usual the print reaches ahead (a slip on ice)
     */
    public float lower(float along, float across, float stretch, float amount) {
        float reach = along >= 0.0F ? front * stretch : back;
        float a = along / reach;
        float c = across / side;
        float q = a * a + c * c;
        if (q < 1.0F) {
            return amount * (1.0F - (float) Math.pow(q, sharpness * 0.5F));
        }
        float out = (float) Math.sqrt(q) - 1.0F;
        if (out >= rimWidth || rim <= 0.0F) {
            return 0.0F;
        }
        float ahead = Math.max(0.0F, a) / (out + 1.0F);
        float raise = amount * rim * Mth.sin(Mth.PI * out / rimWidth) * (1.0F - rimAhead + rimAhead * ahead * 1.6F);
        return -raise;
    }

    /** How far from the foot the print and its rim reach at most, in units of the foot's radius. */
    public float reach(float stretch) {
        return Math.max(front * stretch, Math.max(back, side)) * (1.0F + rimWidth);
    }
}
