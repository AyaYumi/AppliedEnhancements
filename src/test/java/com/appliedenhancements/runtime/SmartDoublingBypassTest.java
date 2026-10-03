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

    @Test void installedEaepWrapperUsesItsNativeAbiWithoutAppliedInterface() throws Exception {
        String installed = System.getProperty("appliedenhancements.eaepTestJar");
        if (installed == null) return; // Maintained ABI fixture handles normal CI; opt in to installed JAR QA.
        String name = "com.extendedae_plus.api.crafting.ScaledProcessingPattern";
        byte[] bytes;
        try (var archive = new java.util.zip.ZipFile(installed)) {
            bytes = archive.getInputStream(archive.getEntry(name.replace('.', '/') + ".class")).readAllBytes();
        }
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(requested)) {
                    if (!requested.equals(name)) return super.loadClass(requested, resolve);
                    Class<?> type = findLoadedClass(requested);
                    if (type == null) type = defineClass(requested, bytes, 0, bytes.length);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
        var original = aware(new boolean[]{true});
        var wrapper = (IPatternDetails) loader.loadClass(name).getConstructor(IPatternDetails.class, long.class)
                .newInstance(original, 7L);
        assertTrue(AelisSmartDoublingApi.isExternallyManaged(wrapper));
        assertFalse(wrapper instanceof com.github.appliedenhancements.integration.ae2.AelisScaledPattern);
        var scale = SmartDoublingPatternAccess.resolve(wrapper);
        assertSame(original, scale.original());
        assertEquals(7L, scale.multiplier());
        var result = ExactScaledTaskReconciliation.reconcile(Map.of(wrapper, 2L, original, 1L),
                Map.of(original, java.math.BigInteger.valueOf(15)));
        assertEquals(Map.of(wrapper, 2L, original, 1L), result.projected());
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
