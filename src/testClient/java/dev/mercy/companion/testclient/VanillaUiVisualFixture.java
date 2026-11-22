package dev.mercy.companion.testclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mercy.companion.CompanionService;
import dev.mercy.companion.MercyMod;
import dev.mercy.companion.client.BagTooltip;
import dev.mercy.companion.client.mixin.ContainerScreenAccessor;
import dev.mercy.companion.net.ActionPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Files;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

/** Isolated rendering exercise, using only a copied integration-test world. */
public final class VanillaUiVisualFixture implements ClientModInitializer {
    private record View(String kind, int scale) {}
    private static final View[] FULL_PLAN = {
        new View("title",4), new View("title",0),
        new View("create-world",4), new View("create-world",0),
        new View("options",4), new View("options",0),
        new View("controls",0), new View("keybinds",0),
        new View("load-world",0),
        new View("hud",4), new View("hud",0),
        new View("inventory",4), new View("inventory",0),
        new View("pause",4), new View("pause",0),
        new View("bag-tooltip",4), new View("bag-tooltip",0),
        new View("prepare-bag-network",0), new View("bag-network",0), new View("bag-actions",0), new View("particles",0)
    };
    private static final View[] PLAN=Boolean.getBoolean("mercy.ui.actionsOnly")?
        new View[]{new View("load-world",0),new View("prepare-bag-network",0),new View("bag-actions",0),new View("particles",0)}:
        Boolean.getBoolean("mercy.ui.bagOnly")?
        new View[]{new View("load-world",0),new View("sword-tooltip",0),new View("bag-empty",0),
            new View("bag-capacity-3",0),new View("bag-capacity-4",0),new View("bag-capacity-5",0),
            new View("bag-capacity-6",0),new View("bag-capacity-7",0),
            new View("bag-tooltip",4),new View("bag-tooltip",0),
            new View("bag-inspect-active",0),new View("bag-inspect-locked",0),
            new View("prepare-bag-network",0),new View("bag-network",0),new View("bag-actions",0),new View("particles",0)}:FULL_PLAN;
    private static final String SAMPLE = """
        {"capacity":5,"selectedSlot":2,"slots":[
          {"slot":2,"uuid":"00000000-0000-0000-0000-000000000002","name":"entity.minecraft.spider","health":16,"maxHealth":16,"status":"stored"},
          {"slot":3,"uuid":"00000000-0000-0000-0000-000000000003","name":"entity.minecraft.enderman","health":35,"maxHealth":40,"status":"stored"},
          {"slot":4,"uuid":"00000000-0000-0000-0000-000000000004","name":"entity.minecraft.creeper","health":3,"maxHealth":20,"status":"stored"},
          {"slot":5,"uuid":"00000000-0000-0000-0000-000000000005","name":"entity.minecraft.warden","health":350,"maxHealth":500,"status":"locked"},
          {"slot":6,"uuid":"00000000-0000-0000-0000-000000000006","name":"entity.minecraft.wither","health":180,"maxHealth":300,"status":"locked"},
          {"slot":0,"uuid":"00000000-0000-0000-0000-000000000000","name":"entity.minecraft.zombie","health":20,"maxHealth":20,"status":"active"},
          {"slot":1,"uuid":"00000000-0000-0000-0000-000000000001","name":"entity.minecraft.skeleton","health":7,"maxHealth":20,"status":"active"}
        ]}
        """;
    private int stage, age, readyAge, captures;
    private final JsonArray views = new JsonArray();
    private final JsonArray networkChecks = new JsonArray();
    private final JsonArray inspectionChecks = new JsonArray();
    private CompletableFuture<String> setupFuture;
    private List<Integer> cycle=List.of();
    private List<java.util.UUID> cycleIds=List.of();
    private int originalNext, browseSelectionTick, commitStage, commitAge, commitSlot;
    private JsonObject explicitSelectionCheck=new JsonObject();
    private int cycleIndex, networkStep, scrollSentAt=-1;
    private static final int[] SCROLLS={-1,-1,-1,1,1,1};

    @Override public void onInitializeClient() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }

    private void tick(Minecraft client) {
        if (!client.isGameLoadFinished()) return;
        try {
            if (stage >= PLAN.length) {
                if (captures < PLAN.length - 2) return;
                if(Boolean.getBoolean("mercy.ui.addons")) AddonCompatibilityChecks.verify(client);
                writeReport(client);
                client.stop();
                return;
            }
            View view = PLAN[stage];
            if (++age == 1) enter(client, view);
            if (age > 2400) throw new IllegalStateException("UI fixture timed out at " + view);
            if (!ready(client, view.kind())) return;
            if (view.kind().equals("load-world")) {
                if (++readyAge < 100) return;
                client.getSingleplayerServer().execute(() -> {
                    var server = client.getSingleplayerServer();
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamemode survival MercyTester");
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set day");
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "difficulty normal");
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "give MercyTester mercy_companions:companion_bag");
                    server.getPlayerList().getPlayer(client.player.getUUID()).getAbilities().invulnerable=true;
                });
                advance();
                return;
            }
            if(view.kind().equals("prepare-bag-network")) {
                if(!setupFuture.isDone()) return;
                var roster=JsonParser.parseString(setupFuture.join()).getAsJsonObject();
                List<Integer> slots=new ArrayList<>();List<java.util.UUID> ids=new ArrayList<>();
                for(var value:roster.getAsJsonArray("slots")) {
                    var entry=value.getAsJsonObject();
                    if(entry.get("status").getAsString().equals("stored")) {slots.add(entry.get("slot").getAsInt());ids.add(java.util.UUID.fromString(entry.get("uuid").getAsString()));}
                }
                if(slots.size()!=3) throw new AssertionError("Expected three real stored companions: "+roster);
                cycle=List.copyOf(slots);cycleIds=List.copyOf(ids);originalNext=roster.get("selectedSlot").getAsInt();cycleIndex=cycle.indexOf(originalNext);
                client.player.getInventory().setSelectedSlot(0);
                BagTooltip.clear();
                ClientPlayNetworking.send(new ActionPayload("refresh",-1));
                advance();
                return;
            }
            if(view.kind().equals("bag-actions")) {
                if(BagActionVisualChecks.tick(client)) {capture(client,view);advance();}
                return;
            }
            if(view.kind().equals("particles")) {
                if(ParticleVisualChecks.tick(client)) { capture(client,view);advance(); }
                return;
            }
            if(view.kind().equals("bag-network")) {
                if(commitStage!=1)hoverBag(client);
                readyAge++;
                if(readyAge>=30 && scrollSentAt<0 && networkStep<SCROLLS.length) {
                    var hover=((ContainerScreenAccessor)client.gui.screen()).mercy$slotAt(client.mouseHandler.getScaledXPos(client.getWindow()),client.mouseHandler.getScaledYPos(client.getWindow()));
                    boolean bagHovered=hover!=null && hover.getItem().is(MercyMod.BAG);
                    boolean received=(boolean)bagField("received"), pending=(boolean)bagField("pending");
                    int count=((List<?>)bagField("entries")).size();
                    if(!bagHovered || !received || pending || count!=3 || client.gui.overlay()!=null) {
                        if(readyAge%20==0) System.out.println("MERCY_SCROLL_WAIT hover="+bagHovered+" received="+received+" pending="+pending+" entries="+count+" overlay="+client.gui.overlay());
                        if(readyAge>400) throw new AssertionError("Native hover/roster never became ready for scroll");
                        return;
                    }
                    browseSelectionTick=(int)bagField("selectionTick");
                    System.out.println("MERCY_SCROLL_BROWSE step="+networkStep+" selected="+bagField("selected")+" entries="+count+" hover="+bagHovered);
                    client.mouseHandler.onScroll(client.getWindow().handle(),0,SCROLLS[networkStep]);
                    System.out.println("MERCY_SCROLL_SENT pending="+bagField("pending"));
                    scrollSentAt=readyAge;
                }
                if(scrollSentAt>=0 && readyAge>=scrollSentAt+25 && networkStep<SCROLLS.length) {
                    int previous=cycle.get(cycleIndex);
                    cycleIndex=Math.floorMod(cycleIndex-SCROLLS[networkStep],cycle.size());
                    int expected=cycle.get(cycleIndex);
                    String json=client.getSingleplayerServer().submit(()->CompanionService.rosterJson(
                        client.getSingleplayerServer().getPlayerList().getPlayer(client.player.getUUID()))).join();
                    int actual=JsonParser.parseString(json).getAsJsonObject().get("selectedSlot").getAsInt();
                    int acknowledged=(int)bagField("selected");
                    java.util.UUID inspected=(java.util.UUID)bagField("inspected");
                    if(actual!=originalNext || acknowledged!=originalNext || !cycleIds.get(cycleIndex).equals(inspected)
                            || (boolean)bagField("pending") || (int)bagField("selectionTick")!=browseSelectionTick)
                        throw new AssertionError("Scroll "+networkStep+" should only inspect="+expected+", serverNext="+actual+", clientNext="+acknowledged+", inspected="+inspected);
                    JsonObject check=new JsonObject();check.addProperty("direction",SCROLLS[networkStep]);check.addProperty("previousInspection",previous);
                    check.addProperty("inspectedSlot",expected);check.addProperty("serverNextUnchanged",actual);check.addProperty("clientNextUnchanged",acknowledged);check.addProperty("noSelectionRequest",true);
                    networkChecks.add(check);System.out.println("MERCY_SCROLL_LOCAL "+check);
                    networkStep++;
                    scrollSentAt=-1;
                }
                if(networkStep==SCROLLS.length && readyAge>280) {
                    if(commitStage==0) {
                        client.mouseHandler.onMove(client.getWindow().handle(),0,0,0,0);commitStage=1;commitAge=readyAge;
                    } else if(commitStage==1 && readyAge-commitAge>=10) {
                        requireNetworkNext(client,originalNext);
                        if(bagField("inspected")!=null || bagField("previewSlot")!=null)throw new AssertionError("Leaving bag did not clear local browsing");
                        explicitSelectionCheck.addProperty("leaving_bag_does_not_commit_browsing",true);
                        hoverBag(client);commitStage=2;commitAge=readyAge;
                    } else if(commitStage==2 && readyAge-commitAge>=10) {
                        cycleIndex=cycle.indexOf(originalNext);commitSlot=cycle.get(Math.floorMod(cycleIndex+1,cycle.size()));
                        client.mouseHandler.onScroll(client.getWindow().handle(),0,-1);commitStage=3;commitAge=readyAge;
                    } else if(commitStage==3 && readyAge-commitAge>=10) {
                        requireNetworkNext(client,originalNext);
                        if(!cycleIds.get(cycle.indexOf(commitSlot)).equals(bagField("inspected")))throw new AssertionError("Reentered bag browsed wrong companion");
                        var button=new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT,0);
                        client.mouseHandler.onButton(client.getWindow().handle(),button,com.mojang.blaze3d.platform.InputConstants.PRESS);
                        client.mouseHandler.onButton(client.getWindow().handle(),button,com.mojang.blaze3d.platform.InputConstants.RELEASE);
                        commitStage=4;commitAge=readyAge;
                    } else if(commitStage==4 && readyAge-commitAge>=25) {
                        requireNetworkNext(client,commitSlot);explicitSelectionCheck.addProperty("native_right_click_commits_server_and_client_next",true);
                        explicitSelectionCheck.addProperty("previousNext",originalNext);explicitSelectionCheck.addProperty("confirmedNext",commitSlot);
                        capture(client,view);advance();
                    }
                }
                return;
            }
            // Keep fixture roster isolated from the automatic live-server refresh payload.
            if (view.kind().equals("bag-tooltip") || view.kind().startsWith("bag-inspect-") || view.kind().startsWith("bag-capacity-") || view.kind().equals("bag-empty")) {
                BagTooltip.accept(sampleFor(view.kind()));
                hoverBag(client);
                if(view.kind().startsWith("bag-inspect-") && readyAge==30) {
                    int tickBefore=(int)bagField("selectionTick"), nextBefore=(int)bagField("selected");
                    boolean active=view.kind().endsWith("active");
                    // Backward from the next stored item visits locked entries, then active entries.
                    for(int count=0;count<(active?3:1);count++) {
                        client.mouseHandler.onScroll(client.getWindow().handle(),0,1);
                        if((boolean)bagField("pending") || (int)bagField("selectionTick")!=tickBefore)
                            throw new AssertionError("Inspecting an active/locked companion sent a selection request");
                    }
                    java.util.UUID expected=new java.util.UUID(0,active?1:6);
                    if(!expected.equals(bagField("inspected")) || (int)bagField("selected")!=nextBefore)
                        throw new AssertionError("Inspection changed next-summon slot or inspected wrong companion");
                    JsonObject check=new JsonObject();check.addProperty("status",active?"active":"locked");
                    check.addProperty("inspectedUuid",expected.toString());check.addProperty("nextSummonSlotUnchanged",true);
                    check.addProperty("noSelectionRequest",true);inspectionChecks.add(check);
                }
            } else if(view.kind().equals("sword-tooltip")) {
                hoverItem(client,"mercy_companions:mercy_sword");
            } else if (client.gui.screen() != null) client.mouseHandler.onMove(client.getWindow().handle(),0,0,0,0);
            if (++readyAge == 60) {
                capture(client, view);
                advance();
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            try { Files.writeString(client.gameDirectory.toPath().resolve("ui-error.txt"), failure.toString()); }
            catch (Exception ignored) {}
            client.stop();
        }
    }

    private void enter(Minecraft client, View view) throws Exception {
        if (stage == 0) client.getWindow().setWindowed(1280,800);
        client.getWindow().setTitle("收纳袋自动测试（请勿操作，会自动关闭）");
        client.options.pauseOnLostFocus = false;
        client.options.mouseWheelSensitivity().set(1.0);
        client.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        client.gui.toastManager().clear();
        client.options.guiScale().set(view.scale());
        client.resizeGui();
        switch (view.kind()) {
            case "title" -> client.gui.setScreen(new TitleScreen(false));
            case "create-world" -> {
                if (!(client.gui.screen() instanceof CreateWorldScreen))
                    CreateWorldScreen.openFresh(client, () -> client.gui.setScreen(new TitleScreen(false)));
            }
            case "options" -> client.gui.setScreen(new OptionsScreen(new TitleScreen(false),client.options));
            case "controls" -> client.gui.setScreen(new ControlsScreen(new OptionsScreen(new TitleScreen(false),client.options),client.options));
            case "keybinds" -> client.gui.setScreen(new KeyBindsScreen(new ControlsScreen(new TitleScreen(false),client.options),client.options));
            case "load-world" -> client.createWorldOpenFlows().openWorld("ui-fixture-world", () -> client.gui.setScreen(new TitleScreen(false)));
            case "hud" -> client.gui.setScreen(null);
            case "inventory" -> client.gui.setScreen(new InventoryScreen(client.player));
            case "pause" -> client.gui.setScreen(new PauseScreen(true));
            case "bag-tooltip", "bag-inspect-active", "bag-inspect-locked", "bag-empty", "bag-capacity-3", "bag-capacity-4", "bag-capacity-5", "bag-capacity-6", "bag-capacity-7", "sword-tooltip" -> client.gui.setScreen(new InventoryScreen(client.player));
            case "prepare-bag-network" -> {
                client.gui.setScreen(new InventoryScreen(client.player));
                setupFuture=client.getSingleplayerServer().submit(()->{
                    var server=client.getSingleplayerServer();
                    var player=server.getPlayerList().getPlayer(client.player.getUUID());
                    var level=(net.minecraft.server.level.ServerLevel)player.level();
                    // Reserve only the two slots exercised by native pickup/swap checks.
                    System.out.println("MERCY_FIXTURE_CLEAR slot2="+player.getInventory().getItem(2)+" slot3="+player.getInventory().getItem(3));
                    player.getInventory().setItem(2,net.minecraft.world.item.ItemStack.EMPTY);
                    player.getInventory().setItem(3,net.minecraft.world.item.ItemStack.EMPTY);
                    player.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(MercyMod.SWORD));
                    player.getInventory().setItem(1,new net.minecraft.world.item.ItemStack(MercyMod.BAG));
                    player.getInventory().setSelectedSlot(0);
                    player.inventoryMenu.broadcastChanges();
                    for(String id:List.of("spider","enderman","creeper")) {
                        var type=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(
                            net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft",id));
                        var mob=(net.minecraft.world.entity.Mob)type.create(level,net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                        mob.setPos(player.position());
                        if(!CompanionService.capture(mob,player.damageSources().playerAttack(player)))
                            throw new AssertionError("Cannot seed companion "+type);
                    }
                    return CompanionService.rosterJson(player);
                });
            }
            case "bag-network" -> client.gui.setScreen(new InventoryScreen(client.player));
            case "bag-actions" -> BagActionVisualChecks.begin(client);
            case "particles" -> { client.gui.setScreen(null); ParticleVisualChecks.begin(client); }
            default -> throw new IllegalArgumentException(view.kind());
        }
    }

    private boolean ready(Minecraft client, String kind) {
        Screen screen = client.gui.screen();
        return switch(kind) {
            case "title" -> screen instanceof TitleScreen;
            case "create-world" -> screen instanceof CreateWorldScreen;
            case "options" -> screen instanceof OptionsScreen;
            case "controls" -> screen instanceof ControlsScreen;
            case "keybinds" -> screen instanceof KeyBindsScreen;
            case "load-world" -> client.player != null && client.level != null;
            case "hud", "particles" -> client.player != null && screen == null;
            case "bag-actions" -> client.player != null;
            case "inventory" -> screen instanceof InventoryScreen;
            case "pause" -> screen instanceof PauseScreen;
            case "bag-tooltip", "bag-inspect-active", "bag-inspect-locked", "bag-empty", "bag-capacity-3", "bag-capacity-4", "bag-capacity-5", "bag-capacity-6", "bag-capacity-7", "sword-tooltip", "prepare-bag-network", "bag-network" -> screen instanceof InventoryScreen;
            default -> false;
        };
    }
    private void advance() { stage++; age=0; readyAge=0; }

    private static Object bagField(String name) throws Exception {
        var field=BagTooltip.class.getDeclaredField(name);field.setAccessible(true);return field.get(null);
    }

    private static void requireNetworkNext(Minecraft client,int expected) throws Exception {
        var json=client.getSingleplayerServer().submit(()->CompanionService.rosterJson(client.getSingleplayerServer().getPlayerList().getPlayer(client.player.getUUID()))).join();
        int actual=JsonParser.parseString(json).getAsJsonObject().get("selectedSlot").getAsInt();
        if(actual!=expected || (int)bagField("selected")!=expected)throw new AssertionError("Next companion mismatch: expected="+expected+" server="+actual+" client="+bagField("selected"));
    }

    private void hoverBag(Minecraft client) throws Exception { hoverItem(client,"mercy_companions:companion_bag"); }

    private void hoverItem(Minecraft client,String target) throws Exception {
        var screen=(InventoryScreen)client.gui.screen();
        var type=net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class;
        var left=type.getDeclaredField("leftPos"); left.setAccessible(true);
        var top=type.getDeclaredField("topPos"); top.setAccessible(true);
        for(var slot:screen.getMenu().slots) {
            var key=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem());
            if(!key.toString().equals(target)) continue;
            double x=(left.getInt(screen)+slot.x+8.0)*client.getWindow().getScreenWidth()/screen.width;
            double y=(top.getInt(screen)+slot.y+8.0)*client.getWindow().getScreenHeight()/screen.height;
            client.mouseHandler.onMove(client.getWindow().handle(),x,y,0,0);
            return;
        }
        throw new IllegalStateException(target+" not present in test inventory");
    }

    private static String sampleFor(String kind) {
        if(kind.equals("bag-tooltip") || kind.startsWith("bag-inspect-")) return SAMPLE;
        int capacity=kind.equals("bag-empty")?3:Integer.parseInt(kind.substring("bag-capacity-".length()));
        JsonObject data=new JsonObject();data.addProperty("capacity",capacity);data.addProperty("selectedSlot",0);
        JsonArray slots=new JsonArray();
        String[] ids={"spider","enderman","creeper","warden","wither","zombie","skeleton"};
        int[] hp={16,35,3,350,180,20,7}, max={16,40,20,500,300,20,20};
        if(!kind.equals("bag-empty")) for(int i=0;i<capacity;i++) {
            JsonObject entry=new JsonObject();entry.addProperty("slot",i);
            entry.addProperty("uuid",new java.util.UUID(0,i).toString());entry.addProperty("type","minecraft:"+ids[i]);
            entry.addProperty("name","entity.minecraft."+ids[i]);entry.addProperty("health",hp[i]);entry.addProperty("maxHealth",max[i]);
            entry.addProperty("status",i>=5?"active":"stored");slots.add(entry);
        }
        data.add("slots",slots);return data.toString();
    }

    private void capture(Minecraft client, View current) throws Exception {
        Screen screen = client.gui.screen();
        String name=current.kind()+"-scale-"+(current.scale()==0?"auto":current.scale())+".png";
        JsonObject view=new JsonObject();
        view.addProperty("file","screenshots/"+name);
        view.addProperty("guiWidth",client.getWindow().getGuiScaledWidth());
        view.addProperty("guiHeight",client.getWindow().getGuiScaledHeight());
        view.addProperty("requestedScale",current.scale());
        view.addProperty("livePlayer",client.player!=null);
        JsonArray clipped=new JsonArray();
        if(screen!=null) for(var child:screen.children()) if(child instanceof AbstractWidget widget && widget.visible)
            if(widget.getX()<0 || widget.getY()<0 || widget.getRight()>screen.width || widget.getBottom()>screen.height)
                clipped.add(widget.getMessage().getString());
        view.add("clippedTopLevelWidgets",clipped);
        views.add(view);
        writeReport(client);
        Screenshot.grab(client.gameDirectory,name,client.gameRenderer.mainRenderTarget(),1,message->{
            System.out.println("MERCY_UI_SCREENSHOT "+name+" "+message.getString());
            captures++;
        });
    }
    private void writeReport(Minecraft client) throws Exception {
        JsonObject report=new JsonObject();
        report.addProperty("scope","Real Minecraft 26.3 rendering. Bag capacity/empty/tooltip screenshots use sample data; sword and bag item rarity are real. Bag-network seeds three real server companions and checks six native mouse-scroll local browsing steps in both directions without changing the server next companion, then commits one native right click through C2S/server/S2C. Bag-actions verifies hover-only tooltip lifetime, unchanged background inventory input, bag-slot right-click actions and native carried-item behavior.");
        report.add("views",views);
        report.add("scrollBrowseChecks",networkChecks);
        report.add("explicitSelectionCheck",explicitSelectionCheck);
        report.add("localInspectionChecks",inspectionChecks);
        Files.writeString(client.gameDirectory.toPath().resolve("ui-results.json"),report.toString());
    }
}
