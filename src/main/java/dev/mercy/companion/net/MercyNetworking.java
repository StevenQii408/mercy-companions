package dev.mercy.companion.net;

import dev.mercy.companion.CompanionService;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public final class MercyNetworking {
    private static final Map<ServerPlayer, Integer> REFRESH_TICKS = new WeakHashMap<>();
    private MercyNetworking() {}

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SelectCompanionPayload.TYPE, SelectCompanionPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BagActionPayload.TYPE, BagActionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RosterPayload.TYPE, RosterPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) -> {
            if (!payload.action().equals("refresh")) return;
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                // The personal roster also drives owner-only companion markers without a held bag.
                if (!player.isAlive()) return;
                if (player.tickCount - REFRESH_TICKS.getOrDefault(player, -100) < 10) return;
                REFRESH_TICKS.put(player, player.tickCount);
                update(player);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SelectCompanionPayload.TYPE, (payload, context) -> {
            if (payload.slot() < 0 || payload.slot() >= 7) return;
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (!player.isAlive() || player.isSpectator() || !CompanionService.hasBag(player)) return;
                // Recheck current ownership, identity, stored state and unlocked capacity in the service.
                CompanionService.select(player, payload.slot(), payload.uuid().toString());
                update(player);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(BagActionPayload.TYPE, (payload, context) -> {
            if (payload.slot() < 0 || payload.slot() >= 7) return;
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                CompanionService.bagAction(player, payload.slot(), payload.uuid().toString());
                update(player);
            });
        });
    }

    public static void update(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, RosterPayload.TYPE)) {
            ServerPlayNetworking.send(player, new RosterPayload(CompanionService.rosterJson(player)));
        }
    }
}
