package dev.mercy.companion.testclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mercy.companion.CompanionService;
import dev.mercy.companion.MercyMod;
import dev.mercy.companion.client.CompanionParticles;
import dev.mercy.companion.client.RosterRefresh;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Observes actual local potion spirals emitted by the production client in the disposable world. */
final class ParticleVisualChecks {
    private static CompletableFuture<UUID> setup;
    private static UUID expected;
    private static int age,observedBlue,observedWhite,authorizedFrames;
    private static boolean refreshStarted;

    static void begin(Minecraft client) {
        age=0;observedBlue=0;observedWhite=0;authorizedFrames=0;refreshStarted=false;expected=null;
        setup=client.getSingleplayerServer().submit(()-> {
            var server=client.getSingleplayerServer();
            var player=server.getPlayerList().getPlayer(client.player.getUUID());
            var level=(net.minecraft.server.level.ServerLevel)player.level();
            var roster=JsonParser.parseString(CompanionService.rosterJson(player)).getAsJsonObject();
            JsonObject stored=null;
            for(var value:roster.getAsJsonArray("slots"))if(value.getAsJsonObject().get("status").getAsString().equals("stored")){stored=value.getAsJsonObject();break;}
            require(stored!=null,"Particle fixture has no stored companion");
            UUID id=UUID.fromString(stored.get("uuid").getAsString());
            CompanionService.select(player,stored.get("slot").getAsInt(),id.toString());
            player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(MercyMod.BAG));
            BlockPos support=player.blockPosition().offset(4,-1,0);
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                level.setBlockAndUpdate(support.offset(dx,0,dz),Blocks.STONE.defaultBlockState());
                for(int dy=1;dy<=5;dy++)level.setBlockAndUpdate(support.offset(dx,dy,dz),Blocks.AIR.defaultBlockState());
            }
            require(CompanionService.summonAt(player,support),"Real ground summon failed for particle fixture");
            // Marker refresh must work even after every bag leaves this player's inventory.
            for(int slot=0;slot<player.getInventory().getContainerSize();slot++)
                if(player.getInventory().getItem(slot).is(MercyMod.BAG))player.getInventory().setItem(slot,ItemStack.EMPTY);
            player.inventoryMenu.broadcastChanges();
            return id;
        });
    }

    static boolean tick(Minecraft client) throws Exception {
        if(!setup.isDone())return false;
        expected=setup.join();
        if(!refreshStarted) {
            CompanionParticles.clear();RosterRefresh.clear();
            refreshStarted=true;
            client.getSingleplayerServer().execute(()-> {
                var entity=client.getSingleplayerServer().overworld().getEntityInAnyDimension(expected);
                if(entity instanceof Mob mob)mob.setNoAi(true);
            });
        }
        if(age++%20==0)RosterRefresh.request();
        LivingEntity pet=null;
        for(var entity:client.level.entitiesForRendering())if(entity.getUUID().equals(expected)&&entity instanceof LivingEntity living)pet=living;
        if(pet!=null) {
            double dx=pet.getX()-client.player.getX(),dz=pet.getZ()-client.player.getZ();
            double dy=pet.getY()+Math.min(pet.getBbHeight()*.6,1.4)-client.player.getEyeY();
            client.player.setYRot((float)(Math.atan2(dz,dx)*180/Math.PI)-90);
            client.player.setXRot((float)(-Math.atan2(dy,Math.sqrt(dx*dx+dz*dz))*180/Math.PI));
            Object roster=field(CompanionParticles.class,"ROSTER",null);
            var mark=roster.getClass().getDeclaredMethod("shouldMark",UUID.class,String.class);mark.setAccessible(true);
            String dimension=client.level.dimension().identifier().toString();
            if((boolean)mark.invoke(roster,expected,dimension))authorizedFrames++;
            require(!(boolean)mark.invoke(roster,UUID.fromString("00000000-0000-0000-0000-000000000999"),dimension),"Wild UUID authorized for particle marker");
            require(!(boolean)mark.invoke(roster,expected,"minecraft:the_nether"),"Other-dimension entity authorized for marker");
            int[] samples=countPotionSpirals(client,pet);observedBlue+=samples[0];observedWhite+=samples[1];
        }
        if(age<80)return false;
        require(pet!=null&&authorizedFrames>=20,"Personal active roster did not synchronize without carrying a bag");
        require(observedBlue>0&&observedWhite>0,"ParticleEngine did not receive both blue and white potion spirals beside the summoned companion");
        JsonObject result=new JsonObject();result.addProperty("active_uuid",expected.toString());
        result.addProperty("authorized_frames",authorizedFrames);result.addProperty("particle_type","minecraft:entity_effect");
        result.addProperty("observed_blue_spell_samples",observedBlue);result.addProperty("observed_white_spell_samples",observedWhite);
        result.addProperty("refresh_without_bag",true);result.addProperty("wild_uuid_rejected",true);result.addProperty("different_dimension_rejected",true);
        Files.writeString(client.gameDirectory.toPath().resolve("particle-results.json"),result.toString());
        return true;
    }

    private static int[] countPotionSpirals(Minecraft client,LivingEntity pet) throws Exception {
        Set<Particle> candidates=Collections.newSetFromMap(new IdentityHashMap<>());
        Object engine=client.particleEngine;
        candidates.addAll((Queue<Particle>)field(ParticleEngine.class,"particlesToAdd",engine));
        var groups=(Map<?,?>)field(ParticleEngine.class,"particles",engine);
        for(Object group:groups.values())candidates.addAll((Queue<Particle>)field(ParticleGroup.class,"particles",group));
        int[] count=new int[2];
        for(Particle particle:candidates)if(particle instanceof SpellParticle&&particle.isAlive()) {
            double x=(double)field(Particle.class,"x",particle),y=(double)field(Particle.class,"y",particle),z=(double)field(Particle.class,"z",particle);
            if(pet.distanceToSqr(x,y,z)>=9)continue;
            float red=(float)field(SingleQuadParticle.class,"rCol",particle),green=(float)field(SingleQuadParticle.class,"gCol",particle),blue=(float)field(SingleQuadParticle.class,"bCol",particle);
            if(close(red,0x72/255F)&&close(green,0xC7/255F)&&close(blue,1F))count[0]++;
            if(close(red,0xED/255F)&&close(green,0xF8/255F)&&close(blue,1F))count[1]++;
        }
        return count;
    }
    private static boolean close(float actual,float expected) {return Math.abs(actual-expected)<.01F;}
    private static Object field(Class<?> type,String name,Object owner) throws Exception {
        var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
