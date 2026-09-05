package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.stacks.AEKey;
import com.appliedenhancements.Config;
import com.appliedenhancements.api.AelisCycleSeedPolicy;
import com.appliedenhancements.test.TestAEKey;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.concurrent.SynchronizedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AelisEmptyCycleExecutionPlanTest {
    private final AEKey material = new TestAEKey("cycle_material");

    @AfterEach
    void unloadConfig() {
        Config.SPEC.acceptConfig(null);
    }

    @ParameterizedTest
    @EnumSource(AelisCycleSeedPolicy.class)
    void localCycleSatisfiedByStockNeedsNoExecutionOrSeedReservation(
            AelisCycleSeedPolicy policy) throws Exception {
        loadConfig(policy);
        var region = new AelisCyclicRegionDetector.Region<>(
                Set.of(material), cycleVariants());
        Object preserved = invoke("solveLocalCyclePlan",
                new Class<?>[] { AelisCyclicRegionDetector.Region.class, AEKey.class,
                        BigInteger.class, Map.class, AelisCyclicDemandSolver.Limits.class },
                region, material, bi(80), Map.of(material, bi(100)), limits());

        var result = (AelisCyclicRegionSolver.Result<?, ?>) property(preserved, "result");
        assertTrue(result.solved());
        assertTrue(result.plan().firings().isEmpty());
        assertTrue(result.plan().missingSeeds().isEmpty());
        assertEquals(Map.of(material, bi(80)), result.plan().requiredAvailable());
        assertTrue(result.plan().executionSchedule().isEmpty());
        assertNoCycleRuntime(preserved);
    }

    @ParameterizedTest
    @EnumSource(AelisCycleSeedPolicy.class)
    void globalCycleSatisfiedByStockNeedsNoExecutionOrSeedReservation(
            AelisCycleSeedPolicy policy) throws Exception {
        loadConfig(policy);
        Object preserved = invoke("solveGlobalCyclePlan",
                new Class<?>[] { Map.class, AEKey.class, BigInteger.class,
                        Map.class, AelisCyclicDemandSolver.Limits.class },
                cycleVariants(), material, bi(80), Map.of(material, bi(100)), limits());

        var result = (AelisCyclicDemandSolver.Result<?, ?>) property(preserved, "result");
        assertTrue(result.solved());
        assertTrue(result.plan().firings().isEmpty());
        assertTrue(result.plan().missing().isEmpty());
        assertEquals(Map.of(material, bi(80)), result.plan().requiredAvailable());
        assertTrue(result.plan().executionSchedule().isEmpty());
        assertNoCycleRuntime(preserved);
    }

    @ParameterizedTest
    @EnumSource(AelisCycleSeedPolicy.class)
    void globalPlanRetainsOrdinaryCraftsWhenItsCycleUsesOnlyStock(
            AelisCycleSeedPolicy policy) throws Exception {
        loadConfig(policy);
        AEKey output = new TestAEKey("finished_order");
        var variants = new HashMap<>(cycleVariants());
        variants.put(output, List.of(new AelisCyclicDemandSolver.Variant<>(
                "finish", output, BigInteger.ONE,
                List.of(new AelisCyclicDemandSolver.Input<>(material, bi(2))))));
        Object preserved = invoke("solveGlobalCyclePlan",
                new Class<?>[] { Map.class, AEKey.class, BigInteger.class,
                        Map.class, AelisCyclicDemandSolver.Limits.class },
                variants, output, BigInteger.ONE, Map.of(material, bi(2)), limits());

        var result = (AelisCyclicDemandSolver.Result<?, ?>) property(preserved, "result");
        assertTrue(result.solved());
        assertEquals(Map.of("finish", BigInteger.ONE), result.plan().firings());
        assertEquals(Map.of(material, bi(2)), result.plan().requiredAvailable());
        assertTrue(result.plan().executionSchedule().isEmpty());
        assertNoCycleRuntime(preserved);
    }

    private Map<AEKey, List<AelisCyclicDemandSolver.Variant<AEKey, String>>> cycleVariants() {
        // Token IDs are sufficient here: no cyclic candidate is executed.
        return Map.of(material, List.of(new AelisCyclicDemandSolver.Variant<>(
                "grow", material, bi(2),
                List.of(new AelisCyclicDemandSolver.Input<>(material, BigInteger.ONE)))));
    }

    private static void assertNoCycleRuntime(Object preserved) throws Exception {
        assertEquals(true, property(preserved, "converged"));
        assertNull(property(preserved, "executionPlan"));
        assertEquals(Map.of(), property(preserved, "retainedSeeds"));
    }

    private static Object invoke(String name, Class<?>[] types, Object... arguments)
            throws Exception {
        var method = AelisPlanner.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw failure;
        }
    }

    private static Object property(Object instance, String name) throws Exception {
        var method = instance.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(instance);
    }

    private static void loadConfig(AelisCycleSeedPolicy policy) throws Exception {
        var data = new SynchronizedConfig(TomlFormat.instance(), HashMap::new);
        Config.SPEC.correct(data);
        data.set("crafting.aelis.cycle_solver.seed_policy", policy.name());
        // NeoForge seals ILoadedConfig; use its actual in-memory holder without saving.
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                .getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        constructor.setAccessible(true);
        Config.SPEC.acceptConfig((IConfigSpec.ILoadedConfig)
                constructor.newInstance(data, null, null));
    }

    private static AelisCyclicDemandSolver.Limits limits() {
        return new AelisCyclicDemandSolver.Limits(
                256, 1_000_000, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
    }

    private static BigInteger bi(long value) {
        return BigInteger.valueOf(value);
    }
}
