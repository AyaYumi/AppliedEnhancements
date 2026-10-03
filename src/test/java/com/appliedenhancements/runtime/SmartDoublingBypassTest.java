package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.*;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.appliedenhancements.api.AelisSmartDoublingApi;
import com.extendedae_plus.api.smartDoubling.ISmartDoublingAwarePattern;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SmartDoublingBypassTest {
    @Test void enabledSwitchIsReadAgainWithoutStaleClassCache() {
        boolean[] enabled = {false};
        var pattern = aware(enabled);
        assertFalse(AelisSmartDoublingApi.isExternallyManaged(pattern));
        enabled[0] = true;
        assertTrue(AelisSmartDoublingApi.isExternallyManaged(pattern));
        enabled[0] = false;
        assertFalse(AelisSmartDoublingApi.isExternallyManaged(pattern));
    }

    @Test void allEnabledTasksBypassLocalRewriteAndKeepSamePlan() {
        var enabled = aware(new boolean[]{true});
        var plan = plan(Map.of(enabled, 17L));
        assertSame(plan, AelisCraftingPlanRewrite.rewriteOrdinaryPatterns(plan,
                ignored -> { throw new AssertionError("Native task must not be rewritten"); }));
    }

    @Test void mixedPlanOnlyExposesDisabledTasksToRewrite() {
        var enabled = aware(new boolean[]{true});
        var disabled = aware(new boolean[]{false});
        var plan = plan(Map.of(enabled, 17L, disabled, 4L));
        var result = AelisCraftingPlanRewrite.rewriteOrdinaryPatterns(plan, ordinary -> {
            assertEquals(Map.of(disabled, 4L), ordinary.patternTimes());
            return ordinary;
        });
        assertEquals(plan.patternTimes(), result.patternTimes());
    }

    @Test void optionalRewriteFailureReturnsOriginalExecutablePlan() {
        var plan = plan(Map.of(aware(new boolean[]{false}), 17L));
        assertSame(plan, assertDoesNotThrow(() -> AelisCraftingPlanRewrite.rewriteOrdinaryPatterns(plan,
                ignored -> { throw new IllegalStateException("Unrecognized native rewrite"); })));
    }

    @Test void unreadableEnabledSwitchPreservesNativeOwnership() {
        var pattern = (IPatternDetails) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IPatternDetails.class, ISmartDoublingAwarePattern.class},
                (p,m,a) -> { throw new IllegalStateException("Optional API unavailable"); });
        assertTrue(assertDoesNotThrow(() -> AelisSmartDoublingApi.isExternallyManaged(pattern)));
        assertNull(assertDoesNotThrow(() -> SmartDoublingPatternAccess.resolve(pattern)));
    }

    private static IPatternDetails aware(boolean[] enabled) {
        return (IPatternDetails) Proxy.newProxyInstance(SmartDoublingBypassTest.class.getClassLoader(),
                new Class<?>[]{IPatternDetails.class, ISmartDoublingAwarePattern.class},
                (p,m,a) -> switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    case "eap$allowScaling" -> enabled[0];
                    case "getDefinition" -> null;
                    default -> throw new AssertionError(m.getName());
                });
    }
    private static ICraftingPlan plan(Map<IPatternDetails, Long> tasks) {
        return new ICraftingPlan() {
            public GenericStack finalOutput() { return null; }
            public long bytes() { return 0; }
            public boolean simulation() { return false; }
            public boolean multiplePaths() { return false; }
            public KeyCounter usedItems() { return new KeyCounter(); }
            public KeyCounter emittedItems() { return new KeyCounter(); }
            public KeyCounter missingItems() { return new KeyCounter(); }
            public Map<IPatternDetails, Long> patternTimes() { return tasks; }
        };
    }
}
