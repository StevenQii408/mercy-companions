package dev.mercy.companion;

import com.google.gson.*;
import dev.mercy.companion.ai.AiController;
import dev.mercy.companion.ai.TemporaryMinions;
import dev.mercy.companion.net.MercyNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Server-authoritative roster. Companions deliberately do not save into vanilla entity chunks.
 * Their complete NBT has one durable owner: this per-world roster. Unloading preserves deployment.
 * Terminal death is persisted immediately, so reconnecting cannot resurrect a dead companion.
 */
public final class CompanionService {
    public static final String OWNER_TAG = "mercy_owner:";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ResourceKey<Enchantment> COVENANT = ResourceKey.create(Registries.ENCHANTMENT, MercyMod.id("covenant"));
    private static MinecraftServer server;
    private static Path file;
    private static Store store = new Store();
    private static final Map<UUID, Mob> active = new HashMap<>();
    private static final Map<UUID, Long> restoreAfter = new HashMap<>();
    private static final List<Mob> pendingLoads = new ArrayList<>();
    private static final List<Mob> pendingUnloads = new ArrayList<>();
    private static final Set<UUID> restoring = new HashSet<>();
    private static boolean closing;
    private static boolean healthy = true;

    private static final class Store {
        int format = 2;
        Map<String, Roster> players = new LinkedHashMap<>();
    }
    private static final class Roster {
        boolean starterGiven;
        boolean bagGiven;
        Map<Integer, Pet> pets = new TreeMap<>();
        List<Integer> stack = new ArrayList<>(); // Bottom to top; only intentionally stored companions.
    }
    private static final class Pet {
        String uuid, name, type, data, mode = "follow";
        float health, maxHealth;
        boolean deployed;
        String dimension;
        double x, y, z;
        long lastCombat = Long.MIN_VALUE / 2, nextSummonAt;
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(CompanionService::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            closing = true;
            reconcileEntities();
            for (Mob mob : List.copyOf(active.values())) {
                Pet pet = pet(mob);
                if (mob.getHealth() <= 0) onDeath(mob);
                else if (pet != null) snapshot(mob, pet);
            }
            persist();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            active.clear(); restoreAfter.clear(); pendingLoads.clear(); pendingUnloads.clear(); restoring.clear(); server = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(CompanionService::tick);
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof Mob mob && isCompanion(mob)) {
                if (mob.getHealth() <= 0) onDeath(mob);
                else pendingUnloads.add(mob);
            }
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Mob mob && isCompanion(mob)) pendingLoads.add(mob);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayer player = handler.player;
            Roster roster = roster(player.getUUID());
            if (!roster.starterGiven && healthy) {
                roster.starterGiven = true;
                if (persist()) {
                    ItemStack sword = new ItemStack(MercyMod.SWORD);
                    if (!player.getInventory().add(sword)) player.drop(sword, false, net.minecraft.util.Prediction.SERVER_ONLY);
                    tell(player, "已获得仁慈之剑。用它完成最后一击，伙伴会存入伙伴收纳袋。");
                } else roster.starterGiven = false;
            }
            if (!roster.bagGiven && healthy) {
                roster.bagGiven = true;
                if (persist()) {
                    ItemStack bag = new ItemStack(MercyMod.BAG);
                    if (!player.getInventory().add(bag)) player.drop(bag, false, net.minecraft.util.Prediction.SERVER_ONLY);
                    tell(player, "已获得伙伴收纳袋：对地面右键召唤，对伙伴右键收回；在物品栏悬停袋子，滚轮浏览，右键确认。");
                } else roster.bagGiven = false;
            }
            MercyNetworking.update(player);
        });
    }

    private static void start(MinecraftServer s) {
        server = s; closing = false; healthy = true;
        active.clear(); restoreAfter.clear(); pendingLoads.clear(); pendingUnloads.clear(); restoring.clear();
        file = s.getWorldPath(LevelResource.ROOT).resolve("data/mercy_companions.json");
        store = new Store();
        if (Files.exists(file)) {
            try {
                Store loaded = GSON.fromJson(Files.readString(file), Store.class);
                if (loaded == null || (loaded.format != 1 && loaded.format != 2) || loaded.players == null)
                    throw new IOException("Unknown or invalid companion roster format");
                boolean legacy = loaded.format == 1;
                if (legacy) Files.copy(file, file.resolveSibling("mercy_companions.v1.backup.json"), StandardCopyOption.REPLACE_EXISTING);
                for (String owner : loaded.players.keySet()) UUID.fromString(owner);
                Set<UUID> identities = new HashSet<>();
                for (Roster roster : loaded.players.values()) {
                    if (roster.pets == null) throw new IOException("Missing pet slots");
                    if (roster.stack == null) roster.stack = new ArrayList<>();
                    for (var entry : roster.pets.entrySet()) {
                        Pet pet = entry.getValue();
                        if (entry.getKey() < 0 || entry.getKey() > 6 || pet == null || pet.data == null)
                            throw new IOException("Invalid pet slot");
                        if (!identities.add(UUID.fromString(pet.uuid))) throw new IOException("Duplicate companion identity");
                        if (!(pet.health > 0)) throw new IOException("Dead companion in live roster");
                        if (pet.type == null) {
                            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(pet.data)), NbtAccounter.create(4_194_304));
                            pet.type = tag.getStringOr("id", "minecraft:unknown");
                        }
                        pet.mode = "follow";
                        // Version 1 saved all companions as stored on orderly shutdown and had no dimension metadata.
                        if (legacy) pet.deployed = false;
                        if (pet.deployed && (pet.dimension == null || !Double.isFinite(pet.x) || !Double.isFinite(pet.y) || !Double.isFinite(pet.z)))
                            throw new IOException("Active companion has no valid world position");
                        if (pet.deployed) Identifier.parse(pet.dimension);
                    }
                    normalizeStack(roster);
                }
                loaded.format = 2;
                store = loaded;
                if (legacy) persist();
            } catch (Exception ex) {
                healthy = false; // Fail closed. Never overwrite a damaged roster with an empty one.
                MercyMod.LOGGER.error("Cannot read companion roster; capture/summon disabled, original file preserved", ex);
            }
        }
    }

    private static boolean persist() {
        if (server == null || file == null || !healthy) return false;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(store), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException ex) {
            healthy = false;
            MercyMod.LOGGER.error("Cannot save companion roster; new captures and summons disabled", ex);
            return false;
        }
    }

    public static UUID owner(Entity entity) {
        if (entity == null) return null;
        for (String tag : entity.entityTags()) {
            if (tag.startsWith(OWNER_TAG)) {
                try { return UUID.fromString(tag.substring(OWNER_TAG.length())); }
                catch (IllegalArgumentException ignored) { return null; }
            }
        }
        return null;
    }
    public static boolean isCompanion(Entity entity) { return owner(entity) != null; }
    public static boolean sameOwner(Entity a, Entity b) {
        UUID owner = owner(a);
        return owner != null && owner.equals(owner(b));
    }
    private static Roster roster(UUID owner) { return store.players.computeIfAbsent(owner.toString(), k -> new Roster()); }
    private static void normalizeStack(Roster roster) {
        Set<Integer> seen = new HashSet<>();
        roster.stack.removeIf(slot -> slot == null || !roster.pets.containsKey(slot)
                || roster.pets.get(slot).deployed || !seen.add(slot));
        for (var entry : roster.pets.entrySet())
            if (!entry.getValue().deployed && !roster.stack.contains(entry.getKey())) roster.stack.add(entry.getKey());
    }
    private static int slotOf(Roster roster, Pet pet) {
        return roster.pets.entrySet().stream().filter(e -> e.getValue() == pet).map(Map.Entry::getKey).findFirst().orElse(-1);
    }
    private static void push(Roster roster, int slot) {
        roster.stack.remove(Integer.valueOf(slot));
        roster.stack.add(slot);
    }
    private static int nextSlot(ServerPlayer player) {
        Roster roster = roster(player.getUUID());
        for (int i = roster.stack.size() - 1; i >= 0; i--) {
            int slot = roster.stack.get(i);
            Pet pet = roster.pets.get(slot);
            if (pet != null && !pet.deployed && slot < capacity(player)) return slot;
        }
        return -1;
    }
    private static Pet pet(Entity mob) {
        UUID owner = owner(mob);
        if (owner == null) return null;
        Roster roster = store.players.get(owner.toString());
        if (roster == null) return null;
        for (Pet pet : roster.pets.values()) if (mob.getStringUUID().equals(pet.uuid)) return pet;
        return null;
    }
    public static boolean hasSword(ServerPlayer player) {
        return player.getInventory().contains(stack -> stack.is(MercyMod.SWORD));
    }
    public static boolean hasBag(ServerPlayer player) {
        return player.getInventory().contains(stack -> stack.is(MercyMod.BAG));
    }
    private static boolean holdsBag(ServerPlayer player) {
        return player.getMainHandItem().is(MercyMod.BAG) || player.getOffhandItem().is(MercyMod.BAG);
    }
    public static int capacity(ServerPlayer player) {
        int best = 0;
        var holder = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(COVENANT);
        if (holder.isPresent()) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.is(MercyMod.SWORD)) best = Math.max(best, EnchantmentHelper.getItemEnchantmentLevel(holder.get(), stack));
            }
        }
        return 3 + Math.clamp(best, 0, 4);
    }
    public static boolean eligible(Entity entity) {
        if (TemporaryMinions.isTemporary(entity)) return false;
        return entity instanceof Mob && !(entity instanceof EnderDragon) && !isCompanion(entity)
                && (entity.getType().getCategory() == MobCategory.MONSTER
                    || net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().equals("enderman")
                    || entity instanceof net.minecraft.world.entity.animal.rabbit.Rabbit rabbit
                        && rabbit.getVariant() == net.minecraft.world.entity.animal.rabbit.Rabbit.Variant.EVIL);
    }
    public static boolean canCapture(LivingEntity entity, DamageSource source) {
        return healthy && eligible(entity) && source.is(DamageTypes.PLAYER_ATTACK)
                && source.getEntity() instanceof ServerPlayer player && source.getDirectEntity() == player
                && player.getMainHandItem().is(MercyMod.SWORD);
    }
    public static boolean capture(LivingEntity entity, DamageSource source) {
        if (!canCapture(entity, source)) return false;
        Mob mob = (Mob) entity;
        ServerPlayer player = (ServerPlayer) source.getEntity();
        Roster roster = roster(player.getUUID());
        int slot = -1;
        if (roster.pets.size() < capacity(player)) {
            for (int i = 0; i < capacity(player); i++) if (!roster.pets.containsKey(i)) { slot = i; break; }
        }
        if (slot < 0) {
            mob.setHealth(1);
            tell(player, "伙伴名额已满。目标保留 1 点生命。");
            return true;
        }
        mob.addTag(OWNER_TAG + player.getUUID());
        // A captured rider/mount must not carry an unrelated hostile entity into the roster.
        mob.unRide();
        mob.ejectPassengers();
        mob.setPersistenceRequired();
        mob.setCanPickUpLoot(false);
        mob.setTarget(null);
        mob.setLastHurtByMob(null);
        mob.setHealth(mob.getMaxHealth()); // The only free heal; later healing follows manual potion rules.
        mob.clearFire();
        mob.removeAllEffects();
        mob.deathTime = 0;
        if (mob instanceof WitherBoss wither) wither.setInvulnerableTicks(0);
        Pet pet = new Pet();
        pet.uuid = mob.getStringUUID();
        pet.deployed = false;
        roster.pets.put(slot, pet);
        AiController.attach(mob);
        snapshot(mob, pet);
        push(roster, slot);
        if (persist()) mob.discard();
        else {
            pet.deployed = true;
            roster.stack.remove(Integer.valueOf(slot));
            active.put(mob.getUUID(), mob);
        }
        player.sendSystemMessage(Component.literal("收服成功：").append(mob.getDisplayName())
                .append("（" + roster.pets.size() + "/" + capacity(player) + "），"
                    + (pet.deployed ? "保存失败，伙伴暂留原地。" : "已存入伙伴收纳袋。")));
        MercyNetworking.update(player);
        return true;
    }

    public static void onDeath(LivingEntity entity) {
        UUID owner = owner(entity);
        if (owner == null) return;
        Roster roster = store.players.get(owner.toString());
        if (roster == null) return;
        boolean removed = roster.pets.values().removeIf(pet -> entity.getStringUUID().equals(pet.uuid));
        normalizeStack(roster);
        active.remove(entity.getUUID()); restoreAfter.remove(entity.getUUID());
        if (removed) {
            persist();
            ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(owner);
            if (player != null) player.sendSystemMessage(Component.literal("伙伴 ").append(entity.getDisplayName())
                    .append(" 已永久死亡，名额已释放。"));
            if (player != null) MercyNetworking.update(player);
        }
    }
    /** Keeps one roster entry if vanilla transforms a mob (e.g. drowning or zombification). */
    public static void onConversion(Mob original, Mob replacement) {
        UUID owner = owner(original);
        if (owner == null) return;
        Pet pet = pet(original);
        if (pet == null) return;
        replacement.addTag(OWNER_TAG + owner);
        replacement.setPersistenceRequired();
        replacement.setCanPickUpLoot(false);
        replacement.setHealth(Math.min(original.getHealth(), replacement.getMaxHealth()));
        replacement.setTarget(null);
        pet.uuid = replacement.getStringUUID();
        pet.deployed = true;
        active.remove(original.getUUID());
        active.put(replacement.getUUID(), replacement);
        restoreAfter.remove(original.getUUID());
        AiController.attach(replacement);
        snapshot(replacement, pet);
        persist();
    }
    public static void touchCombat(LivingEntity entity) {
        if (server != null && isCompanion(entity)) {
            Pet pet = pet(entity);
            if (pet != null) pet.lastCombat = server.overworld().getGameTime();
            checkpoint(entity);
        }
    }
    /** Called after damage/healing as well as periodic ticks to durably preserve expendable health. */
    public static void checkpoint(LivingEntity entity) {
        if (entity instanceof Mob mob && mob.getHealth() > 0 && active.containsKey(mob.getUUID())) {
            Pet pet = pet(mob);
            if (pet != null) { snapshot(mob, pet); persist(); }
        }
    }
    private static void snapshot(Mob mob, Pet pet) {
        try {
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, mob.registryAccess());
            mob.saveWithoutId(output);
            CompoundTag tag = output.buildResult();
            pet.type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
            tag.putString("id", pet.type);
            tag.remove("Passengers");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bytes);
            pet.data = Base64.getEncoder().encodeToString(bytes.toByteArray());
            pet.name = mob.hasCustomName() ? mob.getName().getString() : mob.getType().getDescriptionId();
            pet.health = mob.getHealth();
            pet.maxHealth = mob.getMaxHealth();
            pet.dimension = mob.level().dimension().identifier().toString();
            pet.x = mob.getX(); pet.y = mob.getY(); pet.z = mob.getZ();
        } catch (Exception ex) {
            healthy = false;
            MercyMod.LOGGER.error("Unable to serialize companion {}", mob.getUUID(), ex);
        }
    }
    /** Process entity lifecycle after all dimension transfers in this tick have completed. */
    private static boolean reconcileEntities() {
        boolean dirty = false;
        for (Mob mob : List.copyOf(pendingLoads)) {
            if (mob.isRemoved()) continue;
            Pet pet = pet(mob);
            if (pet == null || !pet.deployed) { mob.discard(); continue; }
            Mob previous = active.get(mob.getUUID());
            if (previous != null && previous != mob && !previous.isRemoved()) { mob.discard(); continue; }
            active.put(mob.getUUID(), mob);
            restoreAfter.remove(mob.getUUID());
            AiController.attach(mob);
            snapshot(mob, pet);
            dirty = true;
        }
        pendingLoads.clear();
        for (Mob mob : List.copyOf(pendingUnloads)) {
            if (active.get(mob.getUUID()) != mob) continue;
            Pet pet = pet(mob);
            if (mob.getHealth() <= 0) { onDeath(mob); continue; }
            if (pet != null && pet.deployed) { snapshot(mob, pet); dirty = true; }
            active.remove(mob.getUUID());
            restoreAfter.put(mob.getUUID(), server.overworld().getGameTime() + 40);
        }
        pendingUnloads.clear();
        return dirty;
    }

    private static void tick(MinecraftServer s) {
        if (closing || !healthy) return;
        boolean dirty = reconcileEntities();
        for (Mob mob : List.copyOf(active.values())) {
            if (mob.getHealth() <= 0) { onDeath(mob); continue; }
            Pet pet = pet(mob);
            if (pet == null || !pet.deployed) { active.remove(mob.getUUID()); mob.discard(); continue; }
            if (mob.isRemoved()) {
                snapshot(mob, pet);
                active.remove(mob.getUUID());
                restoreAfter.put(mob.getUUID(), s.overworld().getGameTime() + 40);
                dirty = true;
                continue;
            }
            ServerPlayer owner = s.getPlayerList().getPlayer(owner(mob));
            AiController.tick(mob, owner, "follow");
            if (s.getTickCount() % 40 == 0 || pet.health != mob.getHealth()) {
                snapshot(mob, pet); dirty = true;
            }
        }
        if (dirty) persist();
        if (s.getTickCount() % 20 == 0) restoreDeployed();
    }

    private static Mob restore(ServerLevel level, UUID owner, Pet pet) throws IOException {
        CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(pet.data)), NbtAccounter.create(4_194_304));
        var input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag);
        // Restore an existing roster member, even in Peaceful; this is not a new wild spawn.
        // Validate identity below before the candidate can be added to the world.
        Entity restored = EntityType.loadEntityRecursive(input, level,
                new EntitySpawnRequest(EntitySpawnReason.LOAD, true), entity -> entity);
        if (!(restored instanceof Mob mob)) throw new IOException("Companion is not a mob");
        if (!owner.equals(owner(mob)) || !mob.getStringUUID().equals(pet.uuid)) throw new IOException("Companion ownership mismatch");
        if (mob.getHealth() <= 0) throw new IOException("Cannot restore a dead companion");
        mob.setTarget(null); mob.setLastHurtByMob(null); mob.setPersistenceRequired();
        if (mob instanceof WitherBoss wither) wither.setInvulnerableTicks(0);
        return mob;
    }

    /** Restores world residents at their saved world position, never into the bag or at the owner. */
    private static void restoreDeployed() {
        if (!healthy || closing) return;
        long now = server.overworld().getGameTime();
        for (var playerEntry : store.players.entrySet()) {
            UUID owner = UUID.fromString(playerEntry.getKey());
            for (Pet pet : List.copyOf(playerEntry.getValue().pets.values())) {
                UUID id = UUID.fromString(pet.uuid);
                if (!pet.deployed || active.containsKey(id) || restoring.contains(id)
                        || now < restoreAfter.getOrDefault(id, 0L)) continue;
                Entity existing = null;
                for (ServerLevel world : server.getAllLevels()) {
                    existing = world.getEntity(id);
                    if (existing != null) break;
                }
                if (existing != null) {
                    if (existing instanceof Mob mob && owner.equals(owner(mob)) && mob.isAlive() && !mob.isRemoved()) {
                        active.put(id, mob); AiController.attach(mob); snapshot(mob, pet); persist();
                    }
                    continue;
                }
                ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(pet.dimension)));
                if (level == null || !level.areEntitiesActuallyLoadedAndTicking(new ChunkPos(((int) Math.floor(pet.x)) >> 4, ((int) Math.floor(pet.z)) >> 4))) continue;
                restoring.add(id);
                Mob candidate = null;
                try {
                    Mob mob = restore(level, owner, pet);
                    candidate = mob;
                    mob.setPos(pet.x, pet.y, pet.z);
                    AiController.attach(mob);
                    active.put(id, mob);
                    if (!level.addFreshEntity(mob)) {
                        active.remove(id); restoreAfter.put(id, now + 100);
                    }
                } catch (Exception ex) {
                    if (candidate != null && level.getEntity(id) != candidate && !pendingLoads.contains(candidate)) {
                        active.remove(id, candidate);
                        candidate.discard();
                    }
                    restoreAfter.put(id, now + 200);
                    MercyMod.LOGGER.error("Unable to restore deployed companion {} at its saved position", id, ex);
                } finally { restoring.remove(id); }
            }
        }
    }

    private static double supportHeight(ServerLevel level, BlockPos pos, Mob mob) {
        var shape = level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.of(mob));
        AABB footprint = mob.getBoundingBox();
        double top = Double.NEGATIVE_INFINITY;
        for (AABB part : shape.toAabbs()) {
            AABB surface = part.move(pos);
            if (footprint.maxX > surface.minX && footprint.minX < surface.maxX
                    && footprint.maxZ > surface.minZ && footprint.minZ < surface.maxZ) {
                top = Math.max(top, surface.maxY);
            }
        }
        return top;
    }

    private static boolean positionForSummon(ServerLevel level, BlockPos support, Mob mob) {
        if (!level.hasChunkAt(support) || !level.getWorldBorder().isWithinBounds(support)) return false;
        double x = support.getX() + .5, z = support.getZ() + .5;
        mob.resetFallDistance();
        mob.setDeltaMovement(Vec3.ZERO);
        mob.setPos(x, support.getY() + 1, z);
        double feet = supportHeight(level, support, mob);
        if (!Double.isFinite(feet) && level.getBlockState(support).is(Blocks.SNOW)) {
            // A one-layer snow cover can be clicked, but mobs stand on the block underneath.
            feet = supportHeight(level, support.below(), mob);
        }
        if (!Double.isFinite(feet)) {
            if (!level.getFluidState(support.above()).is(FluidTags.WATER)) return false;
            feet = support.getY() + 1;
        }
        mob.setPos(x, feet, z);
        AABB bounds = mob.getBoundingBox();
        if (!level.getWorldBorder().isWithinBounds(bounds) || !level.noCollision(mob)) return false;
        for (BlockPos pos : BlockPos.betweenClosed(bounds)) {
            var fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.LAVA) && bounds.intersects(fluid.getAABB(level, pos))) return false;
        }
        return true;
    }

    public static boolean summonAt(ServerPlayer player, BlockPos support) {
        if (!healthy || !player.isAlive() || player.isSpectator() || !holdsBag(player)) return false;
        if (player.distanceToSqr(Vec3.atCenterOf(support)) > 64) return false;
        int slot = nextSlot(player);
        if (slot < 0) { tell(player, "袋中没有可以出战的伙伴。悬停袋子，用滚轮选中已出战伙伴后右键袋子，或手持袋子右键伙伴，即可主动收回。"); return false; }
        Roster roster = roster(player.getUUID());
        Pet pet = roster.pets.get(slot);
        long now = server.overworld().getGameTime();
        if (now < pet.nextSummonAt) { tell(player, "伙伴召唤冷却中，请稍候。"); return false; }
        UUID id = UUID.fromString(pet.uuid);
        if (active.containsKey(id)) return false;
        Pet before = GSON.fromJson(GSON.toJson(pet), Pet.class);
        List<Integer> previousOrder = new ArrayList<>(roster.stack);
        ServerLevel level = (ServerLevel) player.level();
        Mob mob = null;
        boolean added = false, committed = false;
        try {
            mob = restore(level, player.getUUID(), pet);
            if (!positionForSummon(level, support, mob)) { tell(player, "点击的位置没有足够、安全的召唤空间，请换一处地面。"); return false; }
            AiController.attach(mob);
            pet.deployed = true;
            pet.nextSummonAt = now + 40;
            roster.stack.remove(Integer.valueOf(slot));
            snapshot(mob, pet);
            if (!persist()) return false;
            committed = true;
            active.put(id, mob);
            added = level.addFreshEntity(mob);
            if (!added) { tell(player, "召唤失败，伙伴仍在收纳袋中。"); return false; }
            MercyNetworking.update(player);
            return true;
        } catch (Exception ex) {
            // A listener can throw after the entity was actually accepted. Keep that live entity authoritative.
            added = mob != null && (level.getEntity(id) == mob || pendingLoads.contains(mob));
            MercyMod.LOGGER.error("Unable to complete summon for companion {}", pet.uuid, ex);
            tell(player, "召唤遇到问题，原始记录已保留，请查看日志。");
            return added;
        } finally {
            if (!added) {
                if (mob != null) { active.remove(id, mob); mob.discard(); }
                roster.pets.put(slot, before);
                roster.stack = previousOrder;
                if (committed) persist();
            }
        }
    }

    public static boolean manualRecall(ServerPlayer player, Mob mob) {
        if (!healthy || !player.isAlive() || player.isSpectator() || !holdsBag(player)) return false;
        if (!player.getUUID().equals(owner(mob)) || player.level() != mob.level() || player.distanceToSqr(mob) > 64) return false;
        Pet pet = pet(mob);
        if (pet == null || !pet.deployed || !mob.isAlive()) return false;
        boolean recalled = bagAction(player, slotOf(roster(player.getUUID()), pet), mob.getStringUUID());
        if (recalled) MercyNetworking.update(player);
        return recalled;
    }

    /** Explicit bag action, including residents of unloaded chunks and other dimensions.
     * The roster owns their NBT; no chunk needs loading and no replacement entity is spawned.
     * Slot AND identity are checked so a delayed click can never recall a new occupant of that slot.
     */
    public static boolean bagAction(ServerPlayer player, int slot, String uuid) {
        if (!healthy || closing || !player.isAlive() || player.isSpectator() || !hasBag(player)
                || slot < 0 || slot >= 7) return false;
        // Drain transfer/unload events before reading health or choosing the live instance.
        if (reconcileEntities() && !persist()) return false;
        if (!healthy) return false;
        Roster roster = roster(player.getUUID());
        Pet pet = roster.pets.get(slot);
        if (pet == null || !pet.uuid.equals(uuid)) return false;
        List<Integer> previousOrder = new ArrayList<>(roster.stack);
        if (!pet.deployed) {
            // Locked entries may be ordered, but nextSlot still enforces the sword's capacity.
            if (!roster.stack.isEmpty() && roster.stack.getLast() == slot) return true;
            push(roster, slot);
            if (!persist()) { roster.stack = previousOrder; return false; }
            return true;
        }
        UUID id = UUID.fromString(pet.uuid);
        Mob mob = active.get(id);
        if (mob != null && mob.getHealth() <= 0) { onDeath(mob); return false; }
        if (mob != null && mob.isRemoved()) {
            snapshot(mob, pet);
            active.remove(id, mob);
            mob = null;
        }
        if (mob == null) {
            for (ServerLevel level : server.getAllLevels()) {
                Entity existing = level.getEntity(id);
                if (existing == null || existing.isRemoved()) continue;
                if (!(existing instanceof Mob candidate) || !player.getUUID().equals(owner(candidate))) return false;
                mob = candidate;
                break;
            }
        }
        if (mob != null && (!mob.isAlive() || mob.getHealth() <= 0)) { onDeath(mob); return false; }
        long now = server.overworld().getGameTime();
        long elapsed = now - pet.lastCombat;
        if (elapsed < 200) { tell(player, "战斗结束 " + ((200 - elapsed + 19) / 20) + " 秒后才能收回。"); return false; }
        if (mob != null) {
            mob.unRide(); mob.ejectPassengers();
            snapshot(mob, pet);
        }
        if (!healthy || !(pet.health > 0)) return false;
        Pet before = GSON.fromJson(GSON.toJson(pet), Pet.class);
        pet.deployed = false;
        pet.nextSummonAt = now + 40;
        push(roster, slot);
        if (!persist()) { roster.pets.put(slot, before); roster.stack = previousOrder; return false; }
        // Commit first: an unload callback cannot recreate a resident now marked stored.
        active.remove(id);
        restoreAfter.remove(id);
        if (mob != null) mob.discard();
        return true;
    }

    public static void select(ServerPlayer player, int slot, String uuid) {
        if (!healthy || !player.isAlive() || player.isSpectator() || !hasBag(player)) return;
        Roster roster = roster(player.getUUID());
        Pet pet = roster.pets.get(slot);
        if (pet == null || pet.deployed || slot < 0 || slot >= capacity(player) || !pet.uuid.equals(uuid)) return;
        if (nextSlot(player) == slot) return;
        List<Integer> previousOrder = new ArrayList<>(roster.stack);
        push(roster, slot);
        if (!persist()) roster.stack = previousOrder;
    }

    public static String rosterJson(ServerPlayer player) {
        JsonObject root = new JsonObject();
        root.addProperty("capacity", capacity(player));
        root.addProperty("healthy", healthy);
        root.addProperty("selectedSlot", nextSlot(player));
        Roster roster = roster(player.getUUID());
        List<Integer> order = new ArrayList<>(roster.stack);
        Collections.reverse(order);
        for (var entry : roster.pets.entrySet()) if (entry.getValue().deployed) order.add(entry.getKey());
        JsonArray slots = new JsonArray();
        for (int index : order) {
            Pet pet = roster.pets.get(index);
            if (pet == null) continue;
            Mob mob = active.get(UUID.fromString(pet.uuid));
            JsonObject item = new JsonObject();
            item.addProperty("slot", index); item.addProperty("uuid", pet.uuid); item.addProperty("name", pet.name);
            item.addProperty("type", pet.type);
            item.addProperty("health", mob != null ? mob.getHealth() : pet.health);
            item.addProperty("maxHealth", mob != null ? mob.getMaxHealth() : pet.maxHealth);
            item.addProperty("mode", "follow"); item.addProperty("deployed", pet.deployed);
            item.addProperty("loaded", mob != null && !mob.isRemoved());
            item.addProperty("dimension", pet.dimension);
            item.addProperty("x", mob != null ? mob.getX() : pet.x);
            item.addProperty("y", mob != null ? mob.getY() : pet.y);
            item.addProperty("z", mob != null ? mob.getZ() : pet.z);
            item.addProperty("status", pet.deployed ? "active" : index >= capacity(player) ? "locked" : "stored");
            slots.add(item);
        }
        root.add("slots", slots);
        return GSON.toJson(root);
    }
    private static void tell(ServerPlayer player, String message) { player.sendSystemMessage(Component.literal(message)); }
    private CompanionService() {}
}
