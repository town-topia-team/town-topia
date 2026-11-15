package com.alex.test.village.chat;

import com.alex.test.Tr;
import com.alex.test.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Map.entry;

/**
 * What hurt a villager, in words. Every hit carries a damage type ("cactus",
 * "lava", "arrow"), so the cause is always known; the alert used to read only
 * starving and falling, and called everything else either "under attack" (a
 * creature or object was behind it) or "an unknown source" (none was).
 *
 * <p>Each cause is one whole translatable sentence that takes the villager's
 * name ("%s walked into a cactus"), so a translation can put the name wherever
 * its language wants it.
 *
 * @param key            the damage type's id, e.g. {@code minecraft:cactus}
 * @param sentence       the whole sentence, given the villager's name: "Bram walked into a cactus"
 * @param attack         a creature, player, projectile or explosion did it (red alert)
 * @param hazard         the block that hurt them, when one could be found; else null
 * @param walkingMistake a route should never have put the villager here
 * @param player         the player behind the hit, if one was
 */
public record DamageCause(String key, Sentence sentence, boolean attack,
                          @Nullable BlockPos hazard, boolean walkingMistake, @Nullable UUID player) {

    /** A whole sentence about the villager it happened to. */
    @FunctionalInterface
    public interface Sentence {
        MutableComponent about(Component victim);
    }

    /** A whole sentence naming the villager and someone (or something) else. */
    @FunctionalInterface
    private interface WithOther {
        MutableComponent about(Component victim, Component other);
    }

    /** How long after a hit the hitter still gets named on burning, poison and falls. */
    private static final int RECENT_ATTACKER_TICKS = 100;

    /**
     * Fire, lava and lightning set a villager alight, and the burning that
     * follows hurts as "on fire": one incident, which used to be two messages.
     */
    private static final Set<String> BURNING = Stream.of(DamageTypes.IN_FIRE, DamageTypes.CAMPFIRE,
                    DamageTypes.LAVA, DamageTypes.LIGHTNING_BOLT, DamageTypes.ON_FIRE)
            .map(type -> type.location().toString())
            .collect(Collectors.toUnmodifiableSet());

    /** Hits that share this key share one wait between chat messages. */
    public String alertGroup() {
        return BURNING.contains(key) ? "burning" : key;
    }

    /**
     * A damage type the world deals on its own.
     *
     * @param block          the block to point at, or null when there is none
     * @param walkingMistake a route led the villager into it
     * @param afterHit       the sentence when someone who hit them just before
     *                       may be the real cause; null where that never applies
     */
    private record Hazard(Sentence sentence, @Nullable Predicate<BlockState> block,
                          boolean walkingMistake, @Nullable WithOther afterHit) {}

    private static Hazard hazard(Sentence sentence) {
        return new Hazard(sentence, null, false, null);
    }

    private static final Map<ResourceKey<DamageType>, Hazard> HAZARDS = Map.ofEntries(
            entry(DamageTypes.CACTUS, new Hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.cactus", "%s walked into a cactus", v),
                    s -> s.is(Blocks.CACTUS), true, null)),
            entry(DamageTypes.IN_FIRE, new Hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.in_fire", "%s is standing in fire", v),
                    s -> s.is(BlockTags.FIRE), true, null)),
            entry(DamageTypes.CAMPFIRE, new Hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.campfire", "%s walked into a campfire", v),
                    s -> s.is(BlockTags.CAMPFIRES), true, null)),
            entry(DamageTypes.LAVA, new Hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.lava", "%s is in lava", v),
                    s -> s.getFluidState().is(FluidTags.LAVA), true, null)),
            entry(DamageTypes.HOT_FLOOR, new Hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.hot_floor", "%s is standing on a magma block", v),
                    s -> s.is(Blocks.MAGMA_BLOCK), true, null)),
            entry(DamageTypes.FREEZE, new Hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.freeze", "%s is freezing in powder snow", v),
                    s -> s.is(Blocks.POWDER_SNOW), true, null)),
            entry(DamageTypes.DROWN, new Hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.drown", "%s is drowning", v),
                    null, true, null)),
            entry(DamageTypes.CRAMMING, new Hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.cramming", "%s is being crushed in a crowd", v),
                    null, true, null)),
            entry(DamageTypes.SWEET_BERRY_BUSH, new Hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.sweet_berry_bush", "%s walked into a berry bush", v),
                    s -> s.is(Blocks.SWEET_BERRY_BUSH), false, null)),
            entry(DamageTypes.STALAGMITE, new Hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.stalagmite", "%s fell onto pointed dripstone", v),
                    s -> s.is(Blocks.POINTED_DRIPSTONE), false, null)),
            entry(DamageTypes.WITHER, new Hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.wither", "%s is withering", v),
                    s -> s.is(Blocks.WITHER_ROSE), false,
                    (v, h) -> Tr.c("chat.dynamicvillages.damage.wither_after_hit",
                            "%1$s is withering after being hit by %2$s", v, h))),
            entry(DamageTypes.ON_FIRE, new Hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.on_fire", "%s is on fire", v),
                    null, false,
                    (v, h) -> Tr.c("chat.dynamicvillages.damage.on_fire_after_hit",
                            "%1$s is on fire after being hit by %2$s", v, h))),
            entry(DamageTypes.MAGIC, new Hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.magic", "%s is poisoned", v),
                    null, false,
                    (v, h) -> Tr.c("chat.dynamicvillages.damage.magic_after_hit",
                            "%1$s is poisoned after being hit by %2$s", v, h))),
            entry(DamageTypes.FALL, new Hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.fall", "%s took fall damage", v),
                    null, false,
                    (v, h) -> Tr.c("chat.dynamicvillages.damage.fall_after_hit",
                            "%1$s took fall damage after being hit by %2$s", v, h))),
            entry(DamageTypes.STARVE, hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.starve", "%s is starving", v))),
            entry(DamageTypes.LIGHTNING_BOLT, hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.lightning_bolt", "%s was struck by lightning", v))),
            entry(DamageTypes.DRAGON_BREATH, hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.dragon_breath", "%s was hurt by dragon's breath", v))),
            entry(DamageTypes.BAD_RESPAWN_POINT, hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.bad_respawn_point",
                            "%s was caught in an exploding bed or respawn anchor", v))),
            entry(DamageTypes.FLY_INTO_WALL, hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.fly_into_wall", "%s flew into a wall", v))),
            entry(DamageTypes.FELL_OUT_OF_WORLD, hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.fell_out_of_world", "%s fell out of the world", v))),
            entry(DamageTypes.OUTSIDE_BORDER, hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.outside_border", "%s is outside the world border", v))),
            entry(DamageTypes.DRY_OUT, hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.dry_out", "%s dried out", v))),
            entry(DamageTypes.GENERIC, hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.generic", "%s took damage", v))),
            entry(DamageTypes.GENERIC_KILL, hazard(
                    v -> Tr.c("chat.dynamicvillages.damage.generic_kill", "%s was killed by a command", v))));

    public static DamageCause of(NpcEntity npc, ServerLevel level, DamageSource source) {
        String key = source.typeHolder().unwrapKey()
                .map(k -> k.location().toString())
                .orElse(source.type().msgId());
        Entity who = source.getEntity();
        Entity what = source.getDirectEntity();
        boolean explosion = source.is(DamageTypeTags.IS_EXPLOSION);
        boolean attack = who instanceof LivingEntity || what instanceof Projectile || explosion;

        Hazard hazard = source.typeHolder().unwrapKey().map(HAZARDS::get).orElse(null);
        if (hazard != null) {
            LivingEntity hitter = hazard.afterHit() != null ? recentAttacker(npc) : null;
            Sentence sentence = hitter == null ? hazard.sentence() : with(hazard.afterHit(), nameOf(hitter));
            BlockPos at = hazard.block() == null ? null : touching(npc, level, hazard.block());
            return new DamageCause(key, sentence, explosion, at, hazard.walkingMistake(), null);
        }

        if (source.is(DamageTypes.IN_WALL)) {
            BlockPos wall = blockInHead(npc, level);
            Sentence sentence = wall == null
                    ? v -> Tr.c("chat.dynamicvillages.damage.in_wall", "%s is stuck inside a block", v)
                    : with((v, block) -> Tr.c("chat.dynamicvillages.damage.in_wall_named",
                                    "%1$s is stuck inside %2$s", v, block),
                            level.getBlockState(wall).getBlock().getName());
            return new DamageCause(key, sentence, false, wall, true, null);
        }

        if (source.is(DamageTypes.FALLING_BLOCK) || source.is(DamageTypes.FALLING_ANVIL)
                || source.is(DamageTypes.FALLING_STALACTITE)) {
            // The falling block is both "who" and "what" here, never a creature.
            Sentence sentence = what instanceof FallingBlockEntity falling
                    ? with((v, block) -> Tr.c("chat.dynamicvillages.damage.falling_block_named",
                                    "%1$s was hit by falling %2$s", v, block),
                            falling.getBlockState().getBlock().getName())
                    : v -> Tr.c("chat.dynamicvillages.damage.falling_block", "%s was hit by a falling block", v);
            return new DamageCause(key, sentence, false, null, false, null);
        }

        return new DamageCause(key, attackSentence(source, key, who, what, explosion), attack, null, false,
                who instanceof Player player ? player.getUUID() : null);
    }

    private static Sentence attackSentence(DamageSource source, String key,
                                           @Nullable Entity who, @Nullable Entity what,
                                           boolean explosion) {
        if (explosion) {
            return either(who != null ? who : what,
                    v -> Tr.c("chat.dynamicvillages.damage.explosion", "%s was caught in an explosion", v),
                    (v, c) -> Tr.c("chat.dynamicvillages.damage.explosion_by", "%1$s was blown up by %2$s", v, c));
        }
        if (source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
                || source.is(DamageTypes.PLAYER_ATTACK)) {
            // Players and villagers are named, so "hit by Steve" rather than "attacked by Steve".
            WithOther byWho = who != null && isPerson(who)
                    ? (v, c) -> Tr.c("chat.dynamicvillages.damage.hit_by", "%1$s was hit by %2$s", v, c)
                    : (v, c) -> Tr.c("chat.dynamicvillages.damage.attacked_by", "%1$s is being attacked by %2$s", v, c);
            return either(who, v -> Tr.c("chat.dynamicvillages.damage.hit", "%s was hit", v), byWho);
        }
        if (source.is(DamageTypes.STING)) {
            return either(who,
                    v -> Tr.c("chat.dynamicvillages.damage.sting", "%s was stung", v),
                    (v, c) -> Tr.c("chat.dynamicvillages.damage.sting_by", "%1$s was stung by %2$s", v, c));
        }
        // A dispenser's arrow has no shooter.
        if (source.is(DamageTypes.ARROW)) {
            return either(who,
                    v -> Tr.c("chat.dynamicvillages.damage.arrow", "%s was hit by an arrow", v),
                    DamageCause::shotBy);
        }
        if (source.is(DamageTypes.MOB_PROJECTILE)) {
            return either(who,
                    v -> Tr.c("chat.dynamicvillages.damage.projectile", "%s was hit by a projectile", v),
                    DamageCause::shotBy);
        }
        if (source.is(DamageTypes.TRIDENT)) {
            return either(who,
                    v -> Tr.c("chat.dynamicvillages.damage.trident", "%s was hit by a trident", v),
                    (v, c) -> Tr.c("chat.dynamicvillages.damage.trident_by",
                            "%1$s was hit by a trident thrown by %2$s", v, c));
        }
        if (source.is(DamageTypes.SPIT)) {
            return either(who,
                    v -> Tr.c("chat.dynamicvillages.damage.spit", "%s was spat on", v),
                    (v, c) -> Tr.c("chat.dynamicvillages.damage.spit_by", "%1$s was spat on by %2$s", v, c));
        }
        if (source.is(DamageTypes.WIND_CHARGE)) {
            return either(who,
                    v -> Tr.c("chat.dynamicvillages.damage.wind_charge", "%s was hit by a wind charge", v),
                    (v, c) -> Tr.c("chat.dynamicvillages.damage.wind_charge_by",
                            "%1$s was hit by a wind charge from %2$s", v, c));
        }
        // Fireworks count as an explosion, so they are named above.
        if (source.is(DamageTypes.FIREBALL) || source.is(DamageTypes.UNATTRIBUTED_FIREBALL)) {
            return either(who,
                    v -> Tr.c("chat.dynamicvillages.damage.fireball", "%s was hit by a fireball", v),
                    (v, c) -> Tr.c("chat.dynamicvillages.damage.fireball_by",
                            "%1$s was hit by a fireball from %2$s", v, c));
        }
        if (source.is(DamageTypes.WITHER_SKULL)) {
            return either(who,
                    v -> Tr.c("chat.dynamicvillages.damage.wither_skull", "%s was hit by a wither skull", v),
                    (v, c) -> Tr.c("chat.dynamicvillages.damage.wither_skull_by",
                            "%1$s was hit by a wither skull from %2$s", v, c));
        }
        if (source.is(DamageTypes.THROWN)) {
            return either(who,
                    v -> Tr.c("chat.dynamicvillages.damage.thrown", "%s was hit by a thrown item", v),
                    (v, c) -> Tr.c("chat.dynamicvillages.damage.thrown_by",
                            "%1$s was hit by something thrown by %2$s", v, c));
        }
        if (source.is(DamageTypes.INDIRECT_MAGIC)) {
            return either(who,
                    v -> Tr.c("chat.dynamicvillages.damage.indirect_magic", "%s was hurt by magic", v),
                    (v, c) -> Tr.c("chat.dynamicvillages.damage.indirect_magic_by",
                            "%1$s was hurt by magic from %2$s", v, c));
        }
        if (source.is(DamageTypes.THORNS)) {
            return either(who,
                    v -> Tr.c("chat.dynamicvillages.damage.thorns", "%s was hurt by thorns", v),
                    (v, c) -> Tr.c("chat.dynamicvillages.damage.thorns_by",
                            "%1$s was hurt by the thorns on %2$s", v, c));
        }
        if (source.is(DamageTypes.SONIC_BOOM)) {
            return either(who,
                    v -> Tr.c("chat.dynamicvillages.damage.sonic_boom", "%s was hit by a sonic boom", v),
                    (v, c) -> Tr.c("chat.dynamicvillages.damage.sonic_boom_by",
                            "%1$s was hit by a sonic boom from %2$s", v, c));
        }

        // Another mod's damage: no sentence for it, but its name says what it was.
        Entity culprit = who != null ? who : what;
        if (culprit == null) {
            return v -> Tr.c("chat.dynamicvillages.damage.other", "%1$s took damage (%2$s)", v, key);
        }
        Component name = nameOf(culprit);
        return v -> Tr.c("chat.dynamicvillages.damage.other_by", "%1$s was hurt by %2$s (%3$s)", v, name, key);
    }

    private static MutableComponent shotBy(Component victim, Component shooter) {
        return Tr.c("chat.dynamicvillages.damage.shot_by", "%1$s was shot by %2$s", victim, shooter);
    }

    /** The sentence naming who did it, or the one without a name when nobody did. */
    private static Sentence either(@Nullable Entity who, Sentence alone, WithOther byWho) {
        return who == null ? alone : with(byWho, nameOf(who));
    }

    private static Sentence with(WithOther sentence, Component other) {
        return victim -> sentence.about(victim, other);
    }

    private static boolean isPerson(Entity entity) {
        return entity instanceof Player || entity instanceof NpcEntity;
    }

    /**
     * "Steve", "Bram", a name-tagged mob's name, or the mob's own name in the
     * player's language ("Zombie"), the way vanilla's death messages name it.
     */
    static MutableComponent nameOf(Entity entity) {
        if (isPerson(entity) || entity.hasCustomName()) return entity.getDisplayName().copy();
        return entity.getType().getDescription().copy();
    }

    /** Whoever hit the villager in the last few seconds, for damage that does not say who. */
    @Nullable
    private static LivingEntity recentAttacker(NpcEntity npc) {
        LivingEntity attacker = npc.getLastHurtByMob();
        if (attacker == null) return null;
        return npc.tickCount - npc.getLastHurtByMobTimestamp() < RECENT_ATTACKER_TICKS ? attacker : null;
    }

    /**
     * The nearest matching block the villager is touching or standing on.
     * Its box covers at most a few blocks, and this runs only when it is hurt.
     */
    @Nullable
    private static BlockPos touching(NpcEntity npc, ServerLevel level, Predicate<BlockState> block) {
        // A little past the box, so the magma under its feet counts too.
        AABB box = npc.getBoundingBox().inflate(0.05);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
            if (!block.test(level.getBlockState(pos))) continue;
            double distance = pos.getCenter().distanceToSqr(npc.position());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** The solid block at head height, as vanilla's own "stuck in a wall" check sees it. */
    @Nullable
    private static BlockPos blockInHead(NpcEntity npc, ServerLevel level) {
        float width = npc.getBbWidth() * 0.8F;
        AABB eyes = AABB.ofSize(npc.getEyePosition(), width, 1.0E-6, width);
        for (BlockPos pos : BlockPos.betweenClosed(
                Mth.floor(eyes.minX), Mth.floor(eyes.minY), Mth.floor(eyes.minZ),
                Mth.floor(eyes.maxX), Mth.floor(eyes.maxY), Mth.floor(eyes.maxZ))) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && state.isSuffocating(level, pos)) return pos.immutable();
        }
        return null;
    }
}
