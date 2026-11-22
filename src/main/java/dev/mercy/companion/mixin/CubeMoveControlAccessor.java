package dev.mercy.companion.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(targets="net.minecraft.world.entity.monster.cubemob.AbstractCubeMob$CubeMobMoveControl")
public interface CubeMoveControlAccessor {
    @Invoker("setDirection") void mercy$setDirection(float angle,boolean aggressive);
    @Invoker("setWantedMovement") void mercy$setWantedMovement(double speed);
}
