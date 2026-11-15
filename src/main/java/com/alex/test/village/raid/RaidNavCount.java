package com.alex.test.village.raid;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.util.StringJoiner;

/**
 * Round 0 of docs/plans/2026-10-18-raider-map-walking.md: counts the walks raiders and the
 * Warlord ask Minecraft's pathfinder for, how many of those found no path, and how often a
 * walking raider stood stuck. About once a minute, with the next count after the minute is up,
 * one {@code [RaidNav]} line; nothing is written while no raider walks. Reads only: nothing here
 * steers. Its own line, not {@code [VanillaNav]}: a raid asks hundreds of times a minute and
 * would drown the villagers' count.
 *
 * <p>Minecraft's own attack goals (crossbow, axe, bow, zombie) chase inside vanilla code and are
 * not counted. Since round 3 the line also counts raiders' walks on the village map
 * ({@link RaiderMapWalk}, switch {@code raidmap}), since round 4 their map walks toward a foe
 * ({@link RaidApproachGoal}, switch {@code raidchase}).
 */
public final class RaidNavCount {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One minute. */
    private static final long LINE_TICKS = 1200L;

    public enum Site {
        /** Raider marching into the village (RaidMarchGoal). */
        MARCH("march"),
        /** Looter walking to a container (RaidMarchGoal). */
        LOOT("loot"),
        /** Kidnapper going for a villager (RaidMarchGoal). */
        SNATCH("snatch"),
        /** Raider running home with goods, on retreat, or leading a captive (RaidMarchGoal). */
        HOME("home"),
        /** Scout walking back to its post (RaidScoutGoal). */
        SCOUT("scout"),
        /** Warlord walking to its target (WarlordSwingGoal). */
        WARLORD_CHASE("warlord-chase"),
        /** Warlord's detour walk after no way for 30 s (WarlordUnstuck). */
        WARLORD_DETOUR("warlord-detour"),
        /** Warlord's once-a-second "is there a way" test (WarlordUnstuck.canReach). */
        WARLORD_CHECK("warlord-check"),
        /** Round 5: a big body's stretch along its map route (WideMapWalk, switch raidbig). */
        BIG_LEG("big-body stretches"),
        /** Raider pathfinding round 5a: a short swim to a dry map tile (RaiderMapWalk, switch raidwater). */
        SWIM("swim ashore");

        final String label;

        Site(String label) { this.label = label; }
    }

    /** Round 3 (10-19): what raiders' walks on the village map did ({@code raidmap}). */
    enum MapEvent {
        PLANNED("planned"),
        /** Bug 10 (10-19, {@code raidreplan}): of those, planned toward someone moving while still walking. */
        PLANNED_AHEAD("of them planned while walking on"),
        NO_ROUTE("no route"),
        DONE("walked"),
        STUCK("stuck"),
        /** Two stucks in a row and Minecraft's path took over (bug B2, 10-23: not when another door was taken). */
        BACK_TO_MINECRAFT("back to Minecraft for 10 s"),
        /** Raider pathfinding round 4 ({@code raidnovanilla}): no way to the march door, another one taken. */
        OTHER_DOOR("another door after no way"),
        /** Round 4: no way to a looter's chest, looting given up. */
        LOOT_GIVEN_UP("chests given up, no way"),
        /** Round 5: the Warlord's "is there a way" test asked of the map. */
        WAY_CHECK("Warlord way checks");

        final String label;

        MapEvent(String label) { this.label = label; }
    }

    /** Round 4 (10-19): raiders walking toward a far or hidden foe on the map ({@code raidchase}). */
    enum FightEvent {
        STARTED("started"),
        HANDED("handed to the attack close up"),
        GAVE_UP("given to Minecraft for 10 s");

        final String label;

        FightEvent(String label) { this.label = label; }
    }

    private static final int[] MAP = new int[MapEvent.values().length];
    private static final int[] FIGHT = new int[FightEvent.values().length];
    private static final int[] ASKED = new int[Site.values().length];
    private static final int[] NO_PATH = new int[Site.values().length];
    /** Ten-second checks on walking raiders since the last line, and how many moved under a block. */
    private static int checks;
    private static int stuck;
    /** Asks and checks since the last line. */
    private static int pending;
    private static long windowStart = Long.MIN_VALUE;
    /** Round 5: big-body tile maps finished since the last line, their tiles and server time. */
    private static int tileMaps;
    private static int tileMapTiles;
    private static int tileMapTicks;
    private static double tileMapMillis;

    private RaidNavCount() {}

    /**
     * {@code who} asked Minecraft's pathfinder for a walk at {@code site}; {@code found} false
     * when it got no path (or one already walked). Server thread only.
     */
    public static void note(Site site, Entity who, boolean found) {
        ASKED[site.ordinal()]++;
        if (!found) NO_PATH[site.ordinal()]++;
        counted(who);
    }

    /** A raider's walk on the village map did {@code what}. */
    static void noteMap(MapEvent what, Entity who) {
        MAP[what.ordinal()]++;
        counted(who);
    }

    /** A raider's map walk toward its foe did {@code what}. */
    static void noteFight(FightEvent what, Entity who) {
        FIGHT[what.ordinal()]++;
        counted(who);
    }

    /** A big-body tile map ({@link com.alex.test.village.WideTiles}) was finished. */
    static void noteTileMap(com.alex.test.village.WideTiles tiles, net.minecraft.world.level.Level level) {
        tileMaps++;
        tileMapTiles += tiles.tileCount();
        tileMapTicks += tiles.slices();
        tileMapMillis += tiles.millis();
        counted(level.getGameTime());
    }

    /** A walking raider's ten-second check: {@code stood} true when it moved under a block. */
    static void noteCheck(Entity who, boolean stood) {
        checks++;
        if (stood) stuck++;
        counted(who);
    }

    private static void counted(Entity who) {
        counted(who.level().getGameTime());
    }

    private static void counted(long now) {
        // A quiet spell (or a new world) starts a fresh minute here.
        if (pending == 0 || now < windowStart) windowStart = now;
        pending++;
        if (now - windowStart < LINE_TICKS) return;
        StringJoiner line = new StringJoiner(", ");
        int asked = 0;
        for (Site s : Site.values()) {
            int n = ASKED[s.ordinal()];
            if (n == 0) continue;
            asked += n;
            int none = NO_PATH[s.ordinal()];
            line.add(none > 0 ? s.label + " " + n + " (" + none + " no path)" : s.label + " " + n);
            ASKED[s.ordinal()] = 0;
            NO_PATH[s.ordinal()] = 0;
        }
        StringJoiner map = new StringJoiner(", ");
        for (MapEvent e : MapEvent.values()) {
            int n = MAP[e.ordinal()];
            if (n > 0) map.add(n + " " + e.label);
            MAP[e.ordinal()] = 0;
        }
        StringJoiner fight = new StringJoiner(", ");
        for (FightEvent e : FightEvent.values()) {
            int n = FIGHT[e.ordinal()];
            if (n > 0) fight.add(n + " " + e.label);
            FIGHT[e.ordinal()] = 0;
        }
        String tileLine = tileMaps == 0 ? "none" : String.format(java.util.Locale.ROOT, "%d made (%d tiles, %.1f ms over %d ticks)",
                tileMaps, tileMapTiles, tileMapMillis, tileMapTicks);
        LOGGER.info("[RaidNav] {} Minecraft pathfinder asks in the last {} s: {}; map walks: {}; fight approaches on the map: {}; big-body tile maps: {}; walking raiders stood stuck {} of {} ten-second checks",
                asked, (now - windowStart) / 20, asked == 0 ? "none" : line, map.length() == 0 ? "none" : map,
                fight.length() == 0 ? "none" : fight, tileLine, stuck, checks);
        tileMaps = 0;
        tileMapTiles = 0;
        tileMapTicks = 0;
        tileMapMillis = 0.0;
        checks = 0;
        stuck = 0;
        pending = 0;
    }
}
