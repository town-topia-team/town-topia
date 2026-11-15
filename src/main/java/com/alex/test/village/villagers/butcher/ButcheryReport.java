package com.alex.test.village.villagers.butcher;

import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ButcheryBuilding;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.villagers.butcher.behavior.ButcherActivity;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** The Butchery's rows on its page in the Mayor's buildings tab: "label\tvalue". */
public final class ButcheryReport {

    private ButcheryReport() {}

    public static List<String> rows(VillageManager manager, ButcheryBuilding butchery) {
        List<String> rows = new ArrayList<>();
        rows.add("Chopping block\t" + (butchery.canButcher() ? "Ready"
                : butchery.firstChoppingBlock() == null ? "Needs a stripped log"
                : "Needs open floor beside it"));
        rows.add("Grindstone\t" + (butchery.getGrindstone() != null ? "Yes" : "None (optional)"));
        rows.add("Ranch\t" + ranchLine(manager, butchery));
        return rows;
    }

    private static String ranchLine(VillageManager manager, ButcheryBuilding butchery) {
        BlockPos from = butchery.killSpot() != null ? butchery.killSpot() : butchery.getDoorPos();
        double best = Double.MAX_VALUE;
        for (Building building : manager.getBuildings()) {
            if (building instanceof RanchBuilding ranch && ranch.isValid()) {
                best = Math.min(best, Math.sqrt(from.distSqr(ranch.getGatePos())));
            }
        }
        if (best == Double.MAX_VALUE) return "No Ranch yet";
        if (best > ButcherActivity.MAX_BUTCHERY_DIST) {
            return "Too far: " + (int) best + " blocks (max " + (int) ButcherActivity.MAX_BUTCHERY_DIST + ")";
        }
        return (int) best + " blocks from the gate";
    }
}
