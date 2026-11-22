package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import dev.mercy.companion.ai.AiController;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(Warden.class)
public abstract class WardenMixin {
    @Inject(method="canTargetEntity",at=@At("HEAD"),cancellable=true)
    private void mercy$ignoreOwner(Entity target,CallbackInfoReturnable<Boolean> cir) {
        if (!AiController.canTarget((Mob)(Object)this,target)) cir.setReturnValue(false);
    }
    @Inject(method="setAttackTarget",at=@At("HEAD"),cancellable=true)
    private void mercy$ignoreFriendlyAttack(LivingEntity target,CallbackInfo ci) {
        if (!AiController.allowTargetSelection((Mob)(Object)this,target)) ci.cancel();
    }
    @Inject(method="increaseAngerAt(Lnet/minecraft/world/entity/Entity;IZ)V",at=@At("HEAD"),cancellable=true)
    private void mercy$ignoreFriendlyNoise(Entity target,int anger,boolean listening,CallbackInfo ci) {
        if (!AiController.canTarget((Mob)(Object)this,target)) ci.cancel();
    }
}
