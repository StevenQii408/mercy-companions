package dev.mercy.companion.mixin;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Phantom.class)
public interface PhantomAccessor {
    @Accessor("moveTargetPoint") void mercy$setMoveTargetPoint(Vec3 point);
    @Accessor("anchorPoint") void mercy$setAnchorPoint(BlockPos point);
}
