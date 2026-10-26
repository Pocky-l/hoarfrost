package com.pockyl.hoarfrost.client.render;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import com.pockyl.hoarfrost.world.LeafSnow;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * A leaves model with snow lying on it: the leaves keep their own texture and colour, and the faces that carry snow
 * ({@link LeafSnow}) get a second, untinted quad over them with the snow texture - coplanar like the overlay of a grass
 * block side, so it follows any leaves model. The snow texture is laid out by the block's own coordinates (a cap from
 * above, a band hanging from the top edge of a side), turned and mirrored per block so the pattern does not repeat.
 *
 * <p>The snow is drawn cutout even when fast graphics draw the leaves solid, so its gaps stay open.
 */
public final class SnowyLeavesModel extends BakedModelWrapper<BakedModel> {
    private static final ModelProperty<Integer> SNOW = new ModelProperty<>();
    /** Variants of a snow quad: four turns of the cap, and a side band plain or mirrored. */
    private static final int VARIANTS = 4;

    private final TextureAtlasSprite top;
    private final TextureAtlasSprite side;
    private final BooleanSupplier enabled;
    private final Map<BakedQuad, BakedQuad[]> snow = new ConcurrentHashMap<>();

    public SnowyLeavesModel(BakedModel original, TextureAtlasSprite top, TextureAtlasSprite side, BooleanSupplier enabled) {
        super(original);
        this.top = top;
        this.side = side;
        this.enabled = enabled;
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        data = super.getModelData(level, pos, state, data);
        int mask = enabled.getAsBoolean() ? LeafSnow.mask(level, pos) : 0;
        return mask == 0 ? data : data.derive().with(SNOW, mask).build();
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        ChunkRenderTypeSet types = super.getRenderTypes(state, rand, data);
        return mask(data) == 0 || types.contains(RenderType.cutoutMipped())
                ? types
                : ChunkRenderTypeSet.union(types, ChunkRenderTypeSet.of(RenderType.cutoutMipped()));
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction face, RandomSource rand, ModelData data,
            @Nullable RenderType renderType) {
        int mask = mask(data);
        if (mask == 0 || state == null || renderType != null && renderType != RenderType.cutoutMipped()) {
            return super.getQuads(state, face, rand, data, renderType);
        }
        // The leaves themselves, unless fast graphics draw them in another layer than the snow.
        boolean ownLayer = renderType == null || super.getRenderTypes(state, rand, data).contains(renderType);
        List<BakedQuad> leaves = super.getQuads(state, face, rand, data, ownLayer ? renderType : null);
        List<BakedQuad> quads = null;
        for (BakedQuad quad : leaves) {
            Direction direction = quad.getDirection();
            int bit = direction == Direction.UP ? LeafSnow.TOP : direction.getAxis().isHorizontal() ? LeafSnow.side(direction) : 0;
            if ((mask & bit) == 0) {
                continue;
            }
            if (quads == null) {
                quads = new ArrayList<>(ownLayer ? leaves.size() + 2 : 2);
                if (ownLayer) {
                    quads.addAll(leaves);
                }
            }
            int variant = direction == Direction.UP ? LeafSnow.rotation(mask) : (mask & LeafSnow.MIRROR) != 0 ? 1 : 0;
            quads.add(snow.computeIfAbsent(quad, this::bake)[variant]);
        }
        if (quads != null) {
            return quads;
        }
        return ownLayer ? leaves : List.of();
    }

    private static int mask(ModelData data) {
        Integer mask = data.get(SNOW);
        return mask == null ? 0 : mask;
    }

    private BakedQuad[] bake(BakedQuad leaf) {
        BakedQuad[] variants = new BakedQuad[VARIANTS];
        for (int v = 0; v < VARIANTS; v++) {
            variants[v] = bake(leaf, v);
        }
        return variants;
    }

    /** The leaf face again, with the snow texture mapped by position: the top turned {@code variant} quarter turns. */
    private BakedQuad bake(BakedQuad leaf, int variant) {
        Direction direction = leaf.getDirection();
        boolean onTop = direction == Direction.UP;
        TextureAtlasSprite sprite = onTop ? top : side;
        int[] vertices = leaf.getVertices().clone();
        for (int i = 0; i < 4; i++) {
            int at = i * IQuadTransformer.STRIDE;
            float x = Mth.clamp(Float.intBitsToFloat(vertices[at + IQuadTransformer.POSITION]), 0.0F, 1.0F);
            float y = Mth.clamp(Float.intBitsToFloat(vertices[at + IQuadTransformer.POSITION + 1]), 0.0F, 1.0F);
            float z = Mth.clamp(Float.intBitsToFloat(vertices[at + IQuadTransformer.POSITION + 2]), 0.0F, 1.0F);
            float u;
            float v;
            if (onTop) {
                float[] uv = turn(x, z, variant);
                u = uv[0];
                v = uv[1];
            } else {
                // As a viewer facing the side sees it: u from left to right, v down from the top edge.
                u = switch (direction) {
                    case NORTH -> 1.0F - x;
                    case SOUTH -> x;
                    case WEST -> z;
                    default -> 1.0F - z;
                };
                if ((variant & 1) != 0) {
                    u = 1.0F - u;
                }
                v = 1.0F - y;
            }
            vertices[at + IQuadTransformer.UV0] = Float.floatToRawIntBits(sprite.getU(u));
            vertices[at + IQuadTransformer.UV0 + 1] = Float.floatToRawIntBits(sprite.getV(v));
        }
        return new BakedQuad(vertices, -1, direction, sprite, leaf.isShade(), leaf.hasAmbientOcclusion());
    }

    private static float[] turn(float u, float v, int quarters) {
        return switch (quarters & 3) {
            case 1 -> new float[] {1.0F - v, u};
            case 2 -> new float[] {1.0F - u, 1.0F - v};
            case 3 -> new float[] {v, 1.0F - u};
            default -> new float[] {u, v};
        };
    }
}
