package com.appliedenhancements.runtime;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEKey;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Bridges AE2 15's int menus and UELM's native long menus without narrowing long requests. */
public final class NativeCraftingMenuCompat {
    private static final ClassValue<Field> AMOUNT = new ClassValue<>() {
        @Override protected Field computeValue(Class<?> type) {
            for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
                try {
                    Field field = owner.getDeclaredField("amount");
                    if (field.getType() != int.class && field.getType() != long.class) {
                        throw new IllegalStateException("Unsupported crafting amount field: " + field);
                    }
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException absent) {
                    // Menu subclasses inherit AE2's private amount field.
                }
            }
            throw new IllegalStateException("Missing crafting amount field in " + type.getName());
        }
    };
    private static final ClassValue<Method> CONFIRM = numericMethod(
            "confirm", 0, int.class, boolean.class, boolean.class);
    private static final ClassValue<Method> PLAN = numericMethod(
            "planJob", 1, AEKey.class, int.class, CalculationStrategy.class);

    private NativeCraftingMenuCompat() {}

    public static long amount(Object menu) {
        try { return AMOUNT.get(menu.getClass()).getLong(menu); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    public static void setAmount(Object menu, long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative crafting amount");
        Field field = AMOUNT.get(menu.getClass());
        try {
            if (field.getType() == long.class) field.setLong(menu, amount);
            else field.setInt(menu, (int) Math.min(amount, Integer.MAX_VALUE));
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    public static long maximumNativeAmount(Object amountMenu) {
        return CONFIRM.get(amountMenu.getClass()).getParameterTypes()[0] == long.class
                ? Long.MAX_VALUE : Integer.MAX_VALUE;
    }

    public static void confirm(Object menu, long amount, boolean missing, boolean autoStart) {
        Method method = CONFIRM.get(menu.getClass());
        invoke(method, menu, numericArgument(method, 0, amount), missing, autoStart);
    }

    public static boolean plan(Object menu, AEKey key, long amount, CalculationStrategy strategy) {
        Method method = PLAN.get(menu.getClass());
        return (boolean) invoke(method, menu, key, numericArgument(method, 1, amount), strategy);
    }

    private static Object numericArgument(Method method, int index, long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative crafting amount");
        // Separate returns prevent Java's conditional numeric promotion from boxing both as Long.
        if (method.getParameterTypes()[index] == long.class) return Long.valueOf(amount);
        return Integer.valueOf(Math.toIntExact(amount));
    }

    private static ClassValue<Method> numericMethod(String name, int index, Class<?>... intTypes) {
        return new ClassValue<>() {
            @Override protected Method computeValue(Class<?> type) {
                Class<?>[] longTypes = intTypes.clone();
                longTypes[index] = long.class;
                try {
                    Method method;
                    try { method = type.getMethod(name, longTypes); }
                    catch (NoSuchMethodException vanilla) { method = type.getMethod(name, intTypes); }
                    method.setAccessible(true);
                    return method;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Unsupported crafting menu " + type.getName(), e);
                }
            }
        };
    }

    private static Object invoke(Method method, Object menu, Object... args) {
        try { return method.invoke(menu, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            if (e.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}
