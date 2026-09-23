package com.appliedenhancements.mixin;

import appeng.crafting.execution.CraftingCpuLogic;
import appeng.menu.me.common.IncrementalUpdateHelper;
import appeng.menu.me.crafting.CraftingStatus;
import com.appliedenhancements.ae2.ExactCraftingStatusEntry;
import com.appliedenhancements.api.AelisExactCraftingCpu;
import java.math.BigInteger;
import java.util.HashMap;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(value = CraftingStatus.class, remap = false)
public abstract class CraftingStatusExactMixin {
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "create", at = @At(value = "INVOKE",
            target = "Lappeng/menu/me/crafting/CraftingStatusEntry;isDeleted()Z"))
    private static boolean appliedenhancements$keepCompletedSerial(appeng.menu.me.crafting.CraftingStatusEntry entry,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original,
            IncrementalUpdateHelper changes, CraftingCpuLogic logic,
            @com.llamalad7.mixinextras.sugar.Local(ordinal = 0) appeng.api.stacks.AEKey key) {
        if (logic instanceof AelisExactCraftingCpu exact && exact.aelis$getRemainingOutput() != null) {
            ((ExactCraftingStatusEntry) entry).appliedenhancements$setLastBatch(exact.aelis$getLastBatches().get(key));
            ((ExactCraftingStatusEntry) entry).appliedenhancements$setCompleted(
                    exact.aelis$getCompletedOutputs().getOrDefault(key, BigInteger.ZERO));
        }
        return original.call(entry);
    }
    @Inject(method = "create", at = @At("RETURN"))
    private static void appliedenhancements$attachPending(IncrementalUpdateHelper changes, CraftingCpuLogic logic,
            CallbackInfoReturnable<CraftingStatus> ci) {
        if (!(logic instanceof AelisExactCraftingCpu exact)) return;
        var amounts = exact.aelis$getPendingOutputs();
        var bySerial = new HashMap<Long, BigInteger>();
        var activeBySerial = new HashMap<Long, BigInteger>();
        var storedBySerial = new HashMap<Long, BigInteger>();
        exact.aelis$getStoredOutputs().forEach((key, value) -> {
            var serial = changes.getSerial(key);
            if (serial != null) storedBySerial.put(serial, value);
        });
        exact.aelis$getActiveOutputs().forEach((key, value) -> {
            var serial = changes.getSerial(key);
            if (serial != null) activeBySerial.put(serial, value);
        });
        amounts.forEach((key, value) -> {
            var serial = changes.getSerial(key);
            if (serial != null) bySerial.put(serial, value);
        });
        for (var entry : ci.getReturnValue().getEntries()) {
            ((ExactCraftingStatusEntry) entry).appliedenhancements$setStored(
                    storedBySerial.getOrDefault(entry.getSerial(), BigInteger.valueOf(entry.getStoredAmount())));
            ((ExactCraftingStatusEntry) entry).appliedenhancements$setActive(
                    activeBySerial.getOrDefault(entry.getSerial(), BigInteger.valueOf(entry.getActiveAmount())));
            var amount = bySerial.get(entry.getSerial());
            if (amount != null)
                ((ExactCraftingStatusEntry) entry).appliedenhancements$setPending(amount);
        }
    }

    // Send metadata in the very same status snapshot, indexed by stable entry serial.
    // This also covers incremental updates whose item key is deliberately omitted.
    @Inject(method = "write", at = @At("RETURN"))
    private void appliedenhancements$writeExact(FriendlyByteBuf buf, CallbackInfo ci) {
        var entries = ((CraftingStatus) (Object) this).getEntries();
        int count = 0;
        for (var entry : entries) if (((ExactCraftingStatusEntry) entry).appliedenhancements$getPending() != null) count++;
        buf.writeVarInt(count);
        int budget = 0;
        for (var entry : entries) {
            var amount = ((ExactCraftingStatusEntry) entry).appliedenhancements$getPending();
            if (amount == null) continue;
            var bytes = amount.toByteArray(); budget += bytes.length;
            if (bytes.length > 65536 || budget > 262144) throw new IllegalArgumentException("Exact status exceeds packet budget");
            buf.writeVarLong(entry.getSerial()); buf.writeByteArray(bytes);
        }
        int activeCount = 0;
        for (var entry : entries) if (((ExactCraftingStatusEntry) entry).appliedenhancements$getActive() != null) activeCount++;
        buf.writeVarInt(activeCount);
        for (var entry : entries) {
            var amount = ((ExactCraftingStatusEntry) entry).appliedenhancements$getActive();
            if (amount == null) continue;
            var bytes = amount.toByteArray(); budget += bytes.length;
            if (bytes.length > 65536 || budget > 262144) throw new IllegalArgumentException("Exact status exceeds packet budget");
            buf.writeVarLong(entry.getSerial()); buf.writeByteArray(bytes);
        }
        int storedCount = 0;
        for (var entry : entries) if (((ExactCraftingStatusEntry) entry).appliedenhancements$getStored() != null) storedCount++;
        buf.writeVarInt(storedCount);
        for (var entry : entries) {
            var amount = ((ExactCraftingStatusEntry) entry).appliedenhancements$getStored();
            if (amount == null) continue;
            var bytes = amount.toByteArray(); budget += bytes.length;
            if (bytes.length > 65536 || budget > 262144) throw new IllegalArgumentException("Exact status exceeds packet budget");
            buf.writeVarLong(entry.getSerial()); buf.writeByteArray(bytes);
        }
        int completedCount = 0;
        for (var entry : entries) if (((ExactCraftingStatusEntry) entry).appliedenhancements$getCompleted() != null) completedCount++;
        buf.writeVarInt(completedCount);
        for (var entry : entries) {
            var amount = ((ExactCraftingStatusEntry) entry).appliedenhancements$getCompleted();
            if (amount == null) continue;
            var bytes = amount.toByteArray(); budget += bytes.length;
            if (bytes.length > 65536 || budget > 262144) throw new IllegalArgumentException("Exact status exceeds packet budget");
            buf.writeVarLong(entry.getSerial()); buf.writeByteArray(bytes);
        }
        int batchCount = 0;
        for (var entry : entries) if (((ExactCraftingStatusEntry) entry).appliedenhancements$getLastBatch() != null) batchCount++;
        buf.writeVarInt(batchCount);
        for (var entry : entries) {
            var batch = ((ExactCraftingStatusEntry) entry).appliedenhancements$getLastBatch();
            if (batch == null) continue;
            buf.writeVarLong(entry.getSerial());
            var bytes = batch.outputAmount().toByteArray(); budget += bytes.length;
            if (bytes.length > 65536 || budget > 262144) throw new IllegalArgumentException("Batch status exceeds packet budget");
            buf.writeByteArray(bytes); buf.writeVarInt(batch.inputs().size());
            for (var input : batch.inputs().entrySet()) {
                appeng.api.stacks.AEKey.writeOptionalKey(buf, input.getKey());
                bytes = input.getValue().toByteArray(); budget += bytes.length;
                if (bytes.length > 65536 || budget > 262144) throw new IllegalArgumentException("Batch status exceeds packet budget");
                buf.writeByteArray(bytes);
            }
        }
    }
    @Inject(method = "read", at = @At("RETURN"))
    private static void appliedenhancements$readExact(FriendlyByteBuf buf, CallbackInfoReturnable<CraftingStatus> ci) {
        var entries = new HashMap<Long, ExactCraftingStatusEntry>();
        for (var entry : ci.getReturnValue().getEntries()) entries.put(entry.getSerial(), (ExactCraftingStatusEntry) entry);
        int count = buf.readVarInt();
        if (count < 0 || count > entries.size()) throw new io.netty.handler.codec.DecoderException("Invalid exact status count");
        int budget = 0;
        for (int i = 0; i < count; i++) {
            var entry = entries.remove(buf.readVarLong());
            var bytes = buf.readByteArray(65536); budget += bytes.length;
            if (entry == null || bytes.length == 0 || budget > 262144) throw new io.netty.handler.codec.DecoderException("Invalid exact status entry");
            var amount = new BigInteger(bytes);
            if (amount.signum() < 0) throw new io.netty.handler.codec.DecoderException("Negative exact pending amount");
            entry.appliedenhancements$setPending(amount);
        }
        entries.clear();
        for (var entry : ci.getReturnValue().getEntries()) entries.put(entry.getSerial(), (ExactCraftingStatusEntry) entry);
        int activeCount = buf.readVarInt();
        if (activeCount < 0 || activeCount > entries.size()) throw new io.netty.handler.codec.DecoderException("Invalid active status count");
        for (int i = 0; i < activeCount; i++) {
            var entry = entries.remove(buf.readVarLong());
            var bytes = buf.readByteArray(65536); budget += bytes.length;
            if (entry == null || bytes.length == 0 || budget > 262144) throw new io.netty.handler.codec.DecoderException("Invalid active status entry");
            var amount = new BigInteger(bytes);
            if (amount.signum() < 0) throw new io.netty.handler.codec.DecoderException("Negative exact active amount");
            entry.appliedenhancements$setActive(amount);
        }
        entries.clear();
        for (var entry : ci.getReturnValue().getEntries()) entries.put(entry.getSerial(), (ExactCraftingStatusEntry) entry);
        int storedCount = buf.readVarInt();
        if (storedCount < 0 || storedCount > entries.size()) throw new io.netty.handler.codec.DecoderException("Invalid stored status count");
        for (int i = 0; i < storedCount; i++) {
            var entry = entries.remove(buf.readVarLong());
            var bytes = buf.readByteArray(65536); budget += bytes.length;
            if (entry == null || bytes.length == 0 || budget > 262144) throw new io.netty.handler.codec.DecoderException("Invalid stored status entry");
            var amount = new BigInteger(bytes);
            if (amount.signum() < 0) throw new io.netty.handler.codec.DecoderException("Negative exact stored amount");
            entry.appliedenhancements$setStored(amount);
        }
        entries.clear();
        for (var entry : ci.getReturnValue().getEntries()) entries.put(entry.getSerial(), (ExactCraftingStatusEntry) entry);
        int completedCount = buf.readVarInt();
        if (completedCount < 0 || completedCount > entries.size()) throw new io.netty.handler.codec.DecoderException("Invalid completed status count");
        for (int i = 0; i < completedCount; i++) {
            var entry = entries.remove(buf.readVarLong());
            var bytes = buf.readByteArray(65536); budget += bytes.length;
            if (entry == null || bytes.length == 0 || budget > 262144) throw new io.netty.handler.codec.DecoderException("Invalid completed status entry");
            var amount = new BigInteger(bytes);
            if (amount.signum() < 0) throw new io.netty.handler.codec.DecoderException("Negative completed amount");
            entry.appliedenhancements$setCompleted(amount);
        }
        entries.clear();
        for (var entry : ci.getReturnValue().getEntries()) entries.put(entry.getSerial(), (ExactCraftingStatusEntry) entry);
        int batchCount = buf.readVarInt();
        if (batchCount < 0 || batchCount > entries.size()) throw new io.netty.handler.codec.DecoderException("Invalid batch status count");
        for (int i = 0; i < batchCount; i++) {
            var entry = entries.remove(buf.readVarLong());
            var bytes = buf.readByteArray(65536); budget += bytes.length;
            if (entry == null || bytes.length == 0 || budget > 262144) throw new io.netty.handler.codec.DecoderException("Invalid batch status entry");
            var amount = new BigInteger(bytes);
            int inputCount = buf.readVarInt();
            if (inputCount < 0 || inputCount > 64) throw new io.netty.handler.codec.DecoderException("Invalid batch input count");
            var inputs = new java.util.LinkedHashMap<appeng.api.stacks.AEKey, BigInteger>();
            for (int j = 0; j < inputCount; j++) {
                var key = appeng.api.stacks.AEKey.readOptionalKey(buf);
                bytes = buf.readByteArray(65536); budget += bytes.length;
                if (key == null || bytes.length == 0 || budget > 262144) throw new io.netty.handler.codec.DecoderException("Invalid batch input");
                var value = new BigInteger(bytes);
                if (value.signum() <= 0 || inputs.putIfAbsent(key, value) != null) throw new io.netty.handler.codec.DecoderException("Invalid batch input amount");
            }
            if (amount.signum() <= 0) throw new io.netty.handler.codec.DecoderException("Invalid batch output amount");
            entry.appliedenhancements$setLastBatch(new com.appliedenhancements.api.AelisCraftingBatch(amount, inputs));
        }
    }
}
