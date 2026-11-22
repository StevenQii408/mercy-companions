package dev.mercy.companion.client;

import dev.mercy.companion.net.RosterPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class MercyClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(RosterPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {BagTooltip.accept(payload.json());CompanionParticles.accept(payload.json());})
        );
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            BagTooltip.clear();CompanionParticles.clear();RosterRefresh.clear();
        });
        BagTooltip.init();
        CompanionParticles.init();
    }
}
