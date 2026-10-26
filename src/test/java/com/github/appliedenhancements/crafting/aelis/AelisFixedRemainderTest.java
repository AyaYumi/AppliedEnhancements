package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AelisFixedRemainderTest {
    private static AEItemKey dragonBreath;
    private static AEItemKey glassBottle;
    private static AEItemKey bowl;
    private static AEItemKey ironPickaxe;

    @BeforeAll
    static void bootstrap() {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        dragonBreath = AEItemKey.of(Items.DRAGON_BREATH);
        glassBottle = AEItemKey.of(Items.GLASS_BOTTLE);
        bowl = AEItemKey.of(Items.BOWL);
        ironPickaxe = AEItemKey.of(Items.IRON_PICKAXE);
    }

    @Test
    void acceptsDragonBreathReturningGlassBottle() {
        assertTrue(dragonBreath.getReadOnlyStack().hasCraftingRemainingItem());
        assertEquals(glassBottle,
                AelisFixedRemainder.inspect(exact(dragonBreath, glassBottle),
                        dragonBreath, 1, null));
    }

    @Test
    void rejectsDeclaredRemainderDifferentFromVanilla() {
        assertNull(AelisFixedRemainder.inspect(exact(dragonBreath, bowl),
                dragonBreath, 1, null));
    }

    @Test
    void rejectsSameKeyCatalyst() {
        assertNull(AelisFixedRemainder.inspect(exact(dragonBreath, dragonBreath),
                dragonBreath, 1, null));
    }

    @Test
    void rejectsNullArguments() {
        assertNull(AelisFixedRemainder.inspect(null, dragonBreath, 1, null));
        assertNull(AelisFixedRemainder.inspect(exact(dragonBreath, glassBottle),
                null, 1, null));
        assertNull(AelisFixedRemainder.inspect(exact(dragonBreath, null),
                dragonBreath, 1, null));
    }

    @Test
    void rejectsMultipleAndFuzzyChoices() {
        var multiple = new FakeInput(
                () -> new GenericStack[] {
                        new GenericStack(dragonBreath, 1),
                        new GenericStack(dragonBreath, 1)
                },
                () -> 1L, key -> true, () -> glassBottle);
        var fuzzy = new FakeInput(
                () -> new GenericStack[] {
                        new GenericStack(dragonBreath, 1),
                        new GenericStack(glassBottle, 1)
                },
                () -> 1L, key -> true, () -> glassBottle);
        assertNull(AelisFixedRemainder.inspect(multiple, dragonBreath, 1, null));
        assertNull(AelisFixedRemainder.inspect(fuzzy, dragonBreath, 1, null));
    }

    @Test
    void rejectsAmountsOtherThanOne() {
        assertNull(AelisFixedRemainder.inspect(
                exact(dragonBreath, 2, 1, true, glassBottle),
                dragonBreath, 1, null));
        assertNull(AelisFixedRemainder.inspect(
                exact(dragonBreath, 1, 1, true, glassBottle),
                dragonBreath, 2, null));
    }

    @Test
    void rejectsInvalidInput() {
        assertNull(AelisFixedRemainder.inspect(
                exact(dragonBreath, 1, 1, false, glassBottle),
                dragonBreath, 1, null));
    }

    @Test
    void rejectsChangingRemainderAcrossObservations() {
        var calls = new AtomicInteger();
        var changing = new FakeInput(
                () -> new GenericStack[] { new GenericStack(dragonBreath, 1) },
                () -> 1L, key -> true,
                () -> calls.getAndIncrement() == 0 ? glassBottle : bowl);
        assertNull(AelisFixedRemainder.inspect(changing, dragonBreath, 1, null));
    }

    @Test
    void rejectsChangingLayoutAcrossObservations() {
        var calls = new AtomicInteger();
        var changing = new FakeInput(
                () -> new GenericStack[] { new GenericStack(dragonBreath, 1) },
                () -> calls.getAndIncrement() == 0 ? 1L : 2L,
                key -> true, () -> glassBottle);
        assertNull(AelisFixedRemainder.inspect(changing, dragonBreath, 1, null));
    }

    @Test
    void rejectsNonPositiveMultiplier() {
        assertNull(AelisFixedRemainder.inspect(
                exact(dragonBreath, 1, 0, true, glassBottle),
                dragonBreath, 1, null));
    }

    @Test
    void rejectsDamageableSelectedItem() {
        assertNull(AelisFixedRemainder.inspect(exact(ironPickaxe, glassBottle),
                ironPickaxe, 1, null));
    }

    @Test
    void rejectsDamageableRemainder() {
        assertNull(AelisFixedRemainder.inspect(exact(dragonBreath, ironPickaxe),
                dragonBreath, 1, null));
    }

    private static FakeInput exact(AEKey key, AEKey remainder) {
        return exact(key, 1, 1, true, remainder);
    }

    private static FakeInput exact(AEKey key, long amount, long multiplier,
            boolean valid, AEKey remainder) {
        return new FakeInput(
                () -> new GenericStack[] { new GenericStack(key, amount) },
                () -> multiplier, ignored -> valid, () -> remainder);
    }

    private static final class FakeInput implements IPatternDetails.IInput {
        private final Supplier<GenericStack[]> choices;
        private final LongSupplier multiplier;
        private final Predicate<AEKey> valid;
        private final Supplier<AEKey> remainder;

        private FakeInput(Supplier<GenericStack[]> choices, LongSupplier multiplier,
                Predicate<AEKey> valid, Supplier<AEKey> remainder) {
            this.choices = choices;
            this.multiplier = multiplier;
            this.valid = valid;
            this.remainder = remainder;
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return choices.get();
        }

        @Override
        public long getMultiplier() {
            return multiplier.getAsLong();
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return valid.test(input);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return remainder.get();
        }
    }
}
