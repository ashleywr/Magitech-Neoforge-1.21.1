package net.stln.magitech.worldgen;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** Exact before/after checks, also callable in an already-bootstrapped native test server. */
public final class SurfaceOreRegression {
    private record Write(BlockPos pos, BlockState state, int flags) {}
    private record Scenario(int minY, int maxY, int chunkX, int chunkZ, boolean noTargets,
                            boolean wetCaves, boolean directional, boolean xoroshiro, double chance, long seed) {}

    private static final class Scene implements InvocationHandler {
        final Scenario scenario;
        final RandomSource random;
        final Map<BlockPos, BlockState> changed = new HashMap<>();
        final List<Write> writes = new ArrayList<>();
        long minCalls, maxCalls, randomCalls, reads;
        final WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, this);

        Scene(Scenario scenario) {
            this.scenario = scenario;
            random = scenario.xoroshiro ? new XoroshiroRandomSource(scenario.seed) : RandomSource.create(scenario.seed);
        }

        private BlockState state(BlockPos pos) {
            var changedState = changed.get(pos);
            if (changedState != null) return changedState;
            int y = pos.getY();
            if (y < scenario.minY || y >= scenario.maxY) return Blocks.AIR.defaultBlockState();
            int surface = 64 + Math.floorMod(pos.getX() * 7 + pos.getZ() * 13, 17) - 8;
            boolean cave = y > scenario.minY + 8 && y < surface - 8 && Math.floorMod(y + pos.getX() / 3 + pos.getZ() / 5, 23) < 3;
            if (y > surface || cave) return scenario.wetCaves && y < 64 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (!scenario.noTargets && Math.floorMod(pos.getX() * 31 + pos.getZ() * 17 + y * 11, 13) == 0)
                return (y < 0 ? Blocks.DEEPSLATE_REDSTONE_ORE : Blocks.REDSTONE_ORE).defaultBlockState();
            return Blocks.STONE.defaultBlockState();
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getMinBuildHeight" -> { minCalls++; yield scenario.minY; }
                case "getMaxBuildHeight" -> { maxCalls++; yield scenario.maxY; }
                case "getRandom" -> { randomCalls++; yield random; }
                case "getBlockState" -> { reads++; yield state((BlockPos) args[0]); }
                case "setBlock" -> {
                    var pos = ((BlockPos) args[0]).immutable();
                    var state = (BlockState) args[1];
                    changed.put(pos, state);
                    writes.add(new Write(pos, state, (Integer) args[2]));
                    yield true;
                }
                case "toString" -> "Surface-ore regression scene";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new AssertionError("Unexpected world access: " + method);
            };
        }
    }

    private static void generate(Feature<NoneFeatureConfiguration> feature, Scene scene) {
        generate(feature, scene, scene.level);
    }

    private static void generate(Feature<NoneFeatureConfiguration> feature, Scene scene, WorldGenLevel level) {
        var s = scene.scenario;
        boolean placed = feature.place(new FeaturePlaceContext<>(Optional.empty(), level, null, scene.random,
                new BlockPos((s.chunkX << 4) + 7, 73, (s.chunkZ << 4) + 11), NoneFeatureConfiguration.INSTANCE));
        if (!placed) throw new AssertionError("Feature changed its success result");
    }

    private static Feature<NoneFeatureConfiguration> feature(Scenario s, boolean legacy) {
        var state = (s.directional ? Blocks.AMETHYST_CLUSTER : Blocks.GLOWSTONE).defaultBlockState();
        var targets = List.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
        return legacy ? new LegacyOreSurfaceFeature(state, targets, s.chance) : new OreSurfaceFeature(state, targets, s.chance);
    }

    public static Map<String, Object> runAll() {
        var scenarios = new ArrayList<Scenario>();
        for (boolean noTargets : new boolean[]{false, true})
            for (boolean wet : new boolean[]{false, true})
                for (boolean directional : new boolean[]{false, true})
                    for (boolean xoroshiro : new boolean[]{false, true})
                        for (double chance : new double[]{0.0, 0.25, 1.0, Double.NaN})
                            scenarios.add(new Scenario(-64, 320, -2, 3, noTargets, wet, directional, xoroshiro, chance, 20261025L));
        scenarios.add(new Scenario(0, 128, 0, 0, false, true, true, false, 0.25, 7L));
        scenarios.add(new Scenario(0, 256, 17, -31, false, false, false, true, 0.25, -187L));
        scenarios.add(new Scenario(0, 0, 0, 0, true, false, false, false, 0.25, 0L));
        long writes = 0, originalReads = 0, candidateReads = 0;
        for (var s : scenarios) {
            var original = new Scene(s);
            var candidate = new Scene(s);
            generate(feature(s, true), original);
            generate(feature(s, false), candidate);
            if (!original.writes.equals(candidate.writes)) throw new AssertionError("Placement order or properties changed: " + s);
            if (!original.changed.equals(candidate.changed)) throw new AssertionError("Final blocks changed: " + s);
            for (int i = 0; i < 16; i++) if (original.random.nextLong() != candidate.random.nextLong())
                throw new AssertionError("Random stream changed: " + s);
            if (candidate.minCalls != 1 || candidate.maxCalls != 1 || candidate.randomCalls != 1)
                throw new AssertionError("Invariant getters were not hoisted: " + s);
            writes += original.writes.size(); originalReads += original.reads; candidateReads += candidate.reads;
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("scenarios", scenarios.size());
        result.put("identical_ordered_writes", writes);
        result.put("original_block_reads", originalReads);
        result.put("candidate_block_reads", candidateReads);
        result.put("random_streams_identical", true);
        result.put("normal_height_invariant_getters_before", Map.of("min_y",256,"max_y",98560,"random",98304));
        result.put("normal_height_invariant_getters_after", Map.of("min_y",1,"max_y",1,"random",1));
        return result;
    }

    public static Map<String, Object> benchmark() {
        return benchmark(null);
    }

    private interface WorldFactory { WorldGenLevel create(Scene scene) throws ReflectiveOperationException; }

    private record SeedGenWorld(WorldGenLevel level, Map<Long, BlockState> placed, java.util.concurrent.atomic.AtomicInteger heightReads) {}

    private static final class SeedGenFixture implements WorldFactory, AutoCloseable {
        final URLClassLoader loader;
        final Class<?> samplerType;
        final java.lang.reflect.Constructor<?> spaceConstructor, worldConstructor, terrainConstructor;
        final java.lang.reflect.Field placedField;
        final Method createLevel;

        SeedGenFixture(Path jar) throws Exception {
            loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, SurfaceOreRegression.class.getClassLoader());
            var terrainType = loader.loadClass("mobius.seedrenderer.dh.TreeGrower$Terrain");
            var spaceType = loader.loadClass("mobius.seedrenderer.dh.TreeGrower$Space");
            var worldType = loader.loadClass("mobius.seedrenderer.dh.TreeGrower$World");
            var standInType = loader.loadClass("mobius.seedrenderer.dh.TreeGrower$StandIn");
            samplerType = loader.loadClass("mobius.seedrenderer.sample.SeedColumnSampler");
            var paintedTerrain = loader.loadClass("mobius.seedrenderer.dh.PaintedChunks$Terrain");
            terrainConstructor = paintedTerrain.getDeclaredConstructor(samplerType,int.class,boolean.class);terrainConstructor.setAccessible(true);
            spaceConstructor = spaceType.getDeclaredConstructor(terrainType);spaceConstructor.setAccessible(true);
            worldConstructor = worldType.getDeclaredConstructor(ChunkGenerator.class,int.class,int.class,Level.class,ServerLevel.class);worldConstructor.setAccessible(true);
            placedField = spaceType.getDeclaredField("placed");placedField.setAccessible(true);
            createLevel = standInType.getDeclaredMethod("level",spaceType,RandomSource.class,java.util.function.Function.class,worldType,long.class);createLevel.setAccessible(true);
        }

        @SuppressWarnings("unchecked") SeedGenWorld createWorld(Scene scene, boolean floating) throws ReflectiveOperationException {
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            Object sampler = org.mockito.Mockito.mock((Class<Object>)samplerType, invocation -> {
                if (invocation.getMethod().getName().equals("surfaceHeight")) {
                    reads.incrementAndGet();
                    return 64 + Math.floorMod((Integer)invocation.getArgument(0)*7+(Integer)invocation.getArgument(1)*13,17)-8;
                }
                return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
            });
            var terrain = terrainConstructor.newInstance(sampler,64,floating);
            var space = spaceConstructor.newInstance(terrain);
            var placed = (Map<Long,BlockState>)placedField.get(space);
            var s=scene.scenario;
            if(!s.noTargets) {
                int[][] markers={{2,-40,3},{6,30,7},{10,63,11},{14,66,15},{-1,63,8}};
                for(var marker:markers) {
                    var pos=new BlockPos((s.chunkX<<4)+marker[0],marker[1],(s.chunkZ<<4)+marker[2]);
                    placed.put(pos.asLong(),(pos.getY()<0?Blocks.DEEPSLATE_REDSTONE_ORE:Blocks.REDSTONE_ORE).defaultBlockState());
                    for(var direction:net.minecraft.core.Direction.values())
                        placed.put(pos.relative(direction).asLong(),(s.wetCaves?Blocks.WATER:Blocks.AIR).defaultBlockState());
                }
            }
            var world=worldConstructor.newInstance(null,s.minY,s.maxY-s.minY,null,null);
            java.util.function.Function<BlockPos,Object> biomes=pos -> null;
            var level=(WorldGenLevel)createLevel.invoke(null,space,scene.random,biomes,world,s.seed);
            return new SeedGenWorld(level,placed,reads);
        }

        @Override public WorldGenLevel create(Scene scene) throws ReflectiveOperationException { return createWorld(scene,false).level; }
        @Override public void close() throws Exception { loader.close(); }
    }

    public static Map<String,Object> verifySeedGen(Path jar) throws Exception {
        try(var factory=new SeedGenFixture(jar)) {
            int cases=0;long avoidedHeightReads=0;
            for(boolean noTargets:new boolean[]{false,true})for(boolean wet:new boolean[]{false,true})
                for(boolean directional:new boolean[]{false,true})for(double chance:new double[]{0.25,1.0}) {
                    var s=new Scenario(-64,320,-2,3,noTargets,wet,directional,(cases&1)==0,chance,20261025L+cases);
                    var a=new Scene(s);var b=new Scene(s);var aw=factory.createWorld(a,false);var bw=factory.createWorld(b,false);
                    var candidates=net.stln.magitech.compat.seedgen.SeedGenOreCandidates.find(bw.level,
                            List.of(Blocks.REDSTONE_ORE,Blocks.DEEPSLATE_REDSTONE_ORE),Blocks.GLOWSTONE.defaultBlockState(),-32,48,-64,320);
                    if(candidates==null)throw new AssertionError("Verified SeedGen sparse path was not selected");
                    generate(feature(s,true),a,aw.level);generate(feature(s,false),b,bw.level);
                    if(!aw.placed.equals(bw.placed))throw new AssertionError("SeedGen virtual blocks changed: "+s);
                    for(int i=0;i<16;i++)if(a.random.nextLong()!=b.random.nextLong())throw new AssertionError("SeedGen RNG changed");
                    if(noTargets&&bw.heightReads.get()!=0)throw new AssertionError("Target-free sparse scan still sampled terrain height");
                    avoidedHeightReads+=aw.heightReads.get()-bw.heightReads.get();cases++;
                }
            var s=new Scenario(-64,320,-2,3,true,false,false,false,0.25,20261025L);
            var floating=factory.createWorld(new Scene(s),true);
            if(net.stln.magitech.compat.seedgen.SeedGenOreCandidates.find(floating.level,List.of(Blocks.REDSTONE_ORE),Blocks.GLOWSTONE.defaultBlockState(),-32,48,-64,320)!=null)
                throw new AssertionError("Floating terrain must use the full scan");
            var normal=factory.createWorld(new Scene(s),false);
            if(net.stln.magitech.compat.seedgen.SeedGenOreCandidates.find(normal.level,List.of(Blocks.STONE),Blocks.GLOWSTONE.defaultBlockState(),-32,48,-64,320)!=null)
                throw new AssertionError("Implicit terrain targets must use the full scan");
            if(net.stln.magitech.compat.seedgen.SeedGenOreCandidates.find(normal.level,List.of(Blocks.REDSTONE_ORE),Blocks.REDSTONE_ORE.defaultBlockState(),-32,48,-64,320)!=null)
                throw new AssertionError("Self-producing targets must use the full scan");
            return Map.of("seedgen_scenarios",cases,"placements_and_rng_identical",true,"avoided_terrain_height_queries",avoidedHeightReads,"fallback_checks",3);
        }
    }

    public static Map<String, Object> benchmarkSeedGen(Path jar) throws Exception {
        try(var factory=new SeedGenFixture(jar)) { return benchmark(factory); }
    }

    private static Map<String, Object> benchmark(WorldFactory factory) {
        var s = new Scenario(-64,320,-2,3,true,false,false,false,0.25,20261025L);
        var original = feature(s,true);var candidate = feature(s,false);
        for (int i=0;i<64;i++) { generateBenchmark(original,s,factory);generateBenchmark(candidate,s,factory); }
        var oldTimes = new ArrayList<Double>();var newTimes = new ArrayList<Double>();
        var clock=ManagementFactory.getThreadMXBean();
        for (int round=0;round<6;round++) for (int order=0;order<2;order++) {
            boolean old=(round+order)%2==0;
            var scenes = new Scene[256];
            var levels = new WorldGenLevel[256];
            for (int i=0;i<scenes.length;i++) {
                scenes[i] = new Scene(s);
                try { levels[i] = factory==null ? scenes[i].level : factory.create(scenes[i]); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            }
            long start=clock.getCurrentThreadCpuTime();
            for(int i=0;i<scenes.length;i++)generate(old?original:candidate,scenes[i],levels[i]);
            (old?oldTimes:newTimes).add((clock.getCurrentThreadCpuTime()-start)/1e6);
        }
        return Map.of("proxy_chunks_per_round",256,"original_cpu_ms",oldTimes,"candidate_cpu_ms",newTimes,
                "world_proxy",factory==null?"regression fixture":"SeedGen TreeGrower.StandIn","fixture_setup_timed",false);
    }

    private static void generateBenchmark(Feature<NoneFeatureConfiguration> feature, Scenario s, WorldFactory factory) {
        var scene=new Scene(s);
        try { generate(feature,scene,factory==null?scene.level:factory.create(scene)); }
        catch(ReflectiveOperationException e) { throw new IllegalStateException("Cannot build the SeedGen benchmark world",e); }
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        System.out.println("ORE_SURFACE_REGRESSION_PASS " + runAll());
        System.out.println("ORE_SURFACE_BENCHMARK " + benchmark());
    }
}
