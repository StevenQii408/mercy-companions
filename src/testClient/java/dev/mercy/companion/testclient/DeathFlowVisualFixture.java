package dev.mercy.companion.testclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

/** Dedicated entrypoint: observes real death/respawn with the installed addons in a disposable world. */
public final class DeathFlowVisualFixture implements ClientModInitializer {
    private final JsonArray observations=new JsonArray(), actions=new JsonArray();
    private CompletableFuture<?> pending;
    private int phase,age,captures;
    private boolean done;
    private int groundX,groundY,groundZ;

    @Override public void onInitializeClient() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }

    private void tick(Minecraft client) {
        if(!client.isGameLoadFinished())return;
        try {
            client.getWindow().setTitle("死亡流程自动测试（请勿操作，会自动关闭）");
            if(done) {
                if(captures>=observations.size()) {write(client);client.stop();}
                return;
            }
            if(++age>1200)throw new AssertionError("Death fixture timed out at phase "+phase);
            if(pending!=null) {if(!pending.isDone())return;pending.join();pending=null;}
            switch(phase) {
                case 0 -> {
                    client.getWindow().setWindowed(1280,800);
                    client.options.pauseOnLostFocus=false;client.options.guiScale().set(0);
                    if(client.gui.hud.isHidden())client.gui.hud.toggle();
                    client.resizeGui();client.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                    client.gui.toastManager().clear();
                    client.createWorldOpenFlows().openWorld("death-fixture-world",()->client.gui.setScreen(new TitleScreen(false)));
                    next();
                }
                case 1 -> {
                    if(client.player==null || client.level==null)return;
                    pending=client.getSingleplayerServer().submit(()-> {
                        var server=client.getSingleplayerServer();var player=server.getPlayerList().getPlayer(client.player.getUUID());
                        player.setGameMode(GameType.SURVIVAL);player.getAbilities().invulnerable=false;
                        player.getAbilities().flying=false;player.onUpdateAbilities();player.setHealth(20);
                        player.getFoodData().setFoodLevel(20);
                        server.overworld().getGameRules().set(GameRules.IMMEDIATE_RESPAWN,false,server);
                        server.overworld().getGameRules().set(GameRules.KEEP_INVENTORY,true,server);
                        server.overworld().getGameRules().set(GameRules.SPAWN_MOBS,false,server);
                        server.overworld().getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION,false,server);
                        groundX=player.getBlockX();groundY=Math.max(70,player.getBlockY());groundZ=player.getBlockZ();
                        var level=(net.minecraft.server.level.ServerLevel)player.level();
                        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++) {
                            level.setBlockAndUpdate(new BlockPos(groundX+x,groundY-1,groundZ+z),Blocks.STONE.defaultBlockState());
                            for(int y=0;y<=18;y++)level.setBlockAndUpdate(new BlockPos(groundX+x,groundY+y,groundZ+z),Blocks.AIR.defaultBlockState());
                        }
                        command(client,"clear MercyTester");
                        command(client,"give MercyTester mercy_companions:mercy_sword");
                        command(client,"give MercyTester mercy_companions:companion_bag");
                        command(client,"spawnpoint MercyTester "+groundX+" "+groundY+" "+groundZ);
                        command(client,"tp MercyTester "+(groundX+.5)+" "+groundY+" "+(groundZ+.5)+" 0 0");
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set day");
                    });
                    next();
                }
                case 2 -> {if(age>=60){snapshot(client,"normal-alive");fall(client,18);next();}}
                case 3 -> {if(age>=100){requireNonlethalFall(client);snapshot(client,"normal-fall-landed");click(client.options.keyInventory);next();}}
                case 4 -> {if(age>=20){snapshot(client,"inventory-open");if(client.gui.screen()!=null)client.gui.screen().onClose();next();}}
                case 5 -> {if(age>=20){snapshot(client,"inventory-closed");client.pauseGame(false);next();}}
                case 6 -> {if(age>=20){snapshot(client,"pause-open");if(client.gui.screen()!=null)client.gui.screen().onClose();next();}}
                case 7 -> {if(age>=20){snapshot(client,"pause-closed");click(client.options.keyTogglePerspective);next();}}
                case 8 -> {if(age>=20){snapshot(client,"third-person-back");click(client.options.keyTogglePerspective);next();}}
                case 9 -> {if(age>=20){snapshot(client,"third-person-front");click(client.options.keyTogglePerspective);next();}}
                case 10 -> {if(age>=20){snapshot(client,"first-person-restored");click(client.options.keyToggleGui);next();}}
                case 11 -> {if(age>=20){snapshot(client,"f1-hidden");click(client.options.keyToggleGui);next();}}
                case 12 -> {if(age>=20){snapshot(client,"f1-restored");kill(client);next();}}
                case 13 -> {if(age>=100){snapshot(client,"normal-death");respawn(client,"normal");next();}}
                case 14 -> {if(age>=80){requireAlive(client);snapshot(client,"normal-respawn");freecam(true);next();}}
                case 15 -> {if(age>=60){snapshot(client,"freecam-alive");fall(client,10);next();}}
                case 16 -> {if(age>=100){requireNonlethalFall(client);snapshot(client,"freecam-fall-landed");freecam(false);next();}}
                case 17 -> {if(age>=40){snapshot(client,"freecam-off-alive");freecam(true);next();}}
                case 18 -> {if(age>=40){snapshot(client,"freecam-before-death");kill(client);next();}}
                case 19 -> {if(age>=100){snapshot(client,"freecam-death");freecam(false);next();}}
                case 20 -> {if(age>=40){snapshot(client,"freecam-off-after-death");if(client.player.isDeadOrDying())respawn(client,"freecam");next();}}
                case 21 -> {if(age>=80){requireAlive(client);snapshot(client,"freecam-respawn");done=true;}}
                default -> throw new AssertionError("Unknown phase "+phase);
            }
        } catch(Throwable failure) {
            failure.printStackTrace();
            try {Files.writeString(client.gameDirectory.toPath().resolve("death-error.txt"),failure.toString());write(client);}catch(Exception ignored){}
            client.stop();
        }
    }

    private void next(){phase++;age=0;}
    private static void requireAlive(Minecraft client) {
        if(client.player==null || client.player.isDeadOrDying())throw new AssertionError("Respawn did not restore a live player");
    }
    private static void click(KeyMapping binding) {KeyMapping.click(InputConstants.getKey(binding.saveString()));}
    private static void command(Minecraft client,String command) {
        var server=client.getSingleplayerServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),command);
    }
    private void fall(Minecraft client,int height) {
        pending=client.getSingleplayerServer().submit(()-> {
            var player=client.getSingleplayerServer().getPlayerList().getPlayer(client.player.getUUID());
            player.setHealth(20);player.fallDistance=0;
            command(client,"tp MercyTester "+(groundX+.5)+" "+(groundY+height)+" "+(groundZ+.5)+" 0 0");
        });
    }
    private static void requireNonlethalFall(Minecraft client) {
        requireAlive(client);
        if(client.player.getHealth()>=20 || !client.player.onGround())throw new AssertionError("Expected actual nonlethal fall damage, health="+client.player.getHealth()+" grounded="+client.player.onGround());
    }
    private void kill(Minecraft client) {
        pending=client.getSingleplayerServer().submit(()-> {
            var player=client.getSingleplayerServer().getPlayerList().getPlayer(client.player.getUUID());
            player.getAbilities().invulnerable=false;
            boolean accepted=player.hurtServer((net.minecraft.server.level.ServerLevel)player.level(),player.damageSources().genericKill(),Float.MAX_VALUE);
            if(!accepted || !player.isDeadOrDying())throw new AssertionError("Server kill was not lethal");
        });
    }
    private static boolean enabled() throws Exception {
        return (boolean)Class.forName("net.xolt.freecam.Freecam").getMethod("isEnabled").invoke(null);
    }
    private static void freecam(boolean desired) throws Exception {
        if(enabled()!=desired)Class.forName("net.xolt.freecam.Freecam").getMethod("toggle").invoke(null);
    }
    private void respawn(Minecraft client,String context) throws Exception {
        JsonObject action=new JsonObject();action.addProperty("context",context);
        String label=Component.translatable("deathScreen.respawn").getString();
        if(client.gui.screen() instanceof DeathScreen screen) {
            for(var child:screen.children())if(child instanceof Button button && button.active && button.getMessage().getString().equals(label)) {
                action.addProperty("method","real_respawn_button");actions.add(action);
                button.onPress(new KeyEvent(257,0,0));return;
            }
        }
        // Observe the missing screen first, then recover only this disposable client to continue the matrix.
        action.addProperty("method","fallback_respawn_packet_after_missing_button");actions.add(action);
        client.player.respawn();client.gui.setScreen(null);
    }
    private void snapshot(Minecraft client,String name) throws Exception {
        if(name.equals("inventory-open") && !(client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen))throw new AssertionError("Inventory key failed to open inventory");
        if(name.equals("third-person-back") && client.options.getCameraType()!=CameraType.THIRD_PERSON_BACK)throw new AssertionError("F5 did not switch to back view");
        if(name.equals("third-person-front") && client.options.getCameraType()!=CameraType.THIRD_PERSON_FRONT)throw new AssertionError("F5 did not switch to front view");
        if(name.equals("first-person-restored") && client.options.getCameraType()!=CameraType.FIRST_PERSON)throw new AssertionError("F5 did not restore first person");
        if(name.equals("f1-hidden")!=client.gui.hud.isHidden())throw new AssertionError("Unexpected HUD hidden state at "+name);
        JsonObject value=new JsonObject();value.addProperty("name",name);
        value.addProperty("dimension",client.level.dimension().identifier().toString());
        value.addProperty("health",client.player.getHealth());value.addProperty("isDeadOrDying",client.player.isDeadOrDying());
        value.addProperty("showDeathScreen",client.player.shouldShowDeathScreen());value.addProperty("hudHidden",client.gui.hud.isHidden());
        value.addProperty("cameraType",client.options.getCameraType().name());value.addProperty("onGround",client.player.onGround());
        value.addProperty("screen",client.gui.screen()==null?"null":client.gui.screen().getClass().getName());
        value.addProperty("freecamEnabled",enabled());
        var camera=client.getCameraEntity();value.addProperty("camera",camera==null?"null":camera.getClass().getName());
        value.addProperty("cameraIsLocalPlayer",camera==client.player);
        value.addProperty("cameraIsPlayer",camera instanceof net.minecraft.world.entity.player.Player);
        var method=Hud.class.getDeclaredMethod("getCameraPlayer");method.setAccessible(true);
        value.addProperty("hudCameraPlayerPresent",method.invoke(client.gui.hud)!=null);
        value.addProperty("instantRespawn",client.getSingleplayerServer().submit(()->client.getSingleplayerServer().overworld().getGameRules().get(GameRules.IMMEDIATE_RESPAWN)).join());
        JsonArray buttons=new JsonArray();
        if(client.gui.screen()!=null)for(var child:client.gui.screen().children())if(child instanceof Button button) {
            JsonObject item=new JsonObject();item.addProperty("label",button.getMessage().getString());item.addProperty("active",button.active);buttons.add(item);
        }
        value.add("buttons",buttons);value.addProperty("screenshot","screenshots/"+name+".png");observations.add(value);write(client);
        System.out.println("MERCY_DEATH_STATE "+value);
        Screenshot.grab(client.gameDirectory,name+".png",client.gameRenderer.mainRenderTarget(),1,message->{captures++;System.out.println("MERCY_DEATH_SCREENSHOT "+name);});
    }
    private void write(Minecraft client) throws Exception {
        JsonObject result=new JsonObject();result.addProperty("scope","Actual nonlethal falling, HUD keys, menus, server lethal damage and actual respawn button where available; only the dedicated copied test world is modified.");
        result.add("observations",observations);result.add("respawnActions",actions);
        Files.writeString(client.gameDirectory.toPath().resolve("death-results.json"),result.toString());
    }
}
