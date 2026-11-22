package dev.mercy.companion.client.mixin;

import dev.mercy.companion.client.BagTooltip;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe real slot hover while leaving tooltip extraction entirely to the container screen. */
@Mixin(AbstractContainerScreen.class)
abstract class ContainerTooltipMixin extends Screen {
    private ContainerTooltipMixin(Component title) {super(title);}

    @Inject(method="extractTooltip",at=@At("HEAD"))
    private void mercy$observeBagTooltip(GuiGraphicsExtractor graphics,int mouseX,int mouseY,CallbackInfo ci) {
        BagTooltip.updateHover((AbstractContainerScreen<?>)(Object)this,mouseX,mouseY);
    }

    @Override public void mouseMoved(double mouseX,double mouseY) {
        BagTooltip.updateHover((AbstractContainerScreen<?>)(Object)this,mouseX,mouseY);
        super.mouseMoved(mouseX,mouseY);
    }
}
