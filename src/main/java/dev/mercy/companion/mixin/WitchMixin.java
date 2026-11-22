package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import net.minecraft.world.entity.monster.Witch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(Witch.class)
public abstract class WitchMixin {
    // Hide missing health only from the decision to create a free healing potion.
    @Redirect(method="aiStep",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/monster/Witch;getHealth()F"))
    private float mercy$noFreeHealing(Witch self) {
        return CompanionService.isCompanion(self) ? self.getMaxHealth() : self.getHealth();
    }
}
