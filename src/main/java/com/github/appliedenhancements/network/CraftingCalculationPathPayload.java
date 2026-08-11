package com.github.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.github.appliedenhancements.integration.ae2.OmniCalculationPath;
import com.github.appliedenhancements.integration.ae2.OmniCalculationPathMenuBridge;
import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record CraftingCalculationPathPayload(int containerId, OmniCalculationPath path)
        implements CustomPacketPayload {
    public static final Type<CraftingCalculationPathPayload> TYPE =
            new Type<>(AppliedEnhancements.id("crafting_calculation_path"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CraftingCalculationPathPayload> STREAM_CODEC =
            StreamCodec.of(CraftingCalculationPathPayload::encode, CraftingCalculationPathPayload::decode);

    public CraftingCalculationPathPayload {
        if (containerId < 0) {
            throw new IllegalArgumentException("containerId must be non-negative");
        }
        Objects.requireNonNull(path, "path");
    }

    public static void register(net.neoforged.neoforge.network.registration.PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, STREAM_CODEC, CraftingCalculationPathPayload::handle);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, CraftingCalculationPathPayload payload) {
        buffer.writeVarInt(payload.containerId);
        buffer.writeByte(payload.path.networkId());
    }

    private static CraftingCalculationPathPayload decode(RegistryFriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        if (containerId < 0) {
            throw new DecoderException("Crafting calculation path containerId must be non-negative");
        }
        int pathId = buffer.readUnsignedByte();
        try {
            return new CraftingCalculationPathPayload(
                    containerId,
                    OmniCalculationPath.fromNetworkId(pathId));
        } catch (IllegalArgumentException exception) {
            throw new DecoderException(exception);
        }
    }

    private static void handle(CraftingCalculationPathPayload payload, IPayloadContext context) {
        var menu = context.player().containerMenu;
        if (menu.containerId == payload.containerId
                && menu instanceof OmniCalculationPathMenuBridge bridge) {
            bridge.molecularmanipulator$setCalculationPath(payload.path);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
