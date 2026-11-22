package dev.mercy.companion.client;

import java.util.UUID;

/** Headless checks for the server-authoritative owner marker filter. */
public final class CompanionParticleRosterTest {
    private static final UUID OWN=UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String WORLD="minecraft:overworld";
    private static int checks;
    public static void main(String[] args) {
        CompanionParticleRoster roster=new CompanionParticleRoster();
        roster.accept(payload("active",true,true,20,WORLD));
        check(roster.shouldMark(OWN,WORLD),"active loaded own companion is marked");
        check(!roster.shouldMark(UUID.randomUUID(),WORLD),"wild or another owner's UUID is never inferred as companion");
        check(!roster.shouldMark(OWN,"minecraft:the_nether"),"other-dimension companion is not marked");
        roster.accept(payload("stored",false,true,20,WORLD));
        check(!roster.shouldMark(OWN,WORLD)&&!roster.hasDeployed(),"recall clears marker immediately");
        roster.accept(payload("active",true,true,0,WORLD));
        check(!roster.shouldMark(OWN,WORLD),"dead companion is not marked");
        roster.accept(payload("active",true,false,20,WORLD));
        check(!roster.shouldMark(OWN,WORLD)&&roster.hasDeployed(),"unloaded companion stays refreshable without rendering");
        roster.accept(payload("active",true,true,20,WORLD));
        roster.accept("{\"healthy\":false,\"slots\":[]}");
        check(!roster.hasDeployed(),"unhealthy roster cannot retain stale markers");
        roster.accept(payload("active",true,true,20,WORLD));roster.accept("malformed");
        check(!roster.hasDeployed(),"malformed replacement fails closed");
        roster.accept(payload("active",true,true,20,WORLD));roster.clear();
        check(!roster.hasDeployed(),"disconnect clears all markers");
        System.out.println("Companion particle roster: "+checks+" checks passed");
    }
    private static String payload(String status,boolean deployed,boolean loaded,int health,String dimension) {
        return "{\"healthy\":true,\"slots\":[{\"uuid\":\""+OWN+"\",\"status\":\""+status
            +"\",\"deployed\":"+deployed+",\"loaded\":"+loaded+",\"health\":"+health+",\"dimension\":\""+dimension+"\"}]}";
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
}
