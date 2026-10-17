package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.CraftingSimulationState;
import appeng.crafting.inv.ICraftingInventory;
import com.appliedenhancements.test.TestAEKey;
import com.github.appliedenhancements.integration.ae2.AelisCraftingTreeNodeBridge;
import com.github.appliedenhancements.integration.ae2.AelisCraftingTreeProcessBridge;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Exercises the actual missing-simulation certificate with stand-ins for Mixin bridges. */
class AelisUnknownPatternSimulationTest {
    @Test void stableThirdPartyFirstCandidateIsCertifiedForMissingSimulation() throws Exception {
        var fixture = new Fixture(true, true);
        Object proof = fixture.certify();
        assertTrue((boolean) value(proof, "safe"), () -> String.valueOf(uncheckedValue(proof, "reason")));
        assertEquals(List.of(), value(proof, "recoverableProcesses"));
    }

    @Test void stableCandidateCanRecoverThePossibleBitLeftByARealShortage() throws Exception {
        var fixture = new Fixture(true, false);
        Object proof = fixture.certify();
        assertTrue((boolean) value(proof, "safe"));
        assertEquals(List.of(fixture.process), value(proof, "recoverableProcesses"));
        assertFalse(fixture.process.possible); // The proof proposes recovery; it does not mutate the live tree.
    }

    @Test void unknownPatternWithoutACompiledSnapshotRemainsUnsupported() throws Exception {
        var fixture = new Fixture(false, true);
        Object proof = fixture.certify();
        assertFalse((boolean) value(proof, "safe"));
        assertEquals("unknown_pattern", value(proof, "reason"));
    }

    @Test void changedObservedOutputsCannotPassTheSimulationCertificate() throws Exception {
        var fixture = new Fixture(true, true);
        fixture.pattern.outputAmount = 2;
        Object proof = fixture.certify();
        assertFalse((boolean) value(proof, "safe"));
    }

    @Test void changedObservedInputsCannotPassTheSimulationCertificate() throws Exception {
        var fixture = new Fixture(true, true);
        fixture.pattern.inputAmount = 2;
        Object proof = fixture.certify();
        assertFalse((boolean) value(proof, "safe"));
    }

    private static final class Fixture {
        final ThirdPartyPattern pattern = new ThirdPartyPattern();
        final FakeProcess process;
        final Object graph;

        Fixture(boolean snapshot, boolean possible) throws Exception {
            FakeNode root = allocate(FakeNode.class);
            root.key = pattern.output;
            FakeNode raw = allocate(FakeNode.class);
            raw.key = pattern.raw;
            raw.processes = new ArrayList<>();
            process = allocate(FakeProcess.class);
            process.pattern = pattern;
            process.possible = possible;
            root.processes = new ArrayList<>(List.of(process, process));

            Object rootModel = construct("Node", 0, pattern.output, 1L, null);
            Object rawModel = construct("Node", 1, pattern.raw, 1L, null);
            for (Object node : List.of(rootModel, rawModel)) field(node, "reachable", true);
            field(rawModel, "terminal", true);
            list(rootModel, "occurrences").add(root);
            list(rawModel, "occurrences").add(raw);
            Object consumable = construct("GraphConsumableInput", pattern.input(), raw, 1, 4L, false, false);
            Object ordered = construct("OrderedGraphInput", consumable, null);
            Object candidate = construct("CompiledCandidate", 0, process, pattern,
                    List.of(ordered), List.of(), 1L, false, false, false,
                    snapshot ? AelisObservedPatternSemantics.captureStable(pattern) : null,
                    null, List.of());
            list(rootModel, "compiledCandidates").add(candidate);
            field(rootModel, "candidatePatterns", List.of(pattern, pattern));
            field(rootModel, "executionMode", Enum.valueOf((Class) nested("ExecutionMode"), "HYBRID_BARRIER"));
            field(rootModel, "barrierReason", "ordered_pattern_choices");
            graph = construct("Graph", List.of(rootModel, rawModel), new int[]{0, 1},
                    0, 2L, 0L, 0, 1, true, false, false, null);
        }

        Object certify() throws Exception {
            Method method = Arrays.stream(AelisPlanner.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("certifySimulationFirstCandidate")).findFirst().orElseThrow();
            method.setAccessible(true);
            return method.invoke(null, graph, 0, null, null, (AelisPlanner.PauseCheckpoint) () -> {});
        }
    }

    private static final class ThirdPartyPattern implements IPatternDetails {
        final AEKey output = new TestAEKey("emerald", AEKeyType.items());
        final AEKey raw = new TestAEKey("raw", AEKeyType.items());
        long outputAmount = 1;
        long inputAmount = 1;
        @Override public AEItemKey getDefinition() { return null; }
        @Override public GenericStack[] getOutputs() { return new GenericStack[] {new GenericStack(output, outputAmount)}; }
        @Override public IInput[] getInputs() { return new IInput[]{input()}; }
        @Override public boolean supportsPushInputsToExternalInventory() { return true; }
        IInput input() {
            return new IInput() {
                @Override public GenericStack[] getPossibleInputs() { return new GenericStack[]{new GenericStack(raw, inputAmount)}; }
                @Override public long getMultiplier() { return 4; }
                @Override public boolean isValid(AEKey key, Level level) { return raw.equals(key); }
                @Override public AEKey getRemainingKey(AEKey key) { return null; }
            };
        }
    }

    // Constructors of AE2 trees require a running game. Allocate only these test
    // subclasses without constructors; every bridge below uses test-owned fields.
    private static final class FakeNode extends CraftingTreeNode implements AelisCraftingTreeNodeBridge {
        AEKey key;
        ArrayList<CraftingTreeProcess> processes;
        private FakeNode() { super(null, null, null, 1, null, 0); }
        public AEKey molecularmanipulator$getWhat() { return key; }
        public long molecularmanipulator$getAmount() { return 1; }
        public IPatternDetails.IInput molecularmanipulator$getParentInput() { return null; }
        public Level molecularmanipulator$getLevel() { return null; }
        public boolean molecularmanipulator$canEmit() { return false; }
        public ArrayList<CraftingTreeProcess> molecularmanipulator$getProcesses() { return processes; }
        public void molecularmanipulator$buildChildPatterns() {}
        public Iterable<InputTemplate> molecularmanipulator$getValidItemTemplates(ICraftingInventory inventory) { return List.of(); }
        public void molecularmanipulator$request(CraftingSimulationState inventory, long amount, KeyCounter returned) { fail("Certificate invoked native execution"); }
    }

    private static final class FakeProcess extends CraftingTreeProcess implements AelisCraftingTreeProcessBridge {
        IPatternDetails pattern;
        boolean possible;
        private FakeProcess() { super(null, null, null, null); }
        public IPatternDetails molecularmanipulator$getDetails() { return pattern; }
        public Map<CraftingTreeNode, Long> molecularmanipulator$getChildNodes() { return Map.of(); }
        public boolean molecularmanipulator$hasContainerItems() { return false; }
        public boolean molecularmanipulator$limitsQuantity() { return false; }
        public boolean molecularmanipulator$isPossible() { return possible; }
        public void molecularmanipulator$setPossible(boolean value) { possible = value; }
        public boolean molecularmanipulator$hasMultiplePaths() { return false; }
        public void molecularmanipulator$request(CraftingSimulationState inventory, long amount) { fail("Certificate invoked native execution"); }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }
    private static Class<?> nested(String name) throws Exception { return Class.forName(AelisPlanner.class.getName() + "$" + name); }
    private static Object construct(String name, Object... args) throws Exception {
        Constructor<?> constructor = Arrays.stream(nested(name).getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == args.length).findFirst().orElseThrow();
        constructor.setAccessible(true); return constructor.newInstance(args);
    }
    private static void field(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    @SuppressWarnings("unchecked") private static List<Object> list(Object object, String name) throws Exception { return (List<Object>) value(object, name); }
    private static Object value(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static Object uncheckedValue(Object object, String name) {
        try { return value(object, name); } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
