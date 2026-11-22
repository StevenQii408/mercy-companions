package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(AbstractCubeMob.class)
public abstract class CubeMobMixin {
    @Shadow protected abstract void dealDamage(LivingEntity target);
    @Shadow protected abstract boolean canDealDamage();
    @Inject(method="push",at=@At("TAIL"))
    private void mercy$attackOtherMonsters(Entity other,CallbackInfo ci) {
        AbstractCubeMob self=(AbstractCubeMob)(Object)this;
        if (!self.level().isClientSide() && CompanionService.isCompanion(self)
                && other==self.getTarget() && other instanceof LivingEntity living
                && !(other instanceof IronGolem) && canDealDamage()) dealDamage(living);
    }
    @Inject(method="getSplitCount",at=@At("HEAD"),cancellable=true)
    private void mercy$noFreeChildren(CallbackInfoReturnable<Integer> cir) {
        if (CompanionService.isCompanion((Entity)(Object)this)) cir.setReturnValue(0);
    }
}
