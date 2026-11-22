package dev.mercy.companion.testclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.mercy.companion.CompanionService;
import dev.mercy.companion.MercyMod;
import dev.mercy.companion.client.BagTooltip;
import dev.mercy.companion.client.mixin.ContainerScreenAccessor;
import dev.mercy.companion.net.MercyNetworking;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Native mouse callbacks -> real C2S -> authoritative roster -> S2C; no mock action data. */
final class BagActionVisualChecks {
    private static JsonArray checks=new JsonArray();
    private static int stage,age,selectionBefore,underlyingIndex;
    private static CompletableFuture<JsonObject> setup;
    private static JsonObject target;
    private static UUID first;
    private static double oldPortraitX,oldPortraitY;
    private static String rosterBefore;
    private static CompletableFuture<Void> inventorySetup;
    private static int screenshots;

    static void begin(Minecraft client) {
        checks=new JsonArray();stage=0;age=0;screenshots=0;
        client.gui.setScreen(new InventoryScreen(client.player));
        setup=deploy(client,null,7);
    }

    static boolean tick(Minecraft client) throws Exception {
        if(++age>500)throw new AssertionError("Bag action fixture timed out at stage "+stage+": "+state(client));
        switch(stage) {
            case 0 -> {
                if(!setup.isDone())return false;
                target=setup.join();first=uuid(target);hoverBag(client);
                if(age<30||pending())return false;
                if(!first.equals(field("inspected"))) {client.mouseHandler.onScroll(client.getWindow().handle(),0,1);return false;}
                screenshot(client,"bag-rightclick-active-selected");click(client,InputConstants.MOUSE_BUTTON_RIGHT);next();
            }
            case 1 -> {
                if(age<25)return false;assertStoredAndSelected(client,target,7);
                require(screen(client).getMenu().getCarried().isEmpty(),"Bag right click picked up the bag");
                check("bag_slot_scroll_and_right_click_recall_active_to_top");
                setup=deploy(client,first,9);client.gui.setScreen(new InventoryScreen(client.player));next();
            }
            case 2 -> {
                if(!setup.isDone())return false;target=setup.join();hoverBag(client);
                if(age<30||pending())return false;
                double[] point=oldPortraitOverInventory(client);oldPortraitX=point[0];oldPortraitY=point[1];underlyingIndex=(int)point[2];
                inventorySetup=client.getSingleplayerServer().submit(()-> {
                    var p=client.getSingleplayerServer().getPlayerList().getPlayer(client.player.getUUID());
                    p.getInventory().setItem(underlyingIndex,new ItemStack(Items.STONE,2));p.inventoryMenu.broadcastChanges();return null;
                });
                rosterBefore=roster(client).toString();selectionBefore=(int)field("selectionTick");
                screenshot(client,"bag-hover-before-leaving");next();
            }
            case 3 -> {
                if(age<10||!inventorySetup.isDone())return false;
                move(client,oldPortraitX,oldPortraitY);
                if(age<12)return false;
                require(field("previewSlot")==null&&field("previewScreen")==null,"Tooltip stayed open over its old portrait area");
                require(inventorySlot(client,underlyingIndex).getItem().is(Items.STONE),"Background slot did not synchronize");
                check("leaving_bag_for_old_portrait_closes_tooltip");screenshot(client,"bag-old-portrait-area-closed");
                click(client,InputConstants.MOUSE_BUTTON_RIGHT);next();
            }
            case 4 -> {
                if(age<20)return false;
                require(roster(client).toString().equals(rosterBefore)&&(int)field("selectionTick")==selectionBefore,"Click outside bag changed companion roster or sent an action");
                require(screen(client).getMenu().getCarried().is(Items.STONE)&&screen(client).getMenu().getCarried().getCount()==1,"Old portrait area intercepted native half-stack pickup");
                require(inventorySlot(client,underlyingIndex).getItem().getCount()==1,"Background slot did not perform native right click");
                check("old_portrait_click_preserves_roster_and_uses_underlying_inventory");
                click(client,InputConstants.MOUSE_BUTTON_LEFT);next();
            }
            case 5 -> {
                if(age<15)return false;require(screen(client).getMenu().getCarried().isEmpty(),"Restoring background stack failed");
                if(age==15){client.mouseHandler.onScroll(client.getWindow().handle(),0,1);return false;}
                if(age<20)return false;
                require((int)field("selectionTick")==selectionBefore&&roster(client).toString().equals(rosterBefore),"Scroll outside bag changed companion selection");
                check("scroll_outside_bag_keeps_companion_state");hoverBag(client);
                if(age<25||pending())return false;
                if(!uuid(target).equals(field("inspected"))){client.mouseHandler.onScroll(client.getWindow().handle(),0,1);return false;}
                click(client,InputConstants.MOUSE_BUTTON_RIGHT);next();
            }
            case 6 -> {
                if(age<25)return false;assertStoredAndSelected(client,target,9);
                check("reenter_bag_and_recall_active_preserves_health");
                inventorySetup=client.getSingleplayerServer().submit(()-> {
                    var p=client.getSingleplayerServer().getPlayerList().getPlayer(client.player.getUUID());
                    for(var value:JsonParser.parseString(CompanionService.rosterJson(p)).getAsJsonObject().getAsJsonArray("slots")) {
                        var e=value.getAsJsonObject();if(uuid(e).equals(first)){CompanionService.bagAction(p,e.get("slot").getAsInt(),first.toString());break;}
                    }
                    MercyNetworking.update(p);return null;
                });next();
            }
            case 7 -> {
                if(age<25||!inventorySetup.isDone()||pending())return false;
                require(uuid(target).equals(field("inspected")),"Live roster update changed hovered inspection");
                require(roster(client).get("selectedSlot").getAsInt()!=target.get("slot").getAsInt(),"Stored right-click control was already top");
                click(client,InputConstants.MOUSE_BUTTON_RIGHT);next();
            }
            case 8 -> {
                if(age<25)return false;assertStoredAndSelected(client,target,9);
                check("bag_slot_right_click_prioritizes_inspected_stored_companion");screenshot(client,"bag-stored-rightclick-prioritized");
                hoverBag(client);click(client,InputConstants.MOUSE_BUTTON_LEFT);next();
            }
            case 9 -> {
                if(age<15)return false;
                require(screen(client).getMenu().getCarried().is(MercyMod.BAG),"Left click did not pick up bag");
                require(field("previewSlot")==null,"Carried bag retained tooltip");check("left_click_bag_keeps_vanilla_pickup");
                moveSlot(client,inventorySlot(client,2));click(client,InputConstants.MOUSE_BUTTON_LEFT);
                inventorySetup=client.getSingleplayerServer().submit(()-> {
                    var p=client.getSingleplayerServer().getPlayerList().getPlayer(client.player.getUUID());
                    p.getInventory().setItem(3,new ItemStack(Items.STONE,2));p.inventoryMenu.broadcastChanges();return null;
                });next();
            }
            case 10 -> {
                if(age<20||!inventorySetup.isDone())return false;
                require(screen(client).getMenu().getCarried().isEmpty(),"Bag placement failed");
                moveSlot(client,inventorySlot(client,3));click(client,InputConstants.MOUSE_BUTTON_LEFT);next();
            }
            case 11 -> {
                if(age<15)return false;require(screen(client).getMenu().getCarried().is(Items.STONE),"Native stone pickup failed");
                selectionBefore=(int)field("selectionTick");hoverBag(client);
                require(field("previewSlot")==null,"Tooltip appeared while another stack was carried");
                click(client,InputConstants.MOUSE_BUTTON_RIGHT);next();
            }
            case 12 -> {
                if(age<20)return false;
                require((int)field("selectionTick")==selectionBefore,"Carried-item right click sent a companion action");
                require(screen(client).getMenu().getCarried().is(MercyMod.BAG),"Carried stone right click did not perform vanilla swap");
                check("carried_stack_right_click_uses_vanilla_swap_without_payload");
                moveSlot(client,inventorySlot(client,1));click(client,InputConstants.MOUSE_BUTTON_LEFT);next();
            }
            case 13 -> {if(age>=15){moveSlot(client,inventorySlot(client,2));click(client,InputConstants.MOUSE_BUTTON_LEFT);next();}}
            case 14 -> {if(age>=15){moveSlot(client,inventorySlot(client,3));click(client,InputConstants.MOUSE_BUTTON_LEFT);next();}}
            case 15 -> {
                if(age<15)return false;require(screen(client).getMenu().getCarried().isEmpty(),"Fixture left a carried stack");
                client.gui.setScreen(null);next();
            }
            case 16 -> {
                if(age<10)return false;
                require(field("previewSlot")==null&&field("previewScreen")==null,"Closed inventory retained hover state");
                check("preview_state_cleared_on_inventory_close");client.gui.setScreen(new InventoryScreen(client.player));next();
            }
            case 17 -> {
                hoverBag(client);if(age<20||screenshots<3)return false;
                JsonObject report=new JsonObject();report.addProperty("native_mouse_callbacks",true);report.addProperty("old_portrait_underlying_inventory_slot",underlyingIndex);report.add("checks",checks);
                Files.writeString(client.gameDirectory.toPath().resolve("bag-action-results.json"),report.toString());return true;
            }
            default -> throw new AssertionError("Unknown bag action stage "+stage);
        }
        return false;
    }

    private static void next(){stage++;age=0;}
    private static CompletableFuture<JsonObject> deploy(Minecraft client,UUID except,float hp) {
        return client.getSingleplayerServer().submit(()-> {
            var server=client.getSingleplayerServer();var player=server.getPlayerList().getPlayer(client.player.getUUID());
            JsonObject selected=null;
            for(var value:JsonParser.parseString(CompanionService.rosterJson(player)).getAsJsonObject().getAsJsonArray("slots")) {
                var entry=value.getAsJsonObject();
                if(entry.get("status").getAsString().equals("stored") && !uuid(entry).equals(except)){selected=entry;break;}
            }
            require(selected!=null,"No stored companion for native action test");
            CompanionService.select(player,selected.get("slot").getAsInt(),uuid(selected).toString());
            var level=(net.minecraft.server.level.ServerLevel)player.level();BlockPos support=player.blockPosition().offset(4,-1,0);
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++) {
                level.setBlockAndUpdate(support.offset(x,0,z),Blocks.STONE.defaultBlockState());
                for(int y=1;y<=5;y++)level.setBlockAndUpdate(support.offset(x,y,z),Blocks.AIR.defaultBlockState());
            }
            int previousSelected=player.getInventory().getSelectedSlot();
            try {
                player.getInventory().setSelectedSlot(1);
                require(CompanionService.summonAt(player,support),"Action fixture summon failed");
            } finally {player.getInventory().setSelectedSlot(previousSelected);}
            var mob=(Mob)level.getEntity(uuid(selected));require(mob!=null,"Summoned entity missing");
            mob.setNoAi(true);mob.setHealth(hp);MercyNetworking.update(player);
            for(var value:JsonParser.parseString(CompanionService.rosterJson(player)).getAsJsonObject().getAsJsonArray("slots"))
                if(uuid(value.getAsJsonObject()).equals(mob.getUUID()))return value.getAsJsonObject();
            throw new AssertionError("Deployed entity missing from roster");
        });
    }
    private static void assertStoredAndSelected(Minecraft client,JsonObject expected,float hp) throws Exception {
        var roster=roster(client);int slot=expected.get("slot").getAsInt();JsonObject actual=null;
        for(var value:roster.getAsJsonArray("slots"))if(uuid(value.getAsJsonObject()).equals(uuid(expected)))actual=value.getAsJsonObject();
        require(actual!=null && actual.get("status").getAsString().equals("stored"),"Companion was not recalled: "+roster);
        require(Math.abs(actual.get("health").getAsFloat()-hp)<.01,"Recall changed HP: "+actual);
        require(roster.get("selectedSlot").getAsInt()==slot && (int)field("selected")==slot,"Server/client did not acknowledge new top slot");
        boolean synced=false;
        for(Object entry:(List<?>)field("entries"))if(uuid(expected).equals(call(entry,"uuid")) && call(entry,"status").equals("stored"))synced=true;
        require(synced,"Client did not receive stored state");
    }
    private static JsonObject roster(Minecraft client) {
        return JsonParser.parseString(client.getSingleplayerServer().submit(()->CompanionService.rosterJson(
            client.getSingleplayerServer().getPlayerList().getPlayer(client.player.getUUID()))).join()).getAsJsonObject();
    }
    private static UUID uuid(JsonObject entry){return UUID.fromString(entry.get("uuid").getAsString());}
    private static boolean pending()throws Exception{return (boolean)field("pending");}
    private static Object field(String name)throws Exception{var f=BagTooltip.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static Object call(Object object,String name)throws Exception{var m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(object);}
    /** Reconstruct vanilla tooltip placement from its public components, never production hit boxes. */
    private static double[] oldPortraitOverInventory(Minecraft client) throws Exception {
        var s=screen(client);var accessor=(ContainerScreenAccessor)s;
        var component=net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent.create(dev.mercy.companion.net.BagTooltipData.INSTANCE);
        int width=component.getWidth(client.font),height=component.getHeight(client.font)+12;
        int mx=(int)(client.mouseHandler.xpos()*s.width/client.getWindow().getScreenWidth());
        int my=(int)(client.mouseHandler.ypos()*s.height/client.getWindow().getScreenHeight());
        var origin=net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner.INSTANCE.positionTooltip(s.width,s.height,mx,my,width,height);
        int columns=Math.min(4,(int)field("capacity"));
        int gridX=origin.x()+(width-columns*24)/2,gridY=origin.y()+12;
        for(int cell=0;cell<3;cell++)for(Slot slot:s.getMenu().slots) {
            // The native tooltip can overlap the hotbar at auto GUI scale; keep slots 0–3 for other controls.
            if(slot.container!=client.player.getInventory()||slot.getContainerSlot()<4||slot.getContainerSlot()>35)continue;
            int sx=nativePos(screen(client),"leftPos")+slot.x,sy=nativePos(screen(client),"topPos")+slot.y;
            int left=Math.max(gridX+cell*24+3,sx+2),right=Math.min(gridX+cell*24+21,sx+14);
            int top=Math.max(gridY+3,sy+2),bottom=Math.min(gridY+21,sy+14);
            if(right>left&&bottom>top)return new double[]{(left+right)/2.0,(top+bottom)/2.0,slot.getContainerSlot()};
        }
        throw new AssertionError("Fixture tooltip portraits do not overlap a normal inventory slot: origin="+origin+", grid="+gridX+","+gridY);
    }
    private static InventoryScreen screen(Minecraft client){return (InventoryScreen)client.gui.screen();}
    private static Slot inventorySlot(Minecraft client,int index) {
        for(Slot slot:screen(client).getMenu().slots)if(slot.container==client.player.getInventory() && slot.getContainerSlot()==index)return slot;
        throw new AssertionError("Inventory slot missing: "+index);
    }
    private static void hoverBag(Minecraft client) {
        for(Slot slot:screen(client).getMenu().slots)if(slot.getItem().is(MercyMod.BAG)){moveSlot(client,slot);return;}
        throw new AssertionError("Bag not present in inventory");
    }
    private static void moveSlot(Minecraft client,Slot slot) {
        var accessor=(ContainerScreenAccessor)screen(client);move(client,nativePos(screen(client),"leftPos")+slot.x+8,nativePos(screen(client),"topPos")+slot.y+8);
    }
    private static void move(Minecraft client,double x,double y) {
        var screen=screen(client);client.mouseHandler.onMove(client.getWindow().handle(),x*client.getWindow().getScreenWidth()/screen.width,y*client.getWindow().getScreenHeight()/screen.height,0,0);
    }
    private static String state(Minecraft client) {
        var s=screen(client);var accessor=(ContainerScreenAccessor)s;
        double x=client.mouseHandler.xpos()*s.width/client.getWindow().getScreenWidth(),y=client.mouseHandler.ypos()*s.height/client.getWindow().getScreenHeight();
        Slot at=accessor.mercy$slotAt(x,y);
        String preview;try{preview=String.valueOf(field("previewSlot"));}catch(Exception e){preview=e.toString();}
        return "stage="+stage+" carried="+s.getMenu().getCarried()+" slot2="+inventorySlot(client,2).getItem()+" slot3="+inventorySlot(client,3).getItem()+" mouse="+x+","+y+" hit="+(at==null?null:at.index)+" preview="+preview;
    }
    private static int nativePos(InventoryScreen screen,String name) {
        try {var f=net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredField(name);f.setAccessible(true);return f.getInt(screen);}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static void click(Minecraft client,int button) {
        System.out.println("MERCY_NATIVE_CLICK_BEFORE button="+button+" "+state(client));
        var info=new MouseButtonInfo(button,0);client.mouseHandler.onButton(client.getWindow().handle(),info,InputConstants.PRESS);
        client.mouseHandler.onButton(client.getWindow().handle(),info,InputConstants.RELEASE);
        System.out.println("MERCY_NATIVE_CLICK_AFTER button="+button+" "+state(client));
    }
    private static void screenshot(Minecraft client,String name) {
        Screenshot.grab(client.gameDirectory,name+".png",client.gameRenderer.mainRenderTarget(),1,message->screenshots++);
    }
    private static void check(String name){for(var old:checks)if(old.getAsJsonObject().get("name").getAsString().equals(name))return;JsonObject c=new JsonObject();c.addProperty("name",name);c.addProperty("passed",true);checks.add(c);System.out.println("MERCY_BAG_ACTION "+name);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
