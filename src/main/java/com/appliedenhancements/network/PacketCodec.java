package com.appliedenhancements.network;

import java.util.function.BiConsumer;
import java.util.function.Function;

/** Small codec pair shared by Forge packet registration and round-trip tests. */
public record PacketCodec<B, T>(BiConsumer<B, T> encoder, Function<B, T> decoder) {
    public static <B, T> PacketCodec<B, T> of(BiConsumer<B, T> encoder, Function<B, T> decoder) {
        return new PacketCodec<>(encoder, decoder);
    }
    public void encode(B buffer, T value) { encoder.accept(buffer, value); }
    public T decode(B buffer) { return decoder.apply(buffer); }
}
