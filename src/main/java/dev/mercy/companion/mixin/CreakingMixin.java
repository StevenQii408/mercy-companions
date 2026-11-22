package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import dev.mercy.companion.ai.AiController;
import dev.mercy.companion.ai.CreakingCompanion;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.monster.creaking.Creaking;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(Creaking.class)
public abstract class CreakingMixin implements CreakingCompanion {
    @Shadow @Final private static EntityDataAccessor<Optional<BlockPos>> HOME_POS;
    @Shadow @Final private static EntityDataAccessor<Boolean> IS_TEARING_DOWN;
    @Override public void mercy$detachHeart() {
        Creaking self=(Creaking)(Object)this;
        self.getEntityData().set(HOME_POS,Optional.empty());
        self.getEntityData().set(IS_TEARING_DOWN,false);
    }
    // A heart-bound creaking intercepts damage before LivingEntity's death event.
    // Only a genuinely lethal, primary mercy-sword hit is permitted through that immunity.
    @Inject(method="hurtServer",at=@At("HEAD"),cancellable=true)
    private void mercy$heartCapture(ServerLevel level,DamageSource source,float damage,CallbackInfoReturnable<Boolean> cir) {
        Creaking self=(Creaking)(Object)this;
        if (self.isHeartBound() && self.isAlive() && damage >= self.getHealth()
                && AiController.isPrimarySwordStrike(self,source) && CompanionService.canCapture(self,source)
                && CompanionService.capture(self,source)) cir.setReturnValue(true);
    }
    @Inject(method="checkCanMove",at=@At("HEAD"),cancellable=true)
    private void mercy$companionMovement(CallbackInfoReturnable<Boolean> cir) {
        Creaking self=(Creaking)(Object)this;
        if (CompanionService.isCompanion(self)) { self.setIsActive(true); cir.setReturnValue(true); }
    }
    @Inject(method={"tearDown","setTearingDown"},at=@At("HEAD"),cancellable=true)
    private void mercy$notOwnedByHeart(CallbackInfo ci) {
        if (CompanionService.isCompanion((Creaking)(Object)this)) ci.cancel();
    }
}
