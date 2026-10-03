package com.appliedenhancements.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.appliedenhancements.runtime.NativeCraftingLongSafety;
import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class InfiniteStorageSupportTest {
    private static final TestKeyType KEY_TYPE = new TestKeyType();
    private static final TestKey WATER = new TestKey("water");
    private static final TestKey INFINITE = new TestKey("infinite");

    @Test
    void replacesUnmarkedSentinelWithSourceAwarePhysicalAmount() {
        var list = new KeyCounter();
        list.set(WATER, Long.MAX_VALUE);
        list.set(INFINITE, Long.MAX_VALUE);
        var storage = new ProbeStorage(Map.of(WATER, 42L));

        InfiniteStorageSupport.reconcilePlanningSnapshot(
                list, storage, IActionSource.empty(), Set.of(INFINITE), true);

        assertEquals(42, list.get(WATER));
        assertEquals(Long.MAX_VALUE, list.get(INFINITE));
        assertEquals(1, storage.probes);
        assertFalse(InfiniteStorageSupport.isPhysicalExtract());
        assertEquals(1392, NativeCraftingLongSafety.addNonNegative(
                list.get(WATER), 1350, "simulated inventory amount"));
    }

    @Test
    void removesUnextractableSentinelWithoutChangingRealMaximum() {
        var unavailable = new KeyCounter();
        unavailable.set(WATER, Long.MAX_VALUE);
        InfiniteStorageSupport.reconcilePlanningSnapshot(
                unavailable, new ProbeStorage(Map.of()), null, Set.of(), true);
        assertEquals(0, unavailable.get(WATER));
        assertTrue(unavailable.isEmpty());

        var genuineMaximum = new KeyCounter();
        genuineMaximum.set(WATER, Long.MAX_VALUE);
        InfiniteStorageSupport.reconcilePlanningSnapshot(genuineMaximum,
                new ProbeStorage(Map.of(WATER, Long.MAX_VALUE)), null, Set.of(), true);
        assertEquals(Long.MAX_VALUE, genuineMaximum.get(WATER));
        assertThrows(RuntimeException.class, () -> NativeCraftingLongSafety.addNonNegative(
                genuineMaximum.get(WATER), 1350, "simulated inventory amount"));
    }

    @Test
    void restoresPhysicalProbeAfterFailure() {
        var list = new KeyCounter();
        list.set(WATER, Long.MAX_VALUE);
        MEStorage failing = new ProbeStorage(Map.of()) {
            @Override
            public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
                assertTrue(InfiniteStorageSupport.isPhysicalExtract());
                throw new IllegalStateException("probe failed");
            }
        };
        assertThrows(IllegalStateException.class, () ->
                InfiniteStorageSupport.reconcilePlanningSnapshot(
                        list, failing, null, Set.of(), true));
        assertFalse(InfiniteStorageSupport.isPhysicalExtract());
    }

    private static class ProbeStorage implements MEStorage {
        private final Map<AEKey, Long> available;
        private int probes;

        private ProbeStorage(Map<AEKey, Long> available) {
            this.available = available;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            assertTrue(InfiniteStorageSupport.isPhysicalExtract());
            assertEquals(Actionable.SIMULATE, mode);
            assertEquals(Long.MAX_VALUE, amount);
            ++probes;
            return Math.min(amount, available.getOrDefault(what, 0L));
        }

        @Override
        public Component getDescription() {
            return Component.empty();
        }
    }

    private static final class TestKey extends AEKey {
        private final String id;

        private TestKey(String id) {
            this.id = id;
        }

        @Override public AEKeyType getType() { return KEY_TYPE; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public CompoundTag toTag(HolderLookup.Provider registries) {
            return new CompoundTag();
        }
        @Override public Object getPrimaryKey() { return this; }
        @Override public ResourceLocation getId() {
            return ResourceLocation.fromNamespaceAndPath("test", id);
        }
        @Override public void writeToPacket(RegistryFriendlyByteBuf data) {}
        @Override protected Component computeDisplayName() { return Component.literal(id); }
        @Override public void addDrops(long amount, List<ItemStack> drops,
                Level level, BlockPos pos) {}
        @Override public boolean hasComponents() { return false; }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(ResourceLocation.fromNamespaceAndPath("test", "key"),
                    TestKey.class, Component.literal("Test Key"));
        }

        @Override public MapCodec<? extends AEKey> codec() {
            throw new UnsupportedOperationException();
        }
        @Override public AEKey readFromPacket(RegistryFriendlyByteBuf input) {
            return null;
        }
    }
}
