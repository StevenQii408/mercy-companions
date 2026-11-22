package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import dev.mercy.companion.ai.AiController;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(WitherBoss.class)
public abstract class WitherMixin {
    @Shadow private int destroyBlocksTick;
    // WitherBoss overrides Mob.checkDespawn without calling the base implementation.
    @Inject(method="checkDespawn",at=@At("HEAD"),cancellable=true)
    private void mercy$keepWorldCompanion(CallbackInfo ci) {
        if (CompanionService.isCompanion((WitherBoss)(Object)this)) ci.cancel();
    }
    @Inject(method="customServerAiStep",at=@At("HEAD"))
    private void mercy$ordinarySpawn(ServerLevel level,CallbackInfo ci) {
        WitherBoss self=(WitherBoss)(Object)this;
        if (CompanionService.isCompanion(self)) { self.setInvulnerableTicks(0); destroyBlocksTick=0; }
    }
    @Redirect(method="customServerAiStep",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/boss/wither/WitherBoss;heal(F)V"))
    private void mercy$noRegeneration(WitherBoss self,float health) {
        if (!CompanionService.isCompanion(self)) self.heal(health);
    }
    @Inject(method="performRangedAttack(IDDDZ)V",at=@At("HEAD"),cancellable=true)
    private void mercy$noIdleBombardment(int head,double x,double y,double z,boolean dangerous,CallbackInfo ci) {
        WitherBoss self=(WitherBoss)(Object)this;
        // Vanilla launch indices are 0 / 2 / 3; tracked target slots are 0 / 1 / 2.
        int targetSlot=head==0 ? 0 : head-1;
        if (CompanionService.isCompanion(self) && self.getTarget()==null && self.getAlternativeTarget(targetSlot)==0) ci.cancel();
    }
    @Inject(method="setAlternativeTarget",at=@At("HEAD"),cancellable=true)
    private void mercy$noFriendlySideHead(int head,int id,CallbackInfo ci) {
        WitherBoss self=(WitherBoss)(Object)this;
        if (id==0 || !CompanionService.isCompanion(self)) return;
        Entity target=self.level().getEntity(id);
        if (target!=null && !AiController.canTarget(self,target)) {
            self.setAlternativeTarget(head,0);
            ci.cancel();
        }
    }
}
