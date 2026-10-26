package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.inv.ICraftingInventory;
import net.minecraft.world.level.Level;
import sun.misc.Unsafe;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.Config;
import com.appliedenhancements.mixin.CraftingSimulationStateLongSafetyAccessor;
import com.appliedenhancements.runtime.CraftingByteEstimate;
import com.appliedenhancements.test.TestAEKey;
import com.github.appliedenhancements.integration.ae2.*;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.concurrent.SynchronizedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Plans real dragon-breath/glass-bottle fixed remainders through the reflection fixtures. */
class AelisFixedRemainderPlanningTest {
    private static final long SHELL_BATCH = 20_480L;
    private static final long BOTTLE_RETURNS = 81_920L;

    private static AEItemKey dragonBreath;
    private static AEItemKey glassBottle;
    private static AEItemKey bowl;
    private static Level testLevel;

    @BeforeAll
    static void bootstrap() throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        dragonBreath = AEItemKey.of(Items.DRAGON_BREATH);
        glassBottle = AEItemKey.of(Items.GLASS_BOTTLE);
        bowl = AEItemKey.of(Items.BOWL);
        testLevel = allocate(net.minecraft.server.level.ServerLevel.class);
    }

    @AfterEach
    void unloadConfig() {
        Config.SPEC.acceptConfig(null);
    }

    @Test
    void exactFixedRemainderReturnsOneBottlePerConsumedBreath() throws Exception {
        loadConfig(true);
        var fixture = new Fixture(4L, glassBottle, glassBottle);

        fixture.executeDirect(SHELL_BATCH, true);

        assertEquals(BigInteger.valueOf(BOTTLE_RETURNS),
                fixture.state.missing.get(dragonBreath));
        assertEquals(BOTTLE_RETURNS, fixture.missing.get(dragonBreath));
        assertEquals(BigInteger.valueOf(SHELL_BATCH), fixture.state.exact.get(fixture.shell));
        assertEquals(BOTTLE_RETURNS,
                fixture.state.extract(glassBottle, Long.MAX_VALUE, Actionable.SIMULATE));
        // Shell request + breath request + returned bottles + shell pattern bytes.
        assertEquals(BigInteger.valueOf(204_800L), fixture.state.bytes.ceil());
    }

    @Test
    void simulationWithPartialBreathStockStillReturnsBottlesForTheWholeRequest()
            throws Exception {
        loadConfig(true);
        var fixture = new Fixture(4L, glassBottle, glassBottle);
        fixture.state.insert(dragonBreath, 5_120L, Actionable.MODULATE);

        fixture.executeDirect(SHELL_BATCH, true);

        assertEquals(BigInteger.valueOf(76_800L), fixture.state.missing.get(dragonBreath));
        assertEquals(76_800L, fixture.missing.get(dragonBreath));
        assertEquals(0L, fixture.state.extract(dragonBreath, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(BOTTLE_RETURNS,
                fixture.state.extract(glassBottle, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(BigInteger.valueOf(SHELL_BATCH), fixture.state.exact.get(fixture.shell));
    }

    @Test
    void fullyStockedShellRequestConsumesStockWithoutReturningBottles() throws Exception {
        loadConfig(true);
        var fixture = new Fixture(4L, glassBottle, glassBottle);
        fixture.state.insert(fixture.shell, SHELL_BATCH, Actionable.MODULATE);

        fixture.executeDirect(SHELL_BATCH, true);

        assertEquals(0L, fixture.state.extract(fixture.shell, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0L, fixture.state.extract(glassBottle, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0L, fixture.state.extract(dragonBreath, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(fixture.state.missing.isEmpty());
        assertTrue(fixture.state.exact.isEmpty());
        assertTrue(fixture.missing.isEmpty());
    }

    @Test
    void shellReturnBecomesVisibleOnlyToAnOrderedLaterSibling() throws Exception {
        loadConfig(false);

        var shellFirst = new Fixture(4L, glassBottle, glassBottle);
        shellFirst.addSiblingConsumer(true);
        shellFirst.state.insert(dragonBreath, 4L, Actionable.MODULATE);
        shellFirst.executeOuter(1L, false, () -> {});

        assertEquals(0L, shellFirst.state.extract(glassBottle, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0L, shellFirst.state.extract(dragonBreath, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(1L, shellFirst.state.appliedenhancements$getCrafts().get(shellFirst.shellPattern));
        assertTrue(shellFirst.missing.isEmpty());

        var bottleFirst = new Fixture(4L, glassBottle, glassBottle);
        bottleFirst.addSiblingConsumer(false);
        bottleFirst.state.insert(dragonBreath, 4L, Actionable.MODULATE);

        Exception failure = assertThrows(Exception.class,
                () -> bottleFirst.executeOuter(1L, false, () -> {}));
        assertEquals("CraftBranchFailure", failure.getClass().getSimpleName());
        assertEquals(4L, bottleFirst.state.extract(dragonBreath, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0L, bottleFirst.state.extract(glassBottle, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(bottleFirst.state.appliedenhancements$getCrafts().isEmpty());
        assertTrue(bottleFirst.missing.isEmpty());
    }

    @Test
    void lateMissingInputRollsBackConsumedBreathAndReturnedBottles() throws Exception {
        loadConfig(false);
        var fixture = new Fixture(4L, glassBottle, glassBottle);
        fixture.addLateConsumer();
        fixture.state.insert(dragonBreath, 4L, Actionable.MODULATE);

        Exception failure = assertThrows(Exception.class,
                () -> fixture.executeOuter(1L, false, () -> {}));

        assertEquals("CraftBranchFailure", failure.getClass().getSimpleName());
        assertEquals(4L, fixture.state.extract(dragonBreath, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0L, fixture.state.extract(glassBottle, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0L, fixture.state.extract(fixture.shell, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(fixture.state.appliedenhancements$getCrafts().isEmpty());
        assertTrue(fixture.state.missing.isEmpty());
        assertTrue(fixture.missing.isEmpty());
    }

    @Test
    void cancellationAtTheLateInputRollsBackTheCompletedShellSibling() throws Exception {
        loadConfig(false);

        var baseline = new Fixture(4L, glassBottle, glassBottle);
        baseline.addLateConsumer();
        baseline.state.insert(dragonBreath, 4L, Actionable.MODULATE);
        int[] checkpoints = {0};
        assertThrows(CraftBranchFailure.class,
                () -> baseline.executeOuter(1L, false, () -> checkpoints[0]++));
        assertTrue(checkpoints[0] > 0);

        var cancelled = new Fixture(4L, glassBottle, glassBottle);
        cancelled.addLateConsumer();
        cancelled.state.insert(dragonBreath, 4L, Actionable.MODULATE);
        int[] seen = {0};
        Exception failure = assertThrows(Exception.class,
                () -> cancelled.executeOuter(1L, false, () -> {
                    if (++seen[0] == checkpoints[0]) {
                        throw new InterruptedException("cancelled");
                    }
                }));

        assertTrue(failure instanceof InterruptedException);
        assertEquals(4L, cancelled.state.extract(dragonBreath, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0L, cancelled.state.extract(glassBottle, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(cancelled.state.appliedenhancements$getCrafts().isEmpty());
        assertTrue(cancelled.state.missing.isEmpty());
    }

    @Test
    void returnedBottleInventoryOverflowIsRejectedByTheOuterTransaction() throws Exception {
        loadConfig(false);
        var fixture = new Fixture(4L, glassBottle, glassBottle);
        fixture.state.insert(dragonBreath, 4L, Actionable.MODULATE);
        fixture.state.insert(glassBottle, Long.MAX_VALUE, Actionable.MODULATE);

        Exception failure = assertThrows(Exception.class,
                () -> fixture.executeOuter(1L, false, () -> {}));

        assertEquals("Fallback", failure.getClass().getSimpleName());
        assertEquals(Long.MAX_VALUE,
                fixture.state.extract(glassBottle, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(4L, fixture.state.extract(dragonBreath, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(fixture.state.appliedenhancements$getCrafts().isEmpty());
    }

    @Test
    void returnedBottleCountBeyondLongIsRejectedByTheOuterTransaction() throws Exception {
        loadConfig(false);
        var fixture = new Fixture(Long.MAX_VALUE, glassBottle, glassBottle);
        fixture.addExtraFixedInput(Long.MAX_VALUE);
        fixture.makeBreathEmitter();

        Exception failure = assertThrows(Exception.class,
                () -> fixture.executeOuter(1L, false, () -> {}));

        assertEquals("Fallback", failure.getClass().getSimpleName());
        assertEquals(0L, fixture.state.extract(glassBottle, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(fixture.state.appliedenhancements$getCrafts().isEmpty());
        assertTrue(fixture.state.missing.isEmpty());
    }

    @Test
    void changedRuntimeRemainderFallsBackBeforeAnyInventoryMutation() throws Exception {
        loadConfig(true);
        var fixture = new Fixture(4L, glassBottle, bowl);

        Exception failure = assertThrows(Exception.class,
                () -> fixture.executeDirect(1L, true));

        assertEquals("Fallback", failure.getClass().getSimpleName());
        assertEquals(0L, fixture.state.extract(glassBottle, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0L, fixture.state.extract(bowl, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(fixture.state.missing.isEmpty());
        assertTrue(fixture.state.exact.isEmpty());
        assertTrue(fixture.missing.isEmpty());
    }

    @Test
    void realStockIsConsumedByOneBoundedBatch() throws Exception {
        loadConfig(true);
        var fixture = new Fixture(4L, glassBottle, glassBottle);
        fixture.state.insert(dragonBreath, BOTTLE_RETURNS, Actionable.MODULATE);
        fixture.executeDirect(SHELL_BATCH, false);
        assertEquals(0L, fixture.state.extract(dragonBreath, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(BOTTLE_RETURNS, fixture.state.extract(glassBottle, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(fixture.missing.isEmpty());
        assertEquals(SHELL_BATCH, fixture.state.appliedenhancements$getCrafts().get(fixture.shellPattern));
        assertTrue(fixture.steps < 10);
    }

    @Test
    void sessionCompilesThirdPartyFixedReturnsWithoutContainerBarrier() throws Exception {
        loadConfig(false);
        var root = compiledTree(false);
        var state = new State();
        state.insert(dragonBreath, BOTTLE_RETURNS, Actionable.MODULATE);
        var result = new AelisPlanner.Session(1000, 2000, () -> {})
                .tryExecute(root, state, SHELL_BATCH, false, new KeyCounter());
        assertTrue(result.applied(), () -> result.toString());
        assertEquals(0, result.barrierCount());
        assertEquals(BOTTLE_RETURNS, state.extract(glassBottle, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0L, state.extract(dragonBreath, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    @Test
    void sessionRejectsReturnConsumedByAnotherInputInTheSameRecipe() throws Exception {
        loadConfig(false);
        var root = compiledTree(true);
        var state = new State();
        state.insert(dragonBreath, BOTTLE_RETURNS, Actionable.MODULATE);
        var result = new AelisPlanner.Session(1000, 2000, () -> {})
                .tryExecute(root, state, SHELL_BATCH, false, new KeyCounter());
        assertFalse(result.applied());
        assertTrue(result.fallbackReason().startsWith("fixed_remainder_"), result.toString());
        assertEquals(BOTTLE_RETURNS, state.extract(dragonBreath, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(state.appliedenhancements$getCrafts().isEmpty());
    }

    private static FakeNode compiledTree(boolean consumesReturn) throws Exception {
        var breathInput = exactInput(dragonBreath, 4L, glassBottle);
        var bottleInput = exactInput(glassBottle, 1L, null);
        var root = fakeNode(AEItemKey.of(Items.SHULKER_SHELL), null);
        var child = fakeNode(dragonBreath, breathInput);
        var children = new LinkedHashMap<CraftingTreeNode, Long>();
        children.put(child, 4L);
        if (consumesReturn) children.put(fakeNode(glassBottle, bottleInput), 1L);
        IPatternDetails pattern = new IPatternDetails() {
            public AEItemKey getDefinition() { return AEItemKey.of(Items.PAPER); }
            public List<GenericStack> getOutputs() { return List.of(new GenericStack(root.key, 1)); }
            public IInput[] getInputs() {
                return consumesReturn ? new IInput[]{breathInput, bottleInput} : new IInput[]{breathInput};
            }
            public boolean supportsPushInputsToExternalInventory() { return true; }
        };
        var process = allocate(FakeProcess.class);
        process.pattern = pattern;
        process.children = children;
        process.possible = true;
        root.processes.add(process);
        return root;
    }

    private static IPatternDetails.IInput exactInput(AEKey key, long multiplier, AEKey remainder) {
        return new IPatternDetails.IInput() {
            public GenericStack[] getPossibleInputs() { return new GenericStack[]{new GenericStack(key, 1)}; }
            public long getMultiplier() { return multiplier; }
            public boolean isValid(AEKey candidate, Level level) { return key.equals(candidate); }
            public AEKey getRemainingKey(AEKey candidate) { return remainder; }
        };
    }

    private static FakeNode fakeNode(AEKey key, IPatternDetails.IInput input) throws Exception {
        var node = allocate(FakeNode.class);
        node.key = key;
        node.input = input;
        node.processes = new ArrayList<>();
        return node;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static final class FakeNode extends CraftingTreeNode implements AelisCraftingTreeNodeBridge {
        AEKey key;
        IPatternDetails.IInput input;
        ArrayList<CraftingTreeProcess> processes;
        private FakeNode() { super(null, null, null, 1, null, 0); }
        public AEKey molecularmanipulator$getWhat() { return key; }
        public long molecularmanipulator$getAmount() { return 1; }
        public IPatternDetails.IInput molecularmanipulator$getParentInput() { return input; }
        public Level molecularmanipulator$getLevel() { return testLevel; }
        public boolean molecularmanipulator$canEmit() { return false; }
        public ArrayList<CraftingTreeProcess> molecularmanipulator$getProcesses() { return processes; }
        public void molecularmanipulator$buildChildPatterns() {}
        public Iterable<InputTemplate> molecularmanipulator$getValidItemTemplates(ICraftingInventory inventory) {
            return List.of(new InputTemplate(key, 1));
        }
        public void molecularmanipulator$request(CraftingSimulationState inventory, long amount, KeyCounter returned) {
            fail("Fixed-return compiler delegated to native AE2");
        }
    }

    private static final class FakeProcess extends CraftingTreeProcess implements AelisCraftingTreeProcessBridge {
        IPatternDetails pattern;
        Map<CraftingTreeNode, Long> children;
        boolean possible;
        private FakeProcess() { super(null, null, null, null); }
        public IPatternDetails molecularmanipulator$getDetails() { return pattern; }
        public Map<CraftingTreeNode, Long> molecularmanipulator$getChildNodes() { return children; }
        public boolean molecularmanipulator$hasContainerItems() { return true; }
        public boolean molecularmanipulator$limitsQuantity() { return true; }
        public boolean molecularmanipulator$isPossible() { return possible; }
        public void molecularmanipulator$setPossible(boolean value) { possible = value; }
        public boolean molecularmanipulator$hasMultiplePaths() { return false; }
        public void molecularmanipulator$request(CraftingSimulationState inventory, long amount) {
            fail("Fixed-return compiler delegated to native process");
        }
    }

    private static final class Fixture {
        final AEKey shell;
        final AEKey late;
        final State state = new State();
        final KeyCounter missing = new KeyCounter();
        final List<Object> nodes = new ArrayList<>();
        final IPatternDetails shellPattern;
        final int shellIndex;
        final int breathIndex;
        final int bottleIndex;
        int lateIndex = -1;
        int rootIndex;
        int steps;

        Fixture(long breathMultiplier, AEKey declaredRemainder, AEKey actualRemainder)
                throws Exception {
            shell = new TestAEKey("shell", AEKeyType.items());
            late = new TestAEKey("late", AEKeyType.items());
            shellPattern = pattern(List.of(new GenericStack(shell, 1L)));
            shellIndex = addNode(shell, shellPattern);
            field(nodes.get(shellIndex), "outputPerPattern", 1L);
            breathIndex = addNode(dragonBreath, null);
            bottleIndex = addNode(glassBottle, null);
            connect(shellIndex, breathIndex, dragonBreath,
                    breathMultiplier, declaredRemainder, actualRemainder);
            rootIndex = shellIndex;
        }

        void addExtraFixedInput(long multiplier) throws Exception {
            connect(shellIndex, breathIndex, dragonBreath,
                    multiplier, glassBottle, glassBottle);
        }

        void makeBreathEmitter() throws Exception {
            field(nodes.get(breathIndex), "emitter", true);
            field(nodes.get(breathIndex), "terminal", false);
        }

        void addSiblingConsumer(boolean shellFirst) throws Exception {
            addConsumer(shellFirst, true, false, 4L);
        }

        void addLateConsumer() throws Exception {
            addConsumer(true, false, true, 1L);
        }

        void addConsumer(boolean shellFirst, boolean includeBottle,
                boolean includeLate, long bottleMultiplier) throws Exception {
            AEKey finished = new TestAEKey("finished", AEKeyType.items());
            rootIndex = nodes.size();
            addNode(finished, pattern(List.of(new GenericStack(finished, 1L))));
            if (includeLate) {
                lateIndex = addNode(late, null);
            }
            if (shellFirst) {
                connect(rootIndex, shellIndex, shell, 1L, null, null);
                if (includeBottle) {
                    connect(rootIndex, bottleIndex, glassBottle, bottleMultiplier, null, null);
                }
                if (includeLate) {
                    connect(rootIndex, lateIndex, late, 1L, null, null);
                }
            } else {
                if (includeBottle) {
                    connect(rootIndex, bottleIndex, glassBottle, bottleMultiplier, null, null);
                }
                connect(rootIndex, shellIndex, shell, 1L, null, null);
            }
        }

        int addNode(AEKey key, IPatternDetails pattern) throws Exception {
            int index = nodes.size();
            Object node = construct("Node", index, key, 1L, null);
            field(node, "reachable", true);
            field(node, "terminal", pattern == null);
            field(node, "details", pattern);
            field(node, "outputPerPattern", 1L);
            nodes.add(node);
            return index;
        }

        void connect(int ownerIndex, int childIndex, AEKey key, long multiplier,
                AEKey declaredRemainder, AEKey actualRemainder) throws Exception {
            Object owner = nodes.get(ownerIndex);
            IPatternDetails.IInput input = (IPatternDetails.IInput) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{IPatternDetails.IInput.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getPossibleInputs" ->
                                new GenericStack[]{new GenericStack(key, 1L)};
                        case "getMultiplier" -> multiplier;
                        case "getRemainingKey" -> actualRemainder;
                        case "isValid" -> key.equals(args[0]);
                        default -> throw new AssertionError(method.getName());
                    });
            AelisCraftingTreeNodeBridge bridge = (AelisCraftingTreeNodeBridge) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{AelisCraftingTreeNodeBridge.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "molecularmanipulator$getWhat" -> key;
                        case "molecularmanipulator$getAmount" -> 1L;
                        case "molecularmanipulator$getValidItemTemplates" ->
                                List.of(new InputTemplate(key, 1L));
                        case "molecularmanipulator$getLevel" -> null;
                        case "molecularmanipulator$request" ->
                                throw new AssertionError("fixed remainder delegated to native AE2");
                        default -> throw new AssertionError(method.getName());
                    });
            Object graphInput = construct("GraphConsumableInput", input, bridge,
                    childIndex, multiplier, false, false, declaredRemainder);
            list(owner, "orderedInputs").add(construct("OrderedGraphInput", graphInput, null));
            list(owner, "edges").add(construct("Edge", childIndex, multiplier, 1));
        }

        IPatternDetails pattern(List<GenericStack> outputs) {
            return (IPatternDetails) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{IPatternDetails.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getOutputs" -> outputs;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> outputs.toString();
                        default -> throw new AssertionError(method.getName());
                    });
        }

        Object graph() throws Exception {
            int[] topological = IntStream.range(0, nodes.size()).toArray();
            return construct("Graph", nodes, topological, rootIndex,
                    (long) nodes.size(), 0L, 0, 0, false, false, false, null);
        }

        void executeDirect(long order, boolean simulation) throws Exception {
            Method method = Arrays.stream(AelisPlanner.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals("executeTransactionalNode")
                            && candidate.getParameterCount() == 9)
                    .findFirst().orElseThrow();
            method.setAccessible(true);
            invoke(method, graph(), rootIndex, state, order, order, simulation, missing,
                    (AelisPlanner.PauseCheckpoint) () -> {}, sink());
        }

        void executeOuter(long order, boolean simulation,
                AelisPlanner.PauseCheckpoint checkpoint) throws Exception {
            Method method = Arrays.stream(AelisPlanner.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals("execute")
                            && candidate.getParameterCount() == 7)
                    .findFirst().orElseThrow();
            method.setAccessible(true);
            invoke(method, graph(), state, order, simulation, missing, checkpoint, sink());
        }

        AelisPlanner.ProgressSink sink() {
            return new AelisPlanner.ProgressSink() {
                @Override
                public void executionStep() {
                    steps++;
                }
            };
        }
    }

    private static void invoke(Method method, Object... arguments) throws Exception {
        try {
            method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) {
                throw cause;
            }
            if (failure.getCause() instanceof Error cause) {
                throw cause;
            }
            throw failure;
        }
    }

    private static Class<?> nested(String name) throws ClassNotFoundException {
        return Class.forName(AelisPlanner.class.getName() + "$" + name);
    }

    private static Object construct(String name, Object... arguments) throws Exception {
        var constructor = Arrays.stream(nested(name).getDeclaredConstructors())
                .filter(candidate -> candidate.getParameterCount() == arguments.length)
                .findFirst().orElseThrow();
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    private static void field(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (List<Object>) field.get(owner);
    }

    private static void loadConfig(boolean enabled) throws Exception {
        var data = new SynchronizedConfig(TomlFormat.instance(), HashMap::new);
        Config.SPEC.correct(data);
        data.set("crafting.aelis.enable_big_integer_planning", enabled);
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                .getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        constructor.setAccessible(true);
        Config.SPEC.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(data, null, null));
    }

    private static final class State extends CraftingSimulationState
            implements CraftingSimulationStateLongSafetyAccessor,
            AelisBigIntegerCraftingTracker, AelisCalculationPathCarrier,
            AelisCraftingBytesTracker {
        Map<AEKey, BigInteger> exact = new HashMap<>();
        Map<AEKey, BigInteger> missing = new HashMap<>();
        boolean previewOnly;
        AelisCalculationPath path;
        CraftingByteEstimate bytes = CraftingByteEstimate.ZERO;

        @Override
        protected long simulateExtractParent(AEKey key, long amount) {
            return 0;
        }

        @Override
        protected Iterable<AEKey> findFuzzyParent(AEKey key) {
            return List.of();
        }

        @Override
        public void addStackBytes(AEKey key, long amount, long times) {
            assertTrue(amount >= 0 && times >= 0);
        }

        @Override
        public CraftingByteEstimate appliedenhancements$getExactByteEstimate() {
            return bytes;
        }

        @Override
        public void appliedenhancements$addExactBytes(CraftingByteEstimate value) {
            bytes = bytes.add(value);
        }

        @Override
        public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerCraftAmounts() {
            return Map.copyOf(exact);
        }

        @Override
        public void appliedenhancements$setBigIntegerCraftAmounts(
                Map<AEKey, BigInteger> values) {
            exact = new HashMap<>(values);
        }

        @Override
        public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerMissingAmounts() {
            return Map.copyOf(missing);
        }

        @Override
        public void appliedenhancements$setBigIntegerMissingAmounts(
                Map<AEKey, BigInteger> values) {
            missing = new HashMap<>(values);
        }

        @Override
        public boolean appliedenhancements$isPreviewOnly() {
            return previewOnly;
        }

        @Override
        public void appliedenhancements$setPreviewOnly(boolean value) {
            previewOnly = value;
        }

        @Override
        public void appliedenhancements$recordBigIntegerCrafting(
                IPatternDetails pattern, long times) {
            appliedenhancements$recordBigIntegerCrafting(pattern, BigInteger.valueOf(times));
        }

        @Override
        public void appliedenhancements$recordBigIntegerCrafting(
                IPatternDetails pattern, BigInteger times) {
            for (var output : pattern.getOutputs()) {
                exact.merge(output.what(),
                        BigInteger.valueOf(output.amount()).multiply(times), BigInteger::add);
            }
        }

        @Override
        public void appliedenhancements$mergeBigIntegerCraftAmounts(
                Map<AEKey, BigInteger> values) {
            values.forEach((key, value) -> exact.merge(key, value, BigInteger::add));
        }

        @Override
        public void appliedenhancements$beginProjectedCraftingTransfer() {
        }

        @Override
        public void appliedenhancements$endProjectedCraftingTransfer() {
        }

        @Override
        public AelisCalculationPath molecularmanipulator$getCalculationPath() {
            return path;
        }

        @Override
        public void molecularmanipulator$setCalculationPath(AelisCalculationPath value) {
            path = value;
        }

        @Override
        public KeyCounter appliedenhancements$getUnmodifiedCache() {
            return field("unmodifiedCache");
        }

        @Override
        public KeyCounter appliedenhancements$getModifiableCache() {
            return field("modifiableCache");
        }

        @Override
        public KeyCounter appliedenhancements$getRequiredExtract() {
            return field("requiredExtract");
        }

        @Override
        public Map<IPatternDetails, Long> appliedenhancements$getCrafts() {
            return field("crafts");
        }

        @SuppressWarnings("unchecked")
        private <T> T field(String name) {
            try {
                var field = CraftingSimulationState.class.getDeclaredField(name);
                field.setAccessible(true);
                return (T) field.get(this);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
