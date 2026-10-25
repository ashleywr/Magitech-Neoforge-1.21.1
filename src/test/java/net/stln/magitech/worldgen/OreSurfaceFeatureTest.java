package net.stln.magitech.worldgen;

import com.google.gson.Gson;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OreSurfaceFeatureTest {
    @Test void preservesUpstreamWritesAndRandomStreams() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var results = SurfaceOreRegression.runAll();
        assertEquals(67, results.get("scenarios"));
        assertEquals(true, results.get("random_streams_identical"));
        assertTrue(((Number) results.get("identical_ordered_writes")).longValue() > 0);
        System.out.println("ORE_SURFACE_REGRESSION_PASS " + new Gson().toJson(results));
        System.out.println("ORE_SURFACE_BENCHMARK " + new Gson().toJson(SurfaceOreRegression.benchmark()));
        String seedGenJar = System.getProperty("magitech.test.seedgenJar", "");
        if (!seedGenJar.isEmpty()) {
            System.out.println("ORE_SURFACE_SEEDGEN_REGRESSION_PASS "
                    + new Gson().toJson(SurfaceOreRegression.verifySeedGen(java.nio.file.Path.of(seedGenJar))));
            System.out.println("ORE_SURFACE_SEEDGEN_BENCHMARK "
                    + new Gson().toJson(SurfaceOreRegression.benchmarkSeedGen(java.nio.file.Path.of(seedGenJar))));
        }
    }
}
