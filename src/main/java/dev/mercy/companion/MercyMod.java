package dev.mercy.companion;

import dev.mercy.companion.ai.AiController;
import dev.mercy.companion.net.MercyNetworking;
import dev.mercy.companion.net.BagTooltipData;
import java.util.Optional;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.util.Unit;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MercyMod implements ModInitializer {
    public static final String ID = "mercy_companions";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);
    public static final ResourceKey<Item> SWORD_KEY = ResourceKey.create(Registries.ITEM, id("mercy_sword"));
    public static final Item SWORD = new Item(new Item.Properties().setId(SWORD_KEY)
            .sword(ToolMaterial.WOOD, 3.0F, -2.4F).enchantable(15)
            .rarity(Rarity.EPIC)
            .component(DataComponents.UNBREAKABLE, Unit.INSTANCE));
    public static final ResourceKey<Item> BAG_KEY = ResourceKey.create(Registries.ITEM, id("companion_bag"));
    public static final Item BAG = new Item(new Item.Properties().setId(BAG_KEY).stacksTo(1).rarity(Rarity.EPIC)) {
        @Override public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
            return Optional.of(BagTooltipData.INSTANCE);
        }
        @Override public InteractionResult useOn(UseOnContext context) {
            if (context.getClickedFace() != Direction.UP) return InteractionResult.PASS;
            if (context.getPlayer() instanceof ServerPlayer player) {
                CompanionService.summonAt(player, context.getClickedPos());
                MercyNetworking.update(player);
            }
            return InteractionResult.SUCCESS;
        }
    };
    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath(ID, path); }
    @Override public void onInitialize() {
        Registry.register(BuiltInRegistries.ITEM, SWORD_KEY, SWORD);
        Registry.register(BuiltInRegistries.ITEM, BAG_KEY, BAG);
        CompanionService.init();
        AiController.init();
        MercyNetworking.init();
        // Intercept before mount/trade interactions so every companion species can be put away.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!player.getItemInHand(hand).is(BAG) || !(entity instanceof Mob mob)) return InteractionResult.PASS;
            if (player instanceof ServerPlayer serverPlayer) {
                CompanionService.manualRecall(serverPlayer, mob);
                MercyNetworking.update(serverPlayer);
            }
            return InteractionResult.SUCCESS;
        });
        LOGGER.info("Mercy Companions 0.4.2 initialized for Minecraft 26.3");
    }
}
