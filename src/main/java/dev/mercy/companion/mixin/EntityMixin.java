package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import dev.mercy.companion.ai.TemporaryMinions;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method="shouldBeSaved",at=@At("HEAD"),cancellable=true)
    private void mercy$singlePersistenceAuthority(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity)(Object)this;
        if (CompanionService.isCompanion(self) || TemporaryMinions.isTemporary(self)) cir.setReturnValue(false);
    }
    @Inject(method="setRemoved",at=@At("HEAD"))
    private void mercy$removeSummons(Entity.RemovalReason reason, CallbackInfo ci) {
        TemporaryMinions.removeForCaster((Entity)(Object)this);
    }
}
