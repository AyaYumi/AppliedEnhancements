package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import org.junit.jupiter.api.Test;

class ExternalCraftingPlanLongSafetyTest {
    private static final TestKeyType KEY_TYPE = new TestKeyType();
    private static final TestKey OUTPUT = new TestKey("output");
    private static final TestKey CONTAINER = new TestKey("container");

    @Test
    void arbitraryExternalPlanCannotOverflowPatternOutputProduct() {
        ICraftingPlan safe = plan(Map.of(pattern("safe", 1), Long.MAX_VALUE));
        assertDoesNotThrow(() -> NativeCraftingLongSafety.validatePlan(safe));

        ICraftingPlan unsafe = plan(Map.of(
                pattern("unsafe", 2), Long.MAX_VALUE / 2 + 1));
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.validatePlan(unsafe));
    }

    @Test
    void arbitraryExternalPlanCannotOverflowGrossOutputAcrossPatterns() {
        ICraftingPlan unsafe = plan(Map.of(
                pattern("first", 1), Long.MAX_VALUE,
                pattern("second", 1), 1L));

        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.validatePlan(unsafe));
    }

    @Test
    void arbitraryExternalPlanCannotOverflowCraftingPlanSummary() {
        ICraftingPlan storedOverflow = plan(
                Map.of(),
                0,
                new GenericStack(OUTPUT, 1),
                counter(Long.MAX_VALUE),
                new KeyCounter(),
                counter(1));
        assertUnsafe(storedOverflow);

        ICraftingPlan craftingOverflow = plan(
                Map.of(pattern("crafted", 1), 1L),
                0,
                new GenericStack(OUTPUT, 1),
                new KeyCounter(),
                counter(Long.MAX_VALUE),
                new KeyCounter());
        assertUnsafe(craftingOverflow);
    }

    @Test
    void malformedLongFieldsAndCountersAreRejectedAtThePlanBoundary() {
        var finalOutput = new GenericStack(OUTPUT, 1);
        var empty = new KeyCounter();

        assertUnsafe(plan(Map.of(), -1, finalOutput, empty, empty, empty));
        assertUnsafe(plan(
                Map.of(), 0, new GenericStack(OUTPUT, 0), empty, empty, empty));

        assertUnsafe(plan(Map.of(), 0, finalOutput, null, empty, empty));
        assertUnsafe(plan(Map.of(), 0, finalOutput, empty, null, empty));
        assertUnsafe(plan(Map.of(), 0, finalOutput, empty, empty, null));

        assertUnsafe(plan(Map.of(), 0, finalOutput, counter(-1), empty, empty));
        assertUnsafe(plan(Map.of(), 0, finalOutput, empty, counter(-1), empty));
        assertUnsafe(plan(Map.of(), 0, finalOutput, empty, empty, counter(-1)));
    }

    @Test
    void patternInputRisksAreRejectedBeforeInitialCpuExtraction() {
        var overflowingTemplateProduct = pattern(
                "input-product",
                1,
                input(Long.MAX_VALUE / 2 + 1, 2, null));
        assertUnsafe(plan(Map.of(overflowingTemplateProduct, 1L)));

        var overflowingContainerTotal = pattern(
                "container-total",
                1,
                input(Long.MAX_VALUE, 1, OUTPUT),
                input(1, 1, OUTPUT));
        assertUnsafe(plan(Map.of(overflowingContainerTotal, 1L)));
    }

    @Test
    void reusableSelfReturningInputRemainsSafeAtLongMaxCraftCount() {
        var reusablePattern = pattern(
                "reusable-tool",
                1,
                input(1, 1, CONTAINER));
        ICraftingPlan plan = plan(
                Map.of(reusablePattern, Long.MAX_VALUE),
                0,
                new GenericStack(OUTPUT, Long.MAX_VALUE),
                counter(CONTAINER, 1),
                new KeyCounter(),
                new KeyCounter());

        assertDoesNotThrow(() -> NativeCraftingLongSafety.validatePlan(plan));
    }

    @Test
    void cpuVisibleTotalsAreSafeBeforeAnyExtractedInputIsConsumed() {
        ICraftingPlan unsafe = plan(
                Map.of(pattern("pending-output", 1), 1L),
                0,
                new GenericStack(OUTPUT, 1),
                counter(Long.MAX_VALUE),
                new KeyCounter(),
                new KeyCounter());

        assertUnsafe(unsafe);
    }

    private static ICraftingPlan plan(Map<IPatternDetails, Long> patternTimes) {
        return plan(
                patternTimes,
                0,
                new GenericStack(OUTPUT, 1),
                new KeyCounter(),
                new KeyCounter(),
                new KeyCounter());
    }

    private static ICraftingPlan plan(
            Map<IPatternDetails, Long> patternTimes,
            long bytes,
            GenericStack finalOutput,
            KeyCounter usedItems,
            KeyCounter emittedItems,
            KeyCounter missingItems) {
        return new ICraftingPlan() {
            @Override
            public GenericStack finalOutput() {
                return finalOutput;
            }

            @Override
            public long bytes() {
                return bytes;
            }

            @Override
            public boolean simulation() {
                return false;
            }

            @Override
            public boolean multiplePaths() {
                return false;
            }

            @Override
            public KeyCounter usedItems() {
                return usedItems;
            }

            @Override
            public KeyCounter emittedItems() {
                return emittedItems;
            }

            @Override
            public KeyCounter missingItems() {
                return missingItems;
            }

            @Override
            public Map<IPatternDetails, Long> patternTimes() {
                return patternTimes;
            }
        };
    }

    private static IPatternDetails pattern(
            String id, long outputAmount, IPatternDetails.IInput... inputs) {
        return new IPatternDetails() {
            @Override
            public AEItemKey getDefinition() {
                return null;
            }

            @Override
            public IInput[] getInputs() {
                return inputs;
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(OUTPUT, outputAmount));
            }

            @Override
            public String toString() {
                return id;
            }
        };
    }

    private static IPatternDetails.IInput input(
            long multiplier, long possibleAmount, AEKey remainingKey) {
        return new IPatternDetails.IInput() {
            @Override
            public GenericStack[] getPossibleInputs() {
                return new GenericStack[] { new GenericStack(OUTPUT, possibleAmount) };
            }

            @Override
            public long getMultiplier() {
                return multiplier;
            }

            @Override
            public boolean isValid(AEKey input, Level level) {
                return true;
            }

            @Override
            public AEKey getRemainingKey(AEKey template) {
                return remainingKey;
            }
        };
    }

    private static KeyCounter counter(long amount) {
        return counter(OUTPUT, amount);
    }

    private static KeyCounter counter(AEKey key, long amount) {
        var counter = new KeyCounter();
        counter.set(key, amount);
        return counter;
    }

    private static void assertUnsafe(ICraftingPlan plan) {
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.validatePlan(plan));
    }

    private static final class TestKey extends AEKey {
        private final String id;

        private TestKey(String id) {
            this.id = id;
        }

        @Override
        public AEKeyType getType() {
            return KEY_TYPE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag(HolderLookup.Provider registries) {
            return new CompoundTag();
        }

        @Override
        public Object getPrimaryKey() {
            return this;
        }

        @Override
        public ResourceLocation getId() {
            return ResourceLocation.fromNamespaceAndPath("test", id);
        }

        @Override
        public void writeToPacket(RegistryFriendlyByteBuf data) {
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal(id);
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        }

        @Override
        public boolean hasComponents() {
            return false;
        }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(
                    ResourceLocation.fromNamespaceAndPath("test", "key"),
                    TestKey.class,
                    Component.literal("Test Key"));
        }

        @Override
        public MapCodec<? extends AEKey> codec() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AEKey readFromPacket(RegistryFriendlyByteBuf input) {
            return null;
        }
    }
}
