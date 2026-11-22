package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Creeper.class)
public abstract class CreeperMixin {
    @Inject(method="explodeCreeper",at=@At("HEAD"))
    private void mercy$consume(CallbackInfo ci) {
        LivingEntity self=(LivingEntity)(Object)this;
        if (!self.level().isClientSide() && CompanionService.isCompanion(self)) CompanionService.onDeath(self);
    }
}
