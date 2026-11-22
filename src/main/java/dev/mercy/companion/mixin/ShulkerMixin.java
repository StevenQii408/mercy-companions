package dev.mercy.companion.mixin;
import dev.mercy.companion.CompanionService;
import dev.mercy.companion.ai.ShulkerCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Shulker.class)
public abstract class ShulkerMixin implements ShulkerCompanion {
    @Shadow protected abstract boolean teleportSomewhere();
    @Shadow private boolean canStayAt(BlockPos pos,Direction face) { throw new AssertionError(); }
    @Shadow private void setAttachFace(Direction face) { throw new AssertionError(); }
    @Override public boolean mercy$teleportNear(Vec3 destination) {
        Shulker self=(Shulker)(Object)this;
        BlockPos center=BlockPos.containing(destination);
        // Keep the native attachment/collision rule instead of placing a shulker in midair.
        for (int i=0;i<32;i++) {
            BlockPos pos=center.offset(self.getRandom().nextInt(7)-3,self.getRandom().nextInt(5)-2,self.getRandom().nextInt(7)-3);
            if (pos.distSqr(center)<2 || !self.level().hasChunkAt(pos)) continue;
            for (Direction face:Direction.values()) {
                if (!canStayAt(pos,face)) continue;
                setAttachFace(face);
                self.setPos(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5);
                self.playSound(SoundEvents.SHULKER_TELEPORT,1.0F,1.0F);
                return true;
            }
        }
        return false;
    }
    @Inject(method="hitByShulkerBullet",at=@At("HEAD"),cancellable=true)
    private void mercy$noReplication(CallbackInfo ci) {
        if (CompanionService.isCompanion((Shulker)(Object)this)) {
            teleportSomewhere();
            ci.cancel();
        }
    }
}
