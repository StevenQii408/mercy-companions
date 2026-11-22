package dev.mercy.maptest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;

/** Optional addon initialization checks; this fixture never opens a world. */
public final class XaeroSmoke implements ClientModInitializer {
    private int ticks;
    private final boolean carry = Boolean.getBoolean("mercy.smoke.carryon");
    private final String stem = carry ? "carryon-smoke" : "xaero-smoke";

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!client.isGameLoadFinished()) return;
            client.getWindow().setTitle(carry
                ? "Carry On initialization test (closes automatically)"
                : "Xaero initialization test (closes automatically)");
            if (++ticks != 40) return;
            try {
                JsonObject result = new JsonObject(), mods = new JsonObject(), keys = new JsonObject();
                for (String id : new String[]{"mercy_companions", "xaerominimap", "xaeroworldmap", "xaerolib", "iris", "sodium"}) {
                    mods.addProperty(id, FabricLoader.getInstance().getModContainer(id).orElseThrow()
                        .getMetadata().getVersion().getFriendlyString());
                }
                for (String name : new String[]{"gui.xaero_open_map", "gui.xaero_new_waypoint",
                        "gui.xaero_waypoints_key", "gui.xaero_minimap_settings", "gui.xaero_enlarge_map"}) {
                    KeyMapping key = KeyMapping.get(name);
                    if (key == null) throw new AssertionError("Missing map key " + name);
                    keys.addProperty(name, key.saveString());
                }
                if (carry) {
                    mods.addProperty("carryon", FabricLoader.getInstance().getModContainer("carryon")
                        .orElseThrow().getMetadata().getVersion().getFriendlyString());
                    KeyMapping pickup = KeyMapping.get("key.carry.desc");
                    if (pickup == null) throw new AssertionError("Missing Carry On pickup binding");
                    keys.addProperty("key.carry.desc", pickup.saveString());
                    JsonArray shared = new JsonArray();
                    for (KeyMapping key : client.options.keyMappings) {
                        if (key != pickup && key.saveString().equals(pickup.saveString())) shared.add(key.getName());
                    }
                    result.add("pickupSharedBindings", shared);
                    var input = InputConstants.getKey(pickup.saveString());
                    boolean both;
                    KeyMapping.set(input, true);
                    try {
                        both = pickup.isDown() && client.options.keyShift.isDown();
                    } finally {
                        KeyMapping.set(input, false);
                    }
                    result.addProperty("pickupAndSneakReceiveSameKey", both);
                    if (!both) throw new AssertionError("Default pickup and sneak did not both receive Shift");
                }
                result.add("loadedMods", mods);
                result.add("keyBindings", keys);
                result.addProperty("gameLoadFinished", true);
                result.addProperty("screen", client.gui.screen() == null ? "null" : client.gui.screen().getClass().getName());
                Files.writeString(client.gameDirectory.toPath().resolve(stem + "-result.json"), result.toString());
                System.out.println("MERCY_XAERO_SMOKE_PASS " + result);
            } catch (Throwable error) {
                error.printStackTrace();
                try {
                    Files.writeString(client.gameDirectory.toPath().resolve(stem + "-error.txt"), error.toString());
                } catch (Exception ignored) {
                }
            }
            client.stop();
        });
    }
}
