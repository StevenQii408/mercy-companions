package dev.mercy.companion.mixin;
import dev.mercy.companion.ai.AiController;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Brain.class)
public abstract class BrainMixin {
    @Inject(method="eraseMemory",at=@At("HEAD"),cancellable=true)
    private void mercy$keepCommandedTarget(MemoryModuleType<?> type,CallbackInfo ci) {
        if (!AiController.allowBrainErase(type)) ci.cancel();
    }
    @Inject(method="tick",at=@At("HEAD"))
    private void mercy$before(ServerLevel level, LivingEntity entity, CallbackInfo ci) { AiController.beforeBrain(entity); }
    @Inject(method="tick",at=@At("RETURN"))
    private void mercy$after(ServerLevel level, LivingEntity entity, CallbackInfo ci) { AiController.afterBrain(); }
    @Inject(method="setMemoryInternal(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Ljava/lang/Object;)V",at=@At("HEAD"),cancellable=true)
    private void mercy$memory(MemoryModuleType<?> type,Object value,CallbackInfo ci) {
        if (!AiController.allowBrainMemory(type,value)) ci.cancel();
    }
    @Inject(method="setMemoryInternal(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Ljava/lang/Object;J)V",at=@At("HEAD"),cancellable=true)
    private void mercy$expiringMemory(MemoryModuleType<?> type,Object value,long duration,CallbackInfo ci) {
        if (!AiController.allowBrainMemory(type,value)) ci.cancel();
    }
}
