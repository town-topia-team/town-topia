package com.alex.test.village.villagers.guard.settings;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Guard mob allow-list metadata built from the entity registry. */
public final class GuardMobPolicy {

    public record MobOption(ResourceLocation id, String displayName, boolean defaultAllowed) {}

    private static final Set<String> VANILLA_OVERWORLD_MONSTERS = Set.of(
            "bogged",
            "breeze",
            "cave_spider",
            "creeper",
            "drowned",
            "elder_guardian",
            "enderman",
            "evoker",
            "guardian",
            "husk",
            "illusioner",
            "phantom",
            "pillager",
            "ravager",
            "silverfish",
            "skeleton",
            "slime",
            "spider",
            "stray",
            "vex",
            "vindicator",
            "warden",
            "witch",
            "zombie",
            "zombie_villager");

    private static final Set<ResourceLocation> DEFAULT_DISABLED = Set.of(
            EntityType.CREEPER.builtInRegistryHolder().key().location(),
            EntityType.ENDERMAN.builtInRegistryHolder().key().location());

    private GuardMobPolicy() {}

    public static List<MobOption> options() {
        List<MobOption> options = new ArrayList<>();
        Set<ResourceLocation> seen = new HashSet<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() != MobCategory.MONSTER) continue;
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (!isConfigurableOverworldMonster(id)) continue;
            if (!seen.add(id)) continue;
            options.add(new MobOption(id, displayName(id), !DEFAULT_DISABLED.contains(id)));
        }
        options.sort(Comparator.comparing(MobOption::displayName, String.CASE_INSENSITIVE_ORDER));
        return options;
    }

    public static boolean isKnownConfigurableMonster(ResourceLocation id) {
        if (!isConfigurableOverworldMonster(id)) return false;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        return type != null && type.getCategory() == MobCategory.MONSTER;
    }

    /** Mobs guards leave alone unless a player ticks them on (creeper, enderman). */
    public static Set<ResourceLocation> defaultDisabled() {
        return DEFAULT_DISABLED;
    }

    public static boolean defaultAllowed(ResourceLocation id) {
        return isKnownConfigurableMonster(id) && !DEFAULT_DISABLED.contains(id);
    }

    private static final int KNOWN = 1, DEFAULT_ON = 2;
    /**
     * Per mob type, the two answers above, worked out once: mob types do not change
     * while the game runs, and the id lookups ran for every mob in every danger check.
     */
    private static final java.util.Map<EntityType<?>, Integer> TYPE_FLAGS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static int flags(EntityType<?> type) {
        Integer known = TYPE_FLAGS.get(type);
        if (known != null) return known;
        ResourceLocation id = EntityType.getKey(type);
        int flags = (isKnownConfigurableMonster(id) ? KNOWN : 0) | (defaultAllowed(id) ? DEFAULT_ON : 0);
        TYPE_FLAGS.put(type, flags);
        return flags;
    }

    /** {@link #isKnownConfigurableMonster(ResourceLocation)} for a mob type, remembered. */
    public static boolean isKnownConfigurableMonster(EntityType<?> type) {
        return (flags(type) & KNOWN) != 0;
    }

    /** {@link #defaultAllowed(ResourceLocation)} for a mob type, remembered. */
    public static boolean defaultAllowed(EntityType<?> type) {
        return (flags(type) & DEFAULT_ON) != 0;
    }

    /**
     * A hostile mob a guard could ever fight: any monster on the settings list,
     * creepers included -- whether it actually does is the guard's allow list.
     *
     * <p>Hostile is vanilla's {@link Enemy}, not just {@link Monster}: slimes and
     * phantoms are listed in the settings but are not {@code Monster}s, so they
     * could be ticked on and the guard would still never fight one. Creepers
     * used to be refused here outright, so enabling them did nothing.
     */
    public static boolean isGuardAttackCandidate(@Nullable Entity entity) {
        return entity instanceof Mob mob
                && mob instanceof Enemy
                && mob.isAlive()
                && !mob.isRemoved()
                && isKnownConfigurableMonster(mob.getType());
    }

    /** Any live hostile mob, on the settings list or not: what a guard defends itself against. */
    public static boolean isHostileCreature(@Nullable Entity entity) {
        return entity instanceof Mob mob && mob instanceof Enemy && mob.isAlive() && !mob.isRemoved();
    }

    /** Creepers (and modded mobs built on them): village-friendly unless a guard's settings turn them on. */
    public static boolean isCreeper(@Nullable Entity entity) {
        return entity instanceof Creeper;
    }

    /** Vanilla-like proactive targeting: aggressive monsters may choose village NPCs; neutral mobs do not. */
    public static boolean canVanillaMonsterProactivelyTargetNpc(Monster monster) {
        if (monster instanceof Creeper || monster instanceof EnderMan) return false;
        ResourceLocation id = EntityType.getKey(monster.getType());
        return defaultAllowed(id);
    }

    private static boolean isConfigurableOverworldMonster(ResourceLocation id) {
        if (!"minecraft".equals(id.getNamespace())) return true;
        return VANILLA_OVERWORLD_MONSTERS.contains(id.getPath());
    }

    private static String displayName(ResourceLocation id) {
        String base = id.getPath().replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String part : base.split(" ")) {
            if (part.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) out.append(part.substring(1));
        }
        if (!"minecraft".equals(id.getNamespace())) {
            out.append(" (").append(id.getNamespace()).append(')');
        }
        return out.toString();
    }
}
