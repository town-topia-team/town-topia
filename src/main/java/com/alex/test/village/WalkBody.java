package com.alex.test.village;

import com.alex.test.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.PathfinderMob;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The body a village walk drives (round 1 of docs/plans/2026-10-18-raider-map-walking.md). The
 * walker ({@link FollowWalk}, {@link LadderLeg}, {@link FollowRoute}, {@link RouteDoorHandler})
 * takes any {@link PathfinderMob}; this is the one place that tells a villager or traveller
 * ({@link NpcEntity}) from another mob, such as a raider.
 *
 * <p>A villager gets every call exactly as before. Another mob has no stair assist, ladder hold,
 * end-of-walk ease or hurry gait, so those calls do nothing; its pace is told from the walk's own
 * speed (a raider walks at 1.0 and up, above the walker's hurried speed). It never teaches the
 * village map (failed steps, bumped columns) and leaves the doors it opens standing open, as
 * Minecraft's raiders do; fence gates it still shuts behind it.
 */
public final class WalkBody {

    private WalkBody() {}

    /** Steps along a stair instead of hopping it, for this many ticks (villagers only). */
    public static void stairAssist(PathfinderMob mob, int ticks) {
        if (mob instanceof NpcEntity npc) npc.enableStairStepAssist(ticks);
    }

    /** Ends the stair assist at once (villagers only). */
    public static void cancelStairAssist(PathfinderMob mob) {
        if (mob instanceof NpcEntity npc) npc.cancelStairStepAssist();
    }

    /** The rung a ladder leg is climbing toward, null for none (villagers only). */
    public static void ladderTarget(PathfinderMob mob, @Nullable BlockPos rung) {
        if (mob instanceof NpcEntity npc) npc.setLadderClimbTarget(rung);
    }

    /** Slows the walk's last blocks to this share of its pace (villagers only). */
    public static void walkEndEase(PathfinderMob mob, float pace) {
        if (mob instanceof NpcEntity npc) npc.setWalkEndEase(pace);
    }

    /** True when the villager is hurrying (its run gait). Another mob: false, see the class note. */
    public static boolean isRunning(PathfinderMob mob) {
        return mob instanceof NpcEntity npc && npc.isRunning();
    }

    /**
     * True when the walk may teach the village map: mark a step it could not make failed, or keep
     * straightened lines off a column it bumped into. Raiders fight in the streets; what they
     * get stuck on must not change the villagers' routes.
     */
    public static boolean teachesMap(PathfinderMob mob) {
        return mob instanceof NpcEntity;
    }

    /** True when the doors this body opens on its route are shut behind it. Fence gates always are. */
    public static boolean shutsDoorsBehind(PathfinderMob mob) {
        return mob instanceof NpcEntity;
    }

    /** The first 8 characters of the id the logs print: a villager's own id, else the body's UUID. */
    public static String logId(PathfinderMob mob) {
        UUID id = mob instanceof NpcEntity npc && npc.getNpcId() != null ? npc.getNpcId() : mob.getUUID();
        return id.toString().substring(0, 8);
    }

    /** What the logs call it: a villager's job, else the mob's kind ("pillager"). */
    public static String logRole(PathfinderMob mob) {
        if (mob instanceof NpcEntity npc) return String.valueOf(npc.getProfession());
        return BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
    }
}
