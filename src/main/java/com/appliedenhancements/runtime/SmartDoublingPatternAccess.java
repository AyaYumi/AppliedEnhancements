package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
import com.github.appliedenhancements.integration.ae2.AelisScaledPattern;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Cached optional contracts; no class loading or reflection discovery on the tick path. */
public final class SmartDoublingPatternAccess {
    private static final String EAP = "com.extendedae_plus.api.crafting.ScaledProcessingPattern";
    private static final String USELESS = "com.sorrowmist.useless.content.machines.advanced_alloy_furnace.ae.ScaledProcessingPattern";
    private static final String EAP_AWARE = "com.extendedae_plus.api.smartDoubling.ISmartDoublingAwarePattern";
    private static final String USELESS_PROVIDER = "com.sorrowmist.useless.api.crafting.SmartDoublingCraftingProvider";
    public record Scale(IPatternDetails original, long multiplier) {}
    private record Access(boolean external, Method original, Method multiplier, Field multiplierField,
            Method enabled) {}
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> type) {
            boolean eap = inherits(type, EAP);
            boolean useless = inherits(type, USELESS);
            try {
                Method enabled = hasInterface(type, EAP_AWARE) ? type.getMethod("eap$allowScaling") : null;
                if (!eap && !useless) return new Access(false, null, null, null, enabled);
                Method original = type.getMethod("getOriginal");
                if (useless) return new Access(true, original, type.getMethod("getOperationsPerPush"), null, enabled);
                Field multiplier = null;
                for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                    try { multiplier = current.getDeclaredField("multiplier"); break; }
                    catch (NoSuchFieldException absent) { /* inherited optional wrapper */ }
                }
                if (multiplier == null || !multiplier.trySetAccessible())
                    return new Access(true, null, null, null, enabled);
                return new Access(true, original, null, multiplier, enabled);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                return new Access(eap || useless || hasInterface(type, EAP_AWARE), null, null, null, null);
            }
        }
    };
    private static final ClassValue<Boolean> PROVIDERS = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) { return hasInterface(type, USELESS_PROVIDER); }
    };
    private SmartDoublingPatternAccess() {}

    public static boolean isExternallyManagedProvider(Object provider) {
        return PROVIDERS.get(provider.getClass());
    }

    public static boolean isExternallyManaged(IPatternDetails pattern) {
        if (pattern == null) return false;
        var access = ACCESS.get(pattern.getClass());
        if (access.external()) return true;
        if (access.enabled() == null) return false;
        Object enabled = invoke(access.enabled(), pattern);
        return !(enabled instanceof Boolean value) || value;
    }

    /** Reads one wrapper layer; external implementations do not need AelisScaledPattern. */
    public static Scale resolve(IPatternDetails pattern) {
        if (pattern == null) return null;
        var access = ACCESS.get(pattern.getClass());
        try {
            if (access.external()) {
                if (access.original() == null || access.multiplier() == null && access.multiplierField() == null) return null;
                Object original = invoke(access.original(), pattern);
                Object raw = access.multiplier() != null ? invoke(access.multiplier(), pattern)
                        : access.multiplierField().getLong(pattern);
                if (!(original instanceof IPatternDetails base) || base == pattern
                        || !(raw instanceof Number number) || number.longValue() <= 0) return null;
                return new Scale(base, number.longValue());
            }
            if (pattern instanceof AelisScaledPattern scaled) {
                var original = scaled.appliedenhancements$originalPattern();
                long multiplier = scaled.appliedenhancements$operationsPerPush();
                if (original != null && original != pattern && multiplier > 0) return new Scale(original, multiplier);
            }
        } catch (IllegalAccessException | RuntimeException | LinkageError unavailable) {
            // Keep native ownership; callers restore original tasks when the optional ABI is unreadable.
        }
        return null;
    }

    private static Object invoke(Method method, Object target) {
        try { return method.invoke(target); }
        catch (IllegalAccessException | InvocationTargetException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }
    private static boolean inherits(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            if (name.equals(current.getName())) return true;
        return false;
    }
    private static boolean hasInterface(Class<?> type, String name) {
        if (type == null) return false;
        if (name.equals(type.getName())) return true;
        for (var contract : type.getInterfaces()) if (hasInterface(contract, name)) return true;
        return hasInterface(type.getSuperclass(), name);
    }
}
