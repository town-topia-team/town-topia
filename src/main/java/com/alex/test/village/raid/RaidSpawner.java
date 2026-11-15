package com.alex.test.village.raid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.EventHooks;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Turns {@link RaiderKind}s into vanilla raiders at a camp and tags them with
 * the raid they belong to, so a raider loaded back from its chunk can be
 * matched to its raid (or sent away when the raid is over).
 */
public final class RaidSpawner {

    /** The tag on a raider's persistent data. */
    public static final String TAG = "dynamicvillages_raid";
    private static final String TAG_VILLAGE = "village";
    private static final String TAG_RAID = "raid";
    private static final String TAG_GENERATION = "generation";
    private static final String TAG_KIND = "kind";
    private static final String TAG_LOOTER = "looter";
    /** Set by {@link #create} only: one of our own raids', not a Bad Omen raider tagged on the way in. */
    private static final String TAG_OWN = "own";
    /** Spots tried around one place for the Warlord, whose body needs more room than one block. */
    private static final int BOSS_SPOT_TRIES = 12;

    private RaidSpawner() {}

    public static EntityType<? extends PathfinderMob> typeOf(RaiderKind kind) {
        return switch (kind) {
            case PILLAGER -> EntityType.PILLAGER;
            case VINDICATOR -> EntityType.VINDICATOR;
            case WITCH -> EntityType.WITCH;
            case EVOKER -> EntityType.EVOKER;
            case RAVAGER -> EntityType.RAVAGER;
            case ZOMBIE, BABY_ZOMBIE, BRUTE -> EntityType.ZOMBIE;
            case HUSK -> EntityType.HUSK;
            case DROWNED -> EntityType.DROWNED;
            case ZOMBIE_VILLAGER -> EntityType.ZOMBIE_VILLAGER;
            case SKELETON_RIDER, SKELETON -> EntityType.SKELETON;
            case STRAY_RIDER -> EntityType.STRAY;
            case BOGGED_RIDER -> EntityType.BOGGED;
            case WARLORD -> com.alex.test.DynamicVillages.RAID_WARLORD.get();
        };
    }

    /** Marks a skeleton horse a raider rode in on, so it leaves with its rider. */
    private static final String HORSE_TAG = "dynamicvillages_raid_horse";

    /**
     * Puts a created raider into the world, with the horse it rides if it
     * rides one. False when the world refused it.
     */
    public static boolean addToWorld(ServerLevel level, Mob raider) {
        return level.tryAddFreshEntityWithPassengers(raider.getRootVehicle());
    }

    /**
     * Sends a raider away (it got home, or the raid is over), with the horse it
     * rode in on. A rider killed in the fight leaves its horse behind instead:
     * a skeleton horse the player can take.
     */
    /** A horse the riders brought: not one of the village's animals. */
    public static boolean isRaidHorse(Entity entity) {
        return entity.getPersistentData().getBoolean(HORSE_TAG);
    }

    public static void discardWithMount(Entity raider) {
        Entity vehicle = raider.getVehicle();
        raider.discard();
        if (vehicle != null && vehicle.getPersistentData().getBoolean(HORSE_TAG)) vehicle.discard();
    }

    /** A skeleton horse for a rider: tame (no owner), kept, and marked as a raid horse. */
    @Nullable
    private static net.minecraft.world.entity.animal.horse.SkeletonHorse horseFor(ServerLevel level, BlockPos at) {
        net.minecraft.world.entity.animal.horse.SkeletonHorse horse = EntityType.SKELETON_HORSE.create(level);
        if (horse == null) return null;
        horse.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
        EventHooks.finalizeMobSpawn(horse, level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        horse.setTamed(true);
        horse.setAge(0);
        horse.setPersistenceRequired();
        horse.getPersistentData().putBoolean(HORSE_TAG, true);
        return horse;
    }

    /** Extra health for a brute, on top of a zombie's 20. */
    private static final double BRUTE_EXTRA_HEALTH = 20.0;
    // Saved on the raider: keeps the old prefix (LegacyModId.SAVE_PREFIX) so old brutes keep one bonus.
    private static final net.minecraft.resources.ResourceLocation BRUTE_HEALTH = net.minecraft.resources.ResourceLocation
            .fromNamespaceAndPath(com.alex.test.LegacyModId.SAVE_PREFIX, "raid_brute_health");

    /**
     * A raider of {@code kind} placed near {@code around}, tagged, equipped
     * and kept from despawning, but not yet in the world: the raid notes it
     * first, so it expects it when the join event arrives, then adds it.
     * Null when there was nowhere to put it.
     */
    @Nullable
    public static PathfinderMob create(ServerLevel level, RaiderKind kind, BlockPos around, UUID villageId,
                                       UUID raidId, int generation, boolean leader, boolean looter) {
        PathfinderMob raider = typeOf(kind).create(level);
        if (raider == null) return null;
        // The Warlord is big: a spot only counts where its whole body fits.
        BlockPos at = null;
        for (int attempt = 0; attempt < (kind.boss() ? BOSS_SPOT_TRIES : 1) && at == null; attempt++) {
            BlockPos spot = standingSpot(level, around);
            if (spot == null) continue;
            raider.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
            if (!kind.boss() || level.noCollision(raider)) at = spot;
        }
        if (at == null) return null;
        EventHooks.finalizeMobSpawn(raider, level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        // Picked-up drops (spoils, villagers' things) would vanish with it at sunrise.
        raider.setCanPickUpLoot(false);
        if (leader && raider instanceof Raider illager) {
            illager.setPatrolLeader(true);
            illager.setItemSlot(EquipmentSlot.HEAD,
                    Raid.getLeaderBannerInstance(level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)));
            illager.setDropChance(EquipmentSlot.HEAD, 2.0F);
        }
        if (raider instanceof net.minecraft.world.entity.monster.Zombie zombie) {
            // finalizeSpawn may have made a baby or a chicken jockey: the plan decides instead.
            zombie.setBaby(kind == RaiderKind.BABY_ZOMBIE);
            if (zombie.getVehicle() != null) zombie.stopRiding();
            // No door bashing: a broken door takes its building off the village's books at once.
            zombie.setCanBreakDoors(false);
            if (kind == RaiderKind.BRUTE) makeBrute(zombie);
        }
        if (raider instanceof net.minecraft.world.entity.monster.AbstractSkeleton) {
            // A helmet, so the sun does not burn it: riders strike at dawn.
            if (raider.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                raider.setItemSlot(EquipmentSlot.HEAD, new ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
                raider.setDropChance(EquipmentSlot.HEAD, 0.0F);
            }
            if (kind.mounted()) {
                var horse = horseFor(level, at);
                if (horse == null) return null;
                raider.startRiding(horse, true);
            }
        }
        raider.setPersistenceRequired();
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_VILLAGE, villageId);
        tag.putUUID(TAG_RAID, raidId);
        tag.putInt(TAG_GENERATION, generation);
        tag.putString(TAG_KIND, kind.name());
        tag.putBoolean(TAG_LOOTER, looter);
        tag.putBoolean(TAG_OWN, true);
        raider.getPersistentData().put(TAG, tag);
        return raider;
    }

    /**
     * The "Elite gear" twist: the raider's weapon is enchanted (as at an
     * enchanting table, level 20) and it wears an iron chestplate it never
     * drops. A ravager or witch has no weapon: only the armour.
     */
    public static void makeElite(ServerLevel level, PathfinderMob raider) {
        ItemStack weapon = raider.getMainHandItem();
        if (!weapon.isEmpty() && !weapon.isEnchanted()) {
            raider.setItemSlot(EquipmentSlot.MAINHAND, net.minecraft.world.item.enchantment.EnchantmentHelper.enchantItem(
                    level.random, weapon.copy(), 20, level.registryAccess(), java.util.Optional.empty()));
        }
        if (raider.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) {
            raider.setItemSlot(EquipmentSlot.CHEST, new ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
            raider.setDropChance(EquipmentSlot.CHEST, 0.0F);
        }
    }

    /** Iron armour (kept, never dropped) and a second health bar's worth of health. */
    private static void makeBrute(net.minecraft.world.entity.monster.Zombie zombie) {
        zombie.setItemSlot(EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
        zombie.setItemSlot(EquipmentSlot.CHEST, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
        zombie.setDropChance(EquipmentSlot.HEAD, 0.0F);
        zombie.setDropChance(EquipmentSlot.CHEST, 0.0F);
        var health = zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (health != null && !health.hasModifier(BRUTE_HEALTH)) {
            health.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(BRUTE_HEALTH,
                    BRUTE_EXTRA_HEALTH, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
            zombie.setHealth(zombie.getMaxHealth());
        }
        zombie.setCustomName(net.minecraft.network.chat.Component.literal("Brute"));
    }

    /** The raid a mob was spawned for, or null for any other mob. */
    @Nullable
    public static Tagged tagOf(Entity entity) {
        if (!(entity instanceof Mob mob)) return null;
        CompoundTag data = mob.getPersistentData();
        if (!data.contains(TAG, net.minecraft.nbt.Tag.TAG_COMPOUND)) return null;
        CompoundTag tag = data.getCompound(TAG);
        if (!tag.hasUUID(TAG_VILLAGE) || !tag.hasUUID(TAG_RAID)) return null;
        return new Tagged(tag.getUUID(TAG_VILLAGE), tag.getUUID(TAG_RAID), tag.getInt(TAG_GENERATION),
                RaiderKind.byName(tag.getString(TAG_KIND)), tag.getBoolean(TAG_LOOTER));
    }

    /**
     * Tags a raider that came some other way (Minecraft's own Bad Omen raid)
     * so the raid's bookkeeping and the guards treat it like one of ours.
     */
    public static void tag(Mob mob, UUID villageId, UUID raidId, int generation, RaiderKind kind, boolean looter) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_VILLAGE, villageId);
        tag.putUUID(TAG_RAID, raidId);
        tag.putInt(TAG_GENERATION, generation);
        tag.putString(TAG_KIND, kind.name());
        tag.putBoolean(TAG_LOOTER, looter);
        mob.getPersistentData().put(TAG, tag);
    }

    /** The raider kind closest to a vanilla raider's type (an illusioner counts as a vindicator). */
    public static RaiderKind kindOf(Entity entity) {
        EntityType<?> type = entity.getType();
        if (type == EntityType.PILLAGER) return RaiderKind.PILLAGER;
        if (type == EntityType.WITCH) return RaiderKind.WITCH;
        if (type == EntityType.EVOKER) return RaiderKind.EVOKER;
        if (type == EntityType.RAVAGER) return RaiderKind.RAVAGER;
        return RaiderKind.VINDICATOR;
    }

    /** Spawned by one of our own raids ({@link #create}), not a Bad Omen raider tagged later. */
    public static boolean isOwnRaider(Entity entity) {
        if (!(entity instanceof Mob mob)) return false;
        CompoundTag data = mob.getPersistentData();
        return data.contains(TAG, net.minecraft.nbt.Tag.TAG_COMPOUND) && data.getCompound(TAG).getBoolean(TAG_OWN);
    }

    /** A raider sent to loot the village's storage. */
    public static boolean isLooter(Entity entity) {
        Tagged tag = tagOf(entity);
        return tag != null && tag.looter();
    }

    /** A looter with nothing left to take becomes an ordinary raider. */
    public static void stopLooting(Mob mob) {
        CompoundTag data = mob.getPersistentData();
        if (data.contains(TAG, net.minecraft.nbt.Tag.TAG_COMPOUND)) data.getCompound(TAG).putBoolean(TAG_LOOTER, false);
    }

    public record Tagged(UUID villageId, UUID raidId, int generation, @Nullable RaiderKind kind, boolean looter) {}

    /**
     * A free spot on the surface within a few blocks of {@code around}: room
     * for feet and head, something solid underfoot, no water. Tries a ring of
     * spots, then the centre itself.
     */
    @Nullable
    public static BlockPos standingSpot(ServerLevel level, BlockPos around) {
        for (int attempt = 0; attempt < 12; attempt++) {
            int dx = level.random.nextInt(9) - 4;
            int dz = level.random.nextInt(9) - 4;
            BlockPos spot = surface(level, around.getX() + dx, around.getZ() + dz);
            // Not on a tent roof or up a tree: about the camp's own height.
            if (spot != null && Math.abs(dx) + Math.abs(dz) >= 2 && Math.abs(spot.getY() - around.getY()) <= 2) {
                return spot;
            }
        }
        return surface(level, around.getX(), around.getZ());
    }

    @Nullable
    private static BlockPos surface(ServerLevel level, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return null;
        BlockPos feet = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()) return null;
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) return null;
        if (!level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) return null;
        if (!level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)) return null;
        return feet;
    }
}
