package dev.mercy.companion.ai;

import dev.mercy.companion.CompanionService;
import dev.mercy.companion.MercyMod;
import dev.mercy.companion.mixin.MobAccessor;
import dev.mercy.companion.mixin.CreakingHeartAccessor;
import dev.mercy.companion.mixin.PhantomAccessor;
import dev.mercy.companion.mixin.CubeMoveControlAccessor;
import dev.mercy.companion.mixin.VindicatorAccessor;
import java.lang.ref.WeakReference;
import java.util.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.animal.camel.CamelHusk;
import net.minecraft.world.entity.animal.equine.ZombieHorse;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Giant;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.entity.CreakingHeartBlockEntity;

/** Keeps vanilla attack behaviours; replaces only ownership/target/follow decisions. */
public final class AiController {
    private AiController() {}
    private static final Map<Mob, State> STATES = new WeakHashMap<>();
    private static final Map<UUID, Hit> PRIMARY_HITS = new HashMap<>();
    private static final ThreadLocal<Mob> BRAIN_TICK = new ThreadLocal<>();
    private record Hit(UUID target, long tick) {}
    private static final class State {
        WeakReference<ServerPlayer> owner = new WeakReference<>(null);
        LivingEntity target;
        boolean ownerCommand;
        boolean following;
        WalkTarget followWalkTarget;
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            STATES.clear();
            PRIMARY_HITS.clear();
            BRAIN_TICK.remove();
            TemporaryMinions.clear();
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClientSide()) PRIMARY_HITS.put(player.getUUID(), new Hit(entity.getUUID(), world.getGameTime()));
            return InteractionResult.PASS;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, damage) -> !isOwnersDamage(victim, source));
        ServerLivingEntityEvents.ALLOW_DEATH.register((victim, source, damage) -> {
            if (!(victim instanceof Mob) || !isDirectMercySwordAttack(source)) return true;
            // Only the selected primary target can join the roster. Swept targets,
            // friends and unsupported mobs still survive the sword's direct hit.
            if (isPrimarySwordStrike(victim, source) && CompanionService.canCapture(victim, source)
                    && CompanionService.capture(victim, source)) return false;
            victim.setHealth(1);
            return false;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, base, taken, blocked) -> {
            if (taken <= 0) return;
            CompanionService.touchCombat(victim);
            Entity attacker = responsible(source.getEntity());
            if (attacker instanceof LivingEntity living) CompanionService.touchCombat(living);
            LivingEntity victimCaster = TemporaryMinions.casterOf(victim);
            LivingEntity attackerCaster = TemporaryMinions.casterOf(attacker);
            if (victimCaster != null) CompanionService.touchCombat(victimCaster);
            if (attackerCaster != null) CompanionService.touchCombat(attackerCaster);
            if (victim instanceof Mob mob && !canTarget(mob, attacker)) clearFriendlyAnger(mob, attacker);
        });
        ServerLivingEntityEvents.MOB_CONVERSION.register((original, replacement, params) -> {
            if (CompanionService.isCompanion(original)) CompanionService.onConversion(original, replacement);
        });
    }

    public static boolean isDirectMercySwordAttack(DamageSource source) {
        return source.getDirectEntity() instanceof ServerPlayer player
                && source.getEntity() == player && source.is(DamageTypes.PLAYER_ATTACK)
                && player.getMainHandItem().is(MercyMod.SWORD);
    }

    public static boolean isPrimarySwordStrike(LivingEntity victim, DamageSource source) {
        if (!isDirectMercySwordAttack(source)) return false;
        ServerPlayer player = (ServerPlayer)source.getEntity();
        Hit hit = PRIMARY_HITS.get(player.getUUID());
        return hit != null && hit.target().equals(victim.getUUID())
                && player.level().getGameTime() - hit.tick() <= 1;
    }

    public static Entity responsible(Entity entity) {
        for (int i = 0; i < 4 && entity instanceof Projectile projectile; i++) {
            Entity owner = projectile.getOwner();
            if (owner == null || owner == entity) break;
            entity = owner;
        }
        if (entity instanceof AreaEffectCloud cloud && cloud.getOwner() != null) return cloud.getOwner();
        return entity;
    }

    public static boolean isOwnersDamage(Entity victim, DamageSource source) {
        // Area attacks remain hazardous to the owner: only direct combat gets protection.
        if (isAreaOrFireDamage(source)) return false;
        Entity attacker = responsible(source.getEntity());
        if (attacker == null) attacker = responsible(source.getDirectEntity());
        return protects(victim, attacker);
    }

    public static boolean isAreaOrFireDamage(DamageSource source) {
        return source.is(DamageTypeTags.IS_EXPLOSION) || source.is(DamageTypeTags.IS_FIRE)
                || source.is(DamageTypes.FIREBALL) || source.is(DamageTypes.UNATTRIBUTED_FIREBALL)
                || source.is(DamageTypes.WITHER_SKULL) || source.is(DamageTypes.WIND_CHARGE)
                || source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC)
                || source.is(DamageTypes.DRAGON_BREATH) || source.getDirectEntity() instanceof AreaEffectCloud;
    }

    public static boolean protects(Entity victim, Entity attackSource) {
        Entity attacker = responsible(attackSource);
        if (victim == null || attacker == null) return false;
        UUID owner = affiliationOwner(attacker);
        return owner != null && owner.equals(victim.getUUID());
    }

    /** Combat affiliation includes summons without making them permanent companions. */
    public static UUID affiliationOwner(Entity entity) {
        UUID owner = CompanionService.owner(entity);
        return owner != null ? owner : TemporaryMinions.owner(entity);
    }

    public static boolean sameAffiliation(Entity a, Entity b) {
        UUID owner = affiliationOwner(a);
        return owner != null && owner.equals(affiliationOwner(b));
    }

    public static boolean canTarget(Mob mob, Entity target) {
        if (target == null || affiliationOwner(mob) == null) return true;
        if (target == mob || protects(target, mob) || sameAffiliation(mob, target)) return false;
        if (mob instanceof WitherBoss && target.is(EntityTypeTags.WITHER_FRIENDS)) return false;
        return !(mob instanceof Zoglin && (target instanceof Zoglin || target instanceof Creeper));
    }

    public static boolean retainsNativeAggression(Mob mob) {
        return mob instanceof WitherBoss || mob instanceof Warden || mob instanceof Zoglin
                || mob instanceof Vindicator && ((VindicatorAccessor)mob).mercy$isJohnny();
    }

    public static boolean acceptsCombatCommands(Mob mob) {
        // These vanilla types have no attack AI; combat targets would only suspend following.
        return !(mob instanceof Giant || mob instanceof CamelHusk || mob instanceof ZombieHorse
                || mob instanceof SulfurCube || mob instanceof Villager);
    }

    public static boolean allowTargetSelection(Mob mob, LivingEntity target) {
        if (TemporaryMinions.isTemporary(mob)) return TemporaryMinions.allowsTarget(mob, target);
        if (!CompanionService.isCompanion(mob)) return true;
        if (!canTarget(mob, target)) return false;
        if (target == null) return true;
        State state = STATES.get(mob);
        if (state != null && state.ownerCommand) return target == state.target;
        return retainsNativeAggression(mob);
    }

    public static void attach(Mob mob) {
        if (STATES.containsKey(mob)) return;
        State state = new State();
        STATES.put(mob, state);
        mob.setPersistenceRequired();
        mob.setTarget(null);
        mob.setLastHurtByMob(null);
        // Removing the whole Brain would also remove sonic booms, crossbow use, etc.
        if (!retainsNativeAggression(mob)) ((MobAccessor) mob).mercy$getTargetSelector().removeAllGoals(goal ->
                // Keep the dormant Johnny variant available if the player names it later.
                !goal.getClass().getName().equals("net.minecraft.world.entity.monster.illager.Vindicator$VindicatorJohnnyAttackGoal"));
        // Hiding in blocks and waking unbound silverfish would escape the roster.
        mob.getGoalSelector().removeAllGoals(goal -> {
            String name = goal.getClass().getName();
            return name.equals("net.minecraft.world.entity.monster.Silverfish$SilverfishMergeWithStoneGoal")
                    || name.equals("net.minecraft.world.entity.monster.Silverfish$SilverfishWakeUpFriendsGoal");
        });
        TemporaryMinions.adoptSummons(mob);
        ensureMovementGoal(mob, state);
        // Attack goals stay entirely vanilla, including creatures with no native attack AI.
        clearUnwantedMemories(mob, state);
        if (mob instanceof WitherBoss wither) {
            wither.setInvulnerableTicks(0);
            for (int i = 0; i < 3; i++) wither.setAlternativeTarget(i, 0);
        }
        if (mob instanceof Warden warden) preventDigging(warden);
        if (mob instanceof Creaking creaking) {
            if (creaking.getHomePos() != null && mob.level().getBlockEntity(creaking.getHomePos()) instanceof CreakingHeartBlockEntity heart
                    && heart.isProtector(creaking)) ((CreakingHeartAccessor) heart).mercy$clearCreakingInfo();
            ((CreakingCompanion)creaking).mercy$detachHeart();
            creaking.setIsActive(true);
        }
    }

    /** The owner may be absent; legacy mode values now all use follow behaviour. */
    public static void tick(Mob mob, ServerPlayer owner, String mode) {
        attach(mob);
        State state = STATES.get(mob);
        // A sulfur cube rebuilds its vanilla goals when its contained block is removed.
        if (mob instanceof SulfurCube) ensureMovementGoal(mob, state);
        ServerPlayer followOwner = canFollowOwner(mob, owner) ? owner : null;
        if (state.owner.get() != followOwner) state.owner = new WeakReference<>(followOwner);
        if (followOwner == null) clearFollowIntent(mob, state);
        boolean combatCapable = acceptsCombatCommands(mob);
        LivingEntity chosen = followOwner == null || !combatCapable ? null
                : recent(mob, followOwner.getLastHurtMob(), followOwner.tickCount - followOwner.getLastHurtMobTimestamp());
        if (chosen == null && followOwner != null && combatCapable)
            chosen = recent(mob, followOwner.getLastHurtByMob(), followOwner.tickCount - followOwner.getLastHurtByMobTimestamp());
        boolean ownerCommand = chosen != null;
        if (chosen == null && combatCapable && state.ownerCommand && valid(mob, state.target)) {
            chosen = state.target;
            ownerCommand = true;
        }
        // Only the species that already hunt broadly keep choosing third parties on their own.
        if (chosen == null && retainsNativeAggression(mob) && valid(mob, mob.getTarget())) chosen = mob.getTarget();
        state.target = chosen;
        state.ownerCommand = ownerCommand;
        mob.setTarget(chosen);
        clearUnwantedMemories(mob, state);
        if (mob instanceof Warden warden) {
            if (owner != null) warden.clearAnger(owner);
            preventDigging(warden);
            if (chosen != null && state.ownerCommand) {
                // Vanilla combat ends immediately unless the warden is genuinely angry.
                if (!warden.getAngerLevel().isAngry() || warden.tickCount % 20 == 0)
                    warden.increaseAngerAt(chosen, 150, false);
                if (warden.getTarget() != chosen) warden.setAttackTarget(chosen);
            }
        }
        if (mob instanceof WitherBoss wither) {
            wither.setInvulnerableTicks(0);
            // Idle side heads retain their native independent targeting and undead exclusion.
            if (state.ownerCommand) for (int i = 0; i < 3; i++) wither.setAlternativeTarget(i, chosen.getId());
        }
        // Brain mobs do not execute GoalSelector movement; use their walk memory too.
        if (chosen == null && followOwner != null && mob.distanceToSqr(followOwner) > 16.0) {
            state.followWalkTarget = new WalkTarget(followOwner.position(), 1.1F, 3);
            mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET, state.followWalkTarget);
        } else clearFollowIntent(mob, state);
    }

    public static boolean canFollowOwner(Mob mob, ServerPlayer owner) {
        return owner != null && owner.isAlive() && !owner.isRemoved() && !owner.hasDisconnected()
                && owner.level() == mob.level() && owner.getUUID().equals(CompanionService.owner(mob));
    }
    private static ServerPlayer currentOwner(Mob mob, State state) {
        ServerPlayer owner = state.owner.get();
        if (canFollowOwner(mob, owner)) return owner;
        state.owner.clear();
        clearFollowIntent(mob, state);
        return null;
    }
    private static void clearFollowIntent(Mob mob, State state) {
        boolean walkingToOwner = state.followWalkTarget != null
                && memoryOrNull(mob.getBrain(), MemoryModuleType.WALK_TARGET) == state.followWalkTarget;
        if (walkingToOwner) mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        state.followWalkTarget = null;
        if (!state.following && !walkingToOwner) return;
        state.following = false;
        mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        if (mob.getMoveControl() instanceof CubeMoveControlAccessor control) control.mercy$setWantedMovement(0);
        if (mob instanceof Phantom) {
            ((PhantomAccessor)mob).mercy$setMoveTargetPoint(mob.position());
            ((PhantomAccessor)mob).mercy$setAnchorPoint(mob.blockPosition());
        }
    }
    private static LivingEntity recent(Mob mob, LivingEntity target, int age) {
        return age >= 0 && age < 200 && valid(mob, target) ? target : null;
    }
    private static void preventDigging(Warden warden) {
        // Vanilla setDigCooldown only refreshes an existing memory, so initialize it here.
        warden.getBrain().setMemoryWithExpiry(MemoryModuleType.DIG_COOLDOWN, Unit.INSTANCE, 1200L);
    }
    private static void ensureMovementGoal(Mob mob, State state) {
        if (mob instanceof SulfurCube && !mob.getItemBySlot(EquipmentSlot.BODY).isEmpty()) return;
        if (mob.getGoalSelector().getAvailableGoals().stream()
                .noneMatch(wrapped -> wrapped.getGoal() instanceof CompanionMovementGoal))
            mob.getGoalSelector().addGoal(0, new CompanionMovementGoal(mob, state));
    }
    private static boolean valid(Mob mob, LivingEntity target) {
        return target != null && target.isAlive() && !target.isRemoved() && target.level() == mob.level()
                && mob.distanceToSqr(target) < 2304 && canTarget(mob, target)
                && (!(mob instanceof Warden warden) || warden.canTargetEntity(target));
    }
    private static void clearFriendlyAnger(Mob mob, Entity attacker) {
        mob.setLastHurtByMob(null);
        if (!canTarget(mob, mob.getTarget())) mob.setTarget(null);
        if (mob instanceof Warden warden && attacker != null) warden.clearAnger(attacker);
        mob.getBrain().eraseMemory(MemoryModuleType.HURT_BY_ENTITY);
        mob.getBrain().eraseMemory(MemoryModuleType.HURT_BY);
        mob.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
    }
    private static void clearUnwantedMemories(Mob mob, State state) {
        Brain<?> brain = mob.getBrain();
        if (!retainsNativeAggression(mob) || state.ownerCommand) {
            brain.eraseMemory(MemoryModuleType.ANGRY_AT);
            brain.eraseMemory(MemoryModuleType.ROAR_TARGET);
            if (state.target == null) brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
            else if (!(mob instanceof Warden)) brain.setMemory(MemoryModuleType.ATTACK_TARGET, state.target);
        } else {
            // Leave third-party anger/roaring/sensory memories intact; ownership only removes friends.
            LivingEntity roar = memoryOrNull(brain, MemoryModuleType.ROAR_TARGET);
            if (roar != null && !canTarget(mob, roar)) brain.eraseMemory(MemoryModuleType.ROAR_TARGET);
            LivingEntity attack = memoryOrNull(brain, MemoryModuleType.ATTACK_TARGET);
            if (attack != null && !canTarget(mob, attack)) brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
            UUID angry = memoryOrNull(brain, MemoryModuleType.ANGRY_AT);
            if (angry != null && (angry.equals(CompanionService.owner(mob))
                    || mob.level() instanceof net.minecraft.server.level.ServerLevel level
                    && sameAffiliation(mob, level.getEntity(angry))))
                brain.eraseMemory(MemoryModuleType.ANGRY_AT);
        }
        if (!canTarget(mob, mob.getLastHurtByMob())) clearFriendlyAnger(mob, mob.getLastHurtByMob());
    }
    private static <T> T memoryOrNull(Brain<?> brain, MemoryModuleType<T> type) {
        Optional<T> memory = brain.getMemoryInternal(type);
        return memory == null ? null : memory.orElse(null);
    }
    public static void beforeBrain(LivingEntity entity) {
        if (entity instanceof Mob mob && CompanionService.isCompanion(mob)) {
            BRAIN_TICK.set(mob);
            State state = STATES.get(mob);
            if (state != null) clearUnwantedMemories(mob, state);
        }
    }
    public static void afterBrain() { BRAIN_TICK.remove(); }
    public static boolean allowBrainErase(MemoryModuleType<?> type) {
        Mob mob = BRAIN_TICK.get();
        if (mob == null || type != MemoryModuleType.ATTACK_TARGET) return true;
        State state = STATES.get(mob);
        // Species-specific checks (piglins: nearest preferred prey; creakings: players)
        // must not discard an otherwise valid target chosen by the owner's command.
        return state == null || !state.ownerCommand || !valid(mob, state.target);
    }
    public static boolean allowBrainMemory(MemoryModuleType<?> type, Object value) {
        Mob mob = BRAIN_TICK.get();
        if (mob == null) return true;
        if (value instanceof LivingEntity target && !canTarget(mob, target)) return false;
        if (type == MemoryModuleType.ATTACK_TARGET || type == MemoryModuleType.ROAR_TARGET) {
            State state = STATES.get(mob);
            if (retainsNativeAggression(mob) && (state == null || !state.ownerCommand)) return true;
            return state != null && value == state.target;
        }
        return true;
    }

    private static final class CompanionMovementGoal extends Goal {
        private final Mob mob;
        private final State state;
        CompanionMovementGoal(Mob mob, State state) {
            this.mob = mob; this.state = state;
            setFlags(EnumSet.of(Flag.MOVE));
        }
        @Override public boolean canUse() {
            ServerPlayer owner = currentOwner(mob, state);
            return owner != null && state.target == null && mob.distanceToSqr(owner) > 16;
        }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { state.following = true; }
        @Override public void stop() { clearFollowIntent(mob, state); }
        @Override public void tick() {
            ServerPlayer owner = currentOwner(mob, state);
            if (owner == null) return;
            state.following = true;
            Vec3 destination = owner.position();
            if (mob.position().distanceToSqr(destination) < 9) {
                clearFollowIntent(mob, state);
                return;
            }
            if (mob.getMoveControl() instanceof CubeMoveControlAccessor control) {
                Vec3 delta=destination.subtract(mob.position());
                control.mercy$setDirection((float)(Math.atan2(delta.z,delta.x)*180.0/Math.PI)-90.0F,false);
                control.mercy$setWantedMovement(1.1);
            } else if (mob instanceof Shulker) {
                if (mob.tickCount % 40 == 0) ((ShulkerCompanion)mob).mercy$teleportNear(destination);
            } else if (mob instanceof Phantom) {
                ((PhantomAccessor)mob).mercy$setMoveTargetPoint(destination.add(0, 3, 0));
                ((PhantomAccessor)mob).mercy$setAnchorPoint(net.minecraft.core.BlockPos.containing(destination).above(3));
            } else if (mob instanceof Ghast || mob instanceof WitherBoss || mob instanceof Vex) {
                mob.getMoveControl().setWantedPosition(destination.x, destination.y + 2, destination.z, 1.0);
                // Wither uses its own flight controller rather than ground navigation.
                if (mob instanceof WitherBoss) mob.setDeltaMovement(mob.getDeltaMovement().scale(0.8)
                        .add(destination.add(0, 2, 0).subtract(mob.position()).normalize().scale(0.08)));
            } else if (mob.tickCount % 10 == 0) mob.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.1);
        }
    }
}
