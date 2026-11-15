package com.alex.test.village;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.util.StringJoiner;

/**
 * Counts the walks still handed to Minecraft's own pathfinder by villagers and
 * travellers (step 2 of docs/plans/2026-10-18-vanilla-pathfinder-leftovers.md).
 * About once a minute, with the next walk counted after the minute is up, one
 * {@code [VanillaNav]} line says how many each place asked for. Nothing is
 * written while none are asked for. Reads only: nothing here steers.
 *
 * <p>Every place is counted where it asks Minecraft for a path, so a chase
 * re-planned every few ticks counts once per re-plan.
 */
public final class VanillaNavCount {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One minute. */
    private static final long LINE_TICKS = 1200L;

    public enum Site {
        /** Guard or captain closing on a mob it can see (GuardFootwork.chase). */
        CHASE("chase"),
        /** Guard stepping back from an axe or a creeper (GuardFootwork.backOff). */
        STEP_BACK("stepback"),
        /** Guard getting clear of a creeper with no spot straight back (GuardFootwork.evade). */
        CREEPER_AWAY("creeper-away"),
        /** Hurt defender or scared villager running away in the open (DefenderRetreat AWAY). */
        RETREAT_AWAY("retreat-away"),
        /** Straight run to a building the map can't reach (DefenderRetreat DIRECT). */
        RETREAT_STRAIGHT("retreat-straight"),
        /** Archer running to its arrows (ArrowRecovery). */
        ARROWS("arrows"),
        /** Traveller walking to its camp tile (TravelerNight). */
        CAMP("camp"),
        /** Traveller strolling round the camp fire (TravelerNight). */
        STROLL("stroll"),
        /** Traveller walking into the tavern (TravelerNight). */
        TAVERN("tavern"),
        /** Traveller walking to a guest bed (TravelerNight). */
        GUEST_BED("guest-bed"),
        /** Nomad walking to its Town Hall tile inside the village (NomadLogic). */
        NOMAD("nomad"),
        /** Nomad walking in from outside the village (NomadLogic). */
        NOMAD_OFF_MAP("nomad-off-map"),
        /** Merchant walking in while it or its stand is off the map (MerchantLogic). */
        MERCHANT_OFF_MAP("merchant-off-map"),
        /** Traveller walking home off the map, or afloat (AbstractTravelerEntity). */
        HOME("home"),
        /** Traveller's sidestep after no progress home (AbstractTravelerEntity). */
        HOME_SIDESTEP("home-sidestep"),
        /** Traveller fleeing outside the village (TravelerThreatResponse). */
        WILD_FLEE("wild-flee"),
        /** Kidnapped villager led to the raid camp (RaidManager). */
        CAPTIVE("captive");

        final String label;

        Site(String label) { this.label = label; }
    }

    private static final int[] COUNTS = new int[Site.values().length];
    /** Walks counted since the last line. */
    private static int pending;
    private static long windowStart = Long.MIN_VALUE;

    private VanillaNavCount() {}

    /** {@code who} asked Minecraft's pathfinder for a walk at {@code site}. Server thread only. */
    public static void note(Site site, Entity who) {
        long now = who.level().getGameTime();
        // A quiet spell (or a new world) starts a fresh minute at this walk.
        if (pending == 0 || now < windowStart) windowStart = now;
        COUNTS[site.ordinal()]++;
        pending++;
        if (now - windowStart < LINE_TICKS) return;
        StringJoiner line = new StringJoiner(", ");
        for (Site s : Site.values()) {
            int n = COUNTS[s.ordinal()];
            if (n == 0) continue;
            line.add(s.label + " " + n);
            COUNTS[s.ordinal()] = 0;
        }
        LOGGER.info("[VanillaNav] {} Minecraft pathfinder walks in the last {} s: {}",
                pending, (now - windowStart) / 20, line);
        pending = 0;
    }
}
