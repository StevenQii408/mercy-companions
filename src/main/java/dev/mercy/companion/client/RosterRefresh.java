package dev.mercy.companion.client;

import dev.mercy.companion.net.ActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** Shares the tooltip and marker refresh budget: at most one request per second. */
public final class RosterRefresh {
    private static ClientLevel lastLevel;
    private static long lastRequest=Long.MIN_VALUE/2;
    private RosterRefresh() {}

    public static boolean request() {
        Minecraft client=Minecraft.getInstance();
        if(client.level==null||client.player==null||!client.player.isAlive()
                || !ClientPlayNetworking.canSend(ActionPayload.TYPE))return false;
        if(lastLevel!=client.level){lastLevel=client.level;lastRequest=Long.MIN_VALUE/2;}
        long now=client.level.getGameTime();
        if(now>=lastRequest&&now-lastRequest<20)return false;
        lastRequest=now;
        ClientPlayNetworking.send(new ActionPayload("refresh",-1));
        return true;
    }
    public static void clear() {lastLevel=null;lastRequest=Long.MIN_VALUE/2;}
}
