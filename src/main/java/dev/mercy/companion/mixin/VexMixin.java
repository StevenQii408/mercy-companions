package dev.mercy.companion.mixin;

import dev.mercy.companion.ai.TemporaryMinions;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Vex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Vex.class)
public abstract class VexMixin {
    @Inject(method="setOwner", at=@At("TAIL"))
    private void mercy$temporarySummon(Mob caster, CallbackInfo ci) {
        TemporaryMinions.bind((Vex)(Object)this, caster);
    }

    @Inject(method="tick", at=@At("HEAD"), cancellable=true)
    private void mercy$inheritCaster(CallbackInfo ci) {
        Vex self = (Vex)(Object)this;
        if (!self.level().isClientSide() && !TemporaryMinions.tick(self)) ci.cancel();
    }
}
