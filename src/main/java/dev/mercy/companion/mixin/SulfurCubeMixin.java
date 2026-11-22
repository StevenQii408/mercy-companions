package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import java.util.Optional;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Bucketable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SulfurCube.class)
public abstract class SulfurCubeMixin {
    @Inject(method="tickFuse",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/monster/cubemob/SulfurCube;discard()V"))
    private void mercy$permanentExplosionDeath(CallbackInfo ci) {
        SulfurCube cube=(SulfurCube)(Object)this;
        if (CompanionService.isCompanion(cube)) CompanionService.onDeath(cube);
    }
    @Redirect(method="mobInteract",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Bucketable;bucketMobPickup(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/entity/LivingEntity;)Ljava/util/Optional;"))
    private Optional<InteractionResult> mercy$rosterStorageOnly(Player player,InteractionHand hand,LivingEntity cube) {
        if (CompanionService.isCompanion(cube)) return Optional.empty();
        return Bucketable.bucketMobPickup(player,hand,(SulfurCube)cube);
    }
}
