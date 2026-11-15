package com.alex.test.village.villagers.guard.settings;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Persistent Guard settings backed by the villager's existing BrainData. */
public final class GuardSettings {

    public enum SwordTier {
        WOODEN("Wooden Sword", Items.WOODEN_SWORD),
        STONE("Stone Sword", Items.STONE_SWORD),
        IRON("Iron Sword", Items.IRON_SWORD),
        GOLDEN("Golden Sword", Items.GOLDEN_SWORD),
        DIAMOND("Diamond Sword", Items.DIAMOND_SWORD),
        NETHERITE("Netherite Sword", Items.NETHERITE_SWORD);

        public final String displayName;
        public final Item swordItem;
        public final ResourceLocation settingId;

        SwordTier(String displayName, Item swordItem) {
            this.displayName = displayName;
            this.swordItem = swordItem;
            this.settingId = ResourceLocation.fromNamespaceAndPath(
                    DynamicVillages.MODID, "sword_" + name().toLowerCase(Locale.ROOT));
        }

        public static SwordTier bySettingId(ResourceLocation id) {
            for (SwordTier tier : values()) if (tier.settingId.equals(id)) return tier;
            return null;
        }

        public static SwordTier byItem(Item item) {
            for (SwordTier tier : values()) if (tier.swordItem == item) return tier;
            return null;
        }
    }

    private static final String SETTINGS = "GuardSettings";
    private static final String SWORD_TIER_PREFIX = "AllowSwordTier_";
    private static final String MOB_PREFIX = "AllowMob_";
    private static final String PROTECT_PLAYER = "ProtectPlayer";
    private static final String FOLLOW_PLAYER = "FollowPlayer";
    private static final String ATTACK_HOSTILE_MOBS = "AttackHostileMobs";
    private static final String PATROL_AT_NIGHT_ONLY = "PatrolAtNightOnly";
    private static final String USE_GUARD_POSTS = "UseGuardPosts";
    private static final String GUARD_POST_STAY_SECONDS = "GuardPostStaySeconds";
    private static final String NEXT_SWORD_ATTEMPT_AT = "NextSwordAttemptAt";
    private static final String LEGACY_ORDER = "Order";
    private static final String LEGACY_ORDER_TARGET_PLAYER = "OrderTargetPlayer";
    private static final String LEGACY_ORDER_HOLD_POS = "OrderHoldPos";
    private static final int DEFAULT_GUARD_POST_STAY_SECONDS = 60;
    private static final int MIN_GUARD_POST_STAY_SECONDS = 5;
    private static final int MAX_GUARD_POST_STAY_SECONDS = 300;

    private final NpcEntity villager;

    private GuardSettings(NpcEntity villager) {
        this.villager = villager;
    }

    public static GuardSettings of(NpcEntity villager) {
        return new GuardSettings(villager);
    }

    /** Whether the guard may use swords of the given tier. Default: true. */
    public boolean isSwordTierAllowed(SwordTier tier) {
        return isSwordTierAllowed(readTag(), tier);
    }

    private static boolean isSwordTierAllowed(CompoundTag tag, SwordTier tier) {
        String key = SWORD_TIER_KEYS[tier.ordinal()];
        return !tag.contains(key) || tag.getBoolean(key);
    }

    private static final String[] SWORD_TIER_KEYS = new String[SwordTier.values().length];
    static {
        for (SwordTier tier : SwordTier.values()) SWORD_TIER_KEYS[tier.ordinal()] = SWORD_TIER_PREFIX + tier.name();
    }

    /**
     * The allowed-sword set for each mix of ticked tiers, made once: a guard asks
     * every tick, and each ask built six keys and a new set.
     */
    @SuppressWarnings("unchecked")
    private static final Set<Item>[] SWORD_SETS = new Set[1 << SwordTier.values().length];

    public void setSwordTierAllowed(SwordTier tier, boolean allowed) {
        writeTag().putBoolean(SWORD_TIER_PREFIX + tier.name(), allowed);
    }

    /** Returns all sword items this guard may equip or take from storage. */
    public Set<Item> getAllowedSwords() {
        CompoundTag tag = readTag();
        int mask = 0;
        for (SwordTier tier : SwordTier.values()) {
            if (isSwordTierAllowed(tag, tier)) mask |= 1 << tier.ordinal();
        }
        Set<Item> allowed = SWORD_SETS[mask];
        if (allowed == null) {
            Set<Item> made = new LinkedHashSet<>();
            for (SwordTier tier : SwordTier.values()) {
                if ((mask & (1 << tier.ordinal())) != 0) made.add(tier.swordItem);
            }
            allowed = java.util.Collections.unmodifiableSet(made);
            SWORD_SETS[mask] = allowed;
        }
        return allowed;
    }

    public boolean isSwordAllowed(Item item) {
        SwordTier tier = SwordTier.byItem(item);
        return tier != null && isSwordTierAllowed(tier);
    }

    /** Whether this guard may attack the given hostile mob type. Defaults come from {@link GuardMobPolicy}. */
    public boolean isMobAllowed(ResourceLocation mobId) {
        if (!GuardMobPolicy.isKnownConfigurableMonster(mobId)) return false;
        CompoundTag tag = readTag();
        String key = MOB_PREFIX + mobId;
        return tag.contains(key) ? tag.getBoolean(key) : GuardMobPolicy.defaultAllowed(mobId);
    }

    /** Settings key per mob type, made once (asked for every mob in every danger check). */
    private static final java.util.Map<net.minecraft.world.entity.EntityType<?>, String> MOB_KEYS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** {@link #isMobAllowed(ResourceLocation)} for a mob type, without building its key each time. */
    public boolean isMobAllowed(net.minecraft.world.entity.EntityType<?> type) {
        if (!GuardMobPolicy.isKnownConfigurableMonster(type)) return false;
        String key = MOB_KEYS.get(type);
        if (key == null) {
            key = MOB_PREFIX + net.minecraft.world.entity.EntityType.getKey(type);
            MOB_KEYS.put(type, key);
        }
        CompoundTag tag = readTag();
        return tag.contains(key) ? tag.getBoolean(key) : GuardMobPolicy.defaultAllowed(type);
    }

    public void setMobAllowed(ResourceLocation mobId, boolean allowed) {
        if (!GuardMobPolicy.isKnownConfigurableMonster(mobId)) return;
        writeTag().putBoolean(MOB_PREFIX + mobId, allowed);
    }

    public boolean protectPlayer() {
        return readBoolean(PROTECT_PLAYER, false);
    }

    public void setProtectPlayer(boolean enabled) {
        CompoundTag tag = writeTag();
        tag.putBoolean(PROTECT_PLAYER, enabled);
        if (!enabled) tag.putBoolean(FOLLOW_PLAYER, false);
    }

    public boolean followPlayer() {
        return protectPlayer() && readBoolean(FOLLOW_PLAYER, false);
    }

    public void setFollowPlayer(boolean enabled) {
        CompoundTag tag = writeTag();
        tag.putBoolean(FOLLOW_PLAYER, enabled && protectPlayer());
    }

    public boolean attackHostileMobs() {
        return readBoolean(ATTACK_HOSTILE_MOBS, true);
    }

    public void setAttackHostileMobs(boolean enabled) {
        writeTag().putBoolean(ATTACK_HOSTILE_MOBS, enabled);
    }

    public boolean patrolAtNightOnly() {
        return readBoolean(PATROL_AT_NIGHT_ONLY, true);
    }

    public void setPatrolAtNightOnly(boolean enabled) {
        writeTag().putBoolean(PATROL_AT_NIGHT_ONLY, enabled);
    }

    public boolean useGuardPosts() {
        return readBoolean(USE_GUARD_POSTS, true);
    }

    public void setUseGuardPosts(boolean enabled) {
        writeTag().putBoolean(USE_GUARD_POSTS, enabled);
    }

    public int guardPostStaySeconds() {
        CompoundTag tag = readTag();
        int seconds = tag.contains(GUARD_POST_STAY_SECONDS)
                ? tag.getInt(GUARD_POST_STAY_SECONDS)
                : DEFAULT_GUARD_POST_STAY_SECONDS;
        return clampGuardPostStaySeconds(seconds);
    }

    public int guardPostStayTicks() {
        return guardPostStaySeconds() * 20;
    }

    public void setGuardPostStaySeconds(int seconds) {
        writeTag().putInt(GUARD_POST_STAY_SECONDS, clampGuardPostStaySeconds(seconds));
    }

    public static int clampGuardPostStaySeconds(int seconds) {
        return Math.max(MIN_GUARD_POST_STAY_SECONDS,
                Math.min(MAX_GUARD_POST_STAY_SECONDS, seconds));
    }

    /** Cooldown guard so missing crafting resources do not cause every-tick retries. */
    public boolean canAttemptSwordAcquire(long gameTime) {
        return gameTime >= readTag().getLong(NEXT_SWORD_ATTEMPT_AT);
    }

    public void markSwordAcquireAttempt(long gameTime, int cooldownTicks) {
        writeTag().putLong(NEXT_SWORD_ATTEMPT_AT, gameTime + Math.max(1, cooldownTicks));
    }

    public void clearSwordAcquireCooldown() {
        writeTag().remove(NEXT_SWORD_ATTEMPT_AT);
    }

    /**
     * Drops the keys of the removed standing-order system (Patrol / Follow /
     * Hold / Rally / Stand Down, set by sneak+right-click). A guard or archer
     * left with one of those orders ignored its patrol and guard-post settings;
     * nothing reads the keys any more, and this clears them from old saves.
     */
    public void clearLegacyOrder() {
        CompoundTag tag = readTag();
        if (!tag.contains(LEGACY_ORDER) && !tag.contains(LEGACY_ORDER_TARGET_PLAYER)
                && !tag.contains(LEGACY_ORDER_HOLD_POS)) return;
        CompoundTag write = writeTag();
        write.remove(LEGACY_ORDER);
        write.remove(LEGACY_ORDER_TARGET_PLAYER);
        write.remove(LEGACY_ORDER_HOLD_POS);
    }

    private CompoundTag readTag() {
        return villager.getBrainData().getCompound(SETTINGS);
    }

    private boolean readBoolean(String key, boolean defaultValue) {
        CompoundTag tag = readTag();
        return tag.contains(key) ? tag.getBoolean(key) : defaultValue;
    }

    private CompoundTag writeTag() {
        CompoundTag brainData = villager.getBrainData();
        if (!brainData.contains(SETTINGS, Tag.TAG_COMPOUND)) {
            brainData.put(SETTINGS, new CompoundTag());
        }
        return brainData.getCompound(SETTINGS);
    }
}
