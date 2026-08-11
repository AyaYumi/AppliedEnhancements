package com.appliedenhancements.runtime;

import java.util.HashSet;
import java.util.Map;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import org.jetbrains.annotations.ApiStatus;

/** Checked arithmetic used at AE2 native crafting-planner mutation boundaries. */
@ApiStatus.Internal
public final class NativeCraftingLongSafety {
    private NativeCraftingLongSafety() {
    }

    public static boolean exceedsConfiguredLimit(long amount, long configuredMaximum) {
        return amount > configuredMaximum;
    }

    public static long requirePositive(long value, String operation) {
        if (value <= 0) {
            throw unsafe(operation, value, 0, "requires a positive value");
        }
        return value;
    }

    public static long multiplyNonNegative(long left, long right, String operation) {
        requireNonNegative(left, operation);
        requireNonNegative(right, operation);
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            throw unsafe(operation, left, right, "multiplication overflow");
        }
    }

    public static long addNonNegative(long left, long right, String operation) {
        requireNonNegative(left, operation);
        requireNonNegative(right, operation);
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw unsafe(operation, left, right, "addition overflow");
        }
    }

    public static long addExact(long left, long right, String operation) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw unsafe(operation, left, right, "addition overflow");
        }
    }

    public static long ceilDivPositive(long dividend, long divisor, String operation) {
        requirePositive(dividend, operation);
        requirePositive(divisor, operation);
        long quotient = dividend / divisor;
        return dividend % divisor == 0 ? quotient : addNonNegative(quotient, 1, operation);
    }

    /**
     * Validates the arithmetic AE2 performs when it turns any crafting plan into
     * an executing job. This public plan boundary also covers plans returned by
     * external planners that never pass through CraftingSimulationState.
     */
    public static void validatePlan(ICraftingPlan plan) {
        plan = requirePresent(plan, "crafting plan");
        addNonNegative(plan.bytes(), 0, "crafting plan byte total");

        GenericStack finalOutput = requirePresent(plan.finalOutput(), "final crafting output");
        requirePresent(finalOutput.what(), "final crafting output key");
        requirePositive(finalOutput.amount(), "final crafting output amount");

        var summaryStored = new KeyCounter();
        var summaryCrafting = new KeyCounter();
        var maximumWaitingItems = new KeyCounter();
        var cpuTrackedItems = new KeyCounter();
        KeyCounter usedItems = requirePresent(plan.usedItems(), "used item counter");
        for (var used : usedItems) {
            AEKey key = requirePresent(used.getKey(), "used item key");
            long amount = addNonNegative(used.getLongValue(), 0, "used item amount");
            merge(summaryStored, key, amount, "summary stored item total");
            merge(cpuTrackedItems, key, amount, "CPU tracked item total");
        }
        validateAndMergeCounter(
                plan.missingItems(), summaryStored, "missing item", "summary stored item total");

        KeyCounter emittedItems = requirePresent(plan.emittedItems(), "emitted item counter");
        for (var emitted : emittedItems) {
            AEKey key = requirePresent(emitted.getKey(), "emitted item key");
            long amount = addNonNegative(
                    emitted.getLongValue(), 0, "emitted item amount");
            merge(summaryStored, key, amount, "summary stored item total");
            merge(summaryCrafting, key, amount, "summary crafting item total");
            merge(maximumWaitingItems, key, amount, "maximum waiting item total");
            merge(cpuTrackedItems, key, amount, "CPU tracked item total");
        }

        validatePatternOutputs(
                plan.patternTimes(),
                summaryCrafting,
                maximumWaitingItems,
                cpuTrackedItems,
                true);
    }

    /** Validates pattern output totals before they are exposed as a plan. */
    public static void validatePatternOutputs(Map<IPatternDetails, Long> patternTimes) {
        validatePatternOutputs(
                patternTimes,
                new KeyCounter(),
                new KeyCounter(),
                new KeyCounter(),
                false);
    }

    private static void validatePatternOutputs(
            Map<IPatternDetails, Long> patternTimes,
            KeyCounter summaryCrafting,
            KeyCounter maximumWaitingItems,
            KeyCounter cpuTrackedItems,
            boolean validateInputs) {
        patternTimes = requirePresent(patternTimes, "pattern task map");
        var grossOutputs = new KeyCounter();
        for (var task : patternTimes.entrySet()) {
            IPatternDetails pattern = requirePresent(task.getKey(), "crafting pattern");
            long crafts = addNonNegative(
                    requirePresent(task.getValue(), "pattern craft count"),
                    0,
                    "final pattern craft count");

            if (validateInputs && crafts > 0) {
                validatePatternInputs(pattern);
            }

            var outputs = requirePresent(pattern.getOutputs(), "pattern output list");
            var perCraftOutputs = new KeyCounter();
            for (var rawOutput : outputs) {
                GenericStack output = requirePresent(rawOutput, "pattern output");
                AEKey outputKey = requirePresent(output.what(), "pattern output key");
                requirePositive(output.amount(), "pattern output amount");
                merge(
                        perCraftOutputs,
                        outputKey,
                        output.amount(),
                        "matching pattern output total");
                long produced = multiplyNonNegative(
                        output.amount(), crafts, "final pending pattern output");
                multiplyNonNegative(
                        produced,
                        requirePositive(
                                output.what().getAmountPerUnit(), "output amount per unit"),
                        "crafting elapsed-time output amount");
                merge(
                        grossOutputs,
                        outputKey,
                        produced,
                        "gross pending pattern output");
                merge(
                        summaryCrafting,
                        outputKey,
                        produced,
                        "summary crafting item total");
                merge(
                        maximumWaitingItems,
                        outputKey,
                        produced,
                        "maximum waiting item total");
                merge(
                        cpuTrackedItems,
                        outputKey,
                        produced,
                        "CPU tracked item total");
            }
        }
    }

    private static void validatePatternInputs(IPatternDetails pattern) {
        var inputs = requirePresent(pattern.getInputs(), "pattern input list");
        var possibleContainerTotals = new KeyCounter();
        for (var rawInput : inputs) {
            IPatternDetails.IInput input = requirePresent(rawInput, "pattern input");
            long multiplier = requirePositive(
                    input.getMultiplier(), "pattern input multiplier");
            GenericStack[] possibleInputs = requirePresent(
                    input.getPossibleInputs(), "possible pattern inputs");
            var remainingKeys = new HashSet<AEKey>();
            for (var rawPossibleInput : possibleInputs) {
                GenericStack possibleInput = requirePresent(
                        rawPossibleInput, "possible pattern input");
                AEKey possibleInputKey = requirePresent(
                        possibleInput.what(), "possible pattern input key");
                requirePositive(possibleInput.amount(), "possible pattern input amount");
                multiplyNonNegative(
                        possibleInput.amount(),
                        multiplier,
                        "pattern input extraction total");

                AEKey remainingKey = input.getRemainingKey(possibleInputKey);
                if (remainingKey != null && remainingKeys.add(remainingKey)) {
                    merge(
                            possibleContainerTotals,
                            remainingKey,
                            multiplier,
                            "possible container item total");
                }
            }
        }
    }

    private static void validateAndMergeCounter(
            KeyCounter source,
            KeyCounter target,
            String entryName,
            String totalName) {
        source = requirePresent(source, entryName + " counter");
        for (var entry : source) {
            AEKey key = requirePresent(entry.getKey(), entryName + " key");
            long amount = addNonNegative(entry.getLongValue(), 0, entryName + " amount");
            merge(target, key, amount, totalName);
        }
    }

    private static void merge(KeyCounter target, AEKey key, long amount, String operation) {
        long total = addNonNegative(target.get(key), amount, operation);
        target.set(key, total);
    }

    public static boolean causedByUnsafeArithmetic(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof UnsafeNativeCraftingRequestException) {
                return true;
            }
        }
        return false;
    }

    private static void requireNonNegative(long value, String operation) {
        if (value < 0) {
            throw unsafe(operation, value, 0, "requires non-negative operands");
        }
    }

    private static <T> T requirePresent(T value, String operation) {
        if (value == null) {
            throw new UnsafeNativeCraftingRequestException(
                    "Rejected unsafe AE2 crafting plan in " + operation
                            + " (requires a non-null value)");
        }
        return value;
    }

    private static UnsafeNativeCraftingRequestException unsafe(
            String operation, long left, long right, String reason) {
        return new UnsafeNativeCraftingRequestException(
                "Rejected unsafe AE2 native crafting arithmetic in " + operation
                        + ": " + left + " and " + right + " (" + reason + ")");
    }

    static final class UnsafeNativeCraftingRequestException extends RuntimeException {
        UnsafeNativeCraftingRequestException(String message) {
            super(message);
        }
    }
}
