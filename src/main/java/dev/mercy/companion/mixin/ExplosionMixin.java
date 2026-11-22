package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import dev.mercy.companion.ai.AiController;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ServerExplosion.class)
public abstract class ExplosionMixin {
    @Shadow @Final private Entity source;
    @Inject(method="interactsWithBlocks",at=@At("HEAD"),cancellable=true)
    private void mercy$preserveTerrain(CallbackInfoReturnable<Boolean> cir) {
        if (CompanionService.isCompanion(AiController.responsible(source))) cir.setReturnValue(false);
    }
    @Inject(method="createFire",at=@At("HEAD"),cancellable=true)
    private void mercy$noUnattributedFire(List<BlockPos> blocks,CallbackInfo ci) {
        if (CompanionService.isCompanion(AiController.responsible(source))) ci.cancel();
    }
}
