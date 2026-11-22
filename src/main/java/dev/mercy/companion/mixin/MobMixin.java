package dev.mercy.companion.mixin;
import dev.mercy.companion.ai.AiController;
import dev.mercy.companion.ai.TemporaryMinions;
import dev.mercy.companion.CompanionService;
import net.minecraft.world.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Mob.class)
public abstract class MobMixin {
    @Inject(method="checkDespawn", at=@At("HEAD"), cancellable=true)
    private void mercy$keepWorldCompanion(CallbackInfo ci) {
        Mob self = (Mob)(Object)this;
        if (CompanionService.isCompanion(self) || TemporaryMinions.isTemporary(self)) ci.cancel();
    }
    @Inject(method="setTarget", at=@At("HEAD"), cancellable=true)
    private void mercy$targetFilter(LivingEntity target, CallbackInfo ci) {
        if (!AiController.allowTargetSelection((Mob)(Object)this,target)) ci.cancel();
    }
}
