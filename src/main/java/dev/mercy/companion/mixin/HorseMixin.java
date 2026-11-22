package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(AbstractHorse.class)
public abstract class HorseMixin {
    @Redirect(method="aiStep",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/animal/equine/AbstractHorse;heal(F)V"))
    private void mercy$noMountRegeneration(AbstractHorse horse,float amount) {
        if (!CompanionService.isCompanion(horse)) horse.heal(amount);
    }
}
