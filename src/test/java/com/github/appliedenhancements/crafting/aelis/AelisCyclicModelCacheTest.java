package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.execution.InputTemplate;
import com.appliedenhancements.test.TestAEKey;
import com.github.appliedenhancements.integration.ae2.AelisCraftingTreeNodeBridge;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AelisCyclicModelCacheTest {
    @Test void reusesSessionModelButRevalidatesLivePatternsAndTemplates() throws Exception {
        var fixture = new Fixture(true);
        Object first = fixture.model();
        assertNotNull(first);
        assertSame(first, fixture.model());
        assertNotSame(first, new Fixture(true).model());
        fixture.output.set(3);
        assertNull(fixture.model());
        fixture.output.set(2);
        assertSame(first, fixture.model());
        fixture.templateAmount.set(2);
        assertNull(fixture.model());
        fixture.templateAmount.set(1);
        assertSame(first, fixture.model());
    }

    @Test void provenAcyclicGraphSkipsRuntimeCycleTemplateEnumeration() throws Exception {
        var fixture = new Fixture(false);
        assertNull(fixture.model());
        assertNull(fixture.model());
        assertEquals(0, fixture.templateReads);
    }

    private static final class Fixture {
        final AtomicLong output = new AtomicLong(2);
        final AtomicLong templateAmount = new AtomicLong(1);
        final Object graph;
        int templateReads;

        Fixture(boolean cyclic) throws Exception {
            AEKey[] keys = {new TestAEKey("a"), new TestAEKey("b")};
            var nodes = new ArrayList<Object>();
            for (int index = 0; index < 2; index++) {
                Object node = construct("Node", index, keys[index], 1L, null);
                set(node, "reachable", true);
                nodes.add(node);
            }
            for (int index = 0; index < (cyclic ? 2 : 1); index++) {
                int childIndex = 1 - index;
                AEKey childKey = keys[childIndex];
                AEKey key = keys[index];
                var input = (IPatternDetails.IInput) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] {IPatternDetails.IInput.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getMultiplier" -> 1L;
                            case "getPossibleInputs" -> new GenericStack[] {new GenericStack(childKey, 1)};
                            case "getRemainingKey" -> null;
                            default -> throw new AssertionError(method.getName());
                        });
                var pattern = (IPatternDetails) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] {IPatternDetails.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getDefinition" -> null;
                            case "getOutputs" -> List.of(new GenericStack(key, output.get()));
                            case "getInputs" -> new IPatternDetails.IInput[] {input};
                            case "supportsPushInputsToExternalInventory" -> true;
                            default -> throw new AssertionError(method.getName());
                        });
                var bridge = (AelisCraftingTreeNodeBridge) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] {AelisCraftingTreeNodeBridge.class},
                        (proxy, method, args) -> {
                            if (!method.getName().equals("molecularmanipulator$getValidItemTemplates")) {
                                throw new AssertionError(method.getName());
                            }
                            templateReads++;
                            return List.of(new InputTemplate(childKey, templateAmount.get()));
                        });
                Object consumable = construct("GraphConsumableInput", input, bridge, childIndex, 1L, false, false);
                Object ordered = construct("OrderedGraphInput", consumable, null);
                Object candidate = construct("CompiledCandidate", 0, null, pattern, List.of(ordered),
                        List.of(), 2L, false, false, false,
                        AelisObservedPatternSemantics.captureStable(pattern), null, List.of());
                set(nodes.get(index), "candidatePatterns", List.of(pattern));
                set(nodes.get(index), "allCandidatesCompiled", true);
                list(nodes.get(index), "compiledCandidates").add(candidate);
            }
            graph = construct("Graph", nodes, new int[] {0, 1}, 0, 2L, 0L, 0, 0,
                    false, false, false, null);
        }

        Object model() throws Exception {
            Method method = Arrays.stream(AelisPlanner.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("buildGlobalCyclicVariants")).findFirst().orElseThrow();
            method.setAccessible(true);
            return method.invoke(null, graph, null, (AelisPlanner.PauseCheckpoint) () -> {});
        }
    }

    private static Object construct(String name, Object... args) throws Exception {
        var type = Class.forName(AelisPlanner.class.getName() + "$" + name);
        var constructor = Arrays.stream(type.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == args.length).findFirst().orElseThrow();
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (List<Object>) field.get(object);
    }
}
