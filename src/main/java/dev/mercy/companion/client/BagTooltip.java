package dev.mercy.companion.client;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.mercy.companion.client.mixin.ContainerScreenAccessor;
import dev.mercy.companion.net.ActionPayload;
import dev.mercy.companion.net.BagActionPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import dev.mercy.companion.net.BagTooltipData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.ScrollWheelHandler;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Server-confirmed roster rendered in the ordinary item tooltip, without a management screen. */
public final class BagTooltip {
    private static final Identifier BAG = Identifier.fromNamespaceAndPath("mercy_companions","companion_bag");
    record Entry(int slot,UUID uuid,String type,String name,float health,float maximum,String status) {}
    private static List<Entry> entries=List.of();
    private static int capacity=3, selected=-1, ticks, refreshTick=-100, selectionTick=-100;
    private static boolean received, healthy=true, pending;
    private static ScrollWheelHandler wheel=new ScrollWheelHandler();
    private static List<UUID> scrollOrder=List.of();
    private static UUID inspected;
    private static AbstractContainerScreen<?> previewScreen;
    private static Slot previewSlot;
    private static boolean consumedRightClick;
    private BagTooltip() {}

    public static void init() {
        ClientTooltipComponentCallback.EVENT.register(data -> data instanceof BagTooltipData
            ? new BundlePreviewComponent(scrollable(),capacity,selected,inspection(),received,healthy) : null);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            closePreview();
            consumedRightClick=false;
            if (screen instanceof AbstractContainerScreen<?>) {
                ScreenMouseEvents.allowMouseScroll(screen).register(BagTooltip::allowScroll);
                ScreenMouseEvents.allowMouseClick(screen).register(BagTooltip::allowClick);
                ScreenMouseEvents.allowMouseRelease(screen).register((current,event)-> {
                    if(event.button()==InputConstants.MOUSE_BUTTON_RIGHT && consumedRightClick) {consumedRightClick=false;return false;}
                    return true;
                });
                ScreenEvents.remove(screen).register(current -> {
                    if(previewScreen==current)closePreview();
                    consumedRightClick=false;
                });
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ticks++;
            Slot slot=updateHover(client.gui.screen(),client.mouseHandler.getScaledXPos(client.getWindow()),
                client.mouseHandler.getScaledYPos(client.getWindow()));
            if (pending && ticks-selectionTick>40) pending=false;
            if (slot!=null && isBag(slot.getItem()) && ticks-refreshTick>=20
                    && client.player!=null && ClientPlayNetworking.canSend(ActionPayload.TYPE)) {
                refreshTick=ticks;
                RosterRefresh.request();
            }
        });
    }

    public static void clear() {
        entries=List.of(); capacity=3; selected=-1; received=false; healthy=true; pending=false;
        ticks=0; refreshTick=-100; selectionTick=-100; consumedRightClick=false;
        closePreview();
    }

    public static void accept(String json) {
        try {
            var root=JsonParser.parseString(json).getAsJsonObject();
            List<Entry> parsed=new ArrayList<>();
            boolean[] occupied=new boolean[7];
            for (var value:root.getAsJsonArray("slots")) {
                var item=value.getAsJsonObject();
                int slot=item.get("slot").getAsInt();
                if(slot<0 || slot>=7 || occupied[slot]) continue;
                String status=item.get("status").getAsString();
                if(!List.of("stored","active","locked").contains(status)) continue;
                float hp=item.get("health").getAsFloat(), max=item.get("maxHealth").getAsFloat();
                if(!Float.isFinite(hp) || !Float.isFinite(max) || max<=0) continue;
                occupied[slot]=true;
                parsed.add(new Entry(slot,UUID.fromString(item.get("uuid").getAsString()),
                    item.has("type")?item.get("type").getAsString():typeFromName(item.get("name").getAsString()),
                    item.get("name").getAsString(),Math.max(0,hp),max,status));
            }
            capacity=Math.clamp(root.get("capacity").getAsInt(),0,7);
            selected=root.has("selectedSlot")?root.get("selectedSlot").getAsInt():-1;
            healthy=!root.has("healthy") || root.get("healthy").getAsBoolean();
            entries=List.copyOf(parsed); received=true; pending=false;
        } catch (RuntimeException malformed) { healthy=false; received=true; pending=false; }
    }

    private static boolean isBag(ItemStack stack) {
        return !stack.isEmpty() && BAG.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
    /** Track only the real item slot; tooltip pixels never retain hover or receive input. */
    public static Slot updateHover(Screen screen,double x,double y) {
        if(!(screen instanceof AbstractContainerScreen<?> container)
                || !container.getMenu().getCarried().isEmpty()) {
            closePreview();return null;
        }
        Slot slot=((ContainerScreenAccessor)container).mercy$slotAt(x,y);
        if(slot==null || !isBag(slot.getItem())) {closePreview();return null;}
        if(previewScreen!=container || previewSlot!=slot) {
            closePreview();previewScreen=container;previewSlot=slot;
        }
        return slot;
    }

    private static void closePreview() {
        if(previewScreen==null && previewSlot==null && inspected==null && scrollOrder.isEmpty())return;
        previewScreen=null;previewSlot=null;inspected=null;scrollOrder=List.of();wheel=new ScrollWheelHandler();
        // A swallowed press still owns its matching release after the pointer leaves the slot.
        // Pending server actions likewise outlive this purely local browsing session.
    }

    private static boolean allowClick(Screen screen,MouseButtonEvent event) {
        Slot slot=updateHover(screen,event.x(),event.y());
        if(slot==null)return true;
        if(event.button()!=InputConstants.MOUSE_BUTTON_RIGHT) {
            closePreview();return true;
        }
        consumedRightClick=true;
        if(!received || !healthy || pending || !ClientPlayNetworking.canSend(BagActionPayload.TYPE))return false;
        UUID current=inspection();
        Entry chosen=entries.stream().filter(entry->entry.uuid().equals(current)).findFirst().orElse(null);
        if(chosen!=null) {
            inspected=chosen.uuid();pending=true;selectionTick=ticks;
            ClientPlayNetworking.send(new BagActionPayload(chosen.slot(),chosen.uuid()));
        }
        return false;
    }
    private static List<Entry> selectable() {
        return entries.stream().filter(entry->entry.status().equals("stored") && entry.slot()<capacity).toList();
    }
    private static List<Entry> scrollable() {
        List<Entry> available=new ArrayList<>(entries.stream().filter(entry->entry.slot()<capacity).toList());
        available.addAll(entries.stream().filter(entry->entry.slot()>=capacity).toList());
        // Vanilla also constructs tooltip components to ask whether they support a carried item.
        // That query must not recreate browsing state after leaving the bag slot.
        if(previewSlot==null)return available;
        List<UUID> members=available.stream().map(Entry::uuid).toList();
        // Keep the browsing order stable when a confirmed right-click reorders the roster.
        if(!new HashSet<>(members).equals(new HashSet<>(scrollOrder))) scrollOrder=members;
        List<Entry> ordered=new ArrayList<>();
        for(UUID uuid:scrollOrder) for(Entry entry:available) if(entry.uuid().equals(uuid)) ordered.add(entry);
        return ordered;
    }

    private static UUID inspection() {
        if(inspected!=null && entries.stream().anyMatch(entry->entry.uuid().equals(inspected))) return inspected;
        List<Entry> available=selectable();
        Entry next=available.stream().filter(entry->entry.slot()==selected).findFirst()
            .orElse(available.isEmpty()?null:available.getFirst());
        return next!=null?next.uuid():entries.isEmpty()?null:entries.getFirst().uuid();
    }

    private static boolean allowScroll(Screen screen,double x,double y,double horizontal,double vertical) {
        if(updateHover(screen,x,y)==null)return true;
        if(!healthy || !received || pending) return false;
        var movement=wheel.onMouseScroll(horizontal,vertical);
        int scroll=movement.y!=0?movement.y:-movement.x;
        if(scroll==0) return false;
        List<Entry> available=scrollable();
        if(available.size()<2) return false;
        int index=0;
        UUID current=inspection();
        for(int i=0;i<available.size();i++) if(available.get(i).uuid().equals(current)) index=i;
        int next=ScrollWheelHandler.getNextScrollWheelSelection(scroll,index,available.size());
        inspected=available.get(next).uuid();
        return false;
    }

    private static String typeFromName(String name) {
        return name.startsWith("entity.minecraft.")?"minecraft:"+name.substring("entity.minecraft.".length()):"";
    }
}
