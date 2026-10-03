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
            Method enabled, RuntimeException failure) {}
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> type) {
            boolean eap = inherits(type, EAP);
            boolean useless = inherits(type, USELESS);
            try {
                Method enabled = hasInterface(type, EAP_AWARE) ? type.getMethod("eap$allowScaling") : null;
                if (!eap && !useless) return new Access(false, null, null, null, enabled, null);
                Method original = type.getMethod("getOriginal");
                if (useless) return new Access(true, original, type.getMethod("getOperationsPerPush"), null, enabled, null);
                Field multiplier = null;
                for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                    try { multiplier = current.getDeclaredField("multiplier"); break; }
                    catch (NoSuchFieldException absent) { /* inherited optional wrapper */ }
                }
                if (multiplier == null || !multiplier.trySetAccessible())
                    throw new IllegalStateException("Native smart-doubling multiplier is inaccessible: " + type.getName());
                return new Access(true, original, null, multiplier, enabled, null);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                return new Access(eap || useless, null, null, null, null,
                        new IllegalStateException("Native smart-doubling API is incompatible: " + type.getName(), unavailable));
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
        var access = ACCESS.get(pattern.getClass());
        if (access.external()) return true;
        if (access.failure() != null) throw access.failure();
        return access.enabled() != null && Boolean.TRUE.equals(invoke(access.enabled(), pattern));
    }

    /** Reads one wrapper layer; external implementations do not need AelisScaledPattern. */
    public static Scale resolve(IPatternDetails pattern) {
        var access = ACCESS.get(pattern.getClass());
        if (access.external()) {
            if (access.failure() != null) throw access.failure();
            Object original = invoke(access.original(), pattern);
            long multiplier;
            try {
                multiplier = access.multiplier() != null ? ((Number) invoke(access.multiplier(), pattern)).longValue()
                        : access.multiplierField().getLong(pattern);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Cannot read native smart-doubling multiplier", failure);
            }
            if (!(original instanceof IPatternDetails base) || base == pattern || multiplier <= 0)
                throw new IllegalStateException("Invalid native smart-doubling wrapper: " + pattern.getClass().getName());
            return new Scale(base, multiplier);
        }
        if (pattern instanceof AelisScaledPattern scaled)
            return new Scale(scaled.appliedenhancements$originalPattern(), scaled.appliedenhancements$operationsPerPush());
        return null;
    }

    private static Object invoke(Method method, Object target) {
        try { return method.invoke(target); }
        catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("Cannot read native smart-doubling state", failure);
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
