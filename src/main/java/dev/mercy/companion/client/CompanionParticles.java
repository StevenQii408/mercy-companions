package dev.mercy.companion.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.LivingEntity;

/** Local blue-white potion spirals; no particles are broadcast to other players. */
public final class CompanionParticles {
    private static final CompanionParticleRoster ROSTER=new CompanionParticleRoster();
    private static final ColorParticleOption BLUE=ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT,0xFF72C7FF);
    private static final ColorParticleOption WHITE=ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT,0xFFEDF8FF);
    private static int ticks;
    private CompanionParticles() {}

    public static void init() {ClientTickEvents.END_CLIENT_TICK.register(CompanionParticles::tick);}
    public static void accept(String json) {ROSTER.accept(json);}
    public static void clear() {ROSTER.clear();ticks=0;}

    private static void tick(Minecraft client) {
        if(client.level==null||client.player==null||!client.player.isAlive()||client.isPaused())return;
        ticks++;
        // Keep polling unloaded and other-dimension roster entries, so reappearing allies
        // regain their marker even when the player is no longer carrying the bag.
        if(ROSTER.hasDeployed()&&ticks%20==0)RosterRefresh.request();
        if(ticks%8!=0||!ROSTER.hasDeployed())return;
        String dimension=client.level.dimension().identifier().toString();
        for(var entity:client.level.entitiesForRendering()) {
            if(!(entity instanceof LivingEntity living)||!living.isAlive()||living.isRemoved()
                    ||living.isInvisibleTo(client.player)||client.player.distanceToSqr(living)>32*32
                    ||!ROSTER.shouldMark(living.getUUID(),dimension))continue;
            double halfWidth=Math.max(living.getBbWidth()*.5+.15,.3);
            double height=Math.max(living.getBbHeight(),.3);
            for(int i=0;i<2;i++) {
                double angle=(living.getUUID().hashCode()&1023)*Math.PI/512+ticks*.12+i*Math.PI;
                double dx=Math.cos(angle),dz=Math.sin(angle);
                // Stay outside the full body's sides, including large ghasts and giants.
                double radius=halfWidth/Math.max(Math.abs(dx),Math.abs(dz));
                double y=living.getY()+.1+height*(.2+.15*((ticks/8+i*2)%5));
                client.level.addParticle(i==0?BLUE:WHITE,living.getX()+dx*radius,y,
                        living.getZ()+dz*radius,0,.025,0);
            }
        }
    }
}
