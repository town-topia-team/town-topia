package com.alex.test.village.villagers.mage.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.guard.alert.GuardThreatRules;
import com.alex.test.village.villagers.guard.settings.GuardMobPolicy;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.mage.MageBalance;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the mage's spells do in the world. Every spell is particles and direct
 * damage: no block placed or broken, no fire, no real lightning. A Frost Nova leaves no ice; a Chain Spark is drawn, not struck.
 *
 * <p>No friendly damage, ever: a spell only hurts a hostile mob the mage would
 * fight ({@link #isFoe}). Villagers, players, pets and golems are passed through
 * and never touched -- the archer's arrow rule.
 *
 * <p>The Arcane Bolt is its own entity ({@code ArcaneBoltEntity}), drawn as
 * particles only. Falling stars and ward bubbles live here and are moved on
 * every server tick; they are short-lived and not saved. Hits and kills, the
 * bolt's included, wait here until the mage's own activity collects them for
 * its skill ({@link #takeCombat}), as {@code ArcherEvents} does for arrows.
 */
public final class MageSpells {

    // --- colours ---------------------------------------------------------------

    private static final DustParticleOptions ARCANE = new DustParticleOptions(new Vector3f(0.62f, 0.32f, 1.0f), 1.1f);
    private static final DustParticleOptions ARCANE_PALE = new DustParticleOptions(new Vector3f(0.85f, 0.7f, 1.0f), 0.7f);
    private static final DustParticleOptions FROST = new DustParticleOptions(new Vector3f(0.65f, 0.92f, 1.0f), 1.2f);
    private static final DustParticleOptions SPARK = new DustParticleOptions(new Vector3f(0.75f, 0.88f, 1.0f), 0.8f);
    private static final DustParticleOptions WARD = new DustParticleOptions(new Vector3f(0.45f, 0.7f, 1.0f), 0.9f);
    private static final DustParticleOptions STAR = new DustParticleOptions(new Vector3f(1.0f, 0.88f, 0.45f), 1.6f);

    /** Recent attacker memory for self-defence hits, as the archer's. */
    private static final int SELF_DEFENCE_MEMORY_TICKS = 200;
    /** Particle points per block along a spark's line. */
    private static final double SPARK_STEP = 0.3;
    /** How far a spark's zig-zag strays from the straight line. */
    private static final double SPARK_JITTER = 0.25;
    /** A ward bubble is redrawn this often. */
    private static final int WARD_DRAW_EVERY = 5;

    private static final List<Star> STARS = new ArrayList<>();
    private static final List<PracticeBolt> PRACTICE_BOLTS = new ArrayList<>();
    private static final List<Bubble> BUBBLES = new ArrayList<>();
    /** Mage npc id -> {hits, kills} by spells that have not been paid yet. */
    private static final Map<UUID, int[]> PENDING_COMBAT = new HashMap<>();

    private MageSpells() {}

    // =========================================================================
    // Who may be hurt
    // =========================================================================

    /**
     * A hostile mob this mage fights: one ticked on its Monsters tab (from the
     * guards' own list, in or out of water), or any hostile mob that hurt it
     * lately. Never a villager, player, pet, golem or animal: none of those is
     * a {@code Mob} that is an {@code Enemy}.
     */
    public static boolean isFoe(NpcEntity mage, @Nullable Entity entity) {
        if (entity == null || entity == mage || !entity.isAlive()) return false;
        if (GuardMobPolicy.isGuardAttackCandidate(entity) && GuardSettings.of(mage).attackHostileMobs()
                && com.alex.test.village.villagers.mage.MageSettingsBridge.of(mage).isAllowed(
                        com.alex.test.village.villagers.mage.settings.MageSettingsSchema.ALLOWED_MOB_TARGETS,
                        EntityType.getKey(entity.getType()).toString())) {
            return true;
        }
        return GuardThreatRules.isSelfDefenseTarget(mage, entity, SELF_DEFENCE_MEMORY_TICKS);
    }

    /** Hurts {@code victim} if it is a foe; counts the hit (and the kill) for the mage's skill. */
    static boolean hurtFoe(ServerLevel level, NpcEntity mage, LivingEntity victim, float damage) {
        if (damage <= 0f || !isFoe(mage, victim)) return false;
        boolean landed = victim.hurt(sourceFor(level, mage), damage);
        if (landed) {
            int[] tally = PENDING_COMBAT.computeIfAbsent(mage.getNpcId(), id -> new int[2]);
            tally[0]++;
            if (!victim.isAlive()) tally[1]++;
        }
        return landed;
    }

    /** Magic from the mage itself: the kill is credited to it, a witch shrugs most of it off. */
    private static DamageSource sourceFor(ServerLevel level, NpcEntity mage) {
        return mage.isAlive() ? level.damageSources().indirectMagic(mage, mage) : level.damageSources().magic();
    }

    /** {hits, kills} landed by this mage's spells since the last call, and clears them. */
    public static int[] takeCombat(UUID npcId) {
        int[] tally = PENDING_COMBAT.remove(npcId);
        return tally == null ? new int[2] : tally;
    }

    /** Foes of the mage within {@code radius} of {@code centre}. */
    static List<LivingEntity> foesNear(ServerLevel level, NpcEntity mage, Vec3 centre, double radius) {
        AABB box = new AABB(centre, centre).inflate(radius);
        double radiusSq = radius * radius;
        return level.getEntitiesOfClass(LivingEntity.class, box,
                e -> isFoe(mage, e) && e.position().distanceToSqr(centre) <= radiusSq);
    }

    // =========================================================================
    // Arcane Bolt
    // =========================================================================

    /**
     * A bolt ({@link com.alex.test.entity.ArcaneBoltEntity}) landed on a mob this
     * mage cast at: counted with its other hits and kills for its skill.
     */
    public static void recordBoltLanding(UUID mageNpcId, boolean killed) {
        int[] tally = PENDING_COMBAT.computeIfAbsent(mageNpcId, id -> new int[2]);
        tally[0]++;
        if (killed) tally[1]++;
    }

    /** A practice bolt at a target block: the bolt's look, flown straight, harming nothing. */
    private static final class PracticeBolt {
        final ServerLevel level;
        final Vec3 to;
        Vec3 pos;
        int age;

        PracticeBolt(ServerLevel level, Vec3 from, Vec3 to) {
            this.level = level;
            this.pos = from;
            this.to = to;
        }
    }

    /** Blocks a tick a practice bolt flies; it fades if it has not arrived in this long. */
    private static final double PRACTICE_BOLT_SPEED = 0.8;
    private static final int PRACTICE_BOLT_LIFETIME = 60;

    /**
     * A bolt cast at a range target ({@code to}, the face it strikes): purple
     * light that flies there and bursts. Nothing is hurt and no block changes;
     * it is for practice only.
     */
    public static void practiceBolt(ServerLevel level, Vec3 from, Vec3 to) {
        PRACTICE_BOLTS.add(new PracticeBolt(level, from, to));
        level.sendParticles(ParticleTypes.WITCH, from.x, from.y, from.z, 4, 0.1, 0.1, 0.1, 0.05);
        play(level, from, SoundEvents.ILLUSIONER_CAST_SPELL, 0.4f, 1.7f);
    }

    /** @return false once it has struck the target or faded */
    private static boolean tickPracticeBolt(PracticeBolt bolt) {
        Vec3 left = bolt.to.subtract(bolt.pos);
        double dist = left.length();
        if (++bolt.age > PRACTICE_BOLT_LIFETIME || dist <= PRACTICE_BOLT_SPEED) {
            Vec3 at = bolt.to;
            bolt.level.sendParticles(ARCANE, at.x, at.y, at.z, 10, 0.2, 0.2, 0.2, 0.0);
            bolt.level.sendParticles(ParticleTypes.ENCHANTED_HIT, at.x, at.y, at.z, 6, 0.15, 0.15, 0.15, 0.2);
            play(bolt.level, at, SoundEvents.AMETHYST_BLOCK_CHIME, 0.7f, 1.5f);
            return false;
        }
        Vec3 next = bolt.pos.add(left.scale(PRACTICE_BOLT_SPEED / dist));
        bolt.level.sendParticles(ARCANE, next.x, next.y, next.z, 2, 0.04, 0.04, 0.04, 0.0);
        bolt.level.sendParticles(ARCANE_PALE, bolt.pos.x, bolt.pos.y, bolt.pos.z, 1, 0.06, 0.06, 0.06, 0.0);
        bolt.pos = next;
        return true;
    }

    // =========================================================================
    // Frost Nova
    // =========================================================================

    /**
     * Ice shards burst out of the ground around {@code centre}: every foe within
     * the nova's radius takes a little damage and Slowness III. Only particles
     * and the effect: no ice or snow is ever placed.
     *
     * @return how many mobs it caught
     */
    public static int frostNova(ServerLevel level, NpcEntity mage, Vec3 centre, int skill) {
        double radius = MageBalance.NOVA_RADIUS;
        // Rings of frost racing outwards, and shards thrown up from the ground.
        for (int ring = 1; ring <= 4; ring++) {
            double r = radius * ring / 4.0;
            int points = 10 + ring * 6;
            for (int i = 0; i < points; i++) {
                double a = Math.PI * 2 * i / points;
                double x = centre.x + Math.cos(a) * r, z = centre.z + Math.sin(a) * r;
                level.sendParticles(FROST, x, centre.y + 0.15, z, 1, 0.05, 0.02, 0.05, 0.0);
                if (ring == 4 && (i & 1) == 0) {
                    level.sendParticles(ParticleTypes.SNOWFLAKE, x, centre.y + 0.3, z, 1, 0.0, 0.3, 0.0, 0.05);
                }
            }
        }
        level.sendParticles(ParticleTypes.SNOWFLAKE, centre.x, centre.y + 0.5, centre.z, 40,
                radius * 0.4, 0.4, radius * 0.4, 0.08);
        level.sendParticles(ParticleTypes.ITEM_SNOWBALL, centre.x, centre.y + 0.4, centre.z, 20,
                radius * 0.4, 0.3, radius * 0.4, 0.15);
        play(level, centre, SoundEvents.GLASS_BREAK, 1.0f, 0.7f);
        play(level, centre, SoundEvents.PLAYER_HURT_FREEZE, 1.0f, 0.8f);

        int caught = 0;
        float damage = MageBalance.novaDamage(skill);
        int slow = MageBalance.novaSlowTicks(skill);
        for (LivingEntity mob : foesNear(level, mage, centre, radius)) {
            if (Math.abs(mob.getY() - centre.y) > 2.5) continue;
            caught++;
            mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, slow,
                    MageBalance.NOVA_SLOW_AMPLIFIER), mage);
            hurtFoe(level, mage, mob, damage);
            // Frost clinging to each one caught.
            level.sendParticles(FROST, mob.getX(), mob.getY(0.5), mob.getZ(), 8, 0.3, 0.5, 0.3, 0.0);
        }
        return caught;
    }

    // =========================================================================
    // Chain Spark
    // =========================================================================

    /**
     * Lightning drawn from the mage's hands to the first mob of {@code path}
     * and on, mob to mob. Each mob takes a little less than the last. Drawn
     * with particles only: a vanilla lightning bolt would set roofs alight and
     * turn villagers into witches.
     *
     * @return how many mobs it hit
     */
    public static int chainSpark(ServerLevel level, NpcEntity mage, Vec3 from, List<LivingEntity> path, int skill) {
        Vec3 at = from;
        int hits = 0;
        for (LivingEntity mob : path) {
            if (!mob.isAlive()) continue;
            Vec3 to = mob.getBoundingBox().getCenter();
            drawSpark(level, at, to, mage.getRandom());
            if (hurtFoe(level, mage, mob, MageBalance.chainDamageAt(skill, hits))) {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, to.x, to.y, to.z, 10, 0.3, 0.4, 0.3, 0.2);
            }
            hits++;
            at = to;
        }
        if (hits > 0) {
            play(level, from, SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0f, 1.5f);
            play(level, at, SoundEvents.FIREWORK_ROCKET_TWINKLE_FAR, 0.8f, 1.8f);
        }
        return hits;
    }

    /** A zig-zag of sparks from {@code a} to {@code b}. */
    private static void drawSpark(ServerLevel level, Vec3 a, Vec3 b, net.minecraft.util.RandomSource random) {
        Vec3 line = b.subtract(a);
        double length = line.length();
        if (length < 1.0E-3) return;
        int steps = Math.max(2, (int) Math.ceil(length / SPARK_STEP));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            // The ends are pinned to the hands and the mob; the middle wanders.
            double wander = Math.sin(Math.PI * t) * SPARK_JITTER;
            double x = a.x + line.x * t + (random.nextDouble() - 0.5) * 2 * wander;
            double y = a.y + line.y * t + (random.nextDouble() - 0.5) * 2 * wander;
            double z = a.z + line.z * t + (random.nextDouble() - 0.5) * 2 * wander;
            level.sendParticles(SPARK, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
            if (i % 3 == 0) level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    // =========================================================================
    // Starfall
    // =========================================================================

    private static final class Star {
        final ServerLevel level;
        final NpcEntity mage;
        final Vec3 ground;
        int wait;
        int falling;

        Star(ServerLevel level, NpcEntity mage, Vec3 ground, int wait) {
            this.level = level;
            this.mage = mage;
            this.ground = ground;
            this.wait = wait;
        }
    }

    /**
     * {@code stars} glowing stars fall on and around {@code centre}, a few ticks
     * apart, and burst where they land: every foe within each burst is hurt.
     * Nothing catches fire and no block is touched.
     */
    public static void starfall(ServerLevel level, NpcEntity mage, Vec3 centre, int stars) {
        net.minecraft.util.RandomSource random = mage.getRandom();
        for (int i = 0; i < stars; i++) {
            // The first lands dead centre; the rest scatter over the pack.
            double r = i == 0 ? 0.0 : 1.0 + random.nextDouble() * 2.0;
            double a = random.nextDouble() * Math.PI * 2;
            Vec3 ground = new Vec3(centre.x + Math.cos(a) * r, centre.y, centre.z + Math.sin(a) * r);
            STARS.add(new Star(level, mage, ground, i * MageBalance.STAR_SPACING_TICKS));
        }
        play(level, centre.add(0, MageBalance.STAR_HEIGHT * 0.5, 0), SoundEvents.ILLUSIONER_CAST_SPELL, 2.0f, 0.6f);
    }

    /** @return false once the star has landed */
    private static boolean tickStar(Star star) {
        if (star.wait > 0) {
            star.wait--;
            return true;
        }
        ServerLevel level = star.level;
        star.falling++;
        double t = star.falling / (double) MageBalance.STAR_FALL_TICKS;
        Vec3 at = star.ground.add(0, MageBalance.STAR_HEIGHT * (1.0 - t), 0);
        level.sendParticles(STAR, at.x, at.y, at.z, 3, 0.12, 0.12, 0.12, 0.0);
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 0.6, at.z, 2, 0.05, 0.3, 0.05, 0.01);
        if (star.falling < MageBalance.STAR_FALL_TICKS) return true;

        Vec3 g = star.ground;
        level.sendParticles(ParticleTypes.EXPLOSION, g.x, g.y + 0.5, g.z, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(STAR, g.x, g.y + 0.4, g.z, 30, 1.0, 0.3, 1.0, 0.0);
        level.sendParticles(ParticleTypes.END_ROD, g.x, g.y + 0.4, g.z, 20, 0.2, 0.2, 0.2, 0.25);
        level.sendParticles(ParticleTypes.FIREWORK, g.x, g.y + 0.4, g.z, 16, 0.3, 0.2, 0.3, 0.2);
        play(level, g, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 2.0f, 0.8f);
        for (LivingEntity mob : foesNear(level, star.mage, g, MageBalance.STAR_RADIUS)) {
            // Stars land closer together than a mob's hurt cooldown, and each
            // hits as hard as the last: without this a mob in the middle of
            // the pack would shrug off every other one.
            mob.invulnerableTime = 0;
            hurtFoe(level, star.mage, mob, MageBalance.STAR_DAMAGE);
        }
        return false;
    }

    // =========================================================================
    // Ward
    // =========================================================================

    private static final class Bubble {
        final ServerLevel level;
        final LivingEntity ally;
        int ticksLeft;

        Bubble(ServerLevel level, LivingEntity ally, int ticks) {
            this.level = level;
            this.ally = ally;
            this.ticksLeft = ticks;
        }
    }

    /**
     * A shield bubble on {@code ally}: Absorption that soaks up the next few
     * hits, drawn as a faint blue sphere until it breaks or runs out.
     */
    public static void ward(ServerLevel level, NpcEntity mage, LivingEntity ally, Vec3 from, int skill) {
        ally.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, MageBalance.WARD_TICKS,
                MageBalance.wardAmplifier(skill)), mage);
        // A thread of light from the mage's hand to the one it shields.
        Vec3 to = ally.getBoundingBox().getCenter();
        Vec3 line = to.subtract(from);
        int steps = Math.max(2, (int) (line.length() / 0.5));
        for (int i = 0; i <= steps; i++) {
            Vec3 p = from.add(line.scale(i / (double) steps));
            level.sendParticles(WARD, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
        }
        BUBBLES.removeIf(b -> b.ally == ally);
        BUBBLES.add(new Bubble(level, ally, MageBalance.WARD_TICKS));
        drawBubble(level, ally, 40);
        play(level, to, SoundEvents.EVOKER_CAST_SPELL, 0.8f, 1.6f);
    }

    /** Whether {@code ally} has a ward bubble up right now. */
    public static boolean isWarded(LivingEntity ally) {
        for (Bubble bubble : BUBBLES) {
            if (bubble.ally == ally) return true;
        }
        return false;
    }

    /** @return false once the ward is gone */
    private static boolean tickBubble(Bubble bubble) {
        LivingEntity ally = bubble.ally;
        if (!ally.isAlive() || ally.isRemoved()) return false;
        // Broken: every point of it soaked up. It shatters.
        if (ally.getAbsorptionAmount() <= 0f || !ally.hasEffect(MobEffects.ABSORPTION)) {
            Vec3 c = ally.getBoundingBox().getCenter();
            bubble.level.sendParticles(WARD, c.x, c.y, c.z, 24, 0.6, 0.8, 0.6, 0.0);
            play(bubble.level, c, SoundEvents.AMETHYST_CLUSTER_BREAK, 0.8f, 1.2f);
            return false;
        }
        if (--bubble.ticksLeft <= 0) return false;
        if (bubble.ticksLeft % WARD_DRAW_EVERY == 0) drawBubble(bubble.level, ally, 10);
        return true;
    }

    private static void drawBubble(ServerLevel level, LivingEntity ally, int points) {
        AABB box = ally.getBoundingBox();
        Vec3 c = box.getCenter();
        double r = Math.max(box.getXsize(), box.getYsize()) * 0.6 + 0.2;
        net.minecraft.util.RandomSource random = ally.getRandom();
        for (int i = 0; i < points; i++) {
            // Even spread over a sphere.
            double y = random.nextDouble() * 2 - 1;
            double a = random.nextDouble() * Math.PI * 2;
            double s = Math.sqrt(1 - y * y);
            level.sendParticles(WARD, c.x + Math.cos(a) * s * r, c.y + y * r, c.z + Math.sin(a) * s * r,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    // =========================================================================
    // Blink, and the glow in the mage's hands
    // =========================================================================

    /** A puff where the mage vanished and another where it appears. */
    public static void blink(ServerLevel level, Vec3 from, Vec3 to) {
        level.sendParticles(ParticleTypes.PORTAL, from.x, from.y + 1.0, from.z, 30, 0.3, 0.6, 0.3, 0.4);
        level.sendParticles(ARCANE, from.x, from.y + 1.0, from.z, 12, 0.3, 0.6, 0.3, 0.0);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, to.x, to.y + 1.0, to.z, 30, 0.3, 0.6, 0.3, 0.1);
        level.sendParticles(ARCANE, to.x, to.y + 1.0, to.z, 12, 0.3, 0.6, 0.3, 0.0);
        play(level, from, SoundEvents.ENDERMAN_TELEPORT, 0.8f, 1.3f);
    }

    /**
     * Light gathering in the hands while a spell winds up; {@code charge} is
     * how far through the wind-up it is, 0..1, and the glow grows with it.
     */
    public static void chargeGlow(ServerLevel level, Vec3 hand, ParticleOptions colour, float charge) {
        int count = 1 + Math.round(charge * 2);
        double spread = 0.12 - 0.06 * charge;
        level.sendParticles(colour, hand.x, hand.y, hand.z, count, spread, spread, spread, 0.0);
        if (charge > 0.5f) level.sendParticles(ParticleTypes.ENCHANT, hand.x, hand.y, hand.z, 1, 0.1, 0.1, 0.1, 0.3);
    }

    /** The colour each spell's light gathers in. */
    public static ParticleOptions colourFor(com.alex.test.village.villagers.mage.MageSpellPlanner.Spell spell) {
        return switch (spell) {
            case ARCANE_BOLT -> ARCANE;
            case FROST_NOVA -> FROST;
            case WARD -> WARD;
            case CHAIN_SPARK -> SPARK;
            case STARFALL -> STAR;
        };
    }

    /** A hand position from the clip tables ({@code MageCastTiming}), turned with the mage's body. */
    public static Vec3 handAt(NpcEntity mage, com.alex.test.village.villagers.mage.MageCastTiming.Hand hand) {
        double yaw = Math.toRadians(mage.yBodyRot);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        return mage.position().add(fx * hand.ahead() + rx * hand.right(), hand.up(),
                fz * hand.ahead() + rz * hand.right());
    }

    /** How far below its draw point a vanilla ENCHANT particle ends its flight. */
    private static final double ENCHANT_DROP = 1.2;

    /**
     * The mage studying: motes of light drift from a bookshelf to its hands, the
     * way they drift to a vanilla enchanting table.
     */
    public static void studyMotes(ServerLevel level, Vec3 shelf, Vec3 hands) {
        // An ENCHANT particle appears at where it is drawn plus its speed and
        // flies back to 1.2 blocks below where it was drawn: drawn that far above
        // the hands and sent off towards the shelf, it flies from shelf to hands.
        double drawY = hands.y + ENCHANT_DROP;
        level.sendParticles(ParticleTypes.ENCHANT, hands.x, drawY, hands.z, 0,
                shelf.x - hands.x, shelf.y - drawY, shelf.z - hands.z, 1.0);
    }

    /** A Mage Tower's focus crystal waking while a mage studies: a soft purple shimmer about it. */
    public static void crystalGlow(ServerLevel level, net.minecraft.core.BlockPos crystal) {
        level.sendParticles(ARCANE_PALE, crystal.getX() + 0.5, crystal.getY() + 0.5, crystal.getZ() + 0.5,
                2, 0.35, 0.35, 0.35, 0.0);
        if (level.getRandom().nextInt(3) == 0) {
            level.sendParticles(ParticleTypes.END_ROD, crystal.getX() + 0.5, crystal.getY() + 0.7,
                    crystal.getZ() + 0.5, 1, 0.2, 0.2, 0.2, 0.005);
        }
    }

    /** A thread of crystal light from {@code crystal} to the studying mage's hands, with a chime now and then. */
    public static void crystalStream(ServerLevel level, Vec3 crystal, Vec3 hands, boolean chime) {
        Vec3 line = hands.subtract(crystal);
        int steps = Math.max(2, (int) (line.length() / 0.4));
        for (int i = 0; i <= steps; i++) {
            Vec3 p = crystal.add(line.scale(i / (double) steps));
            level.sendParticles(ARCANE, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
        }
        if (chime) play(level, crystal, SoundEvents.AMETHYST_BLOCK_CHIME, 0.6f, 1.2f);
    }

    /** A night light on its way out of the Mage Tower to the dark spot a mage chose. */
    private static final class Wisp {
        final ServerLevel level;
        final Vec3 from;
        final Vec3 to;
        /** Ticks the whole drift takes. */
        final int flight;
        final double arc;
        int age;

        Wisp(ServerLevel level, Vec3 from, Vec3 to) {
            this.level = level;
            this.from = from;
            this.to = to;
            double dist = from.distanceTo(to);
            this.flight = Math.max(1, Math.min(WISP_LIFETIME, (int) Math.ceil(dist / WISP_SPEED)));
            this.arc = Math.min(WISP_ARC, dist * 0.2);
        }

        /** Where the wisp is {@code age} ticks in: along the line, bowed up most at the middle. */
        Vec3 at(int age) {
            double t = Math.min(1.0, age / (double) flight);
            return from.lerp(to, t).add(0, arc * Math.sin(Math.PI * t), 0);
        }
    }

    private static final List<Wisp> WISPS = new ArrayList<>();
    /** Blocks a tick a wisp drifts; it simply settles where it is if it has not arrived in this long. */
    private static final double WISP_SPEED = 0.6;
    private static final int WISP_LIFETIME = 120;
    /** How high a wisp's path bows up over the village, at most. */
    private static final double WISP_ARC = 4.0;
    private static final int MAX_WISPS = 16;

    /**
     * A wisp of light drifts from the Mage Tower's crystal ({@code from}) to the
     * spot where a night light was just left ({@code to}), in a low arc.
     * Particles only; the light block itself is already in place.
     */
    public static void wispFlight(ServerLevel level, Vec3 from, Vec3 to) {
        if (WISPS.size() >= MAX_WISPS) return;
        WISPS.add(new Wisp(level, from, to));
        play(level, from, SoundEvents.AMETHYST_BLOCK_CHIME, 0.5f, 1.6f);
    }

    /** @return false once the wisp has arrived or faded */
    private static boolean tickWisp(Wisp wisp) {
        if (++wisp.age >= wisp.flight) {
            Vec3 at = wisp.to;
            wisp.level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 8, 0.15, 0.15, 0.15, 0.02);
            return false;
        }
        Vec3 now = wisp.at(wisp.age);
        Vec3 before = wisp.at(wisp.age - 1);
        wisp.level.sendParticles(ParticleTypes.END_ROD, now.x, now.y, now.z, 1, 0.03, 0.03, 0.03, 0.0);
        if ((wisp.age & 1) == 0) {
            wisp.level.sendParticles(ARCANE_PALE, before.x, before.y, before.z, 1, 0.05, 0.05, 0.05, 0.0);
        }
        return true;
    }

    /** A faint glow about the hands of a mage on alert, now and then. */
    public static void alertGlow(ServerLevel level, Vec3 hand) {
        level.sendParticles(ARCANE_PALE, hand.x, hand.y, hand.z, 1, 0.06, 0.06, 0.06, 0.0);
    }

    private static void play(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.NEUTRAL, volume,
                pitch * Mth.nextFloat(level.getRandom(), 0.92f, 1.08f));
    }

    // =========================================================================
    // Ticking
    // =========================================================================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (STARS.isEmpty() && BUBBLES.isEmpty() && PRACTICE_BOLTS.isEmpty() && WISPS.isEmpty()) return;
        if (com.alex.test.village.stuckwatch.StuckWatch.worldHeld(event.getServer())) return;
        for (Iterator<PracticeBolt> it = PRACTICE_BOLTS.iterator(); it.hasNext(); ) {
            if (!tickPracticeBolt(it.next())) it.remove();
        }
        for (Iterator<Wisp> it = WISPS.iterator(); it.hasNext(); ) {
            if (!tickWisp(it.next())) it.remove();
        }
        for (Iterator<Star> it = STARS.iterator(); it.hasNext(); ) {
            if (!tickStar(it.next())) it.remove();
        }
        for (Iterator<Bubble> it = BUBBLES.iterator(); it.hasNext(); ) {
            if (!tickBubble(it.next())) it.remove();
        }
    }

    /** Nothing in flight outlives the server it was cast on. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        STARS.clear();
        BUBBLES.clear();
        PRACTICE_BOLTS.clear();
        WISPS.clear();
        PENDING_COMBAT.clear();
        MageActivity.forgetTimers();
    }
}
