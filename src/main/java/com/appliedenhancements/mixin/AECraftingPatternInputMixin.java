package com.appliedenhancements.mixin;

import appeng.api.stacks.AEKey;
import com.appliedenhancements.Config;
import com.appliedenhancements.cache.PatternValidityCache;
import com.appliedenhancements.cache.RemainingKeyCache;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches AE2 pattern input validation and remaining key lookups to reduce redundant calculations.
 * This significantly improves performance during large crafting calculations by avoiding repeated
 * validation checks and container item lookups for the same inputs.
 */
@Mixin(targets = "appeng.crafting.pattern.AECraftingPattern$Input", remap = false)
public abstract class AECraftingPatternInputMixin {
    @Unique
    private volatile RemainingKeyCache appliedenhancements$remainingKeyCache;
    @Unique
    private volatile PatternValidityCache appliedenhancements$validityCache;
    @Unique
    private volatile ConcurrentHashMap<AEKey, RemainingKeyCache> appliedenhancements$remainingKeyCaches;
    @Unique
    private volatile ConcurrentHashMap<AEKey, PatternValidityCache> appliedenhancements$validityCaches;

    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$reuseValidity(AEKey input, Level level,
            CallbackInfoReturnable<Boolean> callback) {
        if (!Config.ENABLE_PATTERN_CACHING.get()) {
            appliedenhancements$clearCaches();
            return;
        }
        var cache = appliedenhancements$validityCache;
        if (cache != null && cache.level() == level
                && (cache.input() == input || cache.input().equals(input))) {
            callback.setReturnValue(cache.valid());
            return;
        }
        var caches = appliedenhancements$validityCaches;
        if (caches != null) {
            cache = caches.get(input);
            if (cache != null && cache.level() == level) {
                appliedenhancements$validityCache = cache;
                callback.setReturnValue(cache.valid());
            }
        }
    }

    @Inject(method = "isValid", at = @At("RETURN"))
    private void appliedenhancements$cacheValidity(AEKey input, Level level,
            CallbackInfoReturnable<Boolean> callback) {
        if (!Config.ENABLE_PATTERN_CACHING.get()) {
            return;
        }
        var next = new PatternValidityCache(input, level, callback.getReturnValue());
        var previous = appliedenhancements$validityCache;
        appliedenhancements$validityCache = next;
        if (previous == null || previous.level() == level
                && (previous.input() == input || previous.input().equals(input))) {
            return;
        }
        var caches = appliedenhancements$validityCaches;
        if (caches == null) {
            synchronized (this) {
                caches = appliedenhancements$validityCaches;
                if (caches == null) {
                    caches = new ConcurrentHashMap<>();
                    caches.put(previous.input(), previous);
                    appliedenhancements$validityCaches = caches;
                }
            }
        }
        appliedenhancements$putBounded(caches, input, next);
    }

    @Inject(method = "getRemainingKey", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$reuseRemainingKey(AEKey template,
            CallbackInfoReturnable<AEKey> callback) {
        if (!Config.ENABLE_PATTERN_CACHING.get()) {
            appliedenhancements$clearCaches();
            return;
        }
        var cache = appliedenhancements$remainingKeyCache;
        if (cache != null && (cache.input() == template || cache.input().equals(template))) {
            callback.setReturnValue(cache.output());
            return;
        }
        var caches = appliedenhancements$remainingKeyCaches;
        if (caches != null) {
            cache = caches.get(template);
            if (cache != null) {
                appliedenhancements$remainingKeyCache = cache;
                callback.setReturnValue(cache.output());
            }
        }
    }

    @Inject(method = "getRemainingKey", at = @At("RETURN"))
    private void appliedenhancements$cacheRemainingKey(AEKey template,
            CallbackInfoReturnable<AEKey> callback) {
        if (!Config.ENABLE_PATTERN_CACHING.get()) {
            return;
        }
        var next = new RemainingKeyCache(template, callback.getReturnValue());
        var previous = appliedenhancements$remainingKeyCache;
        appliedenhancements$remainingKeyCache = next;
        if (previous == null || previous.input() == template || previous.input().equals(template)) {
            return;
        }
        var caches = appliedenhancements$remainingKeyCaches;
        if (caches == null) {
            synchronized (this) {
                caches = appliedenhancements$remainingKeyCaches;
                if (caches == null) {
                    caches = new ConcurrentHashMap<>();
                    caches.put(previous.input(), previous);
                    appliedenhancements$remainingKeyCaches = caches;
                }
            }
        }
        appliedenhancements$putBounded(caches, template, next);
    }

    @Unique
    private static <T> void appliedenhancements$putBounded(
            ConcurrentHashMap<AEKey, T> cache, AEKey key, T value) {
        int limit = Config.PATTERN_CACHE_SIZE.get();
        if (cache.size() >= limit && !cache.containsKey(key)) {
            cache.clear();
        }
        cache.put(key, value);
    }

    @Unique
    private void appliedenhancements$clearCaches() {
        appliedenhancements$remainingKeyCache = null;
        appliedenhancements$validityCache = null;
        appliedenhancements$remainingKeyCaches = null;
        appliedenhancements$validityCaches = null;
    }
}
