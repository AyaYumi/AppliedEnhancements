package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import com.appliedenhancements.test.TestAEKey;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AelisPatternOutputsTest {
    private final AEKey sand = new TestAEKey("sand", AEKeyType.items());
    private final AEKey gravel = new TestAEKey("gravel", AEKeyType.items());

    @Test void aggregatesRepeatedOutputsAndCanRequestEitherOutput() {
        var outputs = List.of(new GenericStack(gravel, 2), new GenericStack(sand, 3),
                new GenericStack(gravel, 4), new GenericStack(sand, 5));
        var layout = AelisPatternOutputs.inspect(sand, outputs);
        assertEquals(8, layout.requestedOutput());
        assertEquals(List.of(new GenericStack(gravel, 6)), layout.secondaryOutputs());
        assertEquals(new AelisPatternOutputs.Layout(6, List.of(new GenericStack(sand, 8))),
                AelisPatternOutputs.inspect(gravel, outputs));
    }

    @Test void retainsCompatibilityBoundaryWhenNoOutputExactlyMatches() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> AelisPatternOutputs.inspect(sand, List.of(new GenericStack(gravel, 1))));
        assertEquals("secondary_or_fuzzy_output", failure.getMessage());
    }

    @Test void rejectsInvalidOutputsAndOverflowInEveryOutputBucket() {
        assertThrows(IllegalArgumentException.class,
                () -> AelisPatternOutputs.inspect(sand, Arrays.asList(new GenericStack(sand, 1), null)));
        assertThrows(IllegalArgumentException.class,
                () -> AelisPatternOutputs.inspect(sand, List.of(new GenericStack(sand, 0))));
        assertThrows(ArithmeticException.class, () -> AelisPatternOutputs.inspect(sand,
                List.of(new GenericStack(sand, Long.MAX_VALUE), new GenericStack(sand, 1))));
        assertThrows(ArithmeticException.class, () -> AelisPatternOutputs.inspect(sand,
                List.of(new GenericStack(sand, 1), new GenericStack(gravel, Long.MAX_VALUE),
                        new GenericStack(gravel, 1))));
    }
}
