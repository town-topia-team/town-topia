package com.alex.test.village.villagers.cleric.behavior;

import com.alex.test.DynamicVillages;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.HealingOrbEntity;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.cleric.ClericBalance;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * {@code /villager clericcast}: a cleric with no brain that throws healing
 * orbs on a loop, for looking at the throw, the orb on its palm, the flight
 * and the burst without waiting for someone to get hurt. Each throw is the
 * real one: same clip, same pacing by skill level, same orb and blessing.
 *
 * <p>It throws at you, or with {@code dummy} at a second villager standing
 * across from it, whose health is knocked down before every throw so the
 * heal always has something to do. Both are throwaway mocap-preview puppets:
 * {@code /villager clericcast stop} or {@code /villager mocap stop} clears them.
 */
public final class ClericCastDemo {

    /** Standing between throws, so each one reads on its own: the blend out, a moment's rest, the blend in. */
    private static final int PAUSE_TICKS = 30;
    /** How far ahead of you the pair stands, and how far apart. */
    private static final double AHEAD = 5.0;
    private static final double SIDE = 3.5;

    private static final class Demo {
        final ServerLevel level;
        final NpcEntity cleric;
        @Nullable final NpcEntity dummy;
        final UUID targetId;
        final int skill;
        int ticks;
        int pause;
        boolean released;

        Demo(ServerLevel level, NpcEntity cleric, @Nullable NpcEntity dummy, UUID targetId, int skill) {
            this.level = level;
            this.cleric = cleric;
            this.dummy = dummy;
            this.targetId = targetId;
            this.skill = skill;
        }
    }

    private static final List<Demo> DEMOS = new ArrayList<>();

    private ClericCastDemo() {}

    /**
     * Spawns a demo cleric in front of {@code caller}.
     *
     * @param skill    the level the throw is paced and blessed at (1..100)
     * @param useDummy throw at a second villager rather than at the caller
     */
    public static Component start(ServerLevel level, Entity caller, int skill, boolean useDummy) {
        return start(level, caller, skill, useDummy, false);
    }

    /**
     * @param rawLimbs draw the cleric as plain rigid boxes, without the limbs
     *                 welded and bent (the {@code clericcast_raw} debug view)
     */
    public static Component start(ServerLevel level, Entity caller, int skill, boolean useDummy, boolean rawLimbs) {
        Vec3 forward = Vec3.directionFromRotation(0, caller.getYRot());
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        Vec3 base = caller.position().add(forward.scale(AHEAD));
        Vec3 clericAt = useDummy ? base.add(right.scale(-SIDE)) : base;

        NpcEntity cleric = puppet(level, clericAt,
                "cleric demo" + (rawLimbs ? " raw" : "") + " (level " + skill + ")");
        if (cleric == null) return Component.literal("Could not create the demo cleric.");
        cleric.setProfession(Profession.CLERIC);
        cleric.setRawLimbs(rawLimbs);

        NpcEntity dummy = null;
        UUID target = caller.getUUID();
        if (useDummy) {
            dummy = puppet(level, base.add(right.scale(SIDE)), "patient");
            if (dummy == null) {
                cleric.discard();
                return Component.literal("Could not create the dummy patient.");
            }
            target = dummy.getUUID();
        }
        DEMOS.add(new Demo(level, cleric, dummy, target, Math.clamp(skill, 1, ClericBalance.MAX_LEVEL)));
        return Component.literal("A demo cleric" + (rawLimbs ? " (raw limbs: rigid boxes, no bending)" : "")
                + " is throwing healing orbs at "
                + (useDummy ? "a dummy patient" : "you") + ", one throw every "
                + String.format(java.util.Locale.ROOT, "%.1f", (ClericBalance.castTicks(skill) + PAUSE_TICKS) / 20.0)
                + " s. Stop it with /villager clericcast stop.");
    }

    /** Removes every demo cleric and dummy in {@code level}; returns how many demos there were. */
    public static int stop(ServerLevel level) {
        int stopped = 0;
        for (Iterator<Demo> it = DEMOS.iterator(); it.hasNext(); ) {
            Demo demo = it.next();
            if (demo.level != level) continue;
            demo.cleric.discard();
            if (demo.dummy != null) demo.dummy.discard();
            it.remove();
            stopped++;
        }
        return stopped;
    }

    @Nullable
    private static NpcEntity puppet(ServerLevel level, Vec3 at, String name) {
        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        if (npc == null) return null;
        double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(at.x), Mth.floor(at.z));
        npc.moveTo(at.x, y, at.z, 0f, 0f);
        npc.setCustomName(Component.literal(name));
        npc.setCustomNameVisible(true);
        npc.setNoAi(true);
        npc.addTag(NpcEntity.MOCAP_PREVIEW_TAG);
        return level.addFreshEntity(npc) ? npc : null;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (DEMOS.isEmpty()) return;
        for (Iterator<Demo> it = DEMOS.iterator(); it.hasNext(); ) {
            Demo demo = it.next();
            if (!tick(demo)) {
                demo.cleric.setHoldingOrb(false);
                if (demo.cleric.isAlive()) demo.cleric.setAnimation(VillagerAnimationState.IDLE);
                it.remove();
            }
        }
    }

    /** One tick of one demo; false once it should end (a puppet or the target is gone). */
    private static boolean tick(Demo demo) {
        NpcEntity cleric = demo.cleric;
        if (cleric.isRemoved() || !cleric.isAlive()
                || (demo.dummy != null && (demo.dummy.isRemoved() || !demo.dummy.isAlive()))) {
            if (demo.dummy != null && !demo.dummy.isRemoved()) demo.dummy.discard();
            return false;
        }
        Entity found = demo.level.getEntity(demo.targetId);
        if (!(found instanceof LivingEntity target) || !target.isAlive()) return false;
        face(cleric, target);
        if (demo.dummy != null) face(demo.dummy, cleric);

        if (demo.pause > 0) {
            demo.pause--;
            return true;
        }
        if (demo.ticks == 0) {
            // A fresh throw: the orb on the palm, the clip from its first frame.
            cleric.setHoldingOrb(true);
            cleric.startWorkAnimation(VillagerAnimationState.CAST, ClericBalance.castSpeed(demo.skill));
            if (demo.dummy != null) demo.dummy.setHealth(demo.dummy.getMaxHealth() * 0.4f);
        }
        demo.ticks++;
        if (!demo.released && demo.ticks >= ClericBalance.releaseTicks(demo.skill)) {
            demo.released = true;
            cleric.setHoldingOrb(false);
            HealingOrbEntity.throwAt(demo.level, cleric, HealingCast.handPosition(cleric), target,
                    ClericCare.blessing(demo.skill), e -> e instanceof NpcEntity && e != cleric, landing -> {});
        }
        if (demo.ticks >= ClericBalance.castTicks(demo.skill)) {
            // Back to standing between throws, so each one shows the blend out of
            // the throw and the blend into the next (cast_controller).
            cleric.setAnimation(VillagerAnimationState.IDLE);
            demo.ticks = 0;
            demo.released = false;
            demo.pause = PAUSE_TICKS;
        }
        return true;
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
