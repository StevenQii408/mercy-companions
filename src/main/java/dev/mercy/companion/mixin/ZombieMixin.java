package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(Zombie.class)
public abstract class ZombieMixin {
    @Redirect(method="hurtServer",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/monster/zombie/Zombie;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
    private double mercy$noReinforcements(Zombie zombie,Holder<Attribute> attribute) {
        if (CompanionService.isCompanion(zombie) && attribute==Attributes.SPAWN_REINFORCEMENTS_CHANCE) return 0;
        return zombie.getAttributeValue(attribute);
    }
}
