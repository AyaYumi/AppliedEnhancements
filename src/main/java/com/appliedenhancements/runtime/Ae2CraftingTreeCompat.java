package com.appliedenhancements.runtime;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.crafting.CraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

/** Optional AE2: Crafting Tree metadata for summaries that bypass native fromJob. */
public final class Ae2CraftingTreeCompat {
    private static final ClassValue<Optional<SummaryApi>> SUMMARY_API = new ClassValue<>() {
        @Override protected Optional<SummaryApi> computeValue(Class<?> summaryType) {
            final Class<?> extension;
            try {
                extension = Class.forName("com.neuvillette.ae2ct.api.ICraftingPlanSummary",
                        false, summaryType.getClassLoader());
            } catch (ClassNotFoundException absent) {
                return Optional.empty();
            }
            if (!extension.isAssignableFrom(summaryType)) return Optional.empty();
            try {
                Method getter = extension.getMethod("getJob");
                Class<?> recipeHelper = getter.getReturnType();
                return Optional.of(new SummaryApi(getter, extension.getMethod("setJob", recipeHelper),
                        recipeHelper.getMethod("fromCraftingPlan", CraftingPlan.class)));
            } catch (ReflectiveOperationException incompatible) {
                throw new IllegalStateException("Unsupported AE2: Crafting Tree summary API", incompatible);
            }
        }
    };

    private Ae2CraftingTreeCompat() {}

    /** Uses AE2CT's own recipe representation and wire format; absent mods are a no-op. */
    public static void initializeSummary(CraftingPlanSummary summary, ICraftingPlan plan) {
        var api = SUMMARY_API.get(summary.getClass()).orElse(null);
        if (api == null) return;
        try {
            if (api.getter.invoke(summary) != null) return;
            // AE2CT's factory accepts CraftingPlan, while AELIS also exposes wrapped plans.
            CraftingPlan nativeView = plan instanceof CraftingPlan nativePlan ? nativePlan
                    : new CraftingPlan(plan.finalOutput(), plan.bytes(), plan.simulation(), plan.multiplePaths(),
                            plan.usedItems(), plan.emittedItems(), plan.missingItems(), plan.patternTimes());
            Object tree = api.factory.invoke(null, nativeView);
            api.setter.invoke(summary, tree);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Could not build AE2: Crafting Tree summary metadata", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not initialize AE2: Crafting Tree summary metadata", failure);
        }
    }

    private record SummaryApi(Method getter, Method setter, Method factory) {}
}
