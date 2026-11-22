package dev.mercy.companion.mixin;
import net.minecraft.world.level.block.entity.CreakingHeartBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(CreakingHeartBlockEntity.class)
public interface CreakingHeartAccessor {
    @Invoker("clearCreakingInfo") void mercy$clearCreakingInfo();
}
