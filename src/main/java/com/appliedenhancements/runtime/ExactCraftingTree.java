package com.appliedenhancements.runtime;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import com.appliedenhancements.util.AmountFormatter;
import com.github.appliedenhancements.crafting.aelis.AelisBigIntegerMath;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Optional AE2CT bridge. Its layout stays native; all quantity arithmetic is exact. */
public final class ExactCraftingTree {
    private record Totals(Map<AEKey, BigInteger> crafted, Map<AEKey, BigInteger> missing,
            Map<AEKey, BigInteger> stored, BigInteger output) {}
    public record Amounts(AEKey key, BigInteger amount, BigInteger stored, BigInteger missing, BigInteger crafted) {}
    private static final Map<Object, Totals> TOTALS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Amounts> NODES = Collections.synchronizedMap(new WeakHashMap<>());

    private ExactCraftingTree() {}

    public static void attach(Object recipes, Map<AEKey, BigInteger> crafted, Map<AEKey, BigInteger> missing) {
        attach(recipes, crafted, missing, Map.of());
    }

    public static void attach(Object recipes, Map<AEKey, BigInteger> crafted, Map<AEKey, BigInteger> missing,
            Map<AEKey, BigInteger> stored) {
        attach(recipes, crafted, missing, stored, null);
    }
    public static void attach(Object recipes, Map<AEKey, BigInteger> crafted, Map<AEKey, BigInteger> missing,
            Map<AEKey, BigInteger> stored, BigInteger output) {
        TOTALS.put(recipes, new Totals(Map.copyOf(crafted), Map.copyOf(missing), Map.copyOf(stored), output));
    }

    public static Amounts amounts(Object node) { return NODES.get(node); }

    public static String label(Object node) {
        Amounts exact = amounts(node);
        if (exact == null) return null;
        return AmountFormatter.format(new BigDecimal(exact.amount()).divide(
                BigDecimal.valueOf(exact.key().getAmountPerUnit()), MathContext.DECIMAL128));
    }

    public static List<Component> tooltip(Object widget, int mouseX, int mouseY, List<Component> original) {
        try {
            var method = widget.getClass().getDeclaredMethod("getMousePoint", double.class, double.class);
            method.setAccessible(true);
            Object point = method.invoke(widget, (double) mouseX, (double) mouseY);
            Object manager = field(widget, "_nodeManager");
            if (manager == null) return original;
            Object node = ((Map<?, ?>) field(manager, "map")).get(point);
            Amounts exact = amounts(node);
            if (exact == null) return original;
            var result = new ArrayList<Component>(original.size());
            for (Component line : original) {
                if (line.getContents() instanceof TranslatableContents text) {
                    BigInteger amount = switch (text.getKey()) {
                        case "gui.ae2ct.OutputAmount", "gui.ae2ct.InputAmount", "gui.ae2ct.MiddenAmount" -> exact.amount();
                        case "gui.ae2ct.StoredAmount" -> exact.stored();
                        case "gui.ae2ct.MissingAmount" -> exact.missing();
                        case "gui.ae2ct.CraftingAmount" -> exact.crafted();
                        default -> null;
                    };
                    if (amount != null) {
                        String value = AmountFormatter.formatFull(amount, exact.key().getAmountPerUnit());
                        String unit = exact.key().getUnitSymbol();
                        result.add(Component.translatable(text.getKey(), unit == null ? value : value + " " + unit)
                                .setStyle(line.getStyle()));
                        continue;
                    }
                }
                result.add(line);
            }
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported AE2CT tooltip API", failure);
        }
    }

    public static Object build(Object helper, boolean missingOnly) {
        try {
            Object recipes = field(helper, "recipeHelper");
            if (recipes == null) return null;
            @SuppressWarnings("unchecked")
            List<CraftingPlanSummaryEntry> entries = (List<CraftingPlanSummaryEntry>) field(helper, "entries");
            Builder builder = new Builder(helper, recipes, entries);
            GenericStack output = (GenericStack) field(recipes, "output");
            var totals = TOTALS.get(recipes);
            Object root = builder.node(output, totals != null && totals.output() != null ? totals.output()
                    : BigInteger.valueOf(output.amount()), null, new HashSet<>(), true);
            if (missingOnly) prune(root);
            Class<?> managerType = Class.forName(helper.getClass().getName() + "$NodeManager", true, helper.getClass().getClassLoader());
            Object manager = managerType.getConstructor(helper.getClass(), builder.nodeType).newInstance(helper, root);
            helper.getClass().getMethod("buildNodePosition", builder.nodeType, managerType).invoke(helper, root, manager);
            return manager;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported AE2CT tree API", failure);
        }
    }

    private static boolean prune(Object node) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked") var children = (List<Object>) field(node, "subNodes");
        for (var it = children.iterator(); it.hasNext();) if (!prune(it.next())) it.remove();
        return amounts(node).missing().signum() > 0 || !children.isEmpty();
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static final class Builder {
        private final Object helper;
        private final Class<?> nodeType;
        private final Constructor<?> nodeConstructor;
        private final Constructor<?> amountConstructor;
        private final Map<AEKey, Recipe> recipes = new LinkedHashMap<>();
        private final Map<AEKey, BigInteger> stored = new HashMap<>();
        private final Map<AEKey, BigInteger> missing = new HashMap<>();
        private int nodes;

        private record Recipe(List<GenericStack> inputs, long output) {}

        @SuppressWarnings("unchecked")
        private Builder(Object helper, Object data, List<CraftingPlanSummaryEntry> entries) throws ReflectiveOperationException {
            this.helper = helper;
            Class<?> type = helper.getClass();
            nodeType = Class.forName(type.getName() + "$Node", true, type.getClassLoader());
            Class<?> amountType = Class.forName(type.getName() + "$AmountHelper", true, type.getClassLoader());
            nodeConstructor = nodeType.getConstructor(type, GenericStack.class, Long.class, amountType);
            amountConstructor = amountType.getConstructor(long.class, long.class, long.class);
            Totals totals = TOTALS.get(data);
            for (var entry : entries) {
                BigInteger storedAmount = BigInteger.valueOf(Math.max(0, entry.getStoredAmount()));
                stored.put(entry.getWhat(), totals == null ? storedAmount
                        : totals.stored().getOrDefault(entry.getWhat(), storedAmount));
                missing.put(entry.getWhat(), totals == null ? BigInteger.valueOf(entry.getMissingAmount())
                        : totals.missing().getOrDefault(entry.getWhat(), BigInteger.valueOf(entry.getMissingAmount())));
            }
            for (Object recipe : (List<?>) field(data, "recipes")) {
                List<GenericStack> outputs = (List<GenericStack>) recipe.getClass().getMethod("outputs").invoke(recipe);
                List<GenericStack> inputs = (List<GenericStack>) recipe.getClass().getMethod("inputs").invoke(recipe);
                if (!outputs.isEmpty() && outputs.getFirst().amount() > 0) {
                    recipes.putIfAbsent(outputs.getFirst().what(), new Recipe(inputs, outputs.getFirst().amount()));
                }
            }
        }

        private Object node(GenericStack stack, BigInteger amount, Object parent, Set<AEKey> active, boolean root)
                throws ReflectiveOperationException {
            AEKey key = stack.what();
            Recipe recipe = recipes.get(key);
            BigInteger used = root ? BigInteger.ZERO : stored.getOrDefault(key, BigInteger.ZERO).min(amount);
            if (!root) stored.computeIfPresent(key, (k, v) -> v.subtract(used));
            BigInteger need = amount.subtract(used);
            boolean expand = recipe != null && !recipe.inputs().isEmpty() && need.signum() > 0
                    && !active.contains(key) && active.size() < 256 && ++nodes <= 8192;
            BigInteger times = expand ? AelisBigIntegerMath.ceilDiv(need, recipe.output()) : BigInteger.ZERO;
            BigInteger crafted = expand ? times.multiply(BigInteger.valueOf(recipe.output())) : BigInteger.ZERO;
            BigInteger absent = expand ? BigInteger.ZERO : missing.getOrDefault(key, BigInteger.ZERO).min(need);
            if (!expand) missing.computeIfPresent(key, (k, v) -> v.subtract(absent));
            // Leaves with no shortage include reusable inputs: summary missing is authoritative.
            BigInteger storedAmount = expand ? used : amount.subtract(absent);
            var exact = new Amounts(key, amount, storedAmount, absent, crafted);
            Object amounts = amountConstructor.newInstance(project(absent), project(storedAmount), project(crafted));
            Object node = nodeConstructor.newInstance(helper, stack, project(amount), amounts);
            nodeType.getField("parent").set(node, parent);
            NODES.put(node, exact);
            if (expand) {
                active.add(key);
                @SuppressWarnings("unchecked") List<Object> children = (List<Object>) field(node, "subNodes");
                for (GenericStack input : recipe.inputs()) {
                    if (input.amount() <= 0) throw new IllegalStateException("Invalid AE2CT recipe input quantity");
                    children.add(node(input, times.multiply(BigInteger.valueOf(input.amount())), node, active, false));
                }
                active.remove(key);
            }
            return node;
        }

        private static long project(BigInteger value) { return AelisBigIntegerMath.saturatingLong(value); }
    }
}
