package dev.mercy.companion.mixin;
import dev.mercy.companion.ai.AiController;
import dev.mercy.companion.CompanionService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
    @Inject(method="canHitEntity",at=@At("HEAD"),cancellable=true)
    private void mercy$ownerSafety(Entity target,CallbackInfoReturnable<Boolean> cir) {
        if ((Object)this instanceof AbstractArrow && AiController.protects(target,((Projectile)(Object)this).getOwner())) cir.setReturnValue(false);
    }
    @Inject(method={"mayInteract","mayBreak"},at=@At("HEAD"),cancellable=true)
    private void mercy$noPetFire(ServerLevel level,BlockPos pos,CallbackInfoReturnable<Boolean> cir) {
        if (CompanionService.isCompanion(AiController.responsible((Entity)(Object)this))) cir.setReturnValue(false);
    }
}
