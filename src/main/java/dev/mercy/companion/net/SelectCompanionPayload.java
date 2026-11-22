package dev.mercy.companion.net;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Stable identity prevents a stale tooltip selecting a replacement companion. */
public record SelectCompanionPayload(int slot, UUID uuid) implements CustomPacketPayload {
    public static final Type<SelectCompanionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mercy_companions", "select_companion"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectCompanionPayload> CODEC = StreamCodec.of(
        (buf, value) -> { buf.writeVarInt(value.slot()); buf.writeUUID(value.uuid()); },
        buf -> new SelectCompanionPayload(buf.readVarInt(),buf.readUUID())
    );
    @Override public Type<SelectCompanionPayload> type() { return TYPE; }
}
