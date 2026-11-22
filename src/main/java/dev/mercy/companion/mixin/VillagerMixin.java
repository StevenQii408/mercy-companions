package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(Villager.class)
public abstract class VillagerMixin {
    // A cured zombie villager retains its roster entry, but not free level-up regeneration.
    @Redirect(method="rewardTradeXp",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/npc/villager/Villager;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z"))
    private boolean mercy$noTradeRegeneration(Villager villager,MobEffectInstance effect) {
        return !CompanionService.isCompanion(villager) && villager.addEffect(effect);
    }
}
