package dev.mercy.companion.ai;

import dev.mercy.companion.CompanionService;
import dev.mercy.companion.mixin.MobAccessor;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.illager.Evoker;

/** Ephemeral vanilla summons; never tagged or entered into the permanent roster. */
public final class TemporaryMinions {
    private TemporaryMinions() {}
    private record Binding(WeakReference<Evoker> caster, UUID owner) {}
    private static final Map<Vex, Binding> BINDINGS = new WeakHashMap<>();

    public static void clear() { BINDINGS.clear(); }

    /** Called before vanilla inserts a summoned vex into its world. */
    public static void bind(Vex vex, Mob caster) {
        if (vex.level().isClientSide() || CompanionService.isCompanion(vex)
                || !(caster instanceof Evoker evoker)) return;
        UUID owner = CompanionService.owner(evoker);
        if (owner == null || BINDINGS.containsKey(vex)) return;
        BINDINGS.put(vex, new Binding(new WeakReference<>(evoker), owner));
        // Keep the native owner-target copier and every movement/charge attack goal.
        // Independent revenge/player hunting would bypass the caster's commands.
        ((MobAccessor)vex).mercy$getTargetSelector().removeAllGoals(goal ->
                !goal.getClass().getName().equals("net.minecraft.world.entity.monster.Vex$VexCopyOwnerTargetGoal"));
        vex.setLastHurtByMob(null);
        vex.setTarget(null);
    }

    /** A newly captured evoker can already have living vanilla summons. */
    public static void adoptSummons(Mob caster) {
        if (!(caster instanceof Evoker) || !(caster.level() instanceof ServerLevel level)) return;
        for (Entity entity : level.getAllEntities())
            if (entity instanceof Vex vex && vex.getOwner() == caster) bind(vex, caster);
    }

    private static Binding binding(Entity entity) {
        if (!(entity instanceof Vex vex) || CompanionService.isCompanion(vex)) return null;
        Binding binding = BINDINGS.get(vex);
        if (binding == null && vex.getOwner() instanceof Evoker caster) {
            bind(vex, caster);
            binding = BINDINGS.get(vex);
        }
        return binding;
    }

    public static boolean isTemporary(Entity entity) { return binding(entity) != null; }
    public static UUID owner(Entity entity) {
        Binding binding = binding(entity);
        return binding == null ? null : binding.owner();
    }

    public static Evoker casterOf(Entity entity) {
        Binding binding = binding(entity);
        return binding == null || !(entity instanceof Vex vex) ? null : liveCaster(vex, binding);
    }

    private static Evoker liveCaster(Vex vex, Binding binding) {
        Evoker caster = binding.caster().get();
        return caster != null && caster.isAlive() && !caster.isRemoved()
                && caster.level() == vex.level() && binding.owner().equals(CompanionService.owner(caster))
                && vex.level() instanceof ServerLevel level && level.getEntity(caster.getUUID()) == caster
                ? caster : null;
    }

    public static boolean allowsTarget(Mob mob, LivingEntity target) {
        if (target == null) return true;
        Binding binding = binding(mob);
        if (binding == null || !(mob instanceof Vex vex)) return false;
        Evoker caster = liveCaster(vex, binding);
        return caster != null && target == caster.getTarget() && target.isAlive()
                && !target.isRemoved() && AiController.canTarget(vex, target);
    }

    /** Returns false when the vanilla tick should stop because its caster is gone. */
    public static boolean tick(Vex vex) {
        Binding binding = binding(vex);
        if (binding == null) return true;
        Evoker caster = liveCaster(vex, binding);
        if (caster == null) {
            vex.discard();
            return false;
        }
        LivingEntity target = caster.getTarget();
        if (!allowsTarget(vex, target)) target = null;
        if (vex.getTarget() != target) {
            vex.setTarget(target);
            if (target == null) vex.setIsCharging(false);
        }
        return true;
    }

    /** Recall, death and chunk removal all invalidate the exact caster instance. */
    public static void removeForCaster(Entity entity) {
        if (!(entity instanceof Evoker)) return;
        // Copy first: removing an entity invokes the same lifecycle hook recursively.
        for (Map.Entry<Vex, Binding> entry : new ArrayList<>(BINDINGS.entrySet())) {
            Vex vex = entry.getKey();
            if (vex != null && entry.getValue().caster().get() == entity && !vex.isRemoved()) vex.discard();
        }
    }
}
