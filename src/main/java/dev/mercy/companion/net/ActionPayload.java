package dev.mercy.companion.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** An intent only. Ownership, capacity and lifecycle remain server-authoritative. */
public record ActionPayload(String action, int slot) implements CustomPacketPayload {
    public static final Type<ActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mercy_companions", "action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.of(
        (buf, value) -> { buf.writeUtf(value.action(), 32); buf.writeVarInt(value.slot()); },
        buf -> new ActionPayload(buf.readUtf(32), buf.readVarInt())
    );
    @Override public Type<ActionPayload> type() { return TYPE; }
}
