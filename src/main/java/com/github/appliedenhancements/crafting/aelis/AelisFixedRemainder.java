package com.github.appliedenhancements.crafting.aelis;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

final class AelisFixedRemainder {
    private AelisFixedRemainder() {}

    static AEKey inspect(IPatternDetails.IInput input, AEKey selectedKey,
            long selectedAmount, Level level) {
        try {
            if (input == null || selectedAmount != 1
                    || !(selectedKey instanceof AEItemKey selectedItem)) {
                return null;
            }
            ItemStack selectedStack = selectedItem.toStack();
            if (selectedStack.isEmpty() || selectedStack.isDamageableItem()
                    || !selectedStack.hasCraftingRemainingItem()) {
                return null;
            }
            ItemStack expectedStack = selectedStack.getCraftingRemainingItem();
            if (expectedStack == null || expectedStack.getCount() != 1) {
                return null;
            }
            AEItemKey expected = AEItemKey.of(expectedStack);
            if (expected == null || expected.equals(selectedItem)
                    || expected.getReadOnlyStack().isDamageableItem()) {
                return null;
            }

            Observation first = observe(input, selectedItem, level);
            if (first == null) {
                return null;
            }
            Observation second = observe(input, selectedItem, level);
            if (!first.equals(second) || !expected.equals(first.remainder())) {
                return null;
            }
            return first.remainder();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Observation observe(IPatternDetails.IInput input,
            AEItemKey selected, Level level) {
        GenericStack[] choices = input.getPossibleInputs();
        if (choices == null || choices.length != 1) {
            return null;
        }
        GenericStack choice = choices[0];
        if (choice == null || choice.what() == null || choice.amount() != 1
                || !selected.equals(choice.what())) {
            return null;
        }
        long multiplier = input.getMultiplier();
        if (multiplier <= 0 || !input.isValid(selected, level)) {
            return null;
        }
        if (!(input.getRemainingKey(selected) instanceof AEItemKey remainder)
                || remainder.equals(selected)
                || remainder.getReadOnlyStack().isDamageableItem()) {
            return null;
        }
        return new Observation(multiplier, choice, remainder);
    }

    private record Observation(long multiplier, GenericStack choice,
            AEItemKey remainder) {}
}
