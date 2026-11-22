package dev.mercy.companion.net;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The server decides whether the owned companion is recalled or moved to the bag's top. */
public record BagActionPayload(int slot, UUID uuid) implements CustomPacketPayload {
    public static final Type<BagActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mercy_companions", "bag_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BagActionPayload> CODEC = StreamCodec.of(
        (buf, value) -> { buf.writeVarInt(value.slot()); buf.writeUUID(value.uuid()); },
        buf -> new BagActionPayload(buf.readVarInt(),buf.readUUID())
    );
    @Override public Type<BagActionPayload> type() { return TYPE; }
}
