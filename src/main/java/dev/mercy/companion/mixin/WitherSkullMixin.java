package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(WitherSkull.class)
public abstract class WitherSkullMixin {
    @Redirect(method="onHitEntity",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/LivingEntity;heal(F)V"))
    private void mercy$noLifesteal(LivingEntity self,float health) {
        if (!CompanionService.isCompanion(self)) self.heal(health);
    }
}
