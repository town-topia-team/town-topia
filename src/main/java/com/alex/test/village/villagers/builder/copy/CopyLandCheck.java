package com.alex.test.village.villagers.builder.copy;

import com.alex.test.DynamicVillages;
import com.alex.test.village.VillageManager;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.MineBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * What the copy's land test (docs/plans/2026-10-23-builder-copy-placed-blocks-design.md) makes of
 * one block, for {@code /villager copycheck}: today's rule, the test's answer and why, and what a copy
 * of its building takes now with the {@code /villager walkfeel copy*} switches as they are. Read only.
 */
public final class CopyLandCheck {

    /** Fresh top-height answers timed for round 0, to compare with whole columns. */
    private static final int TOP_HEIGHT_TIMED = 10;

    private CopyLandCheck() {}

    /**
     * @param building    the registered building the block belongs to, or null
     * @param mine        that building is a mine: its copies keep today's rule
     * @param lowestFloor one below its lowest room tile, or {@link PlacedJudge#NO_FLOOR}
     * @param doorFloor   the floor under its door (the copy's paths rule), or {@link PlacedJudge#NO_FLOOR}
     * @param bareTop     the top of the bare land in the block's column, or {@link LandTrust#NONE}
     * @param builtToday  today's rule takes it
     * @param remembered  a player or a Builder placed it since round 5 ({@link PlacedLand})
     * @param rules       the switches now; {@code logs} is {@code copylogs}
     * @param builtNow    what a copy of its building takes now (today's rule when the copy has no land test)
     * @param msPerColumn time the generator took per whole column; msPerTopHeight per top height only (-1: none)
     */
    public record Inspection(BlockPos pos, String blockName, LandView.Group group, @Nullable String building,
                             boolean mine, int lowestFloor, int doorFloor, LandView.Bare bare, int bareTop,
                             boolean builtToday, boolean remembered, PlacedJudge.Rules rules, boolean logs,
                             PlacedJudge.Judgement judgement, boolean builtNow, LandTrust.Vote vote,
                             LandTrust.Thresholds thresholds, int bareColumns, double msPerColumn,
                             double msPerTopHeight, double totalMs) {}

    public static Inspection inspect(ServerLevel level, @Nullable VillageManager village, BlockPos pos) {
        long started = System.nanoTime();
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        Building building = village == null ? null : buildingAt(village, pos);
        boolean mine = building instanceof MineBuilding;
        Set<BlockPos> tiles = building != null && !CopyLand.excluded(building) ? building.getNavMeshBlocks() : Set.of();
        boolean byFloor = !tiles.isEmpty();

        BuildingShapeScanner.Area area;
        if (village == null) {
            int reach = BuildingShapeScanner.MAX_REACH;
            area = new BuildingShapeScanner.Area(x - reach, Math.max(level.getMinBuildHeight(), y - reach), z - reach,
                    x + reach, Math.min(level.getMaxBuildHeight() - 1, y + reach), z + reach, List.of(), List.of(),
                    null, null, null, null, Integer.MIN_VALUE);
        } else if (building == null) {
            area = BuildingCopier.area(level, village, null, pos, false, null);
        } else {
            BlockPos lo = building.shellMin(), hi = building.shellMax();
            BlockPos centre = new BlockPos((lo.getX() + hi.getX()) / 2, (lo.getY() + hi.getY()) / 2,
                    (lo.getZ() + hi.getZ()) / 2);
            area = BuildingCopier.area(level, village, building, centre, true, byFloor ? tiles : null);
        }
        BuildingShapeScanner scanner = new BuildingShapeScanner(level, area);
        LevelLandView view = new LevelLandView(level, scanner, area);

        int lowestFloor = CopyLand.lowestFloor(tiles);
        int doorFloor = building == null || building.getDoorPos() == null
                ? PlacedJudge.NO_FLOOR : building.getDoorPos().getY() - 1;

        // The trust vote: the land round the building (or the block) against the bare land. Always
        // counted here, even with copytrust off, so the numbers can be compared.
        int[] box = building == null ? new int[] {x, y, z, x, y, z} : BuildingCopier.roofBox(building);
        LandTrust.Thresholds thresholds = LandTrust.Thresholds.DEFAULT;
        List<LandTrust.Sample> samples = CopyLand.samples(view, village, box);
        LandTrust.Vote vote = LandTrust.vote(samples, thresholds);

        PlacedJudge.Rules rules = CopyLand.rules();
        boolean logs = WalkFeel.copyLogs;
        boolean trusted = !rules.trust() || vote.trusted();
        BlockState state = scanner.state(x, y, z);
        String blockName = state == null ? "not loaded" : state.getBlock().getName().getString();
        LandView.Group group = view.group(x, y, z);
        LandView.Bare bare = view.bare(x, y, z);
        int bareTop = view.bareTop(x, z);
        boolean builtToday = scanner.isBuiltToday(x, y, z);
        boolean remembered = view.remembered(x, y, z);
        PlacedJudge.Judgement judgement = PlacedJudge.judge(view, x, y, z, lowestFloor, trusted, rules);
        boolean builtNow = builtToday;
        if (byFloor && (rules.any() || logs)) {
            scanner.useLandTest(new BuildingShapeScanner.LandTest(view, lowestFloor, trusted, rules, logs));
            builtNow = scanner.isBuilt(x, y, z);
        }

        double msPerColumn = view.bareColumns == 0 ? -1 : view.bareNanos / 1e6 / view.bareColumns;
        int columns = view.bareColumns;
        double msPerTopHeight = view.msPerTopHeight(samples, TOP_HEIGHT_TIMED);
        double totalMs = (System.nanoTime() - started) / 1e6;

        for (LandTrust.Sample sample : samples) {
            DynamicVillages.LOGGER.debug("[CopyCheck] sample {} {}: surface {}, bare top {}{}", sample.x(), sample.z(),
                    show(sample.surfaceY()), show(sample.bareTopY()),
                    sample.usable() ? ", gap " + sample.gap() : " (unusable)");
        }
        DynamicVillages.LOGGER.debug("[CopyCheck] {} {}: {} {}{} in {}, lowest floor {}, bare {} (top {}), today {}, "
                        + "test {} {}, remembered {}, copy now {}; rules {} logs {}; vote {}: {} usable of {} looked, "
                        + "{} within {}, {} higher, {} lower, gaps {}; generator {} columns at {} ms, top height {} ms; "
                        + "whole check {} ms",
                pos.toShortString(), blockName, group, building == null ? "-" : building.getDisplayName(),
                mine ? " (mine)" : "", village == null ? "no village" : "village", show(lowestFloor), bare,
                show(bareTop), builtToday ? "taken" : "left out", judgement.verdict(), judgement.reason(), remembered,
                builtNow ? "taken" : "left out", rules, logs, vote.trusted() ? "trusted" : "not trusted",
                vote.usable(), vote.looked(), vote.within(), thresholds.tolerance(), vote.higher(), vote.lower(),
                Arrays.toString(vote.byGap()), columns, String.format("%.3f", msPerColumn),
                String.format("%.3f", msPerTopHeight), String.format("%.1f", totalMs));
        return new Inspection(pos, blockName, group, building == null ? null : building.getDisplayName(), mine,
                lowestFloor, doorFloor, bare, bareTop, builtToday, remembered, rules, logs, judgement, builtNow,
                vote, thresholds, columns, msPerColumn, msPerTopHeight, totalMs);
    }

    /** The building whose space the block is in or nearest to, within the copy's reach of it; else null. */
    @Nullable
    private static Building buildingAt(VillageManager village, BlockPos pos) {
        Building best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Building building : village.getBuildings()) {
            int[] box = BuildingCopier.roofBox(building);
            int reach = BuildingCopier.OWN_REACH;
            if (pos.getX() < box[0] - reach || pos.getX() > box[3] + reach || pos.getZ() < box[2] - reach
                    || pos.getZ() > box[5] + reach || pos.getY() < box[1] - 3 || pos.getY() > box[4] + 3) {
                continue;
            }
            int distance = Math.max(Math.max(box[0] - pos.getX(), pos.getX() - box[3]),
                    Math.max(box[2] - pos.getZ(), pos.getZ() - box[5]));
            if (distance < bestDistance) {
                best = building;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static String show(int y) {
        return y == LandTrust.NONE ? "-" : Integer.toString(y);
    }
}
