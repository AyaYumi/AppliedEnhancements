package com.appliedenhancements.network;

import appeng.api.stacks.AEKey;
import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.ae2.AelisPlanGraphMenuBridge;
import com.appliedenhancements.plan.AelisPlanGraph;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record AelisPlanGraphPayload(int containerId, int request, AelisPlanGraph graph)
        implements CustomPacketPayload {
    private static final int MAX_BYTES = 524_288;
    public static final Type<AelisPlanGraphPayload> TYPE = new Type<>(AppliedEnhancements.id("plan_graph"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AelisPlanGraphPayload> STREAM_CODEC =
            StreamCodec.of(AelisPlanGraphPayload::encode, AelisPlanGraphPayload::decode);

    public AelisPlanGraphPayload {
        if (containerId < 0 || request <= 0) throw new IllegalArgumentException("Invalid graph response");
        Objects.requireNonNull(graph, "graph");
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void encode(RegistryFriendlyByteBuf buffer, AelisPlanGraphPayload packet) {
        int start = buffer.writerIndex();
        buffer.writeVarInt(packet.containerId);
        buffer.writeVarInt(packet.request);
        buffer.writeBoolean(packet.graph.complete());
        buffer.writeVarInt(packet.graph.root());
        buffer.writeVarInt(packet.graph.keys().size());
        for (var key : packet.graph.keys()) {
            AEKey.writeKey(buffer, key);
            if (buffer.writerIndex() - start > MAX_BYTES) throw new IllegalArgumentException("Graph key data exceeds budget");
        }
        buffer.writeVarInt(packet.graph.edges().size());
        for (var edge : packet.graph.edges()) {
            buffer.writeVarInt(edge.consumer());
            buffer.writeVarInt(edge.input());
            buffer.writeBoolean(edge.alternative());
        }
        if (buffer.writerIndex() - start > MAX_BYTES) throw new IllegalArgumentException("Graph exceeds budget");
    }

    private static AelisPlanGraphPayload decode(RegistryFriendlyByteBuf buffer) {
        if (buffer.readableBytes() > MAX_BYTES) throw new DecoderException("Graph exceeds budget");
        try {
            int menu = buffer.readVarInt();
            int request = buffer.readVarInt();
            boolean complete = buffer.readBoolean();
            int root = buffer.readVarInt();
            int count = buffer.readVarInt();
            if (count < 0 || count > AelisPlanGraph.MAX_NODES) throw new DecoderException("Invalid graph key count");
            var keys = new ArrayList<AEKey>(count);
            for (int i = 0; i < count; i++) keys.add(Objects.requireNonNull(AEKey.readKey(buffer)));
            int edgeCount = buffer.readVarInt();
            if (edgeCount < 0 || edgeCount > AelisPlanGraph.MAX_EDGES) throw new DecoderException("Invalid graph edge count");
            var edges = new ArrayList<AelisPlanGraph.Edge>(edgeCount);
            for (int i = 0; i < edgeCount; i++) edges.add(new AelisPlanGraph.Edge(
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean()));
            return new AelisPlanGraphPayload(menu, request, new AelisPlanGraph(keys, edges, root, complete));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new DecoderException("Invalid plan graph", failure);
        }
    }

    public static void handle(AelisPlanGraphPayload packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var menu = context.player().containerMenu;
            if (menu.containerId == packet.containerId && menu instanceof AelisPlanGraphMenuBridge bridge) {
                bridge.appliedenhancements$acceptPlanGraph(packet.request, packet.graph);
            }
        });
    }
}
