package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.appliedenhancements.Config;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import com.appliedenhancements.api.AelisCycleRuntimeController;
import com.appliedenhancements.api.AelisCycleSeedPolicy;
import com.appliedenhancements.runtime.AelisCycleExecutionNbt;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.concurrent.SynchronizedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AelisExactCyclicPlanningTest {
    @BeforeAll static void bootstrap() throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var keys = new net.minecraft.core.MappedRegistry<appeng.api.stacks.AEKeyType>(
                appeng.api.stacks.AEKeyType.REGISTRY_KEY, com.mojang.serialization.Lifecycle.stable());
        appeng.api.stacks.AEKeyTypesInternal.setRegistry(keys);
        appeng.api.stacks.AEKeyTypesInternal.register(appeng.api.stacks.AEKeyType.items());
        keys.freeze();
        // The standalone fixture has no AE2 item-registration event. Its codec
        // only needs a bound missing-content sentinel distinct from these test items.
        var sentinel = net.neoforged.neoforge.registries.DeferredHolder.class.getDeclaredField("holder");
        sentinel.setAccessible(true);
        sentinel.set(appeng.core.definitions.AEItems.MISSING_CONTENT.holder(),
                BuiltInRegistries.ITEM.wrapAsHolder(Items.BARRIER));
    }

    @AfterEach void unloadConfig() { Config.SPEC.acceptConfig(null); }

    @Test void exactCyclicAmountsReachClientRowsAndTooltipsWithoutClampingOrMixingOrdinaryWork() throws Exception {
        AEKey key = AEItemKey.of(Items.EMERALD);
        var cyclic = new BigInteger("44444444444444444444448");
        var crafted = cyclic.multiply(BigInteger.TWO).add(BigInteger.valueOf(17));
        var payload = new com.github.appliedenhancements.network.CraftingCalculationPathPayload(42,
                com.github.appliedenhancements.integration.ae2.AelisCalculationPath.AELIS,
                Map.of(key, Long.MAX_VALUE), Map.of(key, crafted), Map.of(), Map.of(), null, null, Map.of(key, cyclic));
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        // NeoForge's registry setup event normally enables item ID synchronization.
        // Enable it just for this standalone packet round trip, then restore it.
        var setSync = net.neoforged.neoforge.registries.BaseMappedRegistry.class
                .getDeclaredMethod("setSync", boolean.class);
        setSync.setAccessible(true);
        boolean wasSynced = BuiltInRegistries.ITEM.doesSync();
        setSync.invoke(BuiltInRegistries.ITEM, true);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registries,
                net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        try {
            var codec = com.github.appliedenhancements.network.CraftingCalculationPathPayload.STREAM_CODEC;
            codec.encode(buffer, payload);
            var decoded = codec.decode(buffer);
            assertEquals(payload, decoded);
            assertEquals(0, buffer.readableBytes());
            var menu = new com.appliedenhancements.mixin.CraftConfirmMenuMixin() {};
            menu.appliedenhancements$setCyclicCraftAmounts(decoded.cyclicCraftAmounts());
            menu.appliedenhancements$setBigIntegerCyclicCraftAmounts(decoded.bigIntegerCyclicCraftAmounts());
            menu.appliedenhancements$setBigIntegerCraftAmounts(decoded.bigIntegerCraftAmounts());
            assertEquals(cyclic, menu.appliedenhancements$getBigIntegerCyclicCraftAmounts().get(key));
            assertEquals(crafted, menu.appliedenhancements$getBigIntegerCraftAmounts().get(key));
            var amount = menu.appliedenhancements$getBigIntegerCyclicCraftAmounts().get(key);
            assertEquals("44.4Z", com.appliedenhancements.client.CraftingPlanAmountFormatter.format(
                    key, amount, appeng.api.stacks.AmountFormat.SLOT));
            assertEquals("44,444,444,444,444,444,444,448", com.appliedenhancements.client.CraftingPlanAmountFormatter.format(
                    key, amount, appeng.api.stacks.AmountFormat.FULL));
            var snapshot = menu.appliedenhancements$getBigIntegerCyclicCraftAmounts();
            assertSame(snapshot, menu.appliedenhancements$getBigIntegerCyclicCraftAmounts());
            menu.appliedenhancements$setCyclicCraftAmounts(Map.of(key, 7L));
            menu.appliedenhancements$setBigIntegerCyclicCraftAmounts(Map.of());
            assertEquals(BigInteger.valueOf(7), menu.appliedenhancements$getBigIntegerCyclicCraftAmounts().get(key));
            assertEquals(cyclic, snapshot.get(key));
        } finally {
            buffer.release();
            setSync.invoke(BuiltInRegistries.ITEM, wasSynced);
        }
    }

    @Test void cycleDemandUsesTheExactRequestInsteadOfItsSaturatedLongProjection() throws Exception {
        loadConfig(AelisCycleSeedPolicy.PRESERVE_MINIMUM);
        var request = new com.appliedenhancements.api.AelisExactRequest(BigInteger.TEN.pow(25));
        try (var scope = new com.appliedenhancements.runtime.ExactRequestScope(request)) {
            assertEquals(request.amount().multiply(BigInteger.TWO), invoke("cyclicRequestedItems", 2L, request.projection()));
        }
        assertEquals(BigInteger.valueOf(10_000_000_000L), invoke("cyclicRequestedItems", 1L, 10_000_000_000L));
    }

    @ParameterizedTest
    @EnumSource(AelisCycleSeedPolicy.class)
    void globalAndLocalCyclesKeepExactFiringsAndNeedOnlyOneStartupSeed(AelisCycleSeedPolicy policy)
            throws Exception {
        loadConfig(policy);
        AEKey seed = AEItemKey.of(Items.EMERALD);
        var pattern = pattern(seed);
        var candidate = candidate(pattern);
        var variants = Map.of(seed, List.of(new AelisCyclicDemandSolver.Variant<>(
                candidate, seed, BigInteger.TWO,
                List.of(new AelisCyclicDemandSolver.Input<>(seed, BigInteger.ONE)))));
        var demand = BigInteger.TEN.pow(25);
        var limits = new AelisCyclicDemandSolver.Limits(256, 1_000_000,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5), true);
        Object global = invoke("solveGlobalCyclePlan", variants, seed, demand, Map.of(seed, BigInteger.ONE), limits);
        Object local = invoke("solveLocalCyclePlan", new AelisCyclicRegionDetector.Region<>(Set.of(seed), variants),
                seed, demand, Map.of(seed, BigInteger.ONE), limits);
        for (var preserved : List.of(global, local)) {
            assertEquals(true, property(preserved, "converged"));
            var result = property(preserved, "result");
            assertEquals(true, property(result, "solved"),
                    (preserved == global ? "global: " : "local: ") + property(result, "failure"));
            var plan = property(result, "plan");
            var missing = property(plan, preserved == global ? "missing" : "missingSeeds");
            assertEquals(Map.of(), missing);
            var runtimePlan = (AelisCycleExecutionPlan) property(preserved, "executionPlan");
            assertEquals(1, runtimePlan.steps().size());
            var crafts = policy == AelisCycleSeedPolicy.PRESERVE_MINIMUM ? demand : demand.subtract(BigInteger.ONE);
            assertEquals(crafts, runtimePlan.steps().getFirst().exactCrafts());
            assertEquals(Map.of(candidate, crafts), property(plan, "firings"));
            assertEquals(Map.of(seed, 1L), runtimePlan.minimumSeeds());
            assertEquals(Long.MAX_VALUE, runtimePlan.steps().getFirst().crafts());
        }
    }

    @Test void exactCycleNbtRoundTripKeepsHugeStepCountsAndOutstandingOutputs() throws Exception {
        loadConfig(AelisCycleSeedPolicy.PRESERVE_MINIMUM);
        AEKey seed = AEItemKey.of(Items.EMERALD);
        AEKey definition = AEItemKey.of(Items.PAPER);
        var crafts = BigInteger.TEN.pow(25);
        var plan = new AelisCycleExecutionPlan(
                List.of(new AelisCycleExecutionPlan.Step(definition, crafts, Map.of(seed, 1L), Set.of(seed))),
                Map.of(seed, 1L), Set.of(seed), AelisCycleSeedPolicy.PRESERVE_MINIMUM,
                new AelisCycleExecutionPlan.Phase(Set.of(), Map.of(definition, Map.of(seed, 2L))));
        var runtime = AelisCycleRuntimeController.withCyclePhase(plan);
        runtime.patternDispatched(definition, Long.MAX_VALUE);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var encoded = AelisCycleExecutionNbt.write(runtime, registries);
        var restored = AelisCycleExecutionNbt.read(encoded, registries);
        assertNotNull(restored);
        assertEquals(plan, restored.plan());
        assertEquals(runtime.snapshot(), restored.snapshot());
        assertEquals(crafts.subtract(BigInteger.valueOf(Long.MAX_VALUE)), restored.exactRemainingCrafts());
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO),
                restored.snapshot().exactPendingOutputs().get(seed));

        // Existing v2 saves remain readable through their native long fields.
        encoded.putInt("version", 2);
        encoded.remove("exactRemainingCrafts");
        encoded.remove("exactPendingOutputs");
        encoded.putLong("remainingCrafts", Long.MAX_VALUE - 1);
        encoded.getList("pendingOutputs", Tag.TAG_COMPOUND).getCompound(0).putLong("amount", 2);
        var step = encoded.getList("steps", Tag.TAG_COMPOUND).getCompound(0);
        step.remove("exactCrafts");
        var legacy = AelisCycleExecutionNbt.read(encoded, registries);
        assertNotNull(legacy);
        assertEquals(Long.MAX_VALUE - 1, legacy.remainingCrafts());
        assertEquals(Map.of(seed, 2L), legacy.snapshot().pendingOutputs());
    }

    private static IPatternDetails pattern(AEKey seed) {
        return new IPatternDetails() {
            @Override public AEItemKey getDefinition() { return AEItemKey.of(Items.PAPER); }
            @Override public IInput[] getInputs() { return new IInput[0]; }
            @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(seed, 2)); }
        };
    }

    private static Object candidate(IPatternDetails pattern) throws Exception {
        var type = Class.forName(AelisPlanner.class.getName() + "$CompiledCandidate");
        var constructor = Arrays.stream(type.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == 12).findFirst().orElseThrow();
        constructor.setAccessible(true);
        return constructor.newInstance(0, null, pattern, List.of(), List.of(), 2L, false, false, false,
                null, null, List.of());
    }

    private static Object invoke(String name, Object... args) throws Exception {
        var method = Arrays.stream(AelisPlanner.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name) && m.getParameterCount() == args.length)
                .findFirst().orElseThrow();
        method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
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
        data.set("crafting.aelis.enable_big_integer_planning", true);
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                .getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        constructor.setAccessible(true);
        Config.SPEC.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(data, null, null));
    }
}
