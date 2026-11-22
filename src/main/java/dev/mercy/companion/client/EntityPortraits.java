package dev.mercy.companion.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Pixel portraits sampled from vanilla face UVs; resource packs remain effective and no game art is bundled. */
final class EntityPortraits {
    private record Face(Identifier texture,int u,int v,int width,int height,int textureWidth,int textureHeight) {}
    private static final Map<String,Face> FACES=new HashMap<>();
    static {
        face("zombie","zombie/zombie",8,8,8,8,64,64);
        face("giant","zombie/zombie",8,8,8,8,64,64);
        face("zombie_horse","horse/horse_zombie",7,20,6,5,64,64);
        face("camel_husk","camel/camel_husk",28,7,7,14,128,128);
        face("zombie_nautilus","nautilus/zombie_nautilus",14,68,10,8,128,128);
        face("rabbit","rabbit/rabbit_caerbannog",5,21,5,5,64,64);
        face("sulfur_cube","sulfur_cube/sulfur_cube_inner",16,52,16,16,128,128);
        face("husk","zombie/husk",8,8,8,8,64,64);
        face("drowned","zombie/drowned",8,8,8,8,64,64);
        face("zombie_villager","zombie_villager/zombie_villager",8,8,8,10,64,64);
        face("skeleton","skeleton/skeleton",8,8,8,8,64,32);
        face("stray","skeleton/stray",8,8,8,8,64,32);
        face("bogged","skeleton/bogged",8,8,8,8,64,32);
        face("parched","skeleton/parched",8,8,8,8,64,64);
        face("wither_skeleton","skeleton/wither_skeleton",8,8,8,8,64,32);
        face("creeper","creeper/creeper",8,8,8,8,64,32);
        face("spider","spider/spider",40,12,8,8,64,32);
        face("cave_spider","spider/cave_spider",40,12,8,8,64,32);
        face("enderman","enderman/enderman",8,8,8,8,64,32);
        face("blaze","blaze/blaze",8,8,8,8,64,32);
        face("breeze","breeze/breeze",8,8,8,8,32,32);
        face("ghast","ghast/ghast",16,16,16,16,128,64);
        face("warden","warden/warden",10,42,16,16,128,128);
        face("wither","wither/wither",8,8,8,8,64,64);
        face("piglin","piglin/piglin",8,8,10,8,64,64);
        face("piglin_brute","piglin/piglin_brute",8,8,10,8,64,64);
        face("zombified_piglin","piglin/zombified_piglin",8,8,10,8,64,64);
        face("hoglin","hoglin/hoglin",80,20,14,6,128,64);
        face("zoglin","hoglin/zoglin",80,20,14,6,128,64);
        face("pillager","illager/pillager",8,8,8,10,64,64);
        face("vindicator","illager/vindicator",8,8,8,10,64,64);
        face("evoker","illager/evoker",8,8,8,10,64,64);
        face("illusioner","illager/illusioner",8,8,8,10,64,64);
        face("witch","witch/witch",8,8,8,10,64,128);
        face("vex","illager/vex",5,5,5,5,32,32);
        face("ravager","illager/ravager",16,16,16,20,128,128);
        face("guardian","guardian/guardian",16,16,12,12,64,64);
        face("elder_guardian","guardian/guardian_elder",16,16,12,12,64,64);
        face("phantom","phantom/phantom",5,5,7,3,64,64);
        face("shulker","shulker/shulker",6,58,6,6,64,64);
        face("slime","slime/slime",8,8,8,8,64,32);
        face("magma_cube","slime/magmacube",8,8,8,8,64,64);
        face("creaking","creaking/creaking",6,6,6,10,64,64);
        face("silverfish","silverfish/silverfish",2,2,3,2,64,32);
        face("endermite","endermite/endermite",2,2,4,3,64,32);
    }
    private static void face(String entity,String texture,int u,int v,int w,int h,int tw,int th) {
        FACES.put("minecraft:"+entity,new Face(Identifier.withDefaultNamespace("textures/entity/"+texture+".png"),u,v,w,h,tw,th));
    }
    static void draw(GuiGraphicsExtractor graphics,String type,int x,int y) {
        Face face=FACES.get(type);
        if(face==null) {
            graphics.fill(x+1,y+1,x+15,y+15,0xFF7D5893);
            graphics.fill(x+3,y+5,x+6,y+8,0xFFE6C8FF);
            graphics.fill(x+10,y+5,x+13,y+8,0xFFE6C8FF);
            graphics.fill(x+6,y+11,x+10,y+13,0xFF36223E);
            return;
        }
        blit(graphics,face.texture(),face,x,y);
        String eyes=switch(type) {
            case "minecraft:enderman" -> "enderman/enderman_eyes";
            case "minecraft:spider","minecraft:cave_spider" -> "spider/spider_eyes";
            case "minecraft:breeze" -> "breeze/breeze_eyes";
            case "minecraft:creaking" -> "creaking/creaking_eyes";
            case "minecraft:phantom" -> "phantom/phantom_eyes";
            default -> null;
        };
        if(eyes!=null) blit(graphics,Identifier.withDefaultNamespace("textures/entity/"+eyes+".png"),face,x,y);
        if(type.equals("minecraft:guardian") || type.equals("minecraft:elder_guardian")) {
            graphics.fill(x+6,y+6,x+10,y+10,0xFFD9D2B8);
            graphics.fill(x+7,y+7,x+9,y+9,0xFF641E29);
        }
        if(type.equals("minecraft:slime") || type.equals("minecraft:magma_cube")) {
            int eyesColor=type.equals("minecraft:slime")?0xFF254726:0xFFFFC94A;
            graphics.fill(x+3,y+5,x+6,y+8,eyesColor);
            graphics.fill(x+10,y+5,x+13,y+8,eyesColor);
            graphics.fill(x+6,y+11,x+10,y+13,eyesColor);
        }
    }
    private static void blit(GuiGraphicsExtractor graphics,Identifier texture,Face face,int x,int y) {
        int width=face.width()>=face.height()?16:Math.max(1,16*face.width()/face.height());
        int height=face.height()>=face.width()?16:Math.max(1,16*face.height()/face.width());
        graphics.blit(RenderPipelines.GUI_TEXTURED,texture,x+(16-width)/2,y+(16-height)/2,
            face.u(),face.v(),width,height,face.width(),face.height(),face.textureWidth(),face.textureHeight());
    }
    private EntityPortraits() {}
}
