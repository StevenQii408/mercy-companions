package dev.mercy.companion.testclient;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Executes only in the disposable client with the user's requested local addons. */
final class AddonCompatibilityChecks {
    static void verify(Minecraft client) throws Exception {
        if (!Boolean.getBoolean("mercy.ui.addons")) return;
        JsonObject result = new JsonObject();
        for (String id : new String[]{"iris", "sodium", "freecam", "gammautils", "cloth-config", "xaerominimap", "xaeroworldmap"}) {
            require(FabricLoader.getInstance().isModLoaded(id), "Missing addon " + id);
            result.addProperty(id, FabricLoader.getInstance().getModContainer(id).orElseThrow().getMetadata().getVersion().getFriendlyString());
        }
        Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
        String pack = (String) iris.getMethod("getCurrentPackName").invoke(null);
        require(pack.contains("ComplementaryUnbound_r5.9.3"), "Wrong shader pack: " + pack);
        require((boolean) iris.getMethod("isPackInUseQuick").invoke(null), "Shader pack is not active");
        require(!(boolean) iris.getMethod("isFallback").invoke(null), "Iris fell back to vanilla rendering");
        require(((Optional<?>) iris.getMethod("getStoredError").invoke(null)).isEmpty(), "Iris recorded a shader error");
        result.addProperty("active_shader_pack", pack);
        Class<?> gamma = Class.forName("io.github.sjouwer.gammautils.GammaUtils");
        Object config = gamma.getMethod("getConfig").invoke(null);
        Object night = config.getClass().getField("nightVision").get(config);
        boolean enabled = (boolean) night.getClass().getMethod("isEnabled").invoke(night);
        double strength = ((Number) night.getClass().getMethod("getValue").invoke(night)).doubleValue();
        require(enabled && strength == 100.0, "Original unlimited night vision configuration was not loaded");
        result.addProperty("night_vision_enabled", enabled);
        result.addProperty("night_vision_strength", strength);
        Class<?> freecam = Class.forName("net.xolt.freecam.Freecam");
        require(!(boolean) freecam.getMethod("isEnabled").invoke(null), "Unexpected active Freecam at fixture start");
        try {
            freecam.getMethod("toggle").invoke(null);
            require((boolean) freecam.getMethod("isEnabled").invoke(null), "Freecam did not activate");
            require(freecam.getMethod("getFreeCamera").invoke(null) != null, "Freecam camera was not created");
        } finally {
            if ((boolean) freecam.getMethod("isEnabled").invoke(null)) freecam.getMethod("toggle").invoke(null);
        }
        require(!(boolean) freecam.getMethod("isEnabled").invoke(null), "Freecam did not deactivate");
        result.addProperty("freecam_enable_disable", true);
        Files.writeString(client.gameDirectory.toPath().resolve("addon-results.json"), result.toString());
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
