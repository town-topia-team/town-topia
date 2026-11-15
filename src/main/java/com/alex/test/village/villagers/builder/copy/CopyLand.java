package com.alex.test.village.villagers.builder.copy;

import com.alex.test.village.VillageManager;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.MineBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sets up the copy's land test ({@link BuildingShapeScanner.LandTest}) for one building, shared by
 * the copy and {@code /villager copycheck}. Rounds 2, 4, 5, 7 of
 * docs/plans/2026-10-23-builder-copy-placed-blocks-design.md.
 *
 * <p>Only a registered building with a detected floor gets it. Mines keep today's rule (their floor
 * runs on into the mountain, every block there is the world's). Guard posts are tokens, not
 * buildings: a click on one copies with today's rule, like any click outside a building.
 */
final class CopyLand {
    private CopyLand() {}

    /**
     * @param test  what the scanner uses
     * @param vote  the trust vote, or null when {@code copytrust} is off
     * @param view  the land, for its generator counts
     * @param setupMs time spent on the trust samples
     */
    record Setup(BuildingShapeScanner.LandTest test, @Nullable LandTrust.Vote vote, LevelLandView view,
                 double setupMs) {}

    /** Buildings whose copies always keep today's rule. */
    static boolean excluded(@Nullable Building building) {
        return building instanceof MineBuilding;
    }

    /** One below the lowest room tile, or {@link PlacedJudge#NO_FLOOR}. */
    static int lowestFloor(Set<BlockPos> tiles) {
        if (tiles.isEmpty()) return PlacedJudge.NO_FLOOR;
        int lowest = Integer.MAX_VALUE;
        for (BlockPos tile : tiles) lowest = Math.min(lowest, tile.getY());
        return lowest - 1;
    }

    /** The {@code /villager walkfeel copy*} switches as they are now. */
    static PlacedJudge.Rules rules() {
        return new PlacedJudge.Rules(WalkFeel.copyLand, WalkFeel.copySoil, WalkFeel.copyTrust, WalkFeel.copyMemory,
                WalkFeel.copyLookalikes);
    }

    /** Trust samples round {@code box} (a roof box), outside every building's box. */
    static List<LandTrust.Sample> samples(LevelLandView view, @Nullable VillageManager village, int[] box) {
        List<int[]> skip = new ArrayList<>();
        if (village != null) {
            for (Building other : village.getBuildings()) skip.add(BuildingCopier.roofBox(other));
        }
        return view.samples(box[0], box[2], box[3], box[5], skip, LandTrust.Thresholds.DEFAULT.minUsable());
    }

    /**
     * The land test for a copy of {@code building} from its floor {@code tiles}, or null when the copy
     * keeps today's rule: a mine, no detected floor, or every switch off.
     */
    @Nullable
    static Setup prepare(ServerLevel level, VillageManager village, Building building, Set<BlockPos> tiles,
                         BuildingShapeScanner scanner, BuildingShapeScanner.Area area) {
        if (excluded(building) || tiles.isEmpty()) return null;
        PlacedJudge.Rules rules = rules();
        boolean logs = WalkFeel.copyLogs;
        if (!rules.any() && !logs) return null;
        long started = System.nanoTime();
        LevelLandView view = new LevelLandView(level, scanner, area);
        LandTrust.Vote vote = null;
        if (rules.any() && rules.trust()) {
            vote = LandTrust.vote(samples(view, village, BuildingCopier.roofBox(building)),
                    LandTrust.Thresholds.DEFAULT);
        }
        boolean trusted = vote == null || vote.trusted();
        BuildingShapeScanner.LandTest test =
                new BuildingShapeScanner.LandTest(view, lowestFloor(tiles), trusted, rules, logs);
        return new Setup(test, vote, view, (System.nanoTime() - started) / 1e6);
    }

    /** For the {@code BUILDER copy} debug line: what the test changed, "" when the copy kept today's rule. */
    static String summary(@Nullable Setup setup) {
        if (setup == null) return "";
        BuildingShapeScanner.LandTest test = setup.test();
        LandTrust.Vote vote = setup.vote();
        String trust = vote == null ? "trust off"
                : (vote.trusted() ? "trusted" : "NOT trusted") + " " + vote.within() + "/" + vote.usable()
                + " (" + vote.higher() + " higher)";
        return String.format(Locale.ROOT,
                " (land: %s, +%d placed of which %d remembered, -%d world, +%d build logs, %d untrusted, "
                        + "generator %d columns, samples %.1f ms)",
                trust, test.placed, test.remembered, test.world, test.buildLogs, test.untrusted,
                setup.view().bareColumns, setup.setupMs());
    }

    /** The player's extra line when the land was not trusted and stone or dirt was left out because of it. */
    static String untrustedLine(@Nullable Setup setup) {
        if (setup == null || setup.vote() == null || setup.vote().trusted() || setup.test().untrusted == 0) return "";
        return "Some stone or dirt may be missing: this land was made by an older game version or a mod.";
    }
}
