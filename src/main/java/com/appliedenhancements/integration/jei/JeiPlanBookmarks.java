package com.appliedenhancements.integration.jei;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.appliedenhancements.client.plan.OptionalPlanBookmarks;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;

/** Uses JEI's bookmark manager, with an adapter for releases preceding the public API. */
final class JeiPlanBookmarks implements OptionalPlanBookmarks.Handler {
    private final IJeiRuntime runtime;
    private final BookmarkAdder adder;

    JeiPlanBookmarks(IJeiRuntime runtime) throws ReflectiveOperationException {
        this.runtime = runtime;
        this.adder = findAdder(runtime);
    }

    @Override
    public OptionalPlanBookmarks.Result add(List<AEKey> keys) {
        int added = 0;
        int unsupported = 0;
        int failed = 0;
        Exception firstFailure = null;
        for (var key : keys.stream().distinct().toList()) {
            try {
                var ingredient = ingredient(key);
                if (ingredient.isEmpty()) unsupported++;
                else if (adder.add(ingredient.orElseThrow())) added++;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                unsupported++;
                failed++;
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (firstFailure != null) {
            com.appliedenhancements.runtime.AelisPlanningLog.warning("jei_plan_bookmarks",
                    "Could not bookmark {} missing material types in JEI", failed, firstFailure);
        }
        return new OptionalPlanBookmarks.Result(added, unsupported);
    }

    private Optional<ITypedIngredient<?>> ingredient(AEKey key) {
        Object value;
        if (key instanceof AEItemKey item) value = item.toStack();
        else if (key instanceof AEFluidKey fluid) value = fluid.toStack(1000);
        else return Optional.empty();
        return runtime.getIngredientManager().createTypedIngredient(value)
                .map(typed -> (ITypedIngredient<?>) typed);
    }

    private static BookmarkAdder findAdder(IJeiRuntime runtime) throws ReflectiveOperationException {
        try {
            Method getter = IJeiRuntime.class.getMethod("getBookmarkManager");
            Object manager = getter.invoke(runtime);
            Method add = getter.getReturnType().getMethod("add", ITypedIngredient.class);
            return typed -> (boolean) add.invoke(manager, typed);
        } catch (NoSuchMethodException olderJei) {
            // JEI 15 exposes insertion on its internal bookmark list. Use its add
            // methods to preserve persistence, deduplication and overlay notification.
            Object list = field(runtime.getBookmarkOverlay(), "bookmarkList");
            try {
                Method add = list.getClass().getMethod("addIngredientBookmark", ITypedIngredient.class);
                return typed -> (boolean) add.invoke(list, typed);
            } catch (NoSuchMethodException olderJei15) {
                // Before addIngredientBookmark (e.g. 15.20), construct the bookmark
                // through JEI's factory so normalization and ingredient UIDs match JEI.
                ClassLoader loader = list.getClass().getClassLoader();
                Class<?> bookmark = Class.forName("mezz.jei.gui.bookmarks.IBookmark", false, loader);
                Class<?> ingredientBookmark = Class.forName(
                        "mezz.jei.gui.bookmarks.IngredientBookmark", false, loader);
                Method create = ingredientBookmark.getMethod("create", ITypedIngredient.class,
                        IIngredientManager.class);
                Method add = list.getClass().getMethod("add", bookmark);
                IIngredientManager ingredients = runtime.getIngredientManager();
                return typed -> (boolean) add.invoke(list, create.invoke(null, typed, ingredients));
            }
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @FunctionalInterface
    private interface BookmarkAdder {
        boolean add(ITypedIngredient<?> ingredient) throws ReflectiveOperationException;
    }
}
