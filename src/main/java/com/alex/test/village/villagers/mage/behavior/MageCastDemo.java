package com.alex.test.village.villagers.mage.behavior;

import com.alex.test.DynamicVillages;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.ArcaneBoltEntity;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.mage.MageBalance;
import com.alex.test.village.villagers.mage.MageCastTiming;
import com.alex.test.village.villagers.mage.MageSpellPlanner.Spell;
import com.alex.test.village.villagers.mage.MageTargeting;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /villager magecast [spell] [level]}: a mage with no brain casting at a
 * pack of practice dummies on a loop, to watch every spell without waiting for
 * a raid -- the clip, its pacing by level, the light gathering in the hands,
 * the spell itself and what it does to the mobs.
 *
 * <p>Each cast is the real one: the same clip and release frame, the same
 * {@link MageSpells} effects and damage, the same {@link ArcaneBoltEntity}.
 * Only the choosing is skipped: the demo casts what it is told, whether or not
 * the level has learnt it yet. Nothing costs mana or reagents.
 *
 * <p>The dummies are zombies that cannot move, burn or die: 200 health, healed
 * full before every cast. On Peaceful, where the game deletes every zombie the
 * tick after it appears, they are armor stands instead: every spell still
 * plays, but nothing is hurt, as spells only ever hurt hostile mobs. A guard
 * stands with them, knocked to 40% health before every Ward. Dummies that go
 * missing are put back. All of it goes with {@code /villager magecast stop}.
 */
public final class MageCastDemo {

    /** What the command takes: one spell, or {@code all} to go round them. */
    public static final List<String> SPELLS = List.of("all", "bolt", "nova", "chain", "ward", "starfall", "blink");

    /** Standing between casts, so each one reads on its own. */
    private static final int PAUSE_TICKS = 30;
    /** After Starfall, long enough for every star to land before the next cast. */
    private static final int STARFALL_WAIT_TICKS = MageBalance.STAR_FALL_TICKS
            + MageBalance.STAR_SPACING_TICKS * MageBalance.STARFALL_MAX_STARS + 10;
    /** The light gathers in the hands over this many ticks before the release. */
    private static final int GLOW_TICKS = 10;
    /** How far ahead of you the mage stands, and the pack beyond it. */
    private static final double MAGE_AHEAD = 4.0;
    private static final double PACK_BEYOND = 10.0;
    /** Blink hops the mage this far sideways and back. */
    private static final double BLINK_HOP = 7.0;
    private static final float DUMMY_HEALTH = 200f;
    private static final String DEMO_TAG = "dynamicvillages.mage_demo";
    /** Where the pack stands, relative to its centre: in reach of a nova and a spark's jump. */
    private static final double[][] PACK_OFFSETS = {{0, 0}, {1.6, 0.8}, {-1.4, 1.2}, {0.9, -1.7}, {-1.8, -1.0}};
    /** A pack that keeps vanishing (something else removing it) is put back at most this often. */
    private static final int MAX_RESPAWNS = 5;

    /** One step of the loop: a spell, or a blink. */
    private enum Step {
        BOLT(Spell.ARCANE_BOLT), NOVA(Spell.FROST_NOVA), CHAIN(Spell.CHAIN_SPARK),
        WARD(Spell.WARD), STARFALL(Spell.STARFALL), BLINK(null);

        @Nullable final Spell spell;

        Step(@Nullable Spell spell) {
            this.spell = spell;
        }
    }

    private static final class Demo {
        final ServerLevel level;
        final UUID callerId;
        final NpcEntity mage;
        final NpcEntity guard;
        final List<LivingEntity> dummies;
        final Vec3 packAt;
        final Vec3 packForward;
        final Vec3 packRight;
        int respawns;
        final List<Step> steps;
        final int skill;
        final Vec3 home;
        int stepIndex;
        int ticks;
        int releaseAt;
        int endAt;
        int pause;
        boolean released;
        boolean blinkedAway;

        Demo(ServerLevel level, UUID callerId, NpcEntity mage, NpcEntity guard, List<LivingEntity> dummies,
             Vec3 packAt, Vec3 packForward, Vec3 packRight, List<Step> steps, int skill) {
            this.level = level;
            this.callerId = callerId;
            this.mage = mage;
            this.guard = guard;
            this.dummies = dummies;
            this.packAt = packAt;
            this.packForward = packForward;
            this.packRight = packRight;
            this.steps = steps;
            this.skill = skill;
            this.home = mage.position();
        }

        Step step() {
            return steps.get(stepIndex % steps.size());
        }
    }

    private static final List<Demo> DEMOS = new ArrayList<>();

    private MageCastDemo() {}

    /**
     * Spawns the demo in front of {@code caller}: the mage, five dummies in a
     * loose pack beyond it, and a guard at the pack's edge.
     *
     * @param spell one of {@link #SPELLS}
     * @param skill the level the casts are paced and powered at (1..100)
     */
    public static Component start(ServerLevel level, Entity caller, String spell, int skill) {
        List<Step> steps = stepsFor(spell);
        if (steps == null) return Component.literal("Unknown spell '" + spell + "'. Try: " + String.join(", ", SPELLS));
        stop(level);
        skill = Math.clamp(skill, 1, MageBalance.MAX_LEVEL);

        Vec3 forward = Vec3.directionFromRotation(0, caller.getYRot());
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        Vec3 mageAt = caller.position().add(forward.scale(MAGE_AHEAD));
        Vec3 packAt = mageAt.add(forward.scale(PACK_BEYOND));

        NpcEntity mage = puppet(level, mageAt, "mage demo (level " + skill + ")", Profession.MAGE);
        if (mage == null) return Component.literal("Could not create the demo mage.");
        NpcEntity guard = puppet(level, packAt.add(right.scale(3.5)).add(forward.scale(-2.0)),
                "guard (ward target)", Profession.GUARD);
        if (guard == null) {
            mage.discard();
            return Component.literal("Could not create the demo guard.");
        }

        List<LivingEntity> dummies = spawnPack(level, packAt, forward, right);
        if (dummies.isEmpty()) {
            mage.discard();
            guard.discard();
            return Component.literal("Could not create the dummies.");
        }

        DEMOS.add(new Demo(level, caller.getUUID(), mage, guard, dummies, packAt, forward, right, steps, skill));
        String which = steps.size() > 1 ? "every spell in turn" : stepName(steps.get(0));
        String peaceful = isPeaceful(level)
                ? " The world is on Peaceful, which deletes zombies at once, so the dummies are armor stands:"
                        + " every spell plays, but nothing takes damage. Set the difficulty to Easy to see hits."
                : "";
        return Component.literal("A demo mage (level " + skill + ") is casting " + which
                + " at " + dummies.size() + " practice dummies." + peaceful
                + " Stop it with /villager magecast stop.");
    }

    @Nullable
    private static List<Step> stepsFor(String spell) {
        return switch (spell.toLowerCase(Locale.ROOT)) {
            case "all" -> List.of(Step.values());
            case "bolt" -> List.of(Step.BOLT);
            case "nova" -> List.of(Step.NOVA);
            case "chain" -> List.of(Step.CHAIN);
            case "ward" -> List.of(Step.WARD);
            case "starfall" -> List.of(Step.STARFALL);
            case "blink" -> List.of(Step.BLINK);
            default -> null;
        };
    }

    /** Removes every demo mage, guard and dummy in {@code level}; returns how many demos there were. */
    public static int stop(ServerLevel level) {
        int stopped = 0;
        for (Iterator<Demo> it = DEMOS.iterator(); it.hasNext(); ) {
            Demo demo = it.next();
            if (demo.level != level) continue;
            discard(demo);
            it.remove();
            stopped++;
        }
        return stopped;
    }

    private static void discard(Demo demo) {
        MageSpells.takeCombat(demo.mage.getNpcId());
        demo.mage.discard();
        demo.guard.discard();
        for (LivingEntity dummy : demo.dummies) dummy.discard();
    }

    @Nullable
    private static NpcEntity puppet(ServerLevel level, Vec3 at, String name, Profession profession) {
        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        if (npc == null) return null;
        npc.moveTo(at.x, groundY(level, at), at.z, 0f, 0f);
        npc.setCustomName(Component.literal(name));
        npc.setCustomNameVisible(true);
        npc.setNoAi(true);
        npc.addTag(NpcEntity.MOCAP_PREVIEW_TAG);
        npc.addTag(DEMO_TAG);
        if (!level.addFreshEntity(npc)) return null;
        npc.setProfession(profession);
        return npc;
    }

    private static boolean isPeaceful(ServerLevel level) {
        return level.getDifficulty() == Difficulty.PEACEFUL;
    }

    /** A loose pack: one in the middle, four around it, all within a nova's reach and a spark's jump. */
    private static List<LivingEntity> spawnPack(ServerLevel level, Vec3 packAt, Vec3 forward, Vec3 right) {
        List<LivingEntity> dummies = new ArrayList<>();
        for (double[] o : PACK_OFFSETS) {
            Vec3 at = packAt.add(right.scale(o[0])).add(forward.scale(o[1]));
            LivingEntity dummy = isPeaceful(level) ? standDummy(level, at) : dummy(level, at);
            if (dummy != null) dummies.add(dummy);
        }
        return dummies;
    }

    /** On Peaceful: an armor stand, which the game leaves alone. Spells show on it; none hurt it. */
    @Nullable
    private static ArmorStand standDummy(ServerLevel level, Vec3 at) {
        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        if (stand == null) return null;
        stand.moveTo(at.x, groundY(level, at), at.z, level.random.nextFloat() * 360f, 0f);
        stand.setInvulnerable(true);
        stand.setCustomName(Component.literal("practice dummy"));
        stand.setCustomNameVisible(true);
        stand.addTag(DEMO_TAG);
        return level.addFreshEntity(stand) ? stand : null;
    }

    /** A zombie that stands still, never burns and never dies: healed before every cast. */
    @Nullable
    private static Zombie dummy(ServerLevel level, Vec3 at) {
        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie == null) return null;
        zombie.moveTo(at.x, groundY(level, at), at.z, level.random.nextFloat() * 360f, 0f);
        zombie.setNoAi(true);
        zombie.setSilent(true);
        zombie.setPersistenceRequired();
        zombie.setCustomName(Component.literal("practice dummy"));
        zombie.addTag(DEMO_TAG);
        AttributeInstance health = zombie.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) health.setBaseValue(DUMMY_HEALTH);
        zombie.setHealth(zombie.getMaxHealth());
        zombie.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION,
                0, false, false));
        return level.addFreshEntity(zombie) ? zombie : null;
    }

    private static double groundY(ServerLevel level, Vec3 at) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(at.x), Mth.floor(at.z));
    }

    // =========================================================================
    // Ticking
    // =========================================================================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (DEMOS.isEmpty()) return;
        for (Iterator<Demo> it = DEMOS.iterator(); it.hasNext(); ) {
            Demo demo = it.next();
            if (!tick(demo)) {
                discard(demo);
                it.remove();
            }
        }
    }

    /**
     * The demo's puppets and dummies are taken away before the world is saved:
     * otherwise a save-and-quit leaves NoAI mages and 200-health zombies
     * behind, which no demo will ever clear.
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Demo demo : DEMOS) discard(demo);
        DEMOS.clear();
    }

    /** One tick of one demo; false once it should end (the mage or the guard is gone). */
    private static boolean tick(Demo demo) {
        NpcEntity mage = demo.mage;
        if (mage.isRemoved() || !mage.isAlive() || demo.guard.isRemoved() || !demo.guard.isAlive()) return false;
        demo.dummies.removeIf(d -> d.isRemoved() || !d.isAlive());
        if (demo.dummies.isEmpty()) {
            // Gone from under the demo (the difficulty was set to Peaceful, or
            // something killed them): a new pack, of whatever the world allows now.
            if (++demo.respawns > MAX_RESPAWNS) {
                tell(demo, Component.literal("The practice dummies keep disappearing; the mage demo has stopped.")
                        .withStyle(ChatFormatting.RED));
                return false;
            }
            demo.dummies.addAll(spawnPack(demo.level, demo.packAt, demo.packForward, demo.packRight));
            if (demo.dummies.isEmpty()) return false;
            demo.ticks = 0;
            demo.released = false;
            demo.pause = PAUSE_TICKS;
            return true;
        }
        // The demo pays nothing for skill: drain the tally its spells leave.
        MageSpells.takeCombat(mage.getNpcId());

        LivingEntity centre = demo.dummies.get(0);
        face(demo.guard, centre);
        if (demo.pause > 0) {
            demo.pause--;
            face(mage, centre);
            return true;
        }

        Step step = demo.step();
        if (step == Step.BLINK) {
            blink(demo);
            next(demo, PAUSE_TICKS + 10);
            return true;
        }

        // Back home from a blink first, a moment before the next cast, so every
        // spell is seen from the same place.
        if (demo.ticks == 0 && demo.blinkedAway) {
            blink(demo);
            demo.pause = PAUSE_TICKS;
            return true;
        }
        Spell spell = step.spell;
        MageCastTiming.Clip clip = spell.clip();
        if (demo.ticks == 0) begin(demo, spell, clip);
        demo.ticks++;

        LivingEntity aim = spell == Spell.WARD ? demo.guard : centre;
        face(mage, aim);
        if (!demo.released) {
            int window = Math.min(GLOW_TICKS, demo.releaseAt);
            int left = demo.releaseAt - demo.ticks;
            if (left < window) {
                float charge = 1f - left / (float) window;
                MageSpells.chargeGlow(demo.level, MageSpells.handAt(mage, MageCastTiming.castingHand(clip)),
                        MageSpells.colourFor(spell), charge);
                MageCastTiming.Hand other = MageCastTiming.otherHand(clip);
                if (other != null) {
                    MageSpells.chargeGlow(demo.level, MageSpells.handAt(mage, other), MageSpells.colourFor(spell), charge);
                }
            }
            if (demo.ticks >= demo.releaseAt) {
                demo.released = true;
                release(demo, spell, clip);
            }
        }
        if (demo.ticks >= demo.endAt) {
            mage.setAnimation(VillagerAnimationState.IDLE);
            next(demo, spell == Spell.STARFALL ? STARFALL_WAIT_TICKS : PAUSE_TICKS);
        }
        return true;
    }

    /** A fresh cast: every dummy healed, the guard knocked down for a ward, the clip from its first frame. */
    private static void begin(Demo demo, Spell spell, MageCastTiming.Clip clip) {
        for (LivingEntity dummy : demo.dummies) {
            dummy.setHealth(dummy.getMaxHealth());
            dummy.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
        if (spell == Spell.WARD) {
            demo.guard.removeEffect(MobEffects.ABSORPTION);
            demo.guard.setAbsorptionAmount(0f);
            demo.guard.setHealth(demo.guard.getMaxHealth() * 0.4f);
        }
        demo.releaseAt = MageCastTiming.releaseTicks(clip, demo.skill);
        demo.endAt = demo.releaseAt + Math.max(MageBalance.recoveryTicks(demo.skill) / 2,
                MageCastTiming.followThroughTicks(clip));
        demo.mage.startWorkAnimation(MageCastTiming.state(clip), MageCastTiming.clipSpeed(clip, demo.releaseAt));
        String learnt = spell.learnt(demo.skill) ? "" : "  (not learnt until level " + learntAt(spell) + ")";
        String peaceful = isPeaceful(demo.level) ? "  (Peaceful: no damage)" : "";
        tell(demo, Component.literal(spell.label() + learnt + peaceful).withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    /** The spell leaves the hands, exactly as in a fight, and the result goes to the action bar. */
    private static void release(Demo demo, Spell spell, MageCastTiming.Clip clip) {
        NpcEntity mage = demo.mage;
        ServerLevel level = demo.level;
        int skill = demo.skill;
        Vec3 hand = MageSpells.handAt(mage, MageCastTiming.castingHand(clip));
        LivingEntity centre = demo.dummies.get(0);
        switch (spell) {
            case ARCANE_BOLT -> {
                float damage = MageBalance.boltDamage(skill);
                ArcaneBoltEntity.castAt(level, mage, hand, centre, damage, landing -> tell(demo,
                        Component.literal(String.format(Locale.ROOT, "Arcane Bolt hit for %.1f", damage))
                                .withStyle(ChatFormatting.LIGHT_PURPLE)));
            }
            case FROST_NOVA -> {
                int caught = MageSpells.frostNova(level, mage, centre.position(), skill);
                tell(demo, Component.literal(String.format(Locale.ROOT,
                        "Frost Nova caught %d: %.1f damage, slowed %.1f s", caught,
                        MageBalance.novaDamage(skill), MageBalance.novaSlowTicks(skill) / 20.0))
                        .withStyle(ChatFormatting.AQUA));
            }
            case CHAIN_SPARK -> {
                List<MageTargeting.Point> points = new ArrayList<>();
                for (LivingEntity d : demo.dummies) points.add(new MageTargeting.Point(d.getX(), d.getY(), d.getZ()));
                int hits = Math.max(MageBalance.CHAIN_FIRST_JUMPS, MageBalance.chainHits(skill));
                List<LivingEntity> path = new ArrayList<>();
                for (int i : MageTargeting.chain(points, nearest(demo), MageBalance.CHAIN_JUMP_RANGE, hits)) {
                    path.add(demo.dummies.get(i));
                }
                int hit = MageSpells.chainSpark(level, mage, hand, path, skill);
                tell(demo, Component.literal(String.format(Locale.ROOT, "Chain Spark hit %d, %.1f damage first",
                        hit, MageBalance.chainDamageAt(skill, 0))).withStyle(ChatFormatting.YELLOW));
            }
            case WARD -> {
                MageSpells.ward(level, mage, demo.guard, hand, skill);
                tell(demo, Component.literal(String.format(Locale.ROOT, "Ward on the guard: soaks %.0f health",
                        MageBalance.wardHealth(skill))).withStyle(ChatFormatting.BLUE));
            }
            case STARFALL -> {
                int stars = MageBalance.starCount(demo.dummies.size());
                MageSpells.starfall(level, mage, centre.position(), stars);
                tell(demo, Component.literal(String.format(Locale.ROOT, "Starfall: %d stars, %.0f damage each",
                        stars, MageBalance.STAR_DAMAGE)).withStyle(ChatFormatting.GOLD));
            }
        }
    }

    /** Blink: the mage vanishes and reappears a few blocks to the side, then back next time. */
    private static void blink(Demo demo) {
        NpcEntity mage = demo.mage;
        Vec3 forward = demo.dummies.get(0).position().subtract(demo.home).multiply(1, 0, 1);
        if (forward.lengthSqr() < 1.0E-4) forward = new Vec3(0, 0, 1);
        Vec3 side = new Vec3(-forward.z, 0, forward.x).normalize().scale(BLINK_HOP);
        Vec3 to = demo.blinkedAway ? demo.home : demo.home.add(side);
        to = new Vec3(to.x, groundY(demo.level, to), to.z);
        Vec3 from = mage.position();
        mage.teleportTo(to.x, to.y, to.z);
        MageSpells.blink(demo.level, from, to);
        demo.blinkedAway = !demo.blinkedAway;
        tell(demo, Component.literal(String.format(Locale.ROOT, "Blink: %.0f blocks", from.distanceTo(to)))
                .withStyle(ChatFormatting.DARK_PURPLE));
    }

    private static void next(Demo demo, int pause) {
        demo.stepIndex++;
        demo.ticks = 0;
        demo.released = false;
        demo.pause = pause;
    }

    /** The dummy nearest the mage: where a spark starts. */
    private static int nearest(Demo demo) {
        int best = 0;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < demo.dummies.size(); i++) {
            double d = demo.dummies.get(i).distanceToSqr(demo.mage);
            if (d < bestSq) {
                bestSq = d;
                best = i;
            }
        }
        return best;
    }

    private static int learntAt(Spell spell) {
        return switch (spell) {
            case ARCANE_BOLT -> 1;
            case FROST_NOVA -> MageBalance.FROST_NOVA_LEVEL;
            case WARD -> MageBalance.WARD_LEVEL;
            case CHAIN_SPARK -> MageBalance.CHAIN_SPARK_LEVEL;
            case STARFALL -> MageBalance.STARFALL_LEVEL;
        };
    }

    private static String stepName(Step step) {
        return step.spell != null ? step.spell.label() : "Blink";
    }

    /** A line on the caller's action bar, if it is a player still in the game. */
    private static void tell(Demo demo, Component line) {
        if (demo.level.getServer().getPlayerList().getPlayer(demo.callerId) instanceof ServerPlayer player) {
            player.displayClientMessage(line, true);
        }
    }

    /** Turns a brainless puppet to face {@code target}: body, head and all. */
    private static void face(NpcEntity npc, Entity target) {
        double dx = target.getX() - npc.getX();
        double dz = target.getZ() - npc.getZ();
        if (dx * dx + dz * dz < 1e-4) return;
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        npc.setYRot(yaw);
        npc.setYBodyRot(yaw);
        npc.setYHeadRot(yaw);
    }
}
