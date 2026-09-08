package com.appliedenhancements.test;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class TestAEKey extends AEKey {
    private final ResourceLocation id;

    public TestAEKey(String path) {
        this.id = new ResourceLocation("test", path);
    }

    @Override
    public AEKeyType getType() {
        return null;
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
        return id;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public void writeToPacket(FriendlyByteBuf buffer) {
    }

    @Override
    protected Component computeDisplayName() {
        return Component.literal(id.toString());
    }

    @Override
    public void addDrops(
            long amount, List<ItemStack> drops, Level level, BlockPos pos) {
    }
}
