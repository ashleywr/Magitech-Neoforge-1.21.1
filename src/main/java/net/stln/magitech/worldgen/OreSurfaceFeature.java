package net.stln.magitech.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.stln.magitech.compat.seedgen.SeedGenOreCandidates;

import java.util.List;

public class OreSurfaceFeature extends Feature<NoneFeatureConfiguration> {
    private static final Direction[] DIRECTIONS = Direction.values();
    private final BlockState blockToPlace;
    private final List<Block> blockToPlaced;
    private final double propability;

    public OreSurfaceFeature(BlockState blockToPlace, List<Block> blockToPlaced, double propability) {
        super(NoneFeatureConfiguration.CODEC);
        this.blockToPlace = blockToPlace;
        this.blockToPlaced = blockToPlaced;
        this.propability = propability;
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        ChunkPos chunkPos = new ChunkPos(context.origin());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        RandomSource random = level.getRandom();
        boolean directional = blockToPlace.hasProperty(BlockStateProperties.FACING);
        long[] candidates = SeedGenOreCandidates.find(level, blockToPlaced, blockToPlace, minX, minZ, minY, maxY);
        if (candidates != null) {
            long height = (long) maxY - minY;
            long cursor = 0;
            for (long index : candidates) {
                while (cursor < index) { random.nextFloat(); cursor++; }
                float chance = random.nextFloat();
                cursor++;
                if (chance > propability) continue;
                long column = index / height;
                pos.set(minX + (int)(column / 16), minY + (int)(index % height), minZ + (int)(column % 16));
                placeAt(level, pos, directional);
            }
            long draws = Math.max(0L, height) * 256;
            while (cursor < draws) { random.nextFloat(); cursor++; }
            return true;
        }

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dy = minY; dy < maxY; dy++) {
                    // Keep one draw for every original scan position, including non-target blocks.
                    if (random.nextFloat() > propability) continue;
                    pos.set(minX + dx, dy, minZ + dz);
                    placeAt(level, pos, directional);
                }
            }
        }
        return true;
    }

    private void placeAt(WorldGenLevel level, BlockPos.MutableBlockPos pos, boolean directional) {
        BlockState state = level.getBlockState(pos);
        boolean isTargetBlock = false;
        for (Block block : blockToPlaced) {
            if (state.is(block)) {
                isTargetBlock = true;
                break;
            }
        }
        if (!isTargetBlock) return;
        for (Direction dir : DIRECTIONS) {
            BlockPos facePos = pos.relative(dir);
            BlockState faceState = level.getBlockState(facePos);
            if (faceState.isAir() || faceState.is(Blocks.WATER)) {
                BlockState blockState = blockToPlace;
                if (directional) blockState = blockState.setValue(BlockStateProperties.FACING, dir);
                if (faceState.is(Blocks.WATER) && directional)
                    blockState = blockState.setValue(BlockStateProperties.WATERLOGGED, true);
                level.setBlock(facePos, blockState, 2);
            }
        }
    }
}
