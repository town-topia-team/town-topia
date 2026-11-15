package com.alex.test.village.raid;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The kidnappers' choices: who they try to take, and the mark on the raider
 * leading a captive (the guards go for it first).
 */
public final class RaidKidnap {

    /** On a raider leading a captive away. */
    private static final String CAPTOR_TAG = "dynamicvillages_captor";
    /** The snatcher only goes for villagers this close (48 blocks). */
    private static final double REACH_SQ = 48.0 * 48.0;

    private RaidKidnap() {}

    /**
     * The villager the snatcher goes for: a grown villager outside (not in a
     * building, not hiding), never a child or the Mayor, as far from every
     * guard as can be, and not too far from the snatcher. Null when nobody is
     * out.
     */
    @Nullable
    static NpcEntity pickVictim(VillageManager village, Mob snatcher) {
        List<NpcEntity> defenders = new ArrayList<>();
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc != null && npc.isAlive() && model.brain() != null && model.brain().profession().isDefender()) {
                defenders.add(npc);
            }
        }
        NpcEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || model.brain() == null) continue;
            Profession job = model.brain().profession();
            if (job == Profession.CHILD || job.isDefender() || model.brain().isTakingShelter()) continue;
            if (npc.isSleeping() || model.isBrainSuspended()) continue;
            double toSnatcher = npc.distanceToSqr(snatcher);
            if (toSnatcher > REACH_SQ || insideBuilding(village, npc)) continue;
            double nearestGuard = 64.0 * 64.0;
            for (NpcEntity guard : defenders) nearestGuard = Math.min(nearestGuard, guard.distanceToSqr(npc));
            double score = Math.sqrt(nearestGuard) - 0.3 * Math.sqrt(toSnatcher);
            if (score > bestScore) {
                bestScore = score;
                best = npc;
            }
        }
        return best;
    }

    private static boolean insideBuilding(VillageManager village, NpcEntity npc) {
        for (Building building : village.getBuildings()) {
            if (building.isInRoom(npc.blockPosition())) return true;
        }
        return false;
    }

    static void markCaptor(Entity raider, boolean leading) {
        if (leading) raider.getPersistentData().putBoolean(CAPTOR_TAG, true);
        else raider.getPersistentData().remove(CAPTOR_TAG);
    }

    /** A raider leading a captive away: the most important target in the village. */
    public static boolean isCaptor(Entity raider) {
        return raider.getPersistentData().getBoolean(CAPTOR_TAG);
    }

    /**
     * Villagers held by kidnappers right now, by NPC id. Not saved: a restart
     * lets them go, like their suspended minds.
     */
    private static final java.util.Set<java.util.UUID> CAPTIVES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void markCaptive(java.util.UUID npcId, boolean held) {
        if (npcId == null) return;
        if (held) CAPTIVES.add(npcId);
        else CAPTIVES.remove(npcId);
    }

    /** The server stops: the next world starts with nobody held. */
    static void clearCaptives() {
        CAPTIVES.clear();
    }

    /** A villager the kidnappers hold: their prize, so monsters leave it alone. */
    public static boolean isCaptive(Entity entity) {
        return !CAPTIVES.isEmpty() && entity instanceof NpcEntity npc && CAPTIVES.contains(npc.getNpcId());
    }
}
