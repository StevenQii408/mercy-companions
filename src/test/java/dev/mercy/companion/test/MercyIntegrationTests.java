package dev.mercy.companion.test;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.mercy.companion.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.*;

/** Headless integration fixtures using the real Fabric-patched Minecraft 26.3 server. */
public final class MercyIntegrationTests implements ModInitializer {
    private static final List<JsonObject> RESULTS = new ArrayList<>();
    private static MinecraftServer server;
    private static ServerLevel level;
    private static int counter, ticks;
    private static ServerPlayer recallOwner;
    private static Mob recallPet;
    private static float recallHealth;
    private static UUID recallId;
    private static int failed;
    private static String deadOwnerName;
    private static ServerPlayer lastPlayer;
    private static int spawnedForPlayer;
    private static JsonObject restartFixture;
    private static ServerPlayer restartOwner;
    private static final Map<UUID,String> LAST_MESSAGES=new HashMap<>();
    private static final Map<UUID,Mob> CONVERSIONS=new HashMap<>();
    private static ServerPlayer bagOwner,storedRestartOwner;
    private static UUID bagA,bagB,bagC;
    private static List<UUID> storedRestartOrder;
    private static UUID storedRestartSelected;
    private static ServerPlayer unloadOwner;
    private static Mob unloadPet;
    private static UUID unloadId;
    private static Vec3 unloadPosition;
    private static final Set<UUID> UNLOADED=new HashSet<>();
    private static ServerPlayer explosionOwner;
    private static Mob explosionPet;
    private record Peaceful(ServerPlayer owner,Mob pet,String kind,float health,boolean terminal,int initialTick) {}
    private static final List<Peaceful> PEACEFUL_PETS=new ArrayList<>();
    private static net.minecraft.world.Difficulty previousDifficulty;
    private record Retention(String kind,ServerPlayer owner,Mob pet,Vec3 position,List<UUID> stored) {}
    private static final List<Retention> RETENTIONS=new ArrayList<>();
    private static final List<net.minecraft.world.entity.monster.Vex> VEX_SPAWNS=new ArrayList<>();
    private record SpellSample(String kind,ServerPlayer owner,Mob caster,Mob target,List<net.minecraft.world.entity.monster.Vex> vexes) {}
    private static final List<SpellSample> SPELLS=new ArrayList<>();
    private record SweepSample(ServerPlayer owner,Mob primary,Mob secondary,Mob witness) {}
    private static SweepSample sweepSample;
    private record RemoteSample(String kind,ServerPlayer owner,Mob pet,UUID stored,Vec3 origin) {}
    private static final List<RemoteSample> REMOTE=new ArrayList<>();
    private static ServerPlayer remoteRestartOwner;

    @Override public void onInitialize() {
        ServerEntityEvents.ENTITY_UNLOAD.register((entity,world)->UNLOADED.add(entity.getUUID()));
        ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{if(entity instanceof net.minecraft.world.entity.monster.Vex vex)VEX_SPAWNS.add(vex);});
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.MOB_CONVERSION.register((original,replacement,params)->CONVERSIONS.put(original.getUUID(),replacement));
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register((victim,source,base,taken,blocked)->{
            if(victim.getType()==type("zombie_nautilus")&&!source.is(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK))
                System.out.println("[MERCY AQUATIC DIAGNOSTIC] source="+source+" taken="+taken+" health="+victim.getHealth()+" water="+victim.isInWater()+" position="+victim.position());
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server=s; level=s.overworld(); level.getChunk(0,0);
            var clock=level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
            level.clockManager().setTotalTicks(clock,18000);level.clockManager().setPaused(clock,true);
            level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS,false,s);
            if(!Boolean.getBoolean("mercy.test.restart"))initial();
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (server==null) return;
            ++ticks;
            if(Boolean.getBoolean("mercy.test.restart")) {
                if(ticks==10)restart();
                if(ticks==30)checkRestart();
                if(ticks==50){checkStoredRestart();checkMinionRestart();checkRemoteRestartSummon();finish();}
                return;
            }
            if(ticks==10)checkActualExplosion();
            if(ticks==20)changeOwnerStates();
            if(ticks==20)checkTemporaryMinions();
            if(ticks==20)checkRealMercySweep();
            if(ticks==20)checkRemoteGuards();
            if(ticks==60)checkOwnerStates();
            if(ticks==25){beginChunkUnload();releaseRemoteChunk();}
            if(ticks==400){checkChunkUnloaded();recallUnloadedRemote();}
            if(ticks==405&&unloadPosition!=null)level.setChunkForced(((int)Math.floor(unloadPosition.x))>>4,((int)Math.floor(unloadPosition.z))>>4,true);
            if(ticks==405)reloadRemoteChunk();
            if(ticks==465){checkChunkReloaded();checkRecalledRemoteChunk();}
            if(ticks==300)preparePeaceful();
            if(ticks==340)checkPeaceful();
            if (ticks==240){delayed();recallLoadedRemote();}
            if (ticks==285){resummon();checkRemoteResummon();}
            if(ticks==40)provokeCompanions();
            if(ticks==90)checkUncommandedTargets();
            if(ticks==100)commandOrdinaryCompanions();
            if(ticks==200){checkFunctionalFollowers();checkMinionCombatAndDeath();}
            if(ticks==450)test("prepare native temporary Vex persistence control",()->setupSpell("restart"));
            if(ticks%100==0)wardenDiagnostics();
            if (ticks==500) { checkAi(); checkMinionPersistence(); finish(); }
        });
    }
    private record Fight(String type,ServerPlayer owner,Mob pet,Mob target,float originalHealth) {}
    private static final List<Fight> FIGHTS=new ArrayList<>();
    private record Follower(String type,ServerPlayer owner,Mob pet,Mob target,float originalHealth,double originalDistance) {}
    private static final List<Follower> FOLLOWERS=new ArrayList<>();
    private static final Set<String> ORDINARY=Set.of("zombie","skeleton","creeper");
    private static final Set<String> AUTONOMOUS=Set.of("warden","wither","zoglin");
    private record Smoke(ServerPlayer owner,Mob pet,String type) {}
    private static final List<Smoke> SMOKE=new ArrayList<>();
    private static void initial() {
        test("first join grants one mercy sword; repeat joins do not duplicate it", () -> {
            ServerPlayer p=player();p.getInventory().clearContent();
            var sender=net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.getSender(p);
            var joined=net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.invoker();
            joined.onPlayReady(p.connection,sender,server);
            check(p.getInventory().countItem(MercyMod.SWORD)==1,"starter sword missing or duplicated");
            check(p.getInventory().countItem(MercyMod.BAG)==1,"starter companion bag missing or duplicated");
            joined.onPlayReady(p.connection,sender,server);
            check(p.getInventory().countItem(MercyMod.SWORD)==1,"repeat join duplicated starter sword");
            check(p.getInventory().countItem(MercyMod.BAG)==1,"repeat join duplicated starter bag");
        });
        test("new captures enter the bag; ground use deploys LIFO and selection overrides once",MercyIntegrationTests::prepareBagOrder);
        checkSummonSurfaces();
        test("direct fatal sword hit cancels death, binds owner, restores health and emits no loot", () -> {
            ServerPlayer p=player(); Mob z=spawn(type("zombie"));
            int drops=drops(); int xp=p.totalExperience;
            z=capture(p,z);
            check(z.isAlive() && !z.isRemoved(),"captured entity must remain alive");
            check(p.getUUID().equals(CompanionService.owner(z)),"owner missing");
            equal(z.getHealth(),z.getMaxHealth(),"first capture health");
            check(slots(p).size()==1,"one roster slot required");
            check(drops()==drops && p.totalExperience==xp,"capture produced loot or XP");
        });
        test("other weapon can weaken mob; mercy sword finishes capture", () -> {
            ServerPlayer p=player(); Mob z=spawn(type("zombie"));
            p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
            z.hurtServer(level,level.damageSources().playerAttack(p),4);
            check(!CompanionService.isCompanion(z) && z.isAlive(),"early hit captured");
            p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(MercyMod.SWORD)); z=capture(p,z);
            check(CompanionService.isCompanion(z),"finishing capture failed");
        });
        test("wrong weapon fatal hit does not tame", () -> {
            ServerPlayer p=player();p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
            Mob z=spawn(type("zombie")); z.setHealth(1); mark(p,z);
            z.hurtServer(level,level.damageSources().playerAttack(p),1000);
            check(!CompanionService.isCompanion(z) && z.isDeadOrDying(),"wrong weapon tamed");
        });
        test("indirect damage and unmarked sweep victims cannot be captured", () -> {
            ServerPlayer p=player();Mob first=spawn(type("spider")), second=spawn(type("spider"));mark(p,first);
            second.setHealth(1);second.hurtServer(level,level.damageSources().playerAttack(p),1000);
            check(!CompanionService.isCompanion(second),"sweep/unmarked victim was captured");
            check(second.isAlive()&&second.getHealth()==1,"unmarked mercy sweep killed secondary mob");
            AbstractArrow arrow=(AbstractArrow)type("arrow").create(level,EntitySpawnReason.COMMAND);arrow.setOwner(p);
            first.setHealth(1);first.hurtServer(level,level.damageSources().arrow(arrow,p),1000);
            check(!CompanionService.isCompanion(first),"projectile captured target");
            check(first.isDeadOrDying(),"holding mercy sword incorrectly made arrow damage nonlethal");
        });
        for(String kind:List.of("cow","villager","wolf","chicken"))test("mercy sword spares non-capturable "+kind+" at one health",()-> {
            ServerPlayer p=player();Mob mob=spawn(type(kind));int loot=drops();rawCapture(p,mob);
            check(mob.isAlive()&&!mob.isRemoved()&&mob.getHealth()==1,"fatal mercy hit killed or healed passive/neutral mob");
            check(!CompanionService.isCompanion(mob)&&slots(p).isEmpty(),"non-hostile mob became a companion");
            check(drops()==loot,"nonlethal mercy hit emitted death loot/XP");
            mob.damageCooldownTime=0;mark(p,mob);mob.hurtServer(level,level.damageSources().playerAttack(p),1000);
            check(mob.isAlive()&&mob.getHealth()==1&&slots(p).isEmpty(),"repeated mercy hit killed, healed or captured passive/neutral mob");
            mob.damageCooldownTime=0;mob.hurtServer(level,level.damageSources().genericKill(),1000);
            check(mob.isDeadOrDying(),"mercy sword in inventory made unrelated damage nonlethal");
        });
        test("mercy sword spares existing companion without recapturing or restoring health",()-> {
            ServerPlayer p=player();Mob pet=capture(p,spawn(type("spider")));UUID id=pet.getUUID();int loot=drops();rawCapture(p,pet);
            check(pet.isAlive()&&!pet.isRemoved()&&pet.getHealth()==1,"fatal mercy hit killed/healed existing companion");
            check(slots(p).size()==1&&storedIds(p).isEmpty()&&entry(p,id).get("status").getAsString().equals("active"),"nonlethal hit changed deployed roster");
            check(drops()==loot,"spared companion emitted loot");
            p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));pet.damageCooldownTime=0;pet.hurtServer(level,level.damageSources().playerAttack(p),1000);
            check(pet.isDeadOrDying()&&slots(p).isEmpty(),"ordinary weapon no longer kills companion permanently");
        });
        test("prepare actual vanilla sword sweep with low-health secondary mob",()-> {
            ServerPlayer p=player();Mob primary=spawn(type("spider")),secondary=spawn(type("spider")),witness=spawn(type("cow"));
            level.setChunkForced(p.blockPosition().getX()>>4,p.blockPosition().getZ()>>4,true);
            sweepSample=new SweepSample(p,primary,secondary,witness);
        });
        test("mercy sword protects dragon multipart health without capture or dying phase",()-> {
            ServerPlayer p=player();var dragon=(net.minecraft.world.entity.boss.enderdragon.EnderDragon)spawn(type("ender_dragon"));
            dragon.getPhaseManager().setPhase(net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.HOLDING_PATTERN);
            dragon.setHealth(1);mark(p,dragon.head);dragon.hurt(level,dragon.head,level.damageSources().playerAttack(p),1000);
            check(dragon.isAlive()&&dragon.getHealth()==1,"mercy sword killed multipart dragon");
            check(dragon.getPhaseManager().getCurrentPhase().getPhase()!=net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.DYING,"protected dragon entered vanilla dying flight");
            check(!CompanionService.isCompanion(dragon)&&slots(p).isEmpty(),"dragon was captured");dragon.discard();
        });
        test("mercy sword does not grant nonlethal protection to players or armor stands",()-> {
            ServerPlayer attacker=player(),victim=player();victim.setHealth(1);mark(attacker,victim);
            victim.hurtServer(level,level.damageSources().playerAttack(attacker),1000);
            check(victim.isDeadOrDying(),"mercy sword accidentally protected PvP victim");
            var stand=(net.minecraft.world.entity.decoration.ArmorStand)type("armor_stand").create(level,EntitySpawnReason.COMMAND);
            stand.setPos(attacker.position());level.addFreshEntity(stand);attacker.getAbilities().instabuild=true;mark(attacker,stand);
            stand.hurtServer(level,level.damageSources().playerAttack(attacker),1000);
            check(stand.isRemoved(),"mercy sword accidentally protected armor stand from creative break");
        });
        test("dragon/passive mobs excluded; enderman, wither and warden eligible", () -> {
            ServerPlayer p=player();DamageSource src=level.damageSources().playerAttack(p);
            for (EntityType<?> type:List.of(type("ender_dragon"),type("chicken"))) {
                LivingEntity e=(LivingEntity)type.create(level,EntitySpawnReason.COMMAND);
                check(!CompanionService.canCapture(e,src),"excluded type accepted: "+type);
            }
            for(EntityType<?> type:List.of(type("enderman"),type("wither"),type("warden"))) {
                LivingEntity e=(LivingEntity)type.create(level,EntitySpawnReason.COMMAND);
                check(CompanionService.canCapture(e,src),"required type rejected: "+type);
            }
        });
        test("killer rabbit is capturable while ordinary rabbit is excluded", () -> {
            ServerPlayer p=player();var ordinary=(net.minecraft.world.entity.animal.rabbit.Rabbit)spawn(type("rabbit"));
            check(!CompanionService.canCapture(ordinary,level.damageSources().playerAttack(p)),"ordinary rabbit eligible");
            var evil=(net.minecraft.world.entity.animal.rabbit.Rabbit)spawn(type("rabbit"));
            var variant=evil.getClass().getDeclaredMethod("setVariant",net.minecraft.world.entity.animal.rabbit.Rabbit.Variant.class);variant.setAccessible(true);
            variant.invoke(evil,net.minecraft.world.entity.animal.rabbit.Rabbit.Variant.EVIL);evil=capture(p,evil);
            check(CompanionService.isCompanion(evil)&&evil.getVariant()==net.minecraft.world.entity.animal.rabbit.Rabbit.Variant.EVIL,"killer rabbit not captured intact");
            check(!dev.mercy.companion.ai.AiController.retainsNativeAggression(evil),"killer rabbit should use owner-assist policy");
            SMOKE.add(new Smoke(p,evil,"minecraft:rabbit[evil]"));
        });
        test("base capacity is 3; full roster prevents lethal mercy hit without capturing", () -> {
            ServerPlayer p=player();check(CompanionService.capacity(p)==3,"base capacity");
            for(int i=0;i<3;i++) rawCapture(p,spawn(type("spider")));
            Mob extra=spawn(type("spider"));extra=capture(p,extra);
            check(!CompanionService.isCompanion(extra) && extra.isAlive(),"full roster killed/captured target");
            check(slots(p).size()==3,"roster exceeded base capacity");
        });
        test("covenant I-IV provides capacities 4-7; swords never stack capacity", () -> {
            ServerPlayer p=player();var enchant=level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(ResourceKey.create(Registries.ENCHANTMENT,MercyMod.id("covenant")));
            for(int tier=1;tier<=4;tier++) {
                ItemStack sword=new ItemStack(MercyMod.SWORD);sword.enchant(enchant,tier);
                p.setItemSlot(EquipmentSlot.MAINHAND,sword);
                check(CompanionService.capacity(p)==3+tier,"wrong tier "+tier);
            }
            ItemStack extra=new ItemStack(MercyMod.SWORD);extra.enchant(enchant,4);p.getInventory().add(extra);
            check(CompanionService.capacity(p)==7,"multiple swords stacked");
            check(enchant.value().getMaxLevel()==4,"max enchant level not IV");
        });
        test("enchanting-table selection can produce covenant IV", () -> {
            var enchant=level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(ResourceKey.create(Registries.ENCHANTMENT,MercyMod.id("covenant")));
            boolean found=false;
            for(int seed=0;seed<1000;seed++) {
                var list=EnchantmentHelper.selectEnchantment(net.minecraft.util.RandomSource.create(seed),
                    new ItemStack(MercyMod.SWORD),30,java.util.stream.Stream.of(enchant));
                if(list.stream().anyMatch(e->e.level()==4)) {found=true;break;}
            }
            check(found,"no table-level IV result across 1000 seeds");
        });
        test("covenant IV captures seven partners and rejects the eighth", () -> {
            ServerPlayer p=player();var enchant=level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(ResourceKey.create(Registries.ENCHANTMENT,MercyMod.id("covenant")));
            p.getMainHandItem().enchant(enchant,4);
            for(int i=0;i<7;i++)rawCapture(p,spawn(type("spider")));
            check(slots(p).size()==7,"seven-partner capacity not usable");
            Mob eighth=spawn(type("spider"));eighth=capture(p,eighth);
            check(!CompanionService.isCompanion(eighth)&&eighth.isAlive()&&slots(p).size()==7,"eighth partner bypassed cap");
        });
        test("selection rejects a stored slot locked after sword enchantment is reduced",()-> {
            ServerPlayer p=player();var enchant=level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(ResourceKey.create(Registries.ENCHANTMENT,MercyMod.id("covenant")));
            p.getMainHandItem().enchant(enchant,4);Mob fourth=null;
            for(int i=0;i<4;i++){fourth=spawn(type("spider"));rawCapture(p,fourth);}
            p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(MercyMod.SWORD));
            check(entry(p,fourth.getUUID()).get("status").getAsString().equals("locked"),"excess stored slot did not lock");
            String before=selectedValue(p);select(p,fourth.getUUID());check(selectedValue(p).equals(before),"locked slot became selected");
        });
        test("owner immune to pet melee, arrow and sonic damage", () -> {
            ServerPlayer p=player();Mob pet=spawn(type("skeleton"));
            p.setHealth(20);p.damageCooldownTime=0;p.hurtServer(level,level.damageSources().mobAttack(pet),5);
            check(p.getHealth()<20,"fixture player is immune to ordinary wild-mob damage");
            pet=capture(p,pet);
            AbstractArrow arrow=(AbstractArrow)type("arrow").create(level,EntitySpawnReason.COMMAND);arrow.setOwner(pet);
            for(DamageSource src:List.of(level.damageSources().mobAttack(pet),level.damageSources().arrow(arrow,pet),
                level.damageSources().sonicBoom(pet))) {
                p.setHealth(20);p.damageCooldownTime=0;p.hurtServer(level,src,5);equal(p.getHealth(),20,"owner took pet damage: "+src);
            }
        });
        test("owner and companion friendly fire permitted without friendly target retaliation", () -> {
            ServerPlayer p=player();Mob a=spawn(type("spider")),b=spawn(type("spider"));a=capture(p,a);b=capture(p,b);
            float health=b.getHealth();b.damageCooldownTime=0;b.hurtServer(level,level.damageSources().mobAttack(a),3);
            check(b.getHealth()<health,"pet friendly fire incorrectly blocked");
            health=b.getHealth();b.damageCooldownTime=0;b.hurtServer(level,level.damageSources().playerAttack(p),3);
            check(b.getHealth()<health,"owner damage incorrectly blocked");
            check(!b.canAttack(a) && !b.canAttack(p),"friendly entities remain attack targets");
        });
        test("manual potion healing works for living and undead companions", () -> {
            ServerPlayer p=player();Mob spider=spawn(type("spider")),zombie=spawn(type("zombie"));spider=capture(p,spider);zombie=capture(p,zombie);
            spider.setHealth(3);zombie.setHealth(3);
            MobEffects.INSTANT_HEALTH.value().applyInstantaneousEffect(level,p,p,spider,0,1);
            MobEffects.INSTANT_DAMAGE.value().applyInstantaneousEffect(level,p,p,zombie,0,1);
            check(spider.getHealth()>3 && zombie.getHealth()>3,"manual potion failed");
        });
        test("wither innate regeneration disabled, manual undead potion still works", () -> {
            ServerPlayer p=player();WitherBoss wither=(WitherBoss)spawn(type("wither"));wither=capture(p,wither);wither.setHealth(100);
            var tick=WitherBoss.class.getDeclaredMethod("customServerAiStep",ServerLevel.class);tick.setAccessible(true);
            wither.tickCount=20;tick.invoke(wither,level);equal(wither.getHealth(),100,"wither regenerated");
            MobEffects.INSTANT_DAMAGE.value().applyInstantaneousEffect(level,p,p,wither,0,1);
            check(wither.getHealth()>100,"manual wither healing was blocked");
        });
        test("pet death frees slot permanently and produces no loot", () -> {
            ServerPlayer p=player();deadOwnerName=p.getName().getString();Mob z=spawn(type("zombie"));z=capture(p,z);int drops=drops();
            z.damageCooldownTime=0;z.hurtServer(level,level.damageSources().genericKill(),1000);
            check(slots(p).isEmpty(),"dead pet retained roster slot");check(drops()==drops,"dead pet dropped loot/XP");
            summonBag(p);check(slots(p).isEmpty(),"dead pet resurrected");
        });
        test("captured slime split count is zero", () -> {
            ServerPlayer p=player();Mob slime=spawn(type("slime"));slime=capture(p,slime);
            var split=AbstractCubeMob.class.getDeclaredMethod("getSplitCount");split.setAccessible(true);
            check(((Integer)split.invoke(slime))==0,"pet slime can split");
        });
        test("heart-bound creaking is captured by fatal sword strike and survives heart removal", () -> {
            ServerPlayer p=player();var creaking=(net.minecraft.world.entity.monster.creaking.Creaking)spawn(type("creaking"));
            var pos=net.minecraft.core.BlockPos.containing(p.getX()+12,p.getY(),p.getZ());
            var block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("creaking_heart"));
            level.setBlockAndUpdate(pos,block.defaultBlockState());
            var heart=(net.minecraft.world.level.block.entity.CreakingHeartBlockEntity)level.getBlockEntity(pos);
            check(heart!=null,"heart block entity missing");creaking.setHomePos(pos);heart.setCreakingInfo(creaking);
            check(creaking.isHeartBound()&&heart.isProtector(creaking),"fixture not heart-bound");
            creaking=capture(p,creaking);
            check(CompanionService.isCompanion(creaking),"heart-bound lethal capture failed");
            check(!creaking.isHeartBound()&&!heart.isProtector(creaking),"heart linkage retained");
            heart.removeProtector(level.damageSources().genericKill());level.removeBlock(pos,false);
            creaking.tick();check(creaking.isAlive()&&!creaking.isRemoved()&&!creaking.isTearingDown(),"removing heart killed captured creaking");
            check(creaking.checkCanMove(),"captured creaking still gaze-locked");
        });
        test("capturing spider mount detaches untamed skeleton passenger", () -> {
            ServerPlayer p=player();Mob spider=spawn(type("spider")),skeleton=spawn(type("skeleton"));
            skeleton.startRiding(spider,true,true);check(spider.isVehicle(),"fixture did not mount");spider=capture(p,spider);
            check(!spider.isVehicle()&&!skeleton.isPassenger(),"untamed passenger retained");
            check(!CompanionService.isCompanion(skeleton),"passenger was accidentally captured");
        });
        test("capturing skeleton rider detaches untamed spider mount", () -> {
            ServerPlayer p=player();Mob spider=spawn(type("spider")),skeleton=spawn(type("skeleton"));
            skeleton.startRiding(spider,true,true);check(skeleton.isPassenger(),"fixture did not mount");skeleton=capture(p,skeleton);
            check(!skeleton.isPassenger()&&!spider.isVehicle(),"untamed mount retained");
            check(!CompanionService.isCompanion(spider),"mount was accidentally captured");
        });
        test("prepare real companion creeper explosion after entity tracking becomes ready", () -> {
            ServerPlayer p=player();Mob pet=spawn(type("creeper"));pet=capture(p,pet);
            pet.snapTo(p.getX()+1,p.getY(),p.getZ());
            explosionOwner=p;explosionPet=pet;
        });
        test("actual blaze fireball hit and subsequent fire damage can hurt owner", () -> {
            ServerPlayer p=player();Mob blaze=spawn(type("blaze"));blaze=capture(p,blaze);
            var fireball=new net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball(level,blaze,new Vec3(1,0,0));
            fireball.snapTo(p.getX(),p.getY()+1,p.getZ());
            var hit=fireball.getClass().getDeclaredMethod("onHitEntity",EntityHitResult.class);hit.setAccessible(true);
            p.setHealth(20);p.damageCooldownTime=0;hit.invoke(fireball,new EntityHitResult(p));
            check(p.getHealth()<20,"blaze fireball did not hurt owner");
            check(p.isOnFire(),"blaze fireball did not ignite owner");
            float before=p.getHealth();p.damageCooldownTime=0;p.hurtServer(level,level.damageSources().onFire(),1);
            check(p.getHealth()<before,"subsequent fire damage blocked");p.clearFire();
        });
        test("sulfur cube TNT self-destruction permanently removes its roster slot", () -> {
            ServerPlayer p=player();var cube=(net.minecraft.world.entity.monster.cubemob.SulfurCube)spawn(type("sulfur_cube"));cube=capture(p,cube);
            check(cube.equipItem(new ItemStack(Items.TNT)),"cube did not accept TNT");cube.tick();
            check(cube.primeTime(false),"equipped TNT cube could not be primed");
            var fuse=cube.getClass().getDeclaredMethod("setFuse",int.class);fuse.setAccessible(true);fuse.invoke(cube,1);
            var tick=cube.getClass().getDeclaredMethod("tickFuse");tick.setAccessible(true);tick.invoke(cube);
            check(cube.isRemoved(),"TNT cube did not self-destruct");check(slots(p).isEmpty(),"exploded cube can be resummoned");
        });
        test("captured sulfur cube cannot be taken outside roster storage in a bucket", () -> {
            ServerPlayer p=player();Mob cube=spawn(type("sulfur_cube"));cube=capture(p,cube);
            p.getInventory().add(new ItemStack(MercyMod.SWORD));p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.BUCKET));
            var interact=cube.getClass().getDeclaredMethod("mobInteract",net.minecraft.world.entity.player.Player.class,InteractionHand.class);interact.setAccessible(true);
            interact.invoke(cube,p,InteractionHand.MAIN_HAND);
            check(cube.isAlive()&&!cube.isRemoved()&&slots(p).size()==1,"bucket removed captured cube");
            check(p.getMainHandItem().is(Items.BUCKET),"bucket became a captured-cube item");
        });
        test("curing zombie villager keeps owner and health; trading cannot grant free regeneration", () -> {
            ServerPlayer p=player();Mob zombie=spawn(type("zombie_villager"));zombie=capture(p,zombie);zombie.setHealth(5);
            var cure=zombie.getClass().getDeclaredMethod("finishConversion",ServerLevel.class);cure.setAccessible(true);cure.invoke(zombie,level);
            var villager=(net.minecraft.world.entity.npc.villager.Villager)CONVERSIONS.get(zombie.getUUID());
            check(villager!=null&&slots(p).size()==1,"cure lost owner or duplicated roster");equal(villager.getHealth(),5,"cure healed companion");
            villager.setVillagerData(villager.getVillagerData().withLevel(1));villager.setVillagerXp(9);
            var offer=new net.minecraft.world.item.trading.MerchantOffer(new net.minecraft.world.item.trading.ItemCost(Items.EMERALD),new ItemStack(Items.STONE),4,2,.05F);
            var trade=villager.getClass().getDeclaredMethod("rewardTradeXp",net.minecraft.world.item.trading.MerchantOffer.class);trade.setAccessible(true);trade.invoke(villager,offer);
            check(villager.getVillagerData().level()==2,"trade did not cross level-up threshold");
            check(!villager.hasEffect(MobEffects.REGENERATION),"level-up granted free regeneration");
            villager.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.REGENERATION,100));
            check(villager.hasEffect(MobEffects.REGENERATION),"manual regeneration potion blocked");
            setupFunctionalFollower("cured_villager",p,villager);
        });
        test("captured giant keeps vanilla absence of melee attack AI",()-> {
            ServerPlayer p=player();Mob giant=capture(p,spawn(type("giant")));
            check(!hasGoal(giant,net.minecraft.world.entity.ai.goal.MeleeAttackGoal.class),"invented melee attack goal on vanilla giant");
            setupFunctionalFollower("giant",p,giant);
        });
        test("captured creeper still has no contact melee attack",()-> {
            ServerPlayer p=player();Mob creeper=capture(p,spawn(type("creeper")));Mob target=spawn(type("iron_golem"));
            float health=target.getHealth();creeper.doHurtTarget(level,target);
            equal(target.getHealth(),health,"creeper received a non-vanilla melee attack");
        });
        test("skeleton switches between vanilla bow and unarmed attack goals",()-> {
            ServerPlayer p=player();var skeleton=(net.minecraft.world.entity.monster.skeleton.AbstractSkeleton)capture(p,spawn(type("skeleton")));
            skeleton.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.BOW));skeleton.reassessWeaponGoal();
            dev.mercy.companion.ai.AiController.attach(skeleton);
            check(hasGoal(skeleton,net.minecraft.world.entity.ai.goal.RangedBowAttackGoal.class)&&!hasGoal(skeleton,net.minecraft.world.entity.ai.goal.MeleeAttackGoal.class),"bow skeleton lost vanilla ranged-only weapon goal");
            skeleton.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);skeleton.reassessWeaponGoal();
            dev.mercy.companion.ai.AiController.attach(skeleton);
            check(!hasGoal(skeleton,net.minecraft.world.entity.ai.goal.RangedBowAttackGoal.class)&&hasGoal(skeleton,net.minecraft.world.entity.ai.goal.MeleeAttackGoal.class),"unarmed skeleton lost vanilla melee exception");
        });
        for(String kind:List.of("camel_husk","zombie_horse","sulfur_cube"))test("prepare functional "+kind+" companion following during owner combat",()-> {
            ServerPlayer p=player();setupFunctionalFollower(kind,p,capture(p,spawn(type(kind))));
        });
        test("zombie nautilus retains owner's combat target for native charge attack",()-> {
            ServerPlayer p=player();Mob pet=capture(p,spawn(type("zombie_nautilus")));waterHabitat(pet);
            Mob target=spawn(type("iron_golem"));p.setLastHurtMob(target);
            dev.mercy.companion.ai.AiController.tick(pet,p,"follow");
            check(pet.getTarget()==target,"native charge-capable companion ignored owner's combat target");
        });
        for(String kind:List.of("combat","recall"))test("captured evoker keeps native summon spell ("+kind+")",()->setupSpell(kind));
        for(String kind:List.of("zombie","skeleton","creeper","warden","wither","zoglin","creaking","piglin","hoglin","breeze")) test("prepare real "+kind+" companion combat",()->setupAi(kind));
        test("all vanilla monster types can be captured without exception", () -> {
            for(EntityType<?> entityType:net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE) {
                String key=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entityType).toString();
                if(!key.startsWith("minecraft:")||entityType.getCategory()!=MobCategory.MONSTER||key.equals("minecraft:ender_dragon"))continue;
                ServerPlayer p=player();Mob mob=spawn(entityType);
                mob=capture(p,mob);
                check(CompanionService.isCompanion(mob),"owner missing: "+key);
                mob.setDeltaMovement(Vec3.ZERO);
                if(key.equals("minecraft:zombie_nautilus"))waterHabitat(mob);
                SMOKE.add(new Smoke(p,mob,key));
            }
            check(SMOKE.size()>25,"monster enumeration unexpectedly small");
        });
        test("prepare stored-state, damage cooldown and delayed recall checks", () -> {
            recallOwner=player();recallPet=spawn(type("spider"));recallPet=capture(recallOwner,recallPet);recallId=recallPet.getUUID();
            level.setChunkForced(((int)recallPet.getX())>>4,((int)recallPet.getZ())>>4,true);
            recallPet.setHealth(7);recallHealth=7;
            CompanionService.touchCombat(recallPet);CompanionService.manualRecall(recallOwner,recallPet);
            check(!recallPet.isRemoved(),"combat recall bypassed cooldown");
        });
        test("prepare stored bag order and selection for saved-world restart",()-> {
            storedRestartOwner=player();Mob a=spawn(type("spider")),b=spawn(type("spider"));rawCapture(storedRestartOwner,a);rawCapture(storedRestartOwner,b);
            storedRestartOrder=storedIds(storedRestartOwner);check(storedRestartOrder.equals(List.of(b.getUUID(),a.getUUID())),"initial persistence bag order");
            storedRestartSelected=a.getUUID();select(storedRestartOwner,a.getUUID());
            storedRestartOrder=storedIds(storedRestartOwner);
        });
        for(String kind:List.of("offline","death","dimension","no_sword","lower_capacity"))test("prepare active retention after owner "+kind,()->prepareRetention(kind));
        for(String kind:List.of("loaded","dimension","unloaded"))test("prepare explicit remote recall from "+kind+" world state",()->prepareRemote(kind));
        test("prepare deployed companion for actual chunk unload and reload",()-> {
            unloadOwner=player();int x=8200,z=8200;level.setChunkForced(x>>4,z>>4,true);level.getChunk(x>>4,z>>4);
            unloadOwner.snapTo(x,level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,x,z),z);
            unloadPet=capture(unloadOwner,spawn(type("spider")));unloadPet.setHealth(7);CompanionService.checkpoint(unloadPet);
            unloadId=unloadPet.getUUID();unloadPosition=unloadPet.position();
        });
    }
    private record Surface(String name,BlockState state,double collisionTop,double outlineTop) {}
    private record SurfaceFixture(ServerPlayer owner,BlockPos support,UUID pet) {}
    private static int surfaceIndex;
    private static void checkSummonSurfaces() {
        var surfaces=List.of(
            new Surface("ice",Blocks.ICE.defaultBlockState(),1,1),
            new Surface("packed ice",Blocks.PACKED_ICE.defaultBlockState(),1,1),
            new Surface("blue ice",Blocks.BLUE_ICE.defaultBlockState(),1,1),
            new Surface("snow block",Blocks.SNOW_BLOCK.defaultBlockState(),1,1),
            new Surface("one snow layer",Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,1),0,1.0/8),
            new Surface("four snow layers",Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,4),3.0/8,4.0/8),
            new Surface("eight snow layers",Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,8),7.0/8,1),
            new Surface("bottom slab",Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM),.5,.5),
            new Surface("top slab",Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.TOP),1,1),
            new Surface("carpet",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("white_carpet")).defaultBlockState(),1.0/16,1.0/16),
            new Surface("leaves",Blocks.OAK_LEAVES.defaultBlockState(),1,1),
            new Surface("glass",Blocks.GLASS.defaultBlockState(),1,1));
        for(Surface surface:surfaces)test("real bag use on "+surface.name()+" lands exactly on collision surface without clipping",()-> {
            SurfaceFixture fixture=surfaceFixture(surface.state(),"spider");
            useBagViaOutlineRay(fixture.owner(),fixture.support(),surface.outlineTop());
            verifySurfaceLanding(fixture,surface.collisionTop());
        });
        test("real bag use retains water deployment above a submerged solid floor",()-> {
            SurfaceFixture fixture=surfaceFixture(Blocks.STONE.defaultBlockState(),"spider");
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=1;dy<=3;dy++)
                level.setBlockAndUpdate(fixture.support().offset(dx,dy,dz),Blocks.WATER.defaultBlockState());
            useBagViaOutlineRay(fixture.owner(),fixture.support(),1);verifySurfaceLanding(fixture,1);
        });
        test("low ceiling rejects tall companion while accepting a fitting companion at same clicked spot",()-> {
            SurfaceFixture fixture=surfaceFixture(Blocks.ICE.defaultBlockState(),"enderman");
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)level.setBlockAndUpdate(fixture.support().offset(dx,3,dz),Blocks.STONE.defaultBlockState());
            fixture.owner().snapTo(fixture.support().getX()+.5,fixture.support().getY()+1,fixture.support().getZ()+3.5);
            String before=CompanionService.rosterJson(fixture.owner());
            useBagViaOutlineRay(fixture.owner(),fixture.support(),1);
            verifySurfaceRejected(fixture,before,"insufficient overhead");
            Mob smaller=spawn(type("zombie"));rawCapture(fixture.owner(),smaller);
            useBagViaOutlineRay(fixture.owner(),fixture.support(),1);
            verifySurfaceLanding(new SurfaceFixture(fixture.owner(),fixture.support(),smaller.getUUID()),1);
            check(entry(fixture.owner(),fixture.pet()).get("status").getAsString().equals("stored"),"rejected tall companion was consumed by fitting summon");
        });
        test("lava at clicked destination rejects summon and preserves bag identity, health and order",()-> {
            SurfaceFixture fixture=surfaceFixture(Blocks.BLUE_ICE.defaultBlockState(),"spider");
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)level.setBlockAndUpdate(fixture.support().offset(dx,1,dz),Blocks.LAVA.defaultBlockState());
            String before=CompanionService.rosterJson(fixture.owner());useBagViaOutlineRay(fixture.owner(),fixture.support(),1);
            verifySurfaceRejected(fixture,before,"unsafe lava");
        });
        test("target without any nearby supporting collision rejects summon without consuming the companion",()-> {
            SurfaceFixture fixture=surfaceFixture(Blocks.AIR.defaultBlockState(),"spider");
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)level.setBlockAndUpdate(fixture.support().offset(dx,-1,dz),Blocks.AIR.defaultBlockState());
            String before=CompanionService.rosterJson(fixture.owner());
            check(!CompanionService.summonAt(fixture.owner(),fixture.support()),"unsupported air target accepted");
            verifySurfaceRejected(fixture,before,"missing support");
        });
    }
    private static SurfaceFixture surfaceFixture(BlockState surface,String kind) {
        ServerPlayer p=player();BlockPos support=new BlockPos(20000+16*surfaceIndex++,-40,2000);
        level.getChunk(support.getX()>>4,support.getZ()>>4);
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {
            level.setBlockAndUpdate(support.offset(dx,-1,dz),Blocks.STONE.defaultBlockState());
            for(int dy=0;dy<=6;dy++)level.setBlockAndUpdate(support.offset(dx,dy,dz),Blocks.AIR.defaultBlockState());
        }
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)level.setBlockAndUpdate(support.offset(dx,0,dz),surface);
        p.snapTo(support.getX()+.5,support.getY()+4,support.getZ()+.5);
        Mob mob=spawn(type(kind));rawCapture(p,mob);
        check(storedIds(p).equals(List.of(mob.getUUID())),"surface fixture capture did not enter bag");
        return new SurfaceFixture(p,support,mob.getUUID());
    }
    private static void useBagViaOutlineRay(ServerPlayer p,BlockPos support,double outlineTop) {
        Vec3 end=new Vec3(support.getX()+.5,support.getY()+outlineTop-.01,support.getZ()+.5);
        BlockHitResult hit=level.clip(new ClipContext(p.getEyePosition(),end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,p));
        check(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(support)&&hit.getDirection()==net.minecraft.core.Direction.UP,
            "real outline ray did not hit intended top face: "+hit.getBlockPos()+" "+hit.getDirection());
        check(Math.abs(hit.getLocation().y-(support.getY()+outlineTop))<1e-6,"outline ray used incorrect visible surface height");
        MercyMod.BAG.useOn(new net.minecraft.world.item.context.UseOnContext(p,InteractionHand.OFF_HAND,hit));
    }
    private static void verifySurfaceLanding(SurfaceFixture fixture,double collisionTop) {
        Mob deployed=activeMob(fixture.pet());
        check(deployed!=null&&deployed.isAlive()&&!deployed.isRemoved(),"surface summon rejected: "+LAST_MESSAGES.get(fixture.owner().getUUID()));
        Vec3 expected=new Vec3(fixture.support().getX()+.5,fixture.support().getY()+collisionTop,fixture.support().getZ()+.5);
        check(deployed.position().distanceToSqr(expected)<1e-10,"surface summon misplaced: expected="+expected+" actual="+deployed.position());
        check(level.noCollision(deployed),"summoned body clips the floor or overhead");
        check(!level.noCollision(deployed,deployed.getBoundingBox().move(0,-.0001,0)),"summoned feet float above support");
        check(entry(fixture.owner(),fixture.pet()).get("status").getAsString().equals("active"),"surface summon did not commit exactly one active identity");
        deployed.damageCooldownTime=0;deployed.hurtServer(level,level.damageSources().genericKill(),1000);
    }
    private static void verifySurfaceRejected(SurfaceFixture fixture,String before,String reason) {
        check(activeMob(fixture.pet())==null&&worldCopies(fixture.pet())==0,reason+" left a deployed entity");
        check(CompanionService.rosterJson(fixture.owner()).equals(before),reason+" changed stored health, identity, order or state");
    }
    private static void prepareBagOrder() {
        bagOwner=player();Mob a=spawn(type("spider")),b=spawn(type("spider")),c=spawn(type("spider"));
        int beforeDrops=drops();rawCapture(bagOwner,a);rawCapture(bagOwner,b);rawCapture(bagOwner,c);
        bagA=a.getUUID();bagB=b.getUUID();bagC=c.getUUID();
        check(a.isRemoved()&&b.isRemoved()&&c.isRemoved(),"capture left an active world entity");
        check(storedIds(bagOwner).equals(List.of(bagC,bagB,bagA)),"capture must push newest companion to bag top");
        check(drops()==beforeDrops,"bag capture emitted loot or XP");
        equal(entry(bagOwner,bagA).get("health").getAsFloat(),a.getMaxHealth(),"stored first-capture health");
        var ground=supportNear(bagOwner);useBagOn(bagOwner,ground);
        Mob deployed=activeMob(bagC);check(deployed!=null&&!deployed.isRemoved(),"ground use did not deploy newest companion");
        check(deployed.position().distanceToSqr(Vec3.atBottomCenterOf(ground.above()))<=16,"summon ignored clicked ground location");
        String selected=selectedValue(bagOwner);
        CompanionService.select(bagOwner,entry(bagOwner,bagA).get("slot").getAsInt(),UUID.randomUUID().toString());
        check(selectedValue(bagOwner).equals(selected),"wrong UUID changed selection");
        select(bagOwner,bagC);check(selectedValue(bagOwner).equals(selected),"active companion became a bag selection");
        bagOwner.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);select(bagOwner,bagA);
        check(selectedValue(bagOwner).equals(selected),"selection accepted without a bag");
        bagOwner.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(MercyMod.BAG));
        select(bagOwner,bagA);useBagOn(bagOwner,ground.offset(2,0,4));
        check(activeMob(bagA)!=null&&storedIds(bagOwner).equals(List.of(bagB)),"selected companion did not override LIFO once");
        ServerPlayer stranger=player();stranger.snapTo(bagOwner.getX()+1,bagOwner.getY(),bagOwner.getZ());
        check(!CompanionService.manualRecall(stranger,deployed),"another player recalled a companion they do not own");
        check(storedIds(bagOwner).equals(List.of(bagB)),"rejected foreign recall mutated bag");
    }
    private static void checkActualExplosion() {
        test("actual companion creeper explosion hurts owner and permanently removes creeper",()-> {
            check(explosionPet!=null,"explosion fixture did not initialize");
            explosionOwner.setHealth(20);explosionOwner.damageCooldownTime=0;
            explosionPet.snapTo(explosionOwner.getX()+1,explosionOwner.getY(),explosionOwner.getZ());
            var explode=explosionPet.getClass().getDeclaredMethod("explodeCreeper");explode.setAccessible(true);explode.invoke(explosionPet);
            check(explosionOwner.getHealth()<20,"actual creeper explosion did not hurt owner; playerVisible="+worldCopies(explosionOwner.getUUID()));
            check(slots(explosionOwner).isEmpty(),"self-destructed creeper retained roster slot");
        });
    }
    private static void prepareRetention(String kind) {
        ServerPlayer p=player();
        if(kind.equals("lower_capacity")) {
            var enchant=level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(ResourceKey.create(Registries.ENCHANTMENT,MercyMod.id("covenant")));
            p.getMainHandItem().enchant(enchant,4);
            for(int n=0;n<3;n++)rawCapture(p,spawn(type("spider")));
        }
        Mob pet=capture(p,spawn(type("spider")));pet.setHealth(7);CompanionService.checkpoint(pet);
        level.setChunkForced(pet.blockPosition().getX()>>4,pet.blockPosition().getZ()>>4,true);
        RETENTIONS.add(new Retention(kind,p,pet,pet.position(),storedIds(p)));
    }
    private static void prepareRemote(String kind) {
        ServerPlayer p=player();int x=9000+128*REMOTE.size(),z=2048;level.getChunk(x>>4,z>>4);level.setChunkForced(x>>4,z>>4,true);
        p.snapTo(x,level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,x,z),z);
        Mob stored=spawn(type("spider"));rawCapture(p,stored);Mob pet=capture(p,spawn(type("spider")));
        pet.setHealth(7);CompanionService.checkpoint(pet);CompanionService.touchCombat(pet);
        REMOTE.add(new RemoteSample(kind,p,pet,stored.getUUID(),pet.position()));
    }
    private static boolean remoteAction(RemoteSample sample) {
        return CompanionService.bagAction(sample.owner(),entry(sample.owner(),sample.pet().getUUID()).get("slot").getAsInt(),sample.pet().getStringUUID());
    }
    private static void checkRemoteGuards() {
        for(RemoteSample sample:REMOTE)test("remote bag recall validates identity, owner, bag and combat cooldown ("+sample.kind()+")",()-> {
            ServerPlayer p=sample.owner();int slot=entry(p,sample.pet().getUUID()).get("slot").getAsInt();
            if(sample.kind().equals("dimension")) {
                var nether=server.getLevel(net.minecraft.world.level.Level.NETHER);nether.getChunk(4,0);
                check(p.teleportTo(nether,64,120,8,Set.of(),0,0,true),"remote owner dimension transfer failed");
            } else {int x=7800+32*REMOTE.indexOf(sample);level.getChunk(x>>4,0);level.setChunkForced(x>>4,0,true);p.snapTo(x,-60,0);}
            check(!remoteAction(sample),"remote action bypassed ten-second combat cooldown");
            check(!CompanionService.bagAction(p,slot,UUID.randomUUID().toString()),"stale UUID selected a replacement slot occupant");
            ServerPlayer stranger=player();check(!CompanionService.bagAction(stranger,slot,sample.pet().getStringUUID()),"foreign owner remotely recalled companion");
            p.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);check(!remoteAction(sample),"remote action worked with no bag in inventory");p.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(MercyMod.BAG));
            check(!sample.pet().isRemoved()&&storedIds(p).equals(List.of(sample.stored())),"rejected action mutated active identity or bag order");
        });
    }
    private static RemoteSample unloadedRemote() {return REMOTE.stream().filter(s->s.kind().equals("unloaded")).findFirst().orElseThrow();}
    private static void releaseRemoteChunk() {
        RemoteSample sample=unloadedRemote();UNLOADED.remove(sample.pet().getUUID());
        level.setChunkForced(((int)Math.floor(sample.origin().x))>>4,((int)Math.floor(sample.origin().z))>>4,false);
    }
    private static void recallLoadedRemote() {
        for(RemoteSample sample:REMOTE)if(!sample.kind().equals("unloaded"))test("remote bag action recalls "+sample.kind()+" companion with health and LIFO intact",()-> {
            check(sample.kind().equals("dimension")?sample.owner().level()!=sample.pet().level():sample.owner().distanceToSqr(sample.pet())>64,"remote fixture is still within direct recall range");
            int slot=entry(sample.owner(),sample.pet().getUUID()).get("slot").getAsInt();
            sample.owner().setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);check(!remoteAction(sample),"out-of-combat remote action worked without bag");sample.owner().setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(MercyMod.BAG));
            check(!CompanionService.bagAction(sample.owner(),slot,UUID.randomUUID().toString()),"out-of-combat stale UUID recalled live slot");
            check(!CompanionService.bagAction(player(),slot,sample.pet().getStringUUID()),"out-of-combat foreign owner recalled another player's companion");
            check(remoteAction(sample),"explicit remote bag recall was rejected");
            check(sample.pet().isRemoved()&&worldCopies(sample.pet().getUUID())==0,"remote recall left a world entity");
            check(storedIds(sample.owner()).equals(List.of(sample.pet().getUUID(),sample.stored())),"remote recall did not become stack top");
            equal(entry(sample.owner(),sample.pet().getUUID()).get("health").getAsFloat(),7,"remote recall healed companion");
            check(remoteAction(sample)&&storedIds(sample.owner()).equals(List.of(sample.pet().getUUID(),sample.stored())),"repeated explicit action duplicated or reordered stored identity");
            check(!summonBag(sample.owner()),"remote recall skipped forty-tick summon cooldown");
        });
    }
    private static void checkRemoteResummon() {
        RemoteSample sample=REMOTE.stream().filter(s->s.kind().equals("loaded")).findFirst().orElseThrow();
        test("remote recalled companion resummons once with original health and UUID",()-> {
            check(summonBag(sample.owner()),"remote recalled companion could not deploy after cooldown: "+LAST_MESSAGES.get(sample.owner().getUUID())+" loaded="+level.hasChunkAt(supportNear(sample.owner()))+" roster="+CompanionService.rosterJson(sample.owner()));
            Mob pet=activeMob(sample.pet().getUUID());check(pet!=null&&pet.getUUID().equals(sample.pet().getUUID()),"remote resummon lost original identity");
            equal(pet.getHealth(),7,"remote resummon healed companion");
            check(storedIds(sample.owner()).equals(List.of(sample.stored())),"remote resummon disturbed remaining stack");
        });
    }
    private static void recallUnloadedRemote() {
        RemoteSample sample=unloadedRemote();
        test("remote recall stores genuinely unloaded companion without force-loading its chunk",()-> {
            check(UNLOADED.contains(sample.pet().getUUID())&&!level.isPositionEntityTicking(net.minecraft.core.BlockPos.containing(sample.origin()))&&worldCopies(sample.pet().getUUID())==0,"remote fixture entity never actually unloaded");
            check(!entry(sample.owner(),sample.pet().getUUID()).get("loaded").getAsBoolean(),"unloaded remote entry still has active handle");
            check(remoteAction(sample),"unloaded remote recall failed");
            check(!level.isPositionEntityTicking(net.minecraft.core.BlockPos.containing(sample.origin()))&&worldCopies(sample.pet().getUUID())==0,"remote recall loaded or recreated original entity");
            check(storedIds(sample.owner()).equals(List.of(sample.pet().getUUID(),sample.stored())),"unloaded recall did not preserve stack");
            equal(entry(sample.owner(),sample.pet().getUUID()).get("health").getAsFloat(),7,"unloaded recall changed health");
        });
    }
    private static void reloadRemoteChunk() {
        RemoteSample sample=unloadedRemote();level.setChunkForced(((int)Math.floor(sample.origin().x))>>4,((int)Math.floor(sample.origin().z))>>4,true);
    }
    private static void checkRecalledRemoteChunk() {
        RemoteSample sample=unloadedRemote();
        test("loading a remotely recalled companion's old chunk cannot recreate it",()-> {
            check(level.isPositionEntityTicking(net.minecraft.core.BlockPos.containing(sample.origin())),"old chunk did not actually reload");
            check(worldCopies(sample.pet().getUUID())==0&&activeMob(sample.pet().getUUID())==null,"old chunk recreated remotely stored companion");
            check(storedIds(sample.owner()).equals(List.of(sample.pet().getUUID(),sample.stored())),"old chunk load disturbed stored remote order");
        });
    }
    private static void checkRemoteRestartSummon() {
        test("unloaded remote recall survives restart and deploys exactly one original companion",()-> {
            JsonObject data=restartFixture.getAsJsonObject("remoteRecall");UUID id=UUID.fromString(data.get("id").getAsString());
            Mob pet=activeMob(id);check(pet!=null&&worldCopies(id)==1,"remote stored companion duplicated or disappeared after restart summon");
            equal(pet.getHealth(),7,"remote restart summon changed health");
            check(storedIds(remoteRestartOwner).equals(List.of(UUID.fromString(data.get("bottom").getAsString()))),"remote restart summon changed remaining stack order");
        });
    }
    private static void changeOwnerStates() {
        for(Retention r:RETENTIONS)test("apply owner "+r.kind()+" transition",()-> {
            switch(r.kind()) {
                case "offline" -> {
                    net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(r.owner().connection,server);
                    server.getPlayerList().getPlayers().remove(r.owner());server.getPlayerList().getPlayersByUUID().remove(r.owner().getUUID());
                    level.removePlayerImmediately(r.owner(),Entity.RemovalReason.DISCARDED);
                }
                case "death" -> {r.owner().damageCooldownTime=0;r.owner().hurtServer(level,level.damageSources().genericKill(),1000);}
                case "dimension" -> {
                    ServerLevel nether=server.getLevel(net.minecraft.world.level.Level.NETHER);nether.getChunk(0,0);
                    check(r.owner().teleportTo(nether,8,120,8,Set.of(),0,0,true),"owner teleport failed");
                }
                case "no_sword" -> r.owner().setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);
                case "lower_capacity" -> r.owner().setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(MercyMod.SWORD));
            }
        });
    }
    private static void checkOwnerStates() {
        for(Retention r:RETENTIONS)test("owner "+r.kind()+" does not automatically return an active companion to bag",()-> {
            check(!r.pet().isRemoved()&&r.pet().isAlive(),"active companion was removed");
            check(r.pet().level()==level&&r.pet().position().distanceToSqr(r.position())<1,"active companion teleported with owner");
            check(entry(r.owner(),r.pet().getUUID()).get("status").getAsString().equals("active"),"active state changed");
            check(storedIds(r.owner()).equals(r.stored()),"automatic transition changed bag stack");
            equal(r.pet().getHealth(),7,"automatic transition healed companion");
        });
    }
    private static void beginChunkUnload() {
        test("remove last viewer and release companion chunk",()-> {
            check(unloadPet!=null,"unload fixture failed to initialize");
            net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(unloadOwner.connection,server);
            server.getPlayerList().getPlayers().remove(unloadOwner);server.getPlayerList().getPlayersByUUID().remove(unloadOwner.getUUID());
            level.removePlayerImmediately(unloadOwner,Entity.RemovalReason.DISCARDED);
            UNLOADED.remove(unloadId);
            level.setChunkForced(((int)Math.floor(unloadPosition.x))>>4,((int)Math.floor(unloadPosition.z))>>4,false);
        });
    }
    private static void preparePeaceful() {
        test("peaceful control removes wild monsters but retains captured zombie and wither",()-> {
            previousDifficulty=level.getDifficulty();server.setDifficulty(net.minecraft.world.Difficulty.HARD,true);
            for(boolean terminal:new boolean[]{false,true}) {
                ServerPlayer p=player();
                for(String kind:List.of("zombie","wither")) {
                    Mob pet=capture(p,spawn(type(kind)));float hp=kind.equals("wither")?100:7;
                    pet.setHealth(hp);CompanionService.checkpoint(pet);
                    level.setChunkForced(pet.blockPosition().getX()>>4,pet.blockPosition().getZ()>>4,true);
                    PEACEFUL_PETS.add(new Peaceful(p,pet,kind,hp,terminal,pet.tickCount));
                }
            }
            Mob wildZombie=spawn(type("zombie")),wildWither=spawn(type("wither"));
            server.setDifficulty(net.minecraft.world.Difficulty.PEACEFUL,true);
            wildZombie.checkDespawn();wildWither.checkDespawn();
            check(wildZombie.isRemoved()&&wildWither.isRemoved(),"peaceful control did not remove wild zombie/wither");
            for(Peaceful sample:PEACEFUL_PETS) {
                sample.pet().checkDespawn();
                check(!sample.pet().isRemoved()&&activeMob(sample.pet().getUUID())==sample.pet(),"peaceful despawn removed/replaced companion");
            }
        });
    }
    private static void checkPeaceful() {
        for(Peaceful sample:PEACEFUL_PETS)test("peaceful ticks preserve "+sample.kind()+" instance and health ("+(sample.terminal()?"death control":"restart control")+")",()-> {
            check(sample.pet().tickCount-sample.initialTick()>=20,"peaceful companion did not actually tick");
            check(sample.pet().isAlive()&&!sample.pet().isRemoved()&&activeMob(sample.pet().getUUID())==sample.pet(),"peaceful created a despawn/restore cycle");
            equal(sample.pet().getHealth(),sample.health(),"peaceful healed companion");
            check(worldCopies(sample.pet().getUUID())==1&&storedIds(sample.owner()).isEmpty(),"peaceful duplicated companion or moved it into bag");
        });
        for(Peaceful sample:PEACEFUL_PETS)if(sample.terminal())test("lethal damage still permanently kills companion "+sample.kind()+" in peaceful",()-> {
            sample.pet().damageCooldownTime=0;sample.pet().hurtServer(level,level.damageSources().genericKill(),1000);
            check(entry(sample.owner(),sample.pet().getUUID())==null,"peaceful protection blocked terminal death");
            check(!summonBag(sample.owner()),"dead peaceful companion could be summoned");
        });
        if(previousDifficulty!=null)server.setDifficulty(previousDifficulty,true);
    }
    private static void checkChunkUnloaded() {
        test("real chunk unload preserves deployed status without adding bag entry",()-> {
            check(UNLOADED.contains(unloadId)&&!level.isPositionEntityTicking(unloadPet.blockPosition())&&worldCopies(unloadId)==0,
                "fixture chunk did not unload; event="+UNLOADED.contains(unloadId)+" ticking="+level.isPositionEntityTicking(unloadPet.blockPosition())+" visible="+worldCopies(unloadId));
            JsonObject e=entry(unloadOwner,unloadId);check(e!=null&&e.get("status").getAsString().equals("active"),"unload changed deployment state");
            check(!e.get("loaded").getAsBoolean()&&storedIds(unloadOwner).isEmpty(),"unloaded companion entered bag or remained registered");
            equal(e.get("health").getAsFloat(),7,"unload healed companion");
        });
    }
    private static void checkChunkReloaded() {
        test("reloading original chunk restores offline owner's active companion exactly once",()-> {
            Mob restored=activeMob(unloadId);check(restored!=null&&!restored.isRemoved(),"unloaded companion was not restored");
            check(restored.position().distanceToSqr(unloadPosition)<.01,"chunk reload moved companion");
            equal(restored.getHealth(),7,"chunk reload healed companion");
            check(worldCopies(unloadId)==1&&storedIds(unloadOwner).isEmpty(),"chunk reload duplicated companion or put it in bag");
        });
    }
    private static void setupAi(String kind) {
        ServerPlayer p=player();int x=1024+128*(FIGHTS.size()+1);level.getChunk(x>>4,0);
        // Synthetic players do not send client chunk acknowledgments; explicitly tick the arena.
        for(int cx=(x>>4)-1;cx<=(x>>4)+1;cx++)for(int cz=-1;cz<=1;cz++)level.setChunkForced(cx,cz,true);
        int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,x,0);
        p.snapTo(x,y,0);
        Mob pet=spawn(type(kind));pet=capture(p,pet);pet.snapTo(x+3,y,0);pet.setNoGravity(false);pet.setNoAi(false);
        if(kind.equals("skeleton"))pet.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.BOW));
        if(kind.equals("piglin"))pet.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.GOLDEN_SWORD));
        // Combat fixtures isolate combat from Nether-only species' dimension conversion.
        if(pet instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin piglin)piglin.setImmuneToZombification(true);
        if(pet instanceof net.minecraft.world.entity.monster.hoglin.Hoglin hoglin)hoglin.setImmuneToZombification(true);
        pet.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));
        Mob target=spawn(type("iron_golem"));target.snapTo(x+((kind.equals("warden")||kind.equals("breeze"))?14:6),y,0);target.setNoGravity(false);
        if(!ORDINARY.contains(kind)&&!AUTONOMOUS.contains(kind))p.setLastHurtMob(target);
        if(AUTONOMOUS.contains(kind)) {
            Mob friendly=spawn(type("spider"));friendly=capture(p,friendly);friendly.snapTo(x+4,y,2);friendly.setPermanentlyInvulnerable(true);
            check(!pet.canAttack(friendly)&&!pet.canAttack(p),"autonomous pet permits owner/team target");
        }
        FIGHTS.add(new Fight(kind,p,pet,target,target.getHealth()));
    }
    private static boolean hasGoal(Mob mob,Class<?> goalType) {
        return mob.getGoalSelector().getAvailableGoals().stream().anyMatch(wrapped->goalType.isInstance(wrapped.getGoal()));
    }
    private static void setupSpell(String kind) throws Exception {
        ServerPlayer p=player();int x=6000+128*SPELLS.size();
        for(int cx=(x>>4)-1;cx<=(x>>4)+1;cx++)for(int cz=-1;cz<=1;cz++)level.setChunkForced(cx,cz,true);
        int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,x,0);
        p.snapTo(x,y,0);Mob caster=capture(p,spawn(type("evoker")));caster.snapTo(x+3,y,0);
        Mob target=spawn(type("iron_golem"));target.snapTo(x+8,y,0);
        Object spell=caster.getGoalSelector().getAvailableGoals().stream().map(w->w.getGoal())
                .filter(goal->goal.getClass().getName().endsWith("Evoker$EvokerSummonSpellGoal")).findFirst().orElseThrow(()->new AssertionError("native evoker summon goal was removed"));
        int before=VEX_SPAWNS.size();var perform=spell.getClass().getDeclaredMethod("performSpellCasting");perform.setAccessible(true);perform.invoke(spell);
        List<net.minecraft.world.entity.monster.Vex> minions=new ArrayList<>(VEX_SPAWNS.subList(before,VEX_SPAWNS.size()));
        if(kind.equals("combat"))p.setLastHurtMob(target);
        else for(var vex:minions)vex.setNoAi(true);
        SPELLS.add(new SpellSample(kind,p,caster,target,minions));
    }
    private static void resolveMinions(SpellSample sample) {
        // Server entity tracking starts after asynchronous chunk loading has completed.
        for(var vex:VEX_SPAWNS)if(vex.getOwner()==sample.caster()&&!sample.vexes().contains(vex))sample.vexes().add(vex);
        check(sample.vexes().size()==3,"vanilla summon spell did not load three Vex: "+sample.vexes().size());
        if(!sample.kind().equals("combat"))for(var vex:sample.vexes())vex.setNoAi(true);
    }
    private static void checkTemporaryMinions() {
        for(SpellSample sample:SPELLS)test("native summoned Vex inherit allegiance without permanent slots ("+sample.kind()+")",()-> {
            resolveMinions(sample);
            check(slots(sample.owner()).size()==1&&storedIds(sample.owner()).isEmpty(),"temporary Vex consumed permanent capacity or entered bag");
            for(var vex:sample.vexes()) {
                check(dev.mercy.companion.ai.TemporaryMinions.isTemporary(vex),"native Vex was not registered as temporary");
                check(!CompanionService.isCompanion(vex)&&CompanionService.owner(vex)==null,"temporary Vex received permanent ownership tags");
                check(vex.getOwner()==sample.caster(),"native summoner reference changed");
                check(!vex.canAttack(sample.owner())&&!vex.canAttack(sample.caster()),"temporary Vex can target player or summoner");
                check(!vex.canAttack(sample.vexes().get((sample.vexes().indexOf(vex)+1)%3)),"temporary allies can deliberately target each other");
                check(!vex.shouldBeSaved(),"temporary Vex would be persisted in entity chunks");
            }
            var vex=sample.vexes().get(0);sample.owner().damageCooldownTime=0;
            float health=sample.owner().getHealth();sample.owner().hurtServer(level,level.damageSources().mobAttack(vex),3);
            equal(sample.owner().getHealth(),health,"temporary Vex melee hurt its player owner");
        });
        test("temporary Vex cannot be recalled or captured into a permanent bag",()-> {
            SpellSample sample=SPELLS.stream().filter(s->s.kind().equals("combat")).findFirst().orElseThrow();
            var vex=sample.vexes().get(0);
            check(!CompanionService.manualRecall(sample.owner(),vex),"temporary Vex was manually recalled as a permanent companion");
            ServerPlayer stranger=player();int previous=slots(stranger).size();int loot=drops();rawCapture(stranger,vex);
            check(vex.isAlive()&&vex.getHealth()==1&&!CompanionService.isCompanion(vex)&&dev.mercy.companion.ai.TemporaryMinions.isTemporary(vex),"mercy hit killed or permanently captured temporary Vex");
            check(slots(stranger).size()==previous&&slots(sample.owner()).size()==1,"temporary capture attempt changed a permanent roster");
            check(drops()==loot,"temporary Vex produced farming loot or experience");
            stranger.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));vex.damageCooldownTime=0;vex.hurtServer(level,level.damageSources().playerAttack(stranger),1000);
            check(vex.isDeadOrDying()&&drops()==loot,"ordinary weapon failed to kill temporary Vex without loot");
        });
    }
    private static void checkRealMercySweep() {
        test("real vanilla sword sweep spares low-health secondary without capturing it",()-> {
            check(sweepSample!=null,"sweep fixture did not initialize");
            var p=sweepSample.owner();var primary=sweepSample.primary();var secondary=sweepSample.secondary();var witness=sweepSample.witness();
            primary.snapTo(p.getX()+2,p.getY(),p.getZ());secondary.snapTo(p.getX()+2,p.getY(),p.getZ()+.6);witness.snapTo(p.getX()+2,p.getY(),p.getZ()-.6);
            primary.setHealth(10);secondary.setHealth(1);float before=witness.getHealth();
            p.setOnGround(true);p.setSprinting(false);p.setDeltaMovement(Vec3.ZERO);p.setSpeed(.1F);
            var attack=LivingEntity.class.getDeclaredField("attackStrengthTicker");attack.setAccessible(true);attack.setInt(p,100);
            var swap=LivingEntity.class.getDeclaredField("itemSwapTicker");swap.setAccessible(true);swap.setInt(p,100);
            mark(p,primary);p.attack(primary);
            check(primary.getHealth()<10,"real sword attack did not hit primary mob");
            check(witness.getHealth()<before,"vanilla sweep did not actually hit adjacent healthy control");
            check(secondary.isAlive()&&secondary.getHealth()==1&&!CompanionService.isCompanion(secondary),"sweep killed or captured its low-health secondary");
            check(slots(p).isEmpty(),"nonlethal sweep added unexpected roster slot");
        });
    }
    private static void checkMinionCombatAndDeath() {
        test("temporary Vex use native attacks against owner's target",()-> {
            SpellSample sample=SPELLS.stream().filter(s->s.kind().equals("combat")).findFirst().orElseThrow();
            check(sample.target().getHealth()<sample.target().getMaxHealth(),"Vex did not damage target while caster was NoAI");
            check(sample.vexes().stream().anyMatch(v->v.tickCount>100),"temporary Vex did not actually tick");
        });
        test("evoker death immediately removes all temporary Vex",()-> {
            SpellSample sample=SPELLS.stream().filter(s->s.kind().equals("combat")).findFirst().orElseThrow();
            sample.caster().damageCooldownTime=0;sample.caster().hurtServer(level,level.damageSources().genericKill(),10000);
            check(!sample.caster().isAlive()&&sample.vexes().stream().allMatch(Entity::isRemoved),"summoner death left an orphan temporary Vex");
            check(slots(sample.owner()).isEmpty(),"summoner death preserved its permanent slot");
        });
    }
    private static void checkMinionPersistence() {
        test("active temporary Vex are alive before save but excluded from chunk persistence",()-> {
            SpellSample sample=SPELLS.stream().filter(s->s.kind().equals("restart")).findFirst().orElseThrow();
            resolveMinions(sample);
            check(sample.caster().isAlive()&&sample.vexes().size()==3,"restart control summoner is not alive");
            for(var vex:sample.vexes())check(vex.isAlive()&&!vex.isRemoved()&&!vex.shouldBeSaved(),"restart control minion died early or is serializable");
            check(slots(sample.owner()).size()==1,"temporary restart control acquired permanent slots");
        });
    }
    private static void setupFunctionalFollower(String kind,ServerPlayer owner,Mob pet) {
        int x=4000+128*FOLLOWERS.size();
        for(int cx=(x>>4)-1;cx<=(x>>4)+1;cx++)for(int cz=-1;cz<=1;cz++)level.setChunkForced(cx,cz,true);
        int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,x,0);
        owner.snapTo(x,y,0);pet.snapTo(x+12,y,0);pet.setNoGravity(false);pet.setNoAi(false);
        Mob target=spawn(type("iron_golem"));target.snapTo(x+14,y,0);target.setNoGravity(false);
        owner.setLastHurtMob(target);
        FOLLOWERS.add(new Follower(kind,owner,pet,target,target.getHealth(),pet.distanceToSqr(owner)));
    }
    private static void checkFunctionalFollowers() {
        for(Follower sample:FOLLOWERS)test(sample.type()+" follows owner during combat without inventing attack AI",()-> {
            check(sample.pet().tickCount>100,"functional companion did not actually tick");
            check(sample.pet().getTarget()==null,"functional companion became stuck targeting owner's enemy");
            equal(sample.target().getHealth(),sample.originalHealth(),"functional companion invented attack damage");
            check(sample.pet().distanceToSqr(sample.owner())<sample.originalDistance()*.8,"functional companion did not approach owner: pet="+sample.pet().position()+" owner="+sample.owner().position()+" navigation="+sample.pet().getNavigation().isDone());
        });
    }
    private static void provokeCompanions() {
        for(Fight f:FIGHTS)if(ORDINARY.contains(f.type())||f.type().equals("warden")) {
            f.pet().damageCooldownTime=0;f.pet().hurtServer(level,level.damageSources().mobAttack(f.target()),1);
            // Supply native sensory anger, not an attack target or an owner command.
            if(f.pet() instanceof net.minecraft.world.entity.monster.warden.Warden w)w.increaseAngerAt(f.target(),150,false);
        }
    }
    private static void checkUncommandedTargets() {
        for(Fight f:FIGHTS) {
            if(ORDINARY.contains(f.type()))test("uncommanded "+f.type()+" ignores nearby enemy and does not retaliate for self",()->{
                check(f.pet().getTarget()==null,"ordinary companion acquired autonomous target: "+f.pet().getTarget());
                equal(f.target().getHealth(),f.originalHealth(),"ordinary companion attacked before owner combat");
            });
            if(AUTONOMOUS.contains(f.type()))test(f.type()+" retains native aggression without owner command",()->{
                boolean roaring=f.pet() instanceof net.minecraft.world.entity.monster.warden.Warden w&&w.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ROAR_TARGET).orElse(null)==f.target();
                check(roaring||f.pet().getTarget()==f.target()||f.target().getHealth()<f.originalHealth(),"native aggression did not engage enemy; target="+f.pet().getTarget());
            });
        }
    }
    private static void commandOrdinaryCompanions() {
        for(Fight f:FIGHTS)if(ORDINARY.contains(f.type())) {
            if(f.type().equals("skeleton")) {
                f.owner().damageCooldownTime=0;f.owner().hurtServer(level,level.damageSources().mobAttack(f.target()),1);
            } else f.owner().setLastHurtMob(f.target());
        }
    }
    private static void checkAi() {
        for(Fight fight:FIGHTS) test("real "+fight.type()+" AI damages "+(AUTONOMOUS.contains(fight.type())?"native third-party target":"owner's combat target"),()-> {
            check(fight.pet().tickCount>100,"pet not ticking: "+fight.pet().tickCount);
            check(fight.target().getHealth()<fight.originalHealth(),"no attack damage after 500 ticks; target="+fight.pet().getTarget()+" pet="+fight.pet().position()+" enemy="+fight.target().position());
        });
    }
    private static void wardenDiagnostics() {
        for(Fight f:FIGHTS)if(f.pet() instanceof net.minecraft.world.entity.monster.warden.Warden w) {
            var b=w.getBrain();var m=net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET;
            System.out.println("[MERCY WARDEN DIAGNOSTIC] tick="+ticks+" entityTick="+w.tickCount+" noAI="+w.isNoAi()+" pose="+w.getPose()+" effectiveAI="+w.isEffectiveAi()
                +" activities="+b.getActiveActivities()+" anger="+w.getAngerLevel()+" targetAnger="+w.getAngerManagement().getActiveAnger(f.target())+" target="+b.getMemory(m)
                +" sonicCooldown="+b.getTimeUntilExpiry(net.minecraft.world.entity.ai.memory.MemoryModuleType.SONIC_BOOM_COOLDOWN)
                +" sonicDelay="+b.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.SONIC_BOOM_SOUND_DELAY)
                +" emerging="+b.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.IS_EMERGING)
                +" running="+b.getRunningBehaviors()+" brainDead="+b.isBrainDead()
                +" walk="+b.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)+" roar="+b.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ROAR_TARGET)
                +" enemyHealth="+f.target().getHealth());
        }
    }
    private static void delayed() {
        test("explicit evoker recall removes temporary Vex without storing them",()-> {
            SpellSample sample=SPELLS.stream().filter(s->s.kind().equals("recall")).findFirst().orElseThrow();
            check(CompanionService.manualRecall(sample.owner(),sample.caster()),"out-of-combat evoker recall failed");
            check(sample.vexes().stream().allMatch(Entity::isRemoved),"evoker recall left an orphan temporary Vex");
            check(storedIds(sample.owner()).equals(List.of(sample.caster().getUUID())),"temporary Vex entered the bag during summoner recall");
        });
        test("only explicit right-click recall pushes an active companion onto bag top",()-> {
            Mob c=activeMob(bagC);check(c!=null,"bag test's deployed companion missing");
            net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker().interact(bagOwner,level,InteractionHand.OFF_HAND,c,new EntityHitResult(c));
            check(c.isRemoved()&&storedIds(bagOwner).equals(List.of(bagC,bagB)),"manual recall did not push LIFO top");
        });
        for(Retention r:RETENTIONS)if(r.kind().equals("no_sword"))test("owner without sword can explicitly recall with held bag",()-> {
            check(CompanionService.manualRecall(r.owner(),r.pet()),"manual bag recall incorrectly requires a sword");
            check(storedIds(r.owner()).equals(List.of(r.pet().getUUID())),"manual recall did not store companion");
        });
        for(Smoke smoke:SMOKE) test("recall "+smoke.type(),()->{
            CompanionService.manualRecall(smoke.owner(),smoke.pet());
            check(!slots(smoke.owner()).isEmpty(),"roster lost before recall; health="+smoke.pet().getHealth()+" removed="+smoke.pet().isRemoved()+" position="+smoke.pet().position()+" water="+smoke.pet().isInWater());
            check(slots(smoke.owner()).get(0).getAsJsonObject().get("status").getAsString().equals("stored"),"recall failed");
        });
        test("recall and resummon preserve identity, health and single entity count", () -> {
            equal(recallPet.getHealth(),recallHealth,"passive pet health unexpectedly changed");
            CompanionService.manualRecall(recallOwner,recallPet);
            check(recallPet.isRemoved(),"recall failed after cooldown");
            check(slots(recallOwner).get(0).getAsJsonObject().get("status").getAsString().equals("stored"),"not stored");
        });
    }
    private static void resummon() {
        test("manual recall becomes next LIFO summon after cooldown",()-> {
            useBagOn(bagOwner,supportNear(bagOwner));
            check(activeMob(bagC)!=null&&!activeMob(bagC).isRemoved()&&storedIds(bagOwner).equals(List.of(bagB)),"recalled companion was not next summon");
        });
        int offset=0;
        for(Smoke smoke:SMOKE) {
            int x=520+(offset%8)*48,z=520+(offset++/8)*48;level.getChunk(x>>4,z>>4);
            smoke.owner().snapTo(x,level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,x,z),z);
            test("summon "+smoke.type()+" preserves type and permits AI tick",()->{
                summonBag(smoke.owner());
                check(slots(smoke.owner()).get(0).getAsJsonObject().get("status").getAsString().equals("active"),"summon failed; ownerHealth="+smoke.owner().getHealth()+" position="+smoke.owner().position()+" roster="+CompanionService.rosterJson(smoke.owner())+" message="+LAST_MESSAGES.get(smoke.owner().getUUID()));
                for(Entity e:level.getAllEntities())if(e instanceof Mob mob&&smoke.owner().getUUID().equals(CompanionService.owner(e))) {
                    check(mob.getType()==smoke.pet().getType(),"type changed");
                    if(mob instanceof net.minecraft.world.entity.animal.rabbit.Rabbit rabbit)check(rabbit.getVariant()==net.minecraft.world.entity.animal.rabbit.Rabbit.Variant.EVIL,"killer-rabbit variant lost");
                    if(smoke.type().equals("minecraft:zombie_nautilus"))waterHabitat(mob);
                    mob.setNoAi(false);
                    dev.mercy.companion.ai.AiController.tick(mob,smoke.owner(),"follow");
                    mob.tick();
                    mob.setNoAi(true);
                }
            });
        }
        test("resummon after cooldown preserves identity, health and uniqueness", () -> {
            summonBag(recallOwner);
            var matches=new ArrayList<LivingEntity>();
            for(Entity e:level.getAllEntities()) if(e instanceof LivingEntity living && recallOwner.getUUID().equals(CompanionService.owner(e)) && !e.isRemoved()) matches.add(living);
            check(matches.size()==1,"summon count must be one, actual="+matches.size());
            equal(matches.get(0).getHealth(),recallHealth,"summon healed pet");
            check(matches.get(0).getUUID().equals(recallId),"summon changed entity identity");
            recallPet=(Mob)matches.get(0);
            summonBag(recallOwner);
            int count=0;for(Entity e:level.getAllEntities()) if(recallOwner.getUUID().equals(CompanionService.owner(e))&&!e.isRemoved())count++;
            check(count==1,"duplicate summon produced clone");
        });
    }
    private static void restart() {
        test("restart runs in peaceful and reloads companion owner fixtures",()-> {
            check(level.getDifficulty()==net.minecraft.world.Difficulty.PEACEFUL,"restart was not actually peaceful");
        });
        test("restart keeps deployed companion active and outside the bag",()-> {
            restartFixture=JsonParser.parseString(Files.readString(Path.of("persistence-fixture.json"))).getAsJsonObject();
            recallId=UUID.fromString(restartFixture.get("id").getAsString());
            restartOwner=player(restartFixture.get("ownerName").getAsString());
            JsonArray position=restartFixture.getAsJsonArray("position");
            int cx=((int)Math.floor(position.get(0).getAsDouble()))>>4,cz=((int)Math.floor(position.get(2).getAsDouble()))>>4;
            level.setChunkForced(cx,cz,true);level.getChunk(cx,cz);
            JsonObject pet=entry(restartOwner,recallId);
            check(pet!=null&&pet.get("status").getAsString().equals("active"),"restart moved active companion into bag");
            equal(pet.get("health").getAsFloat(),restartFixture.get("health").getAsFloat(),"saved active health");
            check(storedIds(restartOwner).isEmpty(),"active companion entered stack on restart");
            check(!summonBag(restartOwner),"bag spawned a second copy of already-deployed companion");
            for(JsonElement value:restartFixture.getAsJsonArray("peaceful")) {
                JsonObject e=value.getAsJsonObject();ServerPlayer p=player(e.get("ownerName").getAsString());
                int peacefulCx=((int)Math.floor(e.get("x").getAsDouble()))>>4,peacefulCz=((int)Math.floor(e.get("z").getAsDouble()))>>4;
                level.setChunkForced(peacefulCx,peacefulCz,true);level.getChunk(peacefulCx,peacefulCz);
                check(storedIds(p).isEmpty(),"peaceful restart put deployed companion in bag");
            }
        });
        test("restart preserves stored LIFO order and selected next companion",()-> {
            storedRestartOwner=player(restartFixture.get("storedOwnerName").getAsString());
            List<UUID> expected=new ArrayList<>();for(JsonElement e:restartFixture.getAsJsonArray("storedOrder"))expected.add(UUID.fromString(e.getAsString()));
            check(storedIds(storedRestartOwner).equals(expected),"stored order changed across restart");
            UUID selected=UUID.fromString(restartFixture.get("storedSelected").getAsString());
            check(roster(storedRestartOwner).get("selectedSlot").getAsInt()==entry(storedRestartOwner,selected).get("slot").getAsInt(),"next-companion preference was lost");
        });
        test("restart preserves remotely recalled stack and keeps old chunk free of the companion",()-> {
            JsonObject data=restartFixture.getAsJsonObject("remoteRecall");remoteRestartOwner=player(data.get("ownerName").getAsString());
            level.getChunk(7600>>4,64>>4);level.setChunkForced(7600>>4,64>>4,true);remoteRestartOwner.snapTo(7600,-60,64);
            int cx=data.get("chunkX").getAsInt(),cz=data.get("chunkZ").getAsInt();level.setChunkForced(cx,cz,true);level.getChunk(cx,cz);
            UUID id=UUID.fromString(data.get("id").getAsString()),bottom=UUID.fromString(data.get("bottom").getAsString());
            check(storedIds(remoteRestartOwner).equals(List.of(id,bottom)),"remote recall stack did not survive restart");
            check(worldCopies(id)==0&&activeMob(id)==null,"restart recreated remotely stored companion in old chunk");
            equal(entry(remoteRestartOwner,id).get("health").getAsFloat(),7,"remote stored health changed on restart");
        });
    }
    private static void checkRestart() {
        for(JsonElement value:restartFixture.getAsJsonArray("peaceful")) {
            JsonObject e=value.getAsJsonObject();test("peaceful LOAD restores "+e.get("kind").getAsString()+" at saved position once",()-> {
                UUID id=UUID.fromString(e.get("id").getAsString());Mob restored=activeMob(id);
                check(restored!=null&&!restored.isRemoved(),"peaceful LOAD could not restore monster companion");
                equal(restored.getHealth(),e.get("health").getAsFloat(),"peaceful reload healed companion");
                check(restored.position().distanceToSqr(new Vec3(e.get("x").getAsDouble(),e.get("y").getAsDouble(),e.get("z").getAsDouble()))<.01,"peaceful reload moved companion");
                check(worldCopies(id)==1,"peaceful reload duplicated companion");
            });
        }
        test("companions killed in peaceful remain absent after restart",()-> {
            ServerPlayer p=player(restartFixture.get("peacefulDeadOwner").getAsString());check(slots(p).isEmpty(),"peaceful terminal death was not durable");
        });
        test("loaded chunk restores active companion at original position exactly once",()-> {
            Mob found=activeMob(recallId);check(found!=null&&!found.isRemoved(),"active companion did not restore after chunk load");
            JsonArray position=restartFixture.getAsJsonArray("position");Vec3 expected=new Vec3(position.get(0).getAsDouble(),position.get(1).getAsDouble(),position.get(2).getAsDouble());
            check(found.position().distanceToSqr(expected)<.01,"restart relocated active companion to owner/bag position");
            check(found.level().dimension().identifier().toString().equals(restartFixture.get("dimension").getAsString()),"restart changed companion dimension");
            equal(found.getHealth(),restartFixture.get("health").getAsFloat(),"reload healed active companion");
            check(worldCopies(recallId)==1,"restart produced duplicate/missing active entity");
            ServerPlayer dead=player(restartFixture.get("deadOwner").getAsString());check(slots(dead).isEmpty(),"dead pet resurrected across restart");
        });
        test("stored bag still uses persisted selection when clicked on ground",()->useBagOn(storedRestartOwner,supportNear(storedRestartOwner)));
        test("stored remote companion can be summoned after saved-world restart",()->check(summonBag(remoteRestartOwner),"remote stored identity failed to deploy after restart"));
        test("load temporary-minion save region in normal difficulty after peaceful controls",()-> {
            server.setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
            JsonObject data=restartFixture.getAsJsonObject("temporaryVex");player(data.get("ownerName").getAsString());
            int cx=data.get("chunkX").getAsInt(),cz=data.get("chunkZ").getAsInt();
            for(int x=cx-1;x<=cx+1;x++)for(int z=cz-1;z<=cz+1;z++){level.setChunkForced(x,z,true);level.getChunk(x,z);}
        });
    }
    private static void checkMinionRestart() {
        test("restart restores deployed evoker but no temporary Vex orphan in normal difficulty",()-> {
            check(level.getDifficulty()==net.minecraft.world.Difficulty.NORMAL,"peaceful difficulty could hide an orphan Vex");
            JsonObject data=restartFixture.getAsJsonObject("temporaryVex");UUID caster=UUID.fromString(data.get("caster").getAsString());
            check(worldCopies(caster)==1&&activeMob(caster)!=null,"saved summoner did not restore");
            for(JsonElement value:data.getAsJsonArray("vexes"))check(worldCopies(UUID.fromString(value.getAsString()))==0,"temporary Vex survived saved-world restart");
            ServerPlayer p=server.getPlayerList().getPlayer(UUID.fromString(data.get("ownerId").getAsString()));
            check(p!=null&&slots(p).size()==1,"temporary Vex became permanent on restart");
        });
    }
    private static void checkStoredRestart() {
        test("selected stored companion deploys once while other stored entries remain in order",()-> {
            UUID selected=UUID.fromString(restartFixture.get("storedSelected").getAsString());
            check(activeMob(selected)!=null&&worldCopies(selected)==1,"persisted selected companion did not deploy exactly once");
            List<UUID> expected=new ArrayList<>();for(JsonElement e:restartFixture.getAsJsonArray("storedOrder")){UUID id=UUID.fromString(e.getAsString());if(!id.equals(selected))expected.add(id);}
            check(storedIds(storedRestartOwner).equals(expected),"deploying persisted selection reordered remaining bag");
            check(worldCopies(recallId)==1,"stored bag use duplicated separate active companion");
        });
    }
    private static void test(String name,Checked action) {
        JsonObject result=new JsonObject();result.addProperty("name",name);
        try {action.run();result.addProperty("passed",true);System.out.println("[MERCY TEST PASS] "+name);}
        catch(Throwable e) {failed++;result.addProperty("passed",false);result.addProperty("error",e.toString());e.printStackTrace();}
        RESULTS.add(result);
    }
    private static void finish() {
        try {
            JsonObject report=new JsonObject();report.addProperty("minecraft","26.3");report.addProperty("runner","real Fabric dedicated server; synthetic ServerPlayer, no graphical client");
            report.addProperty("passed",RESULTS.size()-failed);report.addProperty("failed",failed);
            JsonArray tests=new JsonArray();RESULTS.forEach(tests::add);report.add("tests",tests);
            Files.writeString(Path.of("results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
            if(!Boolean.getBoolean("mercy.test.restart")&&recallOwner!=null) {
                JsonObject fixture=new JsonObject();fixture.addProperty("ownerName",recallOwner.getName().getString());fixture.addProperty("health",recallHealth);fixture.addProperty("id",recallId.toString());fixture.addProperty("deadOwner",deadOwnerName);
                JsonArray position=new JsonArray();position.add(recallPet.getX());position.add(recallPet.getY());position.add(recallPet.getZ());fixture.add("position",position);
                fixture.addProperty("dimension",recallPet.level().dimension().identifier().toString());fixture.addProperty("storedOwnerName",storedRestartOwner.getName().getString());
                JsonArray order=new JsonArray();storedRestartOrder.forEach(id->order.add(id.toString()));fixture.add("storedOrder",order);fixture.addProperty("storedSelected",storedRestartSelected.toString());
                JsonArray peaceful=new JsonArray();
                for(Peaceful sample:PEACEFUL_PETS) {
                    if(sample.terminal()){fixture.addProperty("peacefulDeadOwner",sample.owner().getName().getString());continue;}
                    JsonObject e=new JsonObject();e.addProperty("ownerName",sample.owner().getName().getString());e.addProperty("id",sample.pet().getStringUUID());e.addProperty("kind",sample.kind());e.addProperty("health",sample.health());
                    e.addProperty("x",sample.pet().getX());e.addProperty("y",sample.pet().getY());e.addProperty("z",sample.pet().getZ());peaceful.add(e);
                }
                fixture.add("peaceful",peaceful);
                SpellSample spell=SPELLS.stream().filter(s->s.kind().equals("restart")).findFirst().orElseThrow();
                JsonObject temporary=new JsonObject();temporary.addProperty("ownerName",spell.owner().getName().getString());temporary.addProperty("ownerId",spell.owner().getStringUUID());
                temporary.addProperty("caster",spell.caster().getStringUUID());temporary.addProperty("chunkX",spell.caster().blockPosition().getX()>>4);temporary.addProperty("chunkZ",spell.caster().blockPosition().getZ()>>4);
                JsonArray minions=new JsonArray();spell.vexes().forEach(v->minions.add(v.getStringUUID()));temporary.add("vexes",minions);fixture.add("temporaryVex",temporary);
                RemoteSample remote=unloadedRemote();JsonObject remoteData=new JsonObject();remoteData.addProperty("ownerName",remote.owner().getName().getString());remoteData.addProperty("id",remote.pet().getStringUUID());remoteData.addProperty("bottom",remote.stored().toString());
                remoteData.addProperty("chunkX",((int)Math.floor(remote.origin().x))>>4);remoteData.addProperty("chunkZ",((int)Math.floor(remote.origin().z))>>4);fixture.add("remoteRecall",remoteData);
                Files.writeString(Path.of("persistence-fixture.json"),fixture.toString());
            }
        } catch(Exception e){e.printStackTrace();}
        server.halt(false);
    }
    private static ServerPlayer player() {return player("MercyTest"+(++counter));}
    private static ServerPlayer player(String name) {
        GameProfile profile=new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),name);
        ServerPlayer p=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
        Connection connection=new Connection(PacketFlow.SERVERBOUND);
        p.connection=new ServerGamePacketListenerImpl(server,connection,p,CommonListenerCookie.createInitial(profile,false)) {
            @Override public void send(Packet<?> packet) {remember(p,packet);}
            @Override public void send(Packet<?> packet,io.netty.channel.ChannelFutureListener listener) {remember(p,packet);}
            @Override public boolean hasClientLoaded() {return true;}
        };
        int x=(counter%8)*24,z=128+(counter/8)*24;
        level.getChunk(x>>4,z>>4);
        p.setNoGravity(true);p.snapTo(x,level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,x,z),z);level.addNewPlayer(p);server.getPlayerList().getPlayers().add(p);server.getPlayerList().getPlayersByUUID().put(p.getUUID(),p);
        lastPlayer=p;spawnedForPlayer=0;
        p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(MercyMod.SWORD));p.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(MercyMod.BAG));return p;
    }
    private static void remember(ServerPlayer p,Packet<?> packet) {
        if(packet.getClass().getSimpleName().contains("SystemChat"))LAST_MESSAGES.put(p.getUUID(),packet.toString());
    }
    private static void waterHabitat(Mob mob) {
        var center=mob.blockPosition();
        var water=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("water")).defaultBlockState();
        var glass=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("glass")).defaultBlockState();
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int dy=0;dy<3;dy++)
            level.setBlockAndUpdate(center.offset(dx,dy,dz),Math.abs(dx)==3||Math.abs(dz)==3?glass:water);
        mob.snapTo(center.getX()+.5,center.getY()+.3,center.getZ()+.5);mob.setDeltaMovement(Vec3.ZERO);
    }
    private static EntityType<?> type(String id) { return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(id)); }
    private static Mob spawn(EntityType<?> type) {
        Mob mob=(Mob)type.create(level,EntitySpawnReason.COMMAND);
        if(mob==null)throw new IllegalStateException("cannot create "+type);
        // Separate fixtures spatially so cramming, hostile auras and explosions cannot cross tests.
        double x=lastPlayer.getX()+5+(spawnedForPlayer++%4)*3,z=lastPlayer.getZ()+4;
        level.getChunk(((int)x)>>4,((int)z)>>4);
        mob.setNoAi(true);mob.setNoGravity(true);mob.snapTo(x,lastPlayer.getY(),z);mob.setPersistenceRequired();
        level.addFreshEntity(mob);return mob;
    }
    private static void mark(ServerPlayer p,Entity mob) {
        AttackEntityCallback.EVENT.invoker().interact(p,level,InteractionHand.MAIN_HAND,mob,new EntityHitResult(mob));
    }
    private static void rawCapture(ServerPlayer p,Mob mob) {
        mob.setHealth(1);mob.damageCooldownTime=0;mark(p,mob);mob.hurtServer(level,level.damageSources().playerAttack(p),1000);
    }
    /** Existing combat fixtures deliberately deploy after capture; bag-only tests use rawCapture. */
    @SuppressWarnings("unchecked")
    private static <T extends Mob> T capture(ServerPlayer p,T mob) {
        rawCapture(p,mob);
        if(!CompanionService.isCompanion(mob))return mob; // Full-capacity rejection leaves the wild mob alive.
        check(mob.isRemoved(),"new capture must be stored, not left deployed");
        JsonObject entry=entry(p,mob.getUUID());
        check(entry!=null&&entry.get("status").getAsString().equals("stored"),"capture missing stored roster entry");
        CompanionService.select(p,entry.get("slot").getAsInt(),mob.getStringUUID());
        boolean summoned=false;
        for(int[] offset:new int[][]{{3,0},{5,4},{3,-4},{-3,0},{0,3},{-5,4},{-5,-4}}) {
            if(CompanionService.summonAt(p,p.blockPosition().offset(offset[0],-1,offset[1]))){summoned=true;break;}
        }
        check(summoned,"fixture could not explicitly deploy captured companion: "+LAST_MESSAGES.get(p.getUUID()));
        Mob restored=activeMob(mob.getUUID());check(restored!=null,"deployment did not register the captured UUID");
        return (T)restored;
    }
    private static boolean summonBag(ServerPlayer p) {return CompanionService.summonAt(p,supportNear(p));}
    private static net.minecraft.core.BlockPos supportNear(ServerPlayer p) {return p.blockPosition().offset(3,-1,0);}
    private static void useBagOn(ServerPlayer p,net.minecraft.core.BlockPos support) {
        var hit=new BlockHitResult(Vec3.atCenterOf(support).add(0,.5,0),net.minecraft.core.Direction.UP,support,false);
        MercyMod.BAG.useOn(new net.minecraft.world.item.context.UseOnContext(p,InteractionHand.OFF_HAND,hit));
    }
    private static void select(ServerPlayer p,UUID id) {
        JsonObject e=entry(p,id);check(e!=null,"selection fixture entry missing: "+id);CompanionService.select(p,e.get("slot").getAsInt(),id.toString());
    }
    private static JsonObject roster(ServerPlayer p) {return JsonParser.parseString(CompanionService.rosterJson(p)).getAsJsonObject();}
    private static String selectedValue(ServerPlayer p) {return roster(p).get("selectedSlot").toString();}
    private static List<UUID> storedIds(ServerPlayer p) {
        List<UUID> ids=new ArrayList<>();for(JsonElement value:slots(p)){JsonObject e=value.getAsJsonObject();if(!e.get("status").getAsString().equals("active"))ids.add(UUID.fromString(e.get("uuid").getAsString()));}return ids;
    }
    private static int worldCopies(UUID id) {
        int count=0;for(ServerLevel world:server.getAllLevels())for(Entity e:world.getAllEntities())if(e.getUUID().equals(id)&&!e.isRemoved())count++;return count;
    }
    private static JsonObject entry(ServerPlayer p,UUID id) {
        for(JsonElement value:slots(p)) {JsonObject e=value.getAsJsonObject();if(e.get("uuid").getAsString().equals(id.toString()))return e;}
        return null;
    }
    private static Mob activeMob(UUID id) {
        Entity found=level.getEntityInAnyDimension(id);if(found instanceof Mob m&&!m.isRemoved())return m;
        // Chunk entity visibility may lag registration until the next server tick. Inspect the
        // actual service-owned instance here; delayed and combat cases also assert real ticking.
        try {var f=CompanionService.class.getDeclaredField("active");f.setAccessible(true);return (Mob)((Map<?,?>)f.get(null)).get(id);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static JsonArray slots(ServerPlayer p) {return JsonParser.parseString(CompanionService.rosterJson(p)).getAsJsonObject().getAsJsonArray("slots");}
    private static int drops() {int count=0;for(Entity e:level.getAllEntities())if(e instanceof net.minecraft.world.entity.item.ItemEntity||e instanceof ExperienceOrb)count++;return count;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void equal(float actual,float expected,String label){check(Math.abs(actual-expected)<0.001,label+": expected "+expected+", actual "+actual);}
    @FunctionalInterface private interface Checked {void run() throws Exception;}
}
