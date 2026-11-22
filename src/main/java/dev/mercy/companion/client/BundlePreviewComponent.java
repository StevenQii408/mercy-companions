package dev.mercy.companion.client;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Uses the vanilla tooltip pipeline and bundle slot sprites, so layout is measured before drawing. */
final class BundlePreviewComponent implements ClientTooltipComponent {
    private static final Identifier SLOT=Identifier.withDefaultNamespace("container/bundle/slot_background");
    private static final Identifier BACK=Identifier.withDefaultNamespace("container/bundle/slot_highlight_back");
    private static final Identifier FRONT=Identifier.withDefaultNamespace("container/bundle/slot_highlight_front");
    private static final int PURPLE=0xFFD5A5FF, GRAY=0xFFAAAAAA, AQUA=0xFF70D8FF;
    private final List<BagTooltip.Entry> regular, overflow;
    private final BagTooltip.Entry chosen;
    private final int capacity, nextSlot;
    private final boolean received, healthy;
    private final Component summary;

    BundlePreviewComponent(List<BagTooltip.Entry> entries,int capacity,int selected,UUID inspected,boolean received,boolean healthy) {
        this.capacity=capacity; this.nextSlot=selected; this.received=received; this.healthy=healthy;
        regular=entries.stream().filter(e->e.slot()<capacity).toList();
        overflow=entries.stream().filter(e->e.slot()>=capacity).toList();
        chosen=entries.stream().filter(entry->entry.uuid().equals(inspected)).findFirst().orElse(null);
        long stored=entries.stream().filter(e->e.status().equals("stored")).count();
        long active=entries.stream().filter(e->e.status().equals("active")).count();
        long locked=entries.stream().filter(e->e.status().equals("locked")).count();
        summary=tr("counts",stored,active,locked);
    }

    @Override public int getWidth(Font font) {
        return Math.max(144,Math.max(font.width(summary),font.width(tr("hint"))));
    }
    @Override public int getHeight(Font font) {
        if(!received || !healthy) return 15;
        return gridHeight(capacity)+(overflow.isEmpty()?0:13+gridHeight(overflow.size()))+49;
    }
    @Override public boolean showTooltipWithItemInHand() { return false; }

    @Override public void extractImage(Font font,int x,int y,int width,int height,GuiGraphicsExtractor graphics) {
        if(!received || !healthy) {
            graphics.text(font,tr(received?"unavailable":"loading"),x,y+2,received?0xFFFF5555:GRAY);
            return;
        }
        drawGrid(graphics,font,regular,capacity,x,y,width);
        int cursor=y+gridHeight(capacity);
        if(!overflow.isEmpty()) {
            graphics.text(font,tr("overflow"),x,cursor+2,GRAY);
            cursor+=13;
            drawGrid(graphics,font,overflow,overflow.size(),x,cursor,width);
            cursor+=gridHeight(overflow.size());
        }
        cursor+=3;
        if(chosen!=null) {
            Component name=chosen.name().startsWith("entity.")?Component.translatable(chosen.name()):Component.literal(chosen.name());
            String statusKey=chosen.status().equals("stored")?(chosen.slot()==nextSlot?"next":"stored"):chosen.status();
            String label=font.plainSubstrByWidth(tr(statusKey,name).getString(),width);
            graphics.text(font,label,x,cursor,PURPLE);
            String hp=tr("health",health(chosen.health()),health(chosen.maximum())).getString();
            graphics.text(font,hp,x,cursor+11,chosen.health()/chosen.maximum()<.3F?0xFFFFAA00:0xFFDDDDDD);
            int barWidth=38, bx=x+width-barWidth, by=cursor+13;
            graphics.fill(bx,by,bx+barWidth,by+5,0xFF35253F);
            graphics.fill(bx,by,bx+Math.max(1,Math.round(barWidth*Math.clamp(chosen.health()/chosen.maximum(),0F,1F))),by+5,PURPLE);
        } else {
            graphics.text(font,tr(regular.isEmpty() && overflow.isEmpty()?"empty":"none_stored"),x,cursor,GRAY);
        }
        graphics.text(font,summary,x,cursor+25,GRAY);
        graphics.text(font,tr("hint"),x,cursor+37,0xFF777777);
    }

    private void drawGrid(GuiGraphicsExtractor graphics,Font font,List<BagTooltip.Entry> contents,int count,int x,int y,int width) {
        int columns=Math.min(4,count), gridX=x+(width-columns*24)/2;
        for(int index=0;index<count;index++) {
            int sx=gridX+(index%4)*24, sy=y+(index/4)*24;
            BagTooltip.Entry entry=index<contents.size()?contents.get(index):null;
            boolean selected=entry!=null && chosen!=null && entry.uuid().equals(chosen.uuid());
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED,selected?BACK:SLOT,sx,sy,24,24);
            if(entry!=null) {
                EntityPortraits.draw(graphics,entry.type(),sx+4,sy+4);
                if(entry.status().equals("stored") && entry.slot()==nextSlot) {
                    graphics.fill(sx+1,sy+14,sx+8,sy+22,0xDD30203C);
                    graphics.text(font,"▶",sx+1,sy+14,PURPLE,false);
                }
                if(entry.status().equals("locked")) {
                    graphics.fill(sx+3,sy+3,sx+21,sy+21,0x77000000);
                    lock(graphics,sx+15,sy+14);
                } else if(entry.status().equals("active")) {
                    graphics.fill(sx+15,sy+13,sx+22,sy+21,0xDD122A3C);
                    graphics.text(font,"↑",sx+16,sy+13,AQUA,false);
                }
            }
            if(selected) graphics.blitSprite(RenderPipelines.GUI_TEXTURED,FRONT,sx,sy,24,24);
        }
    }
    private static void lock(GuiGraphicsExtractor graphics,int x,int y) {
        graphics.fill(x+1,y,x+5,y+5,0xFFE3BC72);
        graphics.fill(x+2,y+1,x+4,y+4,0xFF382A20);
        graphics.fill(x,y+4,x+6,y+8,0xFFE3BC72);
        graphics.fill(x+2,y+5,x+4,y+7,0xFF382A20);
    }
    private static int gridHeight(int count) { return ((count+3)/4)*24; }
    private static String health(float value) {
        return value==Math.rint(value)?Integer.toString((int)value):String.format(Locale.ROOT,"%.1f",value);
    }
    private static Component tr(String key,Object... args) { return Component.translatable("tooltip.mercy_companions.bag."+key,args); }
}
