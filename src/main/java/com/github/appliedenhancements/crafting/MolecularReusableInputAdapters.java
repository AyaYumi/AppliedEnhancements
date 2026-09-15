package com.github.appliedenhancements.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Conservative reusable-input adapters shared by planning, extraction and the
 * three molecular crafting providers.
 */
public final class MolecularReusableInputAdapters {
    // Finite tools are exhaustively validated against the real recipe for every
    // damage state. Keep one synchronous provider push below a watchdog-risky
    // amount of recipe work; larger orders continue in subsequent batches.
    public static final long MAX_DETERMINISTIC_TRANSITIONS = 2_048;

    private MolecularReusableInputAdapters() {
    }

    public enum Mode {
        CONSUMABLE,
        INVARIANT_REUSABLE,
        DETERMINISTIC_DAMAGE,
        UNSUPPORTED
    }

    public record Analysis(Mode mode, AEKey initialKey, long safeCrafts,
            @Nullable AEKey finalKey) {
        public boolean isReusable() {
            return mode == Mode.INVARIANT_REUSABLE
                    || mode == Mode.DETERMINISTIC_DAMAGE;
        }

        public boolean isSupported() {
            return mode != Mode.UNSUPPORTED;
        }
    }

    /**
     * Analyses the actual key extracted by AE2, never just the encoded pattern
     * template. Damageable items are accepted only when every observed
     * transition is exactly Damage+1 and Unbreaking is absent.
     */
    public static Analysis analyze(IPatternDetails.IInput input, AEKey initialKey,
            Level level, long requestedCrafts) {
        if (input == null || initialKey == null || level == null || requestedCrafts <= 0) {
            return unsupported(initialKey);
        }

        try {
            if (!input.isValid(initialKey, level)) {
                return unsupported(initialKey);
            }

            AEKey firstRemainder = input.getRemainingKey(initialKey);
            if (firstRemainder == null) {
                if (isDeterministicDamageCandidate(initialKey, level)) {
                    // A tool on its final use legitimately has no remainder. It
                    // can execute one craft, but can never form a multi-craft batch.
                    return new Analysis(Mode.DETERMINISTIC_DAMAGE, initialKey, 1, null);
                }
                return new Analysis(Mode.CONSUMABLE, initialKey, Long.MAX_VALUE, null);
            }

            if (firstRemainder.equals(initialKey)) {
                if (!(initialKey instanceof AEItemKey itemKey)) {
                    return unsupported(initialKey);
                }
                ItemStack template = itemKey.toStack();
                if (!hasFiniteMutableDurability(template)) {
                    // A non-damageable item returning its own key is a stable catalyst.
                    return new Analysis(Mode.INVARIANT_REUSABLE, initialKey,
                            Long.MAX_VALUE, initialKey);
                }
                // A damageable item can return the same key due to an Unbreaking roll
                // or another contextual rule. Never cache that random result as an
                // infinite catalyst: the remainder has to be proven to be an
                // unconditional identity first. ProjectE's Philosopher's Stone stores
                // its charge in the damage value and returns stack.copy() for every
                // state, so the proof accepts it while random remainders stay rejected.
                if (hasStableSelfRemainder(input, itemKey, template, level)) {
                    return new Analysis(Mode.INVARIANT_REUSABLE, initialKey,
                            Long.MAX_VALUE, initialKey);
                }
                return unsupported(initialKey);
            }

            if (!isDeterministicDamageCandidate(initialKey, level)
                    || !isExactDamageStep(initialKey, firstRemainder, level)) {
                return unsupported(initialKey);
            }

            long limit = Math.min(requestedCrafts, MAX_DETERMINISTIC_TRANSITIONS);
            long completed = 1;
            AEKey current = firstRemainder;
            while (completed < limit) {
                if (!input.isValid(current, level)) {
                    break;
                }
                AEKey next = input.getRemainingKey(current);
                completed++;
                if (next == null) {
                    return new Analysis(Mode.DETERMINISTIC_DAMAGE, initialKey,
                            completed, null);
                }
                if (!isExactDamageStep(current, next, level)) {
                    return unsupported(initialKey);
                }
                current = next;
            }

            return new Analysis(Mode.DETERMINISTIC_DAMAGE, initialKey,
                    completed, current);
        } catch (RuntimeException exception) {
            return unsupported(initialKey);
        }
    }

    private static boolean isExactDamageStep(AEKey current, AEKey next, Level level) {
        if (!(current instanceof AEItemKey currentItem)
                || !(next instanceof AEItemKey nextItem)) {
            return false;
        }

        ItemStack currentStack = currentItem.toStack();
        if (!currentStack.isDamageableItem()
                || hasUnbreaking(currentStack, level)
                || currentStack.getDamageValue() == Integer.MAX_VALUE) {
            return false;
        }

        ItemStack expected = currentStack.copy();
        expected.setCount(1);
        expected.setDamageValue(currentStack.getDamageValue() + 1);
        AEItemKey expectedKey = AEItemKey.of(expected);
        return expectedKey != null && expectedKey.equals(nextItem);
    }

    private static boolean isDeterministicDamageCandidate(AEKey key, Level level) {
        if (!(key instanceof AEItemKey itemKey)) {
            return false;
        }
        ItemStack stack = itemKey.toStack();
        return hasFiniteMutableDurability(stack)
                && stack.hasCraftingRemainingItem()
                && !hasUnbreaking(stack, level);
    }

    /**
     * Damage states an identity probe may visit. The state in use is always probed
     * first, so a bounded sweep never leaves the actually crafted state unproven.
     */
    static final int MAX_IDENTITY_PROBES = 256;

    private static final int MAX_CACHED_IDENTITY_PROBES = 256;
    private static final Map<IPatternDetails.IInput, Map<IdentityProbeKey, Boolean>>
            IDENTITY_PROBES = Collections.synchronizedMap(new IdentityHashMap<>());

    private record IdentityProbeKey(AEKey key, int damage, int maxDamage,
            ResourceKey<Level> dimension) {
    }

    /**
     * Proves that a damageable input returns itself unconditionally: every probed
     * valid damage state must remain unchanged and must resolve to the same
     * remainder twice in a row, which rules out Unbreaking rolls and other
     * per-call randomness. The result is memoized per pattern input, key, damage
     * state and dimension, because planning retries analyze the same input
     * repeatedly.
     */
    private static boolean hasStableSelfRemainder(IPatternDetails.IInput input,
            AEItemKey itemKey, ItemStack template, Level level) {
        if (hasUnbreaking(template, level)) {
            return false;
        }
        var probeKey = new IdentityProbeKey(itemKey, template.getDamageValue(),
                template.getMaxDamage(), level.dimension());
        synchronized (IDENTITY_PROBES) {
            Map<IdentityProbeKey, Boolean> cached = IDENTITY_PROBES.get(input);
            if (cached != null) {
                Boolean known = cached.get(probeKey);
                if (known != null) {
                    return known;
                }
            }
        }

        boolean stable = probeSelfRemainder(input, itemKey, template, level);
        synchronized (IDENTITY_PROBES) {
            if (IDENTITY_PROBES.size() >= MAX_CACHED_IDENTITY_PROBES
                    && !IDENTITY_PROBES.containsKey(input)) {
                IDENTITY_PROBES.clear();
            }
            IDENTITY_PROBES.computeIfAbsent(input, ignored -> new HashMap<>())
                    .put(probeKey, stable);
        }
        return stable;
    }

    private static boolean probeSelfRemainder(IPatternDetails.IInput input,
            AEItemKey itemKey, ItemStack template, Level level) {
        int maxDamage = Math.max(0, template.getMaxDamage() - 1);
        int[] states = identityProbeDamageStates(
                template.getDamageValue(), maxDamage, MAX_IDENTITY_PROBES);
        int probed = 0;
        for (int damage : states) {
            ItemStack state = template.copy();
            state.setCount(1);
            state.setDamageValue(damage);
            AEItemKey stateKey = AEItemKey.of(state);
            if (stateKey == null || !input.isValid(stateKey, level)) {
                continue;
            }
            if (!isSelfRemainder(stateKey, input.getRemainingKey(stateKey))
                    || !isSelfRemainder(stateKey, input.getRemainingKey(stateKey))) {
                return false;
            }
            probed++;
        }
        return probed > 0;
    }

    private static boolean isSelfRemainder(AEKey state, AEKey remainder) {
        if (!(state instanceof AEItemKey stateItem)
                || !(remainder instanceof AEItemKey remainderItem)) {
            return false;
        }
        ItemStack stateStack = stateItem.toStack();
        ItemStack remainderStack = remainderItem.toStack();
        return stateStack.getDamageValue() == remainderStack.getDamageValue()
                && ItemStack.isSameItemSameTags(stateStack, remainderStack);
    }

    /**
     * Damage states visited by an identity probe: the state in use first, then the
     * whole usable range when it fits into {@code limit}, otherwise an evenly
     * spaced sample including both ends. Duplicates collapse and the result never
     * exceeds {@code limit}.
     */
    static int[] identityProbeDamageStates(int currentDamage, int maxDamage, int limit) {
        if (limit <= 0 || maxDamage < 0) {
            return new int[0];
        }
        var seen = new LinkedHashSet<Integer>();
        seen.add(Math.max(0, Math.min(currentDamage, maxDamage)));
        if (maxDamage + 1 <= limit) {
            for (int damage = 0; damage <= maxDamage; damage++) {
                seen.add(damage);
            }
        } else {
            seen.add(0);
            seen.add(maxDamage);
            int samples = limit - seen.size();
            for (int step = 1; step <= samples; step++) {
                seen.add((int) ((long) maxDamage * step / (samples + 1)));
            }
        }
        int[] result = new int[Math.min(limit, seen.size())];
        int index = 0;
        for (int damage : seen) {
            if (index == result.length) {
                break;
            }
            result[index++] = damage;
        }
        return result;
    }

    private static boolean hasFiniteMutableDurability(ItemStack stack) {
        return stack.isDamageableItem()
                && stack.getMaxDamage() > 0
                && !(stack.hasTag() && stack.getTag().getBoolean("Unbreakable"));
    }

    private static boolean hasUnbreaking(ItemStack stack, Level level) {
        var enchantments = stack.getAllEnchantments();
        for (var enchantment : enchantments.keySet()) {
            if (enchantment == Enchantments.UNBREAKING) {
                return true;
            }
        }
        return false;
    }

    private static Analysis unsupported(@Nullable AEKey initialKey) {
        return new Analysis(Mode.UNSUPPORTED, initialKey, 0, null);
    }
}
