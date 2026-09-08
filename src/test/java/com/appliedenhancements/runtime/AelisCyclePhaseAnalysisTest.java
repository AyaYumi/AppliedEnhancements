package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import com.appliedenhancements.api.AelisCycleSeedPolicy;
import com.appliedenhancements.test.TestAEKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class AelisCyclePhaseAnalysisTest {
    private final AEKey cycleDefinition = new TestAEKey("cycle_pattern");
    private final AEKey fuelDefinition = new TestAEKey("scaled_fuel_pattern");
    private final AEKey fuelBaseDefinition = new TestAEKey("unscaled_fuel_pattern");
    private final AEKey rawDefinition = new TestAEKey("raw_pattern");
    private final AEKey consumerDefinition = new TestAEKey("consumer_pattern");
    private final AEKey seed = new TestAEKey("seed");
    private final AEKey fuel = new TestAEKey("fuel");
    private final AEKey alternativeFuel = new TestAEKey("alternative_fuel");
    private final AEKey raw = new TestAEKey("raw");
    private final AEKey finished = new TestAEKey("finished");

    private AelisCycleExecutionPlan cycle() {
        return new AelisCycleExecutionPlan(List.of(new AelisCycleExecutionPlan.Step(
                cycleDefinition, 8, Map.of(seed, 1L), Set.of(seed))),
                Map.of(seed, 1L), Set.of(seed), AelisCycleSeedPolicy.PRESERVE_MINIMUM);
    }

    private AelisCyclePhaseAnalysis.Pattern describe(
            AEKey definition, List<GenericStack> outputs, IPatternDetails.IInput... inputs) {
        return AelisCyclePhaseAnalysis.describe(definition, new IPatternDetails() {
            @Override public AEItemKey getDefinition() { return null; }
            @Override public IInput[] getInputs() { return inputs; }
            @Override public GenericStack[] getOutputs() { return outputs.toArray(GenericStack[]::new); }
        });
    }

    @Test
    void followsAllCycleInputsAndTransitiveOrdinaryPrerequisitesWithoutReleasingConsumers() {
        var cycle = cycle();
        var cyclic = describe(cycleDefinition, List.of(new GenericStack(seed, 8)),
                input(seed, 1, null), input(fuel, 2, null));
        var fuelPattern = describe(fuelDefinition, List.of(new GenericStack(fuel, 32)), input(raw, 4, null));
        var rawPattern = describe(rawDefinition, List.of(new GenericStack(raw, 1)));
        var consumer = describe(consumerDefinition, List.of(new GenericStack(finished, 1)), input(seed, 1, null));

        var prepared = AelisCyclePhaseAnalysis.prepareGraph(cycle,
                List.of(cyclic, fuelPattern, rawPattern, consumer), Map.of(cycleDefinition, cyclic));

        assertEquals(Set.of(fuelDefinition, rawDefinition), prepared.phase().prerequisitePatterns());
        assertFalse(prepared.phase().prerequisitePatterns().contains(fuelBaseDefinition));
        assertEquals(Map.of(cycleDefinition, Map.of(seed, 8L)), prepared.phase().outputsPerPattern());
        assertEquals(cycle.steps(), prepared.steps());
        assertEquals(cycle.minimumSeeds(), prepared.minimumSeeds());
        assertEquals(cycle.protectedKeys(), prepared.protectedKeys());
        assertEquals(cycle.seedPolicy(), prepared.seedPolicy());
    }

    @Test
    void alternativeInputsAndBothSidesOfARegularBridgeRemainAvailable() {
        var otherCycleDefinition = new TestAEKey("other_cycle_pattern");
        var bridgeDefinition = new TestAEKey("bridge_pattern");
        var alternateDefinition = new TestAEKey("alternate_fuel_pattern");
        var plan = new AelisCycleExecutionPlan(List.of(cycle().steps().get(0),
                new AelisCycleExecutionPlan.Step(otherCycleDefinition, 1, Map.of(seed, 1L), Set.of())),
                Map.of(seed, 1L), Set.of(seed));
        var first = describe(cycleDefinition, List.of(new GenericStack(seed, 8)), input(seed, 1, null));
        var second = describe(otherCycleDefinition, List.of(new GenericStack(finished, 8)),
                input(raw, 1, null), input(List.of(new GenericStack(fuel, 1),
                        new GenericStack(alternativeFuel, 1)), 1, null));
        var bridge = describe(bridgeDefinition, List.of(new GenericStack(raw, 1)), input(seed, 1, null));
        var primary = describe(fuelDefinition, List.of(new GenericStack(fuel, 1)));
        var alternate = describe(alternateDefinition, List.of(new GenericStack(alternativeFuel, 1)));

        var prepared = AelisCyclePhaseAnalysis.prepareGraph(plan,
                List.of(first, second, bridge, primary, alternate),
                Map.of(cycleDefinition, first, otherCycleDefinition, second));

        assertEquals(Set.of(bridgeDefinition, fuelDefinition, alternateDefinition),
                prepared.phase().prerequisitePatterns());
    }

    @Test
    void returnedCatalystDoesNotReleaseAnUnrelatedConsumerButContainersCanBePrerequisites() {
        var catalystConsumer = describe(consumerDefinition, List.of(new GenericStack(finished, 1)),
                input(fuel, 1, fuel));
        var containerProducer = describe(fuelDefinition, List.of(new GenericStack(raw, 1)),
                input(alternativeFuel, 1, fuel));
        var cyclic = describe(cycleDefinition, List.of(new GenericStack(seed, 8)),
                input(seed, 1, null), input(fuel, 1, null));

        var prepared = AelisCyclePhaseAnalysis.prepareGraph(cycle(),
                List.of(cyclic, catalystConsumer, containerProducer), Map.of(cycleDefinition, cyclic));

        assertEquals(Set.of(fuelDefinition), prepared.phase().prerequisitePatterns());
        assertFalse(catalystConsumer.producedKeys().contains(fuel));
        assertTrue(containerProducer.producedKeys().contains(fuel));
    }

    @Test
    void explicitCatalystOutputRequiresPositiveNetProduction() {
        var unchanged = describe(consumerDefinition, List.of(new GenericStack(fuel, 1)), input(fuel, 1, null));
        var grown = describe(fuelDefinition, List.of(new GenericStack(fuel, 2)), input(fuel, 1, null));
        var returnedAndGrown = describe(rawDefinition, List.of(new GenericStack(fuel, 1)), input(fuel, 1, fuel));

        assertFalse(unchanged.producedKeys().contains(fuel));
        assertTrue(grown.producedKeys().contains(fuel));
        assertTrue(returnedAndGrown.producedKeys().contains(fuel));
    }

    @Test
    void sumsActualOutputsAndKeepsContainerReturnsOutOfTheExplicitOutputLedger() {
        var pattern = describe(cycleDefinition,
                List.of(new GenericStack(seed, 3), new GenericStack(seed, 5), new GenericStack(raw, 0)),
                input(fuel, 1, alternativeFuel));

        assertEquals(Map.of(seed, 8L), pattern.outputs());
        assertTrue(pattern.producedKeys().contains(alternativeFuel));
        assertFalse(pattern.outputs().containsKey(alternativeFuel));
    }

    @Test
    void substitutionCanProduceAKeyThatAnotherInputChoiceWouldOnlyReturn() {
        var substituting = describe(fuelDefinition, List.of(new GenericStack(fuel, 2)),
                input(List.of(new GenericStack(fuel, 1), new GenericStack(raw, 1)), 2, null));
        var catalyst = describe(consumerDefinition, List.of(new GenericStack(finished, 1)),
                input(List.of(new GenericStack(fuel, 1)), 8, fuel));

        assertTrue(substituting.producedKeys().contains(fuel));
        assertEquals(Set.of(fuel, raw), substituting.inputs());
        assertFalse(catalyst.producedKeys().contains(fuel));
    }

    @Test
    void missingCompletedPatternWithoutMetadataKeepsLegacyBehavior() {
        var cycle = cycle();
        assertSame(cycle, AelisCyclePhaseAnalysis.prepareGraph(cycle, List.of(), Map.of()));
        assertSame(cycle, AelisCyclePhaseAnalysis.prepare(cycle, Map.of()));
    }

    @Test
    void incompleteRestorationRetainsPreviousOutputsAndExpandsKnownPrerequisites() {
        var prior = cycle();
        var phase = new AelisCycleExecutionPlan.Phase(Set.of(fuelDefinition),
                Map.of(cycleDefinition, Map.of(seed, 8L)));
        var cycle = new AelisCycleExecutionPlan(prior.steps(), prior.minimumSeeds(),
                prior.protectedKeys(), prior.seedPolicy(), phase);
        var fuelPattern = describe(fuelDefinition, List.of(new GenericStack(fuel, 1)), input(raw, 1, null));
        var rawPattern = describe(rawDefinition, List.of(new GenericStack(raw, 1)));

        var prepared = AelisCyclePhaseAnalysis.prepareGraph(cycle,
                List.of(fuelPattern, rawPattern), Map.of());

        assertEquals(Set.of(fuelDefinition, rawDefinition), prepared.phase().prerequisitePatterns());
        assertEquals(phase.outputsPerPattern(), prepared.phase().outputsPerPattern());
        assertSame(prepared, AelisCyclePhaseAnalysis.prepareGraph(prepared,
                List.of(fuelPattern, rawPattern), Map.of()));
    }

    @Test
    void cyclicOrdinaryPrerequisitesTerminateTraversal() {
        var cyclic = describe(cycleDefinition, List.of(new GenericStack(seed, 8)), input(fuel, 1, null));
        var fuelPattern = describe(fuelDefinition, List.of(new GenericStack(fuel, 1)), input(raw, 1, null));
        var rawPattern = describe(rawDefinition, List.of(new GenericStack(raw, 1)), input(fuel, 1, null));

        var prepared = AelisCyclePhaseAnalysis.prepareGraph(cycle(),
                List.of(cyclic, fuelPattern, rawPattern), Map.of(cycleDefinition, cyclic));

        assertEquals(Set.of(fuelDefinition, rawDefinition), prepared.phase().prerequisitePatterns());
    }

    private static IPatternDetails.IInput input(AEKey key, long amount, AEKey remainder) {
        return input(List.of(new GenericStack(key, amount)), 1, remainder);
    }

    private static IPatternDetails.IInput input(List<GenericStack> alternatives, long multiplier, AEKey remainder) {
        return new IPatternDetails.IInput() {
            @Override public GenericStack[] getPossibleInputs() { return alternatives.toArray(GenericStack[]::new); }
            @Override public long getMultiplier() { return multiplier; }
            @Override public boolean isValid(AEKey key, Level level) {
                return alternatives.stream().anyMatch(stack -> stack.what().equals(key));
            }
            @Override public AEKey getRemainingKey(AEKey template) { return remainder; }
        };
    }
}
