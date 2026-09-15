package com.github.appliedenhancements.crafting;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;

class MolecularReusableInputAdaptersTest {
    private static String adapterSource() throws IOException {
        return Files.readString(Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/com/github/appliedenhancements/crafting/"
                        + "MolecularReusableInputAdapters.java"));
    }

    @Test
    void identityProbeVisitsTheStateInUseFirstAndTheWholeRangeWhenItFits() {
        int[] states = MolecularReusableInputAdapters.identityProbeDamageStates(3, 4, 256);

        assertArrayEquals(new int[] { 3, 0, 1, 2, 4 }, states);
    }

    @Test
    void identityProbeCoversBothEndsWhenTheRangeIsTooLarge() {
        int[] states = MolecularReusableInputAdapters.identityProbeDamageStates(0, 32767, 8);

        assertEquals(8, states.length);
        assertEquals(0, states[0], "the state in use must be probed first");
        assertEquals(0, Arrays.stream(states).min().orElse(-1));
        assertEquals(32767, Arrays.stream(states).max().orElse(-1));
    }

    @Test
    void identityProbeNeverExceedsItsLimitOrRepeatsStates() {
        for (int limit : new int[] { 1, 2, 3, 7, 64 }) {
            for (int maxDamage : new int[] { 0, 1, 5, 63, 2048, 32767 }) {
                int[] states = MolecularReusableInputAdapters.identityProbeDamageStates(
                        7, maxDamage, limit);
                assertTrue(states.length <= limit, "limit " + limit + " maxDamage " + maxDamage);
                assertEquals(states.length, new LinkedHashSet<>(Arrays.asList(boxed(states))).size(),
                        "duplicates for limit " + limit + " maxDamage " + maxDamage);
                for (int damage : states) {
                    assertTrue(damage >= 0 && damage <= maxDamage,
                            "state " + damage + " outside [0, " + maxDamage + "]");
                }
            }
        }
    }

    @Test
    void identityProbeClampsTheCurrentStateIntoTheUsableRange() {
        int[] states = MolecularReusableInputAdapters.identityProbeDamageStates(100, 50, 4);

        assertEquals(4, states.length);
        assertEquals(50, states[0]);
        assertArrayEquals(new int[] { 50, 0 }, Arrays.copyOf(states, 2));
    }

    @Test
    void identityProbeRejectsDegenerateRanges() {
        assertEquals(0, MolecularReusableInputAdapters.identityProbeDamageStates(0, -1, 10).length);
        assertEquals(0, MolecularReusableInputAdapters.identityProbeDamageStates(0, 10, 0).length);
    }

    @Test
    void damageableSelfRemaindersAreProvenInsteadOfRejectedOutright() throws IOException {
        String source = adapterSource();

        // Damageable catalysts such as ProjectE's Philosopher's Stone return
        // stack.copy() for every damage state. They must still be provable as
        // infinite catalysts, while a random remainder keeps falling back.
        assertTrue(source.contains("hasStableSelfRemainder("));
        assertTrue(source.contains("isSelfRemainder("));
        assertTrue(source.contains("MAX_IDENTITY_PROBES"));
        assertTrue(source.contains("IDENTITY_PROBES.computeIfAbsent("));
        int selfRemainderBranch = source.indexOf("firstRemainder.equals(initialKey)");
        int proof = source.indexOf("hasStableSelfRemainder(input, itemKey, template, level)");
        assertTrue(selfRemainderBranch >= 0, "self-remainder branch is missing");
        assertTrue(proof > selfRemainderBranch,
                "the identity proof must guard the self-remainder branch");
    }

    private static Integer[] boxed(int[] values) {
        Integer[] boxed = new Integer[values.length];
        for (int index = 0; index < values.length; index++) {
            boxed[index] = values[index];
        }
        return boxed;
    }
}
