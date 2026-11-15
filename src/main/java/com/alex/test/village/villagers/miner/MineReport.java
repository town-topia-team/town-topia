package com.alex.test.village.villagers.miner;

import java.util.ArrayList;
import java.util.List;

/**
 * The lines that say how a mine's tunnels run, as "label\tvalue" rows: the
 * Mayor's mine card and the Mine screen show the same ones, read from the
 * same plan the miner digs, so neither promises tunnel the miner will not dig.
 */
public final class MineReport {
    private MineReport() {}

    public static List<String> tunnelRows(MineShaftPlan plan) {
        List<String> rows = new ArrayList<>();
        MineShaftPlan.Stop stop = plan.corridorStop();
        String end = stop == MineShaftPlan.Stop.BORDER ? " to the border"
                : stop == MineShaftPlan.Stop.NEXT_MINE ? " to the next mine"
                : plan.corridorLength() < MineShaftPlan.MAX_MAIN_LENGTH ? " (shortened)" : "";
        rows.add("Main tunnel\t" + plan.corridorLength() + " blocks" + end);
        rows.add("Side tunnels\t" + (plan.leftLength() == plan.rightLength()
                ? plan.leftLength() + " blocks each side"
                : plan.leftLength() + " left, " + plan.rightLength() + " right"));
        int byBorder = plan.branchesCut(MineShaftPlan.Stop.BORDER);
        int byMine = plan.branchesCut(MineShaftPlan.Stop.NEXT_MINE);
        if (byBorder + byMine > 0) {
            rows.add("Cut short\t" + (byMine == 0 ? byBorder + " by the border"
                    : byBorder == 0 ? byMine + " by the next mine"
                    : byMine + " by the next mine, " + byBorder + " by the border"));
        }
        return rows;
    }
}
