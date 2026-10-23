package com.appliedenhancements.integration.emi;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.appliedenhancements.client.plan.OptionalPlanBookmarks;
import com.appliedenhancements.runtime.AelisPlanningLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraftforge.fml.ModList;

/** Client-call-only, reflection-only bridge: EMI is never required to load this class. */
public final class EmiPlanBookmarks implements OptionalPlanBookmarks.Handler {
    private static boolean attempted;
    private static EmiPlanBookmarks instance;

    private final Method itemOf;
    private final Method fluidOf;
    private final Method addFavorite;
    private final Method isEmpty;
    private final Method recipeManager;
    private final Field favorites;
    private boolean broken;

    private EmiPlanBookmarks() throws ReflectiveOperationException {
        ClassLoader loader = EmiPlanBookmarks.class.getClassLoader();
        Class<?> ingredient = Class.forName("dev.emi.emi.api.stack.EmiIngredient", false, loader);
        Class<?> stack = Class.forName("dev.emi.emi.api.stack.EmiStack", false, loader);
        Class<?> forgeStack = Class.forName("dev.emi.emi.api.forge.ForgeEmiStack", false, loader);
        Class<?> runtime = Class.forName("dev.emi.emi.runtime.EmiFavorites", false, loader);
        Class<?> api = Class.forName("dev.emi.emi.api.EmiApi", false, loader);
        itemOf = stack.getMethod("of", AEItemKey.class.getMethod("toStack").getReturnType());
        fluidOf = forgeStack.getMethod("of", AEFluidKey.class.getMethod("toStack", int.class).getReturnType());
        addFavorite = runtime.getMethod("addFavorite", ingredient);
        isEmpty = ingredient.getMethod("isEmpty");
        recipeManager = api.getMethod("getRecipeManager");
        favorites = runtime.getField("favorites");
        if (addFavorite.getReturnType() != void.class || !List.class.isAssignableFrom(favorites.getType())) {
            throw new NoSuchMethodException("Unsupported EMI favorites API");
        }
    }

    /** Resolve only on client availability/add queries, never during JEI registration. */
    public static OptionalPlanBookmarks.Handler availableHandler() {
        if (!attempted) {
            attempted = true;
            try {
                if (ModList.get().isLoaded("emi")) instance = new EmiPlanBookmarks();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                warn(failure);
            }
        }
        return instance != null && instance.ready() ? instance : null;
    }

    private boolean ready() {
        if (broken) return false;
        try {
            // Before EMI reload finishes, serializers/persistence must not be used.
            return recipeManager.invoke(null) != null && favorites.get(null) instanceof List<?>;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            broken = true;
            warn(failure);
            return false;
        }
    }

    @Override
    public OptionalPlanBookmarks.Result add(List<AEKey> keys) {
        if (!ready()) return new OptionalPlanBookmarks.Result(0, keys.size());
        int added = 0;
        int unsupported = 0;
        Throwable firstFailure = null;
        for (var key : keys.stream().distinct().toList()) {
            try {
                Object stack;
                if (key instanceof AEItemKey item) stack = itemOf.invoke(null, item.toStack());
                else if (key instanceof AEFluidKey fluid) stack = fluidOf.invoke(null, fluid.toStack(1000));
                else {
                    unsupported++;
                    continue;
                }
                if (stack == null || (boolean) isEmpty.invoke(stack)) {
                    unsupported++;
                    continue;
                }
                int before = ((List<?>) favorites.get(null)).size();
                // Always pass a freshly converted EmiStack, NEVER an EmiFavorite (which toggles).
                // EMI handles equality, deduplication, persistence and sidebar notification.
                addFavorite.invoke(null, stack);
                if (((List<?>) favorites.get(null)).size() > before) added++;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                unsupported++;
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (firstFailure != null) {
            // Do not retry this batch in JEI: EMI may already have persisted some entries.
            // Subsequent calls can safely fall back to JEI instead of a broken adapter.
            broken = true;
            warn(firstFailure);
        }
        return new OptionalPlanBookmarks.Result(added, unsupported);
    }

    private static void warn(Throwable failure) {
        AelisPlanningLog.warning("emi_plan_bookmarks", "Could not use EMI plan favorites", failure);
    }
}
