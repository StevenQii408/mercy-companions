package dev.mercy.companion.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RosterPayload(String json) implements CustomPacketPayload {
    public static final Type<RosterPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mercy_companions", "roster"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RosterPayload> CODEC = StreamCodec.of(
        (buf, value) -> buf.writeUtf(value.json(), 32767),
        buf -> new RosterPayload(buf.readUtf(32767))
    );
    @Override public Type<RosterPayload> type() { return TYPE; }
}
