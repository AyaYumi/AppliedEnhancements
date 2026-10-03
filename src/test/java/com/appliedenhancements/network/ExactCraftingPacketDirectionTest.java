package com.appliedenhancements.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.appliedenhancements.test.ForgeTestBootstrap;
import net.minecraftforge.network.ICustomPacket;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.simple.SimpleChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Exercises Forge's actual class-to-discriminator lookup, beyond codec round trips. */
class ExactCraftingPacketDirectionTest {
    private static SimpleChannel channel;

    @BeforeAll
    static void registerProductionChannel() throws Exception {
        ForgeTestBootstrap.bootstrapMinecraft();
        NetworkHandler.register();
        var field = NetworkHandler.class.getDeclaredField("CHANNEL");
        field.setAccessible(true);
        channel = (SimpleChannel) field.get(null);
    }

    @Test
    void confirmationUsesServerboundDiscriminator() {
        var value = new ExactCraftingAmountPayload(42, "9223372036854775808", true, false);
        var packet = (ICustomPacket<?>) channel.toVanillaPacket(value, NetworkDirection.PLAY_TO_SERVER);
        var buffer = packet.getInternalData();
        try {
            assertEquals(6, buffer.readUnsignedByte(), "A request must never encode as the client sync packet");
            assertEquals(value, ExactCraftingAmountPayload.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void returningToAmountScreenUsesSeparateClientboundDiscriminator() {
        for (var amount : new String[]{"2147483648", "9223372036854775808", "9".repeat(1024)}) {
            var value = new ExactCraftingAmountSyncPayload(43, amount);
            var packet = (ICustomPacket<?>) channel.toVanillaPacket(value, NetworkDirection.PLAY_TO_CLIENT);
            var buffer = packet.getInternalData();
            try {
                assertEquals(7, buffer.readUnsignedByte());
                assertEquals(value, ExactCraftingAmountSyncPayload.STREAM_CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }
}
