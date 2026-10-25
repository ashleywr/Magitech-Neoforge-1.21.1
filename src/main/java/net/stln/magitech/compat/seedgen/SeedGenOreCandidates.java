package net.stln.magitech.compat.seedgen;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Optional, verified view of SeedGen's sparse virtual terrain; null means use the normal scan. */
public final class SeedGenOreCandidates {
    private static final String PREFIX = "mobius.seedrenderer.dh.";
    private record Access(Field space, Field terrain, Field placed, Field floating) {}
    private static final ClassValue<java.util.Optional<Access>> ACCESS = new ClassValue<>() {
        @Override protected java.util.Optional<Access> computeValue(Class<?> handler) {
            try {
                if (!handler.getName().equals(PREFIX + "TreeGrower$StandIn")
                        || !matches(handler, "f092292a03ea94f4755031b025f167f80ee03b6b889cc070de4b56489ec8aa74")) return java.util.Optional.empty();
                var space = handler.getClassLoader().loadClass(PREFIX + "TreeGrower$Space");
                var terrain = handler.getClassLoader().loadClass(PREFIX + "PaintedChunks$Terrain");
                var world = handler.getClassLoader().loadClass(PREFIX + "PaintedChunks$World");
                if (!matches(space, "9918cb9ce44f69ca72e1dd54e55b989c0e11bb177510782b59ba2ef72a53a4c2")
                        || !matches(terrain, "5d531f46a115b93ffbc747bcfe03cd83e0ea37c6c5892c5d2a972a9af33a5f76")
                        || !matches(world, "a1fed1973b6c4cb239cde8bce16e9dfb06e93a6447a6faa76dd1a152bf328975")) return java.util.Optional.empty();
                return java.util.Optional.of(new Access(field(handler,"space"),field(space,"terrain"),field(space,"placed"),field(terrain,"floating")));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
                return java.util.Optional.empty();
            }
        }
    };

    private SeedGenOreCandidates() {}

    public static long[] find(WorldGenLevel level, List<Block> targets, BlockState replacement,
                              int minX, int minZ, int minY, int maxY) {
        // These blocks may exist in the implicit terrain; replacements must not create new targets.
        if (!Proxy.isProxyClass(level.getClass()) || targets.contains(Blocks.STONE) || targets.contains(Blocks.WATER)
                || targets.contains(Blocks.AIR) || targets.contains(replacement.getBlock())) return null;
        try {
            var handler = Proxy.getInvocationHandler(level);
            var access = ACCESS.get(handler.getClass()).orElse(null);
            if (access == null) return null;
            var space = access.space.get(handler);
            var terrain = access.terrain.get(space);
            if (terrain == null || terrain.getClass() != access.floating.getDeclaringClass() || access.floating.getBoolean(terrain)) return null;
            if (!(access.placed.get(space) instanceof Map<?,?> placed)) return null;
            if (placed.size() > 16384) return null;
            long height = (long)maxY - minY;
            if (height <= 0) return new long[0];
            var candidates = new LongArrayList();
            for (var entry : placed.entrySet()) {
                if (!(entry.getKey() instanceof Long key) || !(entry.getValue() instanceof BlockState state)) return null;
                if (!targets.contains(state.getBlock())) continue;
                var pos = BlockPos.of(key);
                long dx = (long)pos.getX() - minX, dz = (long)pos.getZ() - minZ, dy = (long)pos.getY() - minY;
                if (dx >= 0 && dx < 16 && dz >= 0 && dz < 16 && dy >= 0 && dy < height)
                    candidates.add((dx * 16 + dz) * height + dy);
            }
            long[] indices = candidates.toLongArray();
            Arrays.sort(indices);
            return indices;
        } catch (IllegalAccessException | RuntimeException unsupported) {
            return null;
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        var field = owner.getDeclaredField(name);field.setAccessible(true);return field;
    }

    private static boolean matches(Class<?> type, String expected) {
        String resource = type.getName().replace('.','/') + ".class";
        try (InputStream stream = type.getClassLoader().getResourceAsStream(resource)) {
            return stream != null && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())).equals(expected);
        } catch (Exception unsupported) { return false; }
    }
}
