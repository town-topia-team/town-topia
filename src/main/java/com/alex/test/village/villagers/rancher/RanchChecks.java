package com.alex.test.village.villagers.rancher;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageDays;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ButcheryBuilding;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.villagers.butcher.behavior.ButcherActivity;
import com.alex.test.village.villagers.butcher.settings.ButcherSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * The server's half of the Ranch window's check list (round 6 of
 * docs/plans/2026-10-24-ranch-window.md): where the pen's extra animals go (the Butchery the
 * Butcher would pick) and how many of each kind the Butchers keep. The gate, half steps, grass and
 * dark tiles are checked by the screen from the client's world. Reads only.
 */
public final class RanchChecks {

    /** Where the extra animals go, as {@code ButcherActivity.placeFor} picks. */
    public enum Link {
        /** No Butchery in the village. */
        NO_BUTCHERY,
        /** Butcheries, but none with a chopping block the butcher can work at. */
        NO_CHOPPING_BLOCK,
        /** The nearest working one is beyond {@link ButcherActivity#MAX_BUTCHERY_DIST} of the gate. */
        TOO_FAR,
        /** The nearest working one is near enough. */
        OK
    }

    /** Whether the village has a Butcher near a player, and whether one has its work on. */
    public enum Butchers { NONE, ALL_OFF, WORKING }

    /**
     * {@code distance}: blocks from the gate to that Butchery's kill spot (0 when none);
     * {@code paused}: it is paused for the day after failed walks; {@code keep}: per kind of
     * {@link RanchStatus#KINDS}, the fewest adults a working Butcher allowed to cull it keeps, -1 when
     * no working Butcher culls that kind.
     */
    public record Report(Link link, int distance, boolean paused, Butchers butchers, List<Integer> keep) {}

    public static final Report NONE = new Report(Link.NO_BUTCHERY, 0, false, Butchers.NONE, List.of());

    private RanchChecks() {}

    public static Report compute(ServerLevel level, VillageManager village, RanchBuilding ranch) {
        BlockPos gate = ranch.getGatePos();
        boolean anyButchery = false;
        for (Building building : village.getBuildings()) {
            if (building instanceof ButcheryBuilding butchery && butchery.isValid()) anyButchery = true;
        }
        ButcheryBuilding nearest = ButcherActivity.nearestWorkingButchery(village, gate);
        Link link;
        int distance = 0;
        boolean paused = false;
        if (nearest == null || nearest.killSpot() == null) {
            link = anyButchery ? Link.NO_CHOPPING_BLOCK : Link.NO_BUTCHERY;
        } else {
            double dist = Math.sqrt(nearest.killSpot().distSqr(gate));
            distance = (int) Math.round(dist);
            link = dist > ButcherActivity.MAX_BUTCHERY_DIST ? Link.TOO_FAR : Link.OK;
            paused = nearest.isPaused(VillageDays.dayIndex(level.getDayTime()));
        }

        Butchers butchers = Butchers.NONE;
        int[] keep = new int[RanchStatus.KINDS.size()];
        java.util.Arrays.fill(keep, -1);
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.getProfession() != Profession.BUTCHER) continue;
            ProfessionSettings settings = ProfessionSettings.of(npc, ButcherSettingsSchema.SCHEMA);
            if (!settings.getBool(ButcherSettingsSchema.WORK_ENABLED)) {
                if (butchers == Butchers.NONE) butchers = Butchers.ALL_OFF;
                continue;
            }
            butchers = Butchers.WORKING;
            int keeps = ButcherActivity.keepAdults(settings);
            for (int i = 0; i < keep.length; i++) {
                if (!ButcherActivity.isCullable(settings, RanchStatus.KINDS.get(i))) continue;
                keep[i] = keep[i] < 0 ? keeps : Math.min(keep[i], keeps);
            }
        }
        List<Integer> keeps = new ArrayList<>();
        for (int value : keep) keeps.add(value);
        return new Report(link, distance, paused, butchers, List.copyOf(keeps));
    }
}
