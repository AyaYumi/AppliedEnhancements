package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;

class AelisObservedPatternSemanticsTest {
    private static final TestKeyType KEY_TYPE = new TestKeyType();
    private static final TestKey FEEDBACK = new TestKey("feedback");
    private static final TestKey AUXILIARY = new TestKey("auxiliary");

    @Test
    void acceptsStableUnknownPatternWithoutInspectingItsJavaType() {
        IPatternDetails first = new FirstUnknownPattern();
        IPatternDetails second = new SecondUnknownPattern();

        var snapshot = AelisObservedPatternSemantics.captureStable(first);

        assertNotNull(snapshot);
        assertTrue(snapshot.matches(second));
    }

    @Test
    void rejectsAnOutputThatChangesBetweenObservations() {
        assertNull(AelisObservedPatternSemantics.captureStable(
                new ChangingOutputPattern()));
    }

    @Test
    void rejectsAnInputRemainderThatChangesBetweenObservations() {
        assertNull(AelisObservedPatternSemantics.captureStable(
                new ChangingRemainderPattern()));
    }

    private abstract static class StableUnknownPattern implements IPatternDetails {
        @Override
        public AEItemKey getDefinition() {
            return null;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[] {
                    input(FEEDBACK, 1, null),
                    input(AUXILIARY, 8, null)
            };
        }

        @Override
        public GenericStack[] getOutputs() {
            return List.of(new GenericStack(FEEDBACK, 9)).toArray(GenericStack[]::new);
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return true;
        }
    }

    private static final class FirstUnknownPattern extends StableUnknownPattern {
    }

    private static final class SecondUnknownPattern extends StableUnknownPattern {
    }

    private static final class ChangingOutputPattern extends StableUnknownPattern {
        private long amount = 8;

        @Override
        public GenericStack[] getOutputs() {
            amount++;
            return List.of(new GenericStack(FEEDBACK, amount)).toArray(GenericStack[]::new);
        }
    }

    private static final class ChangingRemainderPattern extends StableUnknownPattern {
        private boolean returnContainer;

        @Override
        public IInput[] getInputs() {
            return new IInput[] { new IInput() {
                @Override
                public GenericStack[] getPossibleInputs() {
                    return new GenericStack[] { new GenericStack(FEEDBACK, 1) };
                }

                @Override
                public long getMultiplier() {
                    return 1;
                }

                @Override
                public boolean isValid(AEKey input, Level level) {
                    return FEEDBACK.equals(input);
                }

                @Override
                public AEKey getRemainingKey(AEKey template) {
                    returnContainer = !returnContainer;
                    return returnContainer ? AUXILIARY : null;
                }
            } };
        }
    }

    private static IPatternDetails.IInput input(
            AEKey key, long amount, AEKey remainingKey) {
        return new IPatternDetails.IInput() {
            @Override
            public GenericStack[] getPossibleInputs() {
                return new GenericStack[] { new GenericStack(key, amount) };
            }

            @Override
            public long getMultiplier() {
                return 1;
            }

            @Override
            public boolean isValid(AEKey input, Level level) {
                return key.equals(input);
            }

            @Override
            public AEKey getRemainingKey(AEKey template) {
                return remainingKey;
            }
        };
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
        public CompoundTag toTag() {
            return new CompoundTag();
        }

        @Override
        public Object getPrimaryKey() {
            return this;
        }

        @Override
        public ResourceLocation getId() {
            return new ResourceLocation("test", id);
        }

        @Override
        public void writeToPacket(FriendlyByteBuf data) {
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal(id);
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops,
                Level level, BlockPos pos) {
        }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(
                    new ResourceLocation("test", "key"),
                    TestKey.class, Component.literal("Test Key"));
        }

        @Override
        public AEKey loadKeyFromTag(CompoundTag tag) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AEKey readFromPacket(FriendlyByteBuf input) {
            return null;
        }
    }
}
