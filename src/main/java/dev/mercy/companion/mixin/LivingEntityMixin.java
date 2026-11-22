package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import dev.mercy.companion.ai.AiController;
import dev.mercy.companion.ai.TemporaryMinions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method="canAttack", at=@At("HEAD"), cancellable=true)
    private void mercy$noFriendlyTargets(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if ((Object)this instanceof Mob mob && !AiController.canTarget(mob, target)) cir.setReturnValue(false);
    }
    @Inject(method="setLastHurtByMob", at=@At("HEAD"), cancellable=true)
    private void mercy$noFriendlyRevenge(LivingEntity attacker, CallbackInfo ci) {
        if ((Object)this instanceof Mob mob && !AiController.canTarget(mob, attacker)) ci.cancel();
    }
    @Inject(method="die", at=@At("HEAD"))
    private void mercy$permanentDeath(DamageSource source, CallbackInfo ci) {
        LivingEntity self=(LivingEntity)(Object)this;
        if (!self.level().isClientSide()) TemporaryMinions.removeForCaster(self);
        if (!self.level().isClientSide() && CompanionService.isCompanion(self)) CompanionService.onDeath(self);
    }
    @Inject(method="dropAllDeathLoot", at=@At("HEAD"), cancellable=true)
    private void mercy$noPetLoot(ServerLevel level, DamageSource source, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (CompanionService.isCompanion(self) || TemporaryMinions.isTemporary(self)) ci.cancel();
    }
}
