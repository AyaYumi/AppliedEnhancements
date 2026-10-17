package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.Config;
import com.appliedenhancements.mixin.CraftingSimulationStateLongSafetyAccessor;
import com.appliedenhancements.test.TestAEKey;
import com.github.appliedenhancements.integration.ae2.*;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Runs the actual transactional executor, with only absent Mixin bridges replaced. */
class AelisBigIntegerTransactionalTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrapMinecraft() throws Exception {
        com.appliedenhancements.test.ForgeTestBootstrap.bootstrapMinecraft();
    }
    @AfterEach void unloadConfig() { Config.SPEC.acceptConfig(null); }

    @Test void essenceChainKeepsExactMissingBeyondLongAndUsesBoundedCatalystRequests() throws Exception {
        loadConfig(true);
        var fixture = new Fixture(true, 1);
        long order = 10_000_000_000_000_000L;
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> fixture.execute(order, true));
        assertEquals(BigInteger.valueOf(order).multiply(BigInteger.valueOf(1024)),
                fixture.state.missing.get(fixture.keys[5]));
        assertEquals(Long.MAX_VALUE, fixture.missing.get(fixture.keys[5]));
        assertEquals(5, fixture.catalystRequests);
        assertEquals(6, fixture.steps);
        assertFalse(fixture.state.previewOnly); // Only the missing total exceeds long here.
        for (int index = 0; index < 5; index++) {
            assertEquals(BigInteger.valueOf(order).multiply(BigInteger.valueOf(4).pow(index)),
                    fixture.state.exact.get(fixture.keys[index]));
        }
    }

    @Test void evenPatternCountsBeyondLongRemainAnExactPreview() throws Exception {
        loadConfig(true);
        var fixture = new Fixture(true, 1);
        long order = 1_000_000_000_000_000_000L;
        fixture.execute(order, true);
        assertEquals(BigInteger.valueOf(order).multiply(BigInteger.valueOf(256)),
                fixture.state.exact.get(fixture.keys[4]));
        assertEquals(BigInteger.valueOf(order).multiply(BigInteger.valueOf(1024)),
                fixture.state.missing.get(fixture.keys[5]));
        assertTrue(fixture.state.previewOnly);
        assertTrue(fixture.state.appliedenhancements$getCrafts().values().stream().allMatch(n -> n > 0));
    }

    @Test void extractsExistingIntermediateAndRawStockBeforeRecordingExactShortfall() throws Exception {
        loadConfig(true);
        var fixture = new Fixture(false, 1);
        fixture.state.insert(fixture.keys[2], 3, Actionable.MODULATE);
        fixture.state.insert(fixture.keys[5], 17, Actionable.MODULATE);
        long order = 1_000_000_000_000_000_000L;
        fixture.execute(order, true);
        assertEquals(BigInteger.valueOf(order).multiply(BigInteger.valueOf(1024))
                        .subtract(BigInteger.valueOf(3 * 64 + 17)),
                fixture.state.missing.get(fixture.keys[5]));
        assertEquals(0, fixture.state.extract(fixture.keys[2], 100, Actionable.SIMULATE));
        assertEquals(0, fixture.state.extract(fixture.keys[5], 100, Actionable.SIMULATE));
    }

    @Test void realAttemptReportsShortageInsteadOfFallingBackToNative() throws Exception {
        loadConfig(true);
        var fixture = new Fixture(true, 1);
        assertThrows(CraftBranchFailure.class, () -> fixture.execute(10_000_000_000_000_000L, false));
        assertTrue(fixture.state.exact.isEmpty());
    }

    @Test void disablingBigIntegerPlanningStillRejectsUnrepresentableDemand() throws Exception {
        loadConfig(false);
        var fixture = new Fixture(false, 1);
        Exception failure = assertThrows(Exception.class, () -> fixture.execute(10_000_000_000_000_000L, true));
        assertEquals("Fallback", failure.getClass().getSimpleName());
    }

    @Test void surplusFromRoundedPatternCountsIsReturnedToInventory() throws Exception {
        loadConfig(true);
        var fixture = new Fixture(false, 3);
        fixture.execute(10, true);
        assertEquals(2, fixture.state.extract(fixture.keys[0], 10, Actionable.SIMULATE));
        assertEquals(BigInteger.valueOf(12), fixture.state.exact.get(fixture.keys[0]));
    }

    @Test void topologicalDemandPromotesWhenOnlyDescendantsExceedLong() throws Exception {
        loadConfig(true);
        var fixture = new Fixture(false, 1);
        fixture.state.insert(fixture.keys[2], 3, Actionable.MODULATE);
        fixture.state.insert(fixture.keys[5], 17, Actionable.MODULATE);
        fixture.executeTopological(1_000_000_000_000_000_000L, true);
        assertEquals(BigInteger.valueOf(1_000_000_000_000_000_000L).multiply(bi(1024))
                .subtract(bi(3 * 64 + 17)), fixture.state.missing.get(fixture.keys[5]));
        assertEquals(Long.MAX_VALUE, fixture.missing.get(fixture.keys[5]));
        assertEquals(bi(1_000_000_000_000_000_000L).multiply(bi(256)).subtract(bi(3 * 16)),
                fixture.state.exact.get(fixture.keys[4]));
        assertTrue(fixture.state.previewOnly);
    }

    @Test void topologicalSmallOrdersKeepRoundedSurplusAndExactLedgers() throws Exception {
        loadConfig(true);
        var fixture = new Fixture(false, 3);
        fixture.executeTopological(10, true);
        assertEquals(bi(12), fixture.state.exact.get(fixture.keys[0]));
        assertEquals(2, fixture.state.extract(fixture.keys[0], 10, Actionable.SIMULATE));
        assertFalse(fixture.state.previewOnly);
    }

    private static BigInteger bi(long value) { return BigInteger.valueOf(value); }

    @Test void largeMultiOutputShortageStaysBatchedAndAccountsForAllStockAndSurplus() throws Exception {
        loadConfig(true);
        var fixture = new MultiOutputFixture(3, 2);
        long order = 198_645_570;
        fixture.state.insert(fixture.sand, 5, Actionable.MODULATE);
        fixture.state.insert(fixture.ore, 17, Actionable.MODULATE);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> fixture.execute(order, true, false));
        long times = (order - 5 + 2) / 3;
        assertEquals(times * 4 - 17, fixture.missing.get(fixture.ore));
        assertEquals(bi(times * 4 - 17), fixture.state.missing.get(fixture.ore));
        assertEquals(times, fixture.state.appliedenhancements$getCrafts().get(fixture.sandPattern));
        assertEquals(times * 2, fixture.state.extract(fixture.gravel, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(2, fixture.state.extract(fixture.sand, 10, Actionable.SIMULATE));
        assertEquals(bi(times * 2), fixture.state.exact.get(fixture.gravel));
        assertEquals(2, fixture.steps); // One producing batch and one raw-input request.
    }

    @Test void laterSiblingUsesSecondaryOutputsWithoutPlanningThemAgain() throws Exception {
        loadConfig(true);
        var fixture = new MultiOutputFixture(1, 2);
        fixture.addConsumer(false);
        fixture.execute(20_000, true, false);
        assertEquals(80_000, fixture.missing.get(fixture.ore));
        assertEquals(0, fixture.missing.get(fixture.gravel));
        assertEquals(0, fixture.state.extract(fixture.gravel, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(20_000L, fixture.state.appliedenhancements$getCrafts().get(fixture.sandPattern));
    }

    @Test void earlierSiblingCannotUseSecondaryOutputsBeforeInputsArePlanned() throws Exception {
        loadConfig(true);
        var fixture = new MultiOutputFixture(1, 2);
        fixture.addConsumer(true);
        fixture.execute(20_000, true, false);
        assertEquals(40_000, fixture.missing.get(fixture.gravel));
        assertEquals(40_000, fixture.state.extract(fixture.gravel, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    @Test void stockSatisfiedMultiOutputRequestDoesNotInventByproducts() throws Exception {
        loadConfig(true);
        var fixture = new MultiOutputFixture(1, 2);
        fixture.state.insert(fixture.sand, 20_000, Actionable.MODULATE);
        fixture.execute(20_000, false, false);
        assertTrue(fixture.state.appliedenhancements$getCrafts().isEmpty());
        assertEquals(0, fixture.state.extract(fixture.gravel, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    @Test void graphAutomaticallyChoosesOrderedExecutionForSecondaryOutputSharing() throws Exception {
        loadConfig(false); // Exercise the real outer transaction without test Mixin bridges.
        var fixture = new MultiOutputFixture(1, 2);
        fixture.addConsumer(false);
        fixture.state.insert(fixture.ore, 80_000, Actionable.MODULATE);
        fixture.execute(20_000, false, true);
        assertEquals(20_000L, fixture.state.appliedenhancements$getCrafts().get(fixture.sandPattern));
        assertEquals(0, fixture.state.extract(fixture.gravel, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    @Test void failedOuterTransactionRollsBackConsumedStockAndProducedSecondaryOutputs() throws Exception {
        loadConfig(false);
        var fixture = new MultiOutputFixture(1, 2);
        fixture.addConsumer(false);
        fixture.addLateMissingInput();
        fixture.state.insert(fixture.ore, 80_000, Actionable.MODULATE);
        assertThrows(CraftBranchFailure.class, () -> fixture.execute(20_000, false, true));
        assertEquals(80_000, fixture.state.extract(fixture.ore, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0, fixture.state.extract(fixture.gravel, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(fixture.state.appliedenhancements$getCrafts().isEmpty());
        assertTrue(fixture.missing.isEmpty());
    }

    @Test void secondaryInventoryOverflowCannotSilentlyCreateAnInexactPlan() throws Exception {
        loadConfig(true);
        var fixture = new MultiOutputFixture(1, 2);
        fixture.state.insert(fixture.gravel, Long.MAX_VALUE, Actionable.MODULATE);
        Exception failure = assertThrows(Exception.class, () -> fixture.execute(1, true, false));
        assertEquals("Fallback", failure.getClass().getSimpleName());
        assertEquals(Long.MAX_VALUE, fixture.state.extract(fixture.gravel, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(fixture.state.appliedenhancements$getCrafts().isEmpty());
    }

    private static final class MultiOutputFixture {
        final AEKey sand = new TestAEKey("sand", appeng.api.stacks.AEKeyType.items());
        final AEKey gravel = new TestAEKey("gravel", appeng.api.stacks.AEKeyType.items());
        final AEKey ore = new TestAEKey("ore", appeng.api.stacks.AEKeyType.items());
        final State state = new State();
        final KeyCounter missing = new KeyCounter();
        final List<Object> nodes = new ArrayList<>();
        final IPatternDetails sandPattern;
        int rootIndex;
        int steps;

        MultiOutputFixture(long primaryAmount, long secondaryAmount) throws Exception {
            sandPattern = pattern(List.of(new GenericStack(sand, primaryAmount), new GenericStack(gravel, secondaryAmount)));
            Object node = addNode(sand, sandPattern);
            field(node, "outputPerPattern", primaryAmount);
            field(node, "secondaryOutputs", List.of(new GenericStack(gravel, secondaryAmount)));
            addNode(ore, null);
            addNode(gravel, null);
            connect(node, 1, ore, 4);
        }

        Object addNode(AEKey key, IPatternDetails pattern) throws Exception {
            Object node = construct("Node", nodes.size(), key, 1L, null);
            field(node, "reachable", true);
            field(node, "terminal", pattern == null);
            field(node, "details", pattern);
            field(node, "outputPerPattern", 1L);
            nodes.add(node);
            return node;
        }

        void addConsumer(boolean byproductFirst) throws Exception {
            var key = new TestAEKey("product", appeng.api.stacks.AEKeyType.items());
            rootIndex = nodes.size();
            Object root = addNode(key, pattern(List.of(new GenericStack(key, 1))));
            if (byproductFirst) connect(root, 2, gravel, 2);
            connect(root, 0, sand, 1);
            if (!byproductFirst) connect(root, 2, gravel, 2);
        }

        void addLateMissingInput() throws Exception {
            var key = new TestAEKey("antimatter", appeng.api.stacks.AEKeyType.items());
            int index = nodes.size();
            addNode(key, null);
            connect(nodes.get(rootIndex), index, key, 1);
        }

        void connect(Object owner, int childIndex, AEKey key, long multiplier) throws Exception {
            IPatternDetails.IInput input = (IPatternDetails.IInput) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{IPatternDetails.IInput.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getMultiplier" -> multiplier;
                        case "getRemainingKey" -> null;
                        case "isValid" -> key.equals(args[0]);
                        case "getPossibleInputs" -> new GenericStack[]{new GenericStack(key, 1)};
                        default -> throw new AssertionError(method.getName());
                    });
            AelisCraftingTreeNodeBridge bridge = (AelisCraftingTreeNodeBridge) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{AelisCraftingTreeNodeBridge.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "molecularmanipulator$getWhat" -> key;
                        case "molecularmanipulator$getAmount" -> 1L;
                        case "molecularmanipulator$getValidItemTemplates" -> List.of(new InputTemplate(key, 1));
                        case "molecularmanipulator$getLevel" -> null;
                        case "molecularmanipulator$request" -> throw new AssertionError("Multi-output batch delegated to native AE2");
                        default -> throw new AssertionError(method.getName());
                    });
            Object inputModel = construct("GraphConsumableInput", input, bridge, childIndex, multiplier, false, false);
            list(owner, "orderedInputs").add(construct("OrderedGraphInput", inputModel, null));
            list(owner, "edges").add(construct("Edge", childIndex, multiplier, 1));
        }

        IPatternDetails pattern(List<GenericStack> outputs) {
            return (IPatternDetails) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IPatternDetails.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getOutputs" -> outputs.toArray(GenericStack[]::new);
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> outputs.toString();
                        default -> throw new AssertionError(method.getName());
                    });
        }

        void execute(long order, boolean simulation, boolean outerTransaction) throws Exception {
            int[] topological = java.util.stream.IntStream.range(0, nodes.size()).toArray();
            Object graph = construct("Graph", nodes, topological, rootIndex, (long) nodes.size(), 0L, 0, 0, false, false, false, null);
            Method method = Arrays.stream(AelisPlanner.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(outerTransaction ? "execute" : "executeTransactionalNode") && m.getParameterCount() == (outerTransaction ? 7 : 9))
                    .findFirst().orElseThrow();
            method.setAccessible(true);
            var sink = new AelisPlanner.ProgressSink() { @Override public void executionStep() { steps++; } };
            try {
                if (outerTransaction) method.invoke(null, graph, state, order, simulation, missing,
                        (AelisPlanner.PauseCheckpoint) () -> {}, sink);
                else method.invoke(null, graph, rootIndex, state, order, order, simulation, missing,
                        (AelisPlanner.PauseCheckpoint) () -> {}, sink);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw failure;
            }
        }
    }

    private static final class Fixture {
        final AEKey[] keys = new AEKey[6];
        final State state = new State();
        final KeyCounter missing = new KeyCounter();
        final Object graph;
        int catalystRequests;
        int steps;

        Fixture(boolean reusable, long output) throws Exception {
            var nodes = new ArrayList<Object>();
            for (int index = 0; index < keys.length; index++) {
                keys[index] = new TestAEKey("essence_" + index, appeng.api.stacks.AEKeyType.items());
                Object node = construct("Node", index, keys[index], 1L, null);
                field(node, "reachable", true);
                field(node, "terminal", index == 5);
                field(node, "outputPerPattern", output);
                final AEKey key = keys[index];
                IPatternDetails pattern = (IPatternDetails) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{IPatternDetails.class}, (proxy, method, args) -> switch (method.getName()) {
                            case "getOutputs" -> new GenericStack[] {new GenericStack(key, output)};
                            case "getDefinition" -> key;
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            case "toString" -> key.toString();
                            default -> throw new AssertionError(method.getName());
                        });
                field(node, "details", pattern);
                nodes.add(node);
            }
            for (int index = 0; index < 5; index++) {
                var ordered = list(nodes.get(index), "orderedInputs");
                if (reusable) {
                    Object mode = Enum.valueOf((Class) nested("BoundaryInputMode"), "INVARIANT_REUSABLE");
                    Object reusableInput = construct("GraphReusableInput", input(), bridge(new TestAEKey("crystal", appeng.api.stacks.AEKeyType.items()), true), mode, 1L);
                    ordered.add(construct("OrderedGraphInput", null, reusableInput));
                }
                Object consumable = construct("GraphConsumableInput", input(), bridge(keys[index + 1], false), index + 1, 4L, false, false);
                ordered.add(construct("OrderedGraphInput", consumable, null));
                list(nodes.get(index), "edges").add(construct("Edge", index + 1, 4L, 1));
            }
            graph = construct("Graph", nodes, new int[]{0, 1, 2, 3, 4, 5}, 0, 6L, 0L, 0, 0, false, false, reusable, null);
        }

        AelisCraftingTreeNodeBridge bridge(AEKey key, boolean reusable) {
            return (AelisCraftingTreeNodeBridge) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{AelisCraftingTreeNodeBridge.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "molecularmanipulator$getWhat" -> key;
                        case "molecularmanipulator$getAmount" -> 1L;
                        case "molecularmanipulator$getValidItemTemplates" -> reusable ? List.of() : List.of(new InputTemplate(key, 1));
                        case "molecularmanipulator$request" -> {
                            assertTrue(reusable);
                            assertEquals(1L, args[1]); // Never request the logical number of catalyst uses.
                            catalystRequests++;
                            ((KeyCounter) args[2]).add(key, 1);
                            yield null;
                        }
                        case "molecularmanipulator$getLevel" -> null;
                        default -> throw new AssertionError(method.getName());
                    });
        }

        void execute(long order, boolean simulation) throws Exception {
            Method method = Arrays.stream(AelisPlanner.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("executeTransactionalNode") && m.getParameterCount() == 9)
                    .findFirst().orElseThrow();
            method.setAccessible(true);
            try {
                method.invoke(null, graph, 0, state, order, order, simulation, missing,
                        (AelisPlanner.PauseCheckpoint) () -> {}, new AelisPlanner.ProgressSink() {
                            @Override public void executionStep() { steps++; }
                        });
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw failure;
            }
        }

        void executeTopological(long order, boolean simulation) throws Exception {
            Method method = Arrays.stream(AelisPlanner.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("executeBigIntegerTopological"))
                    .findFirst().orElseThrow();
            method.setAccessible(true);
            try {
                method.invoke(null, graph, state, order, simulation, missing,
                        (AelisPlanner.PauseCheckpoint) () -> {}, AelisPlanner.ProgressSink.NONE);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                throw failure;
            }
        }
    }

    private static IPatternDetails.IInput input() {
        return (IPatternDetails.IInput) Proxy.newProxyInstance(AelisPlanner.class.getClassLoader(),
                new Class<?>[]{IPatternDetails.IInput.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMultiplier" -> 4L;
                    case "getRemainingKey" -> null;
                    case "isValid" -> true;
                    default -> throw new AssertionError(method.getName());
                });
    }

    private static Class<?> nested(String name) throws ClassNotFoundException {
        return Class.forName(AelisPlanner.class.getName() + "$" + name);
    }
    private static Object construct(String name, Object... args) throws Exception {
        var constructor = Arrays.stream(nested(name).getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == args.length).findFirst().orElseThrow();
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }
    private static void field(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    @SuppressWarnings("unchecked") private static List<Object> list(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return (List<Object>) field.get(owner);
    }
    private static void loadConfig(boolean enabled) throws Exception {
        var data = CommentedConfig.inMemory();
        Config.SPEC.correct(data);
        data.set("crafting.aelis.enable_big_integer_planning", enabled);
        Config.SPEC.setConfig(data);
    }

    private static final class State extends CraftingSimulationState implements CraftingSimulationStateLongSafetyAccessor,
            AelisBigIntegerCraftingTracker, AelisCalculationPathCarrier {
        Map<AEKey, BigInteger> exact = new HashMap<>();
        Map<AEKey, BigInteger> missing = new HashMap<>();
        boolean previewOnly;
        AelisCalculationPath path;
        @Override protected long simulateExtractParent(AEKey key, long amount) { return 0; }
        @Override protected Iterable<AEKey> findFuzzyParent(AEKey key) { return List.of(); }
        @Override public void addStackBytes(AEKey key, long amount, long times) { assertTrue(amount >= 0 && times >= 0); }
        public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerCraftAmounts() { return Map.copyOf(exact); }
        public void appliedenhancements$setBigIntegerCraftAmounts(Map<AEKey, BigInteger> values) { exact = new HashMap<>(values); }
        public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerMissingAmounts() { return Map.copyOf(missing); }
        public void appliedenhancements$setBigIntegerMissingAmounts(Map<AEKey, BigInteger> values) { missing = new HashMap<>(values); }
        public boolean appliedenhancements$isPreviewOnly() { return previewOnly; }
        public void appliedenhancements$setPreviewOnly(boolean value) { previewOnly = value; }
        public void appliedenhancements$recordBigIntegerCrafting(IPatternDetails pattern, long times) { appliedenhancements$recordBigIntegerCrafting(pattern, BigInteger.valueOf(times)); }
        public void appliedenhancements$recordBigIntegerCrafting(IPatternDetails pattern, BigInteger times) {
            for (var output : pattern.getOutputs()) exact.merge(output.what(), BigInteger.valueOf(output.amount()).multiply(times), BigInteger::add);
        }
        public void appliedenhancements$mergeBigIntegerCraftAmounts(Map<AEKey, BigInteger> values) { values.forEach((key, value) -> exact.merge(key, value, BigInteger::add)); }
        public void appliedenhancements$beginProjectedCraftingTransfer() {}
        public void appliedenhancements$endProjectedCraftingTransfer() {}
        public AelisCalculationPath molecularmanipulator$getCalculationPath() { return path; }
        public void molecularmanipulator$setCalculationPath(AelisCalculationPath value) { path = value; }
        public KeyCounter appliedenhancements$getUnmodifiedCache() { return field("unmodifiedCache"); }
        public KeyCounter appliedenhancements$getModifiableCache() { return field("modifiableCache"); }
        public KeyCounter appliedenhancements$getRequiredExtract() { return field("requiredExtract"); }
        public Map<IPatternDetails, Long> appliedenhancements$getCrafts() { return field("crafts"); }
        @SuppressWarnings("unchecked") private <T> T field(String name) {
            try { var field = CraftingSimulationState.class.getDeclaredField(name); field.setAccessible(true); return (T) field.get(this); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
    }
}
