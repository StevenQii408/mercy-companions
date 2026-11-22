package dev.mercy.companion.net;

import net.minecraft.world.inventory.tooltip.TooltipComponent;

/** Common-side marker; the client snapshots its server-confirmed roster when creating the preview. */
public record BagTooltipData() implements TooltipComponent {
    public static final BagTooltipData INSTANCE = new BagTooltipData();
}
