package com.alex.test.village.stats;

import com.alex.test.entity.NpcEntity;
import net.minecraft.world.Difficulty;

/** Health rules that are driven by villager stats. */
public final class HealthSystem {

    /** Ticks between two starving hits on Hard: dead 2.5 min after hunger 0 (30 HP). */
    private static final int STARVE_INTERVAL_HARD = 100;
    /** Normal: dead in 5 min. */
    private static final int STARVE_INTERVAL_NORMAL = 200;
    /** Easy, and Peaceful down to its floor: dead in 10 min on Easy. */
    private static final int STARVE_INTERVAL_EASY = 400;
    /** While the player is away starving runs at half speed (user 10-19). */
    private static final int AWAY_PACE_FACTOR = 2;

    private int starveTimer;

    /**
     * Starvation: half a heart at the difficulty's pace while hunger is 0
     * ({@link #starveDamageIntervalTicks}). It kills on Easy, Normal and Hard;
     * Peaceful stops at half a heart. Asleep ({@code resting}: in bed or on a
     * Town Hall spot) the hit is taken quietly, so it does not wake the
     * villager: only the deadly last hit goes through {@code hurt}, for the
     * death message and the village's bookkeeping.
     *
     * <p>Hunger above 0 again (a meal, live or away) gives back the cleric
     * heals a starving villager may have ({@link VillagerStats#starvingHealsLeft}).
     */
    public void tick(VillagerStats stats, NpcEntity npc, boolean resting) {
        if (stats.hunger > 0f) {
            starveTimer = 0;
            stats.starvingHealsLeft = VillagerStats.STARVING_HEALS;
            return;
        }
        if (++starveTimer >= starveDamageIntervalTicks(npc)) {
            starveTimer = 0;
            float floor = starvationHealthFloor(npc);
            float health = npc.getHealth();
            if (health > floor) {
                if (resting && health - 1f > 0f) {
                    npc.setHealth(Math.max(floor, health - 1f));
                    return;
                }
                npc.hurt(npc.damageSources().starve(), 1f);
                if (npc.isAlive() && npc.getHealth() < floor) {
                    npc.setHealth(floor);
                }
            }
        }
    }

    /**
     * Ticks between one starving hit and the next, by difficulty: Hard 100
     * (dead in 2.5 min), Normal 200 (5 min), Easy and Peaceful 400 (10 min).
     */
    public static int starveDamageIntervalTicks(NpcEntity npc) {
        return switch (npc.level().getDifficulty()) {
            case HARD -> STARVE_INTERVAL_HARD;
            case NORMAL -> STARVE_INTERVAL_NORMAL;
            default -> STARVE_INTERVAL_EASY;
        };
    }

    /**
     * The same, while the player is away: half speed. Exposed for the away
     * calculator ({@code economy/away/AwayHealth}), which turns ticks at zero hunger
     * into starving hits with it.
     */
    public static int awayStarveDamageIntervalTicks(NpcEntity npc) {
        return starveDamageIntervalTicks(npc) * AWAY_PACE_FACTOR;
    }

    /** Lowest health starvation can take a villager to: 0 (death) on Easy,
     *  Normal and Hard; Peaceful stops at 1 HP (half a heart). Until 10-19
     *  this copied the player's rule (only Hard could kill). */
    public static float starvationHealthFloor(NpcEntity npc) {
        Difficulty difficulty = npc.level().getDifficulty();
        return difficulty == Difficulty.PEACEFUL ? 1f : 0f;
    }

    /** The dawn heal to full. A starving villager (hunger 0) gets none: the
     *  heal used to wipe out a night of starving every morning. */
    public void onNightEnded(VillagerStats stats, NpcEntity npc) {
        if (stats.hunger <= 0f) return;
        npc.setHealth(npc.getMaxHealth());
    }
}
