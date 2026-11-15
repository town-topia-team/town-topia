package com.alex.test.village.villagers.archer;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * What storage keeps for the village's archers and never lets the tradesman
 * sell: a full quiver each, the makings of a few more crafts of arrows, and
 * a bow's worth of string twice over plus a spare bow.
 *
 * <p>The merchant asks for arrows, flint, feathers and string exactly when the
 * village has an archer (and for feathers when it has a rancher or a
 * teacher), so the tradesman used to sell the archers' ammunition down to
 * the ordinary 32 of each.
 */
public final class ArcherSupplies {

    /** Flint and feathers kept per archer: about one quiver's worth of crafts at a fletching table. */
    public static final int FLETCHING_KEEP_PER_ARCHER = 8;
    /** String kept per archer: two bows. */
    public static final int STRING_KEEP_PER_ARCHER = 6;
    public static final int BOWS_KEEP_PER_ARCHER = 1;

    private ArcherSupplies() {}

    /** How many of {@code item} storage holds back from sale for this village's archers. */
    public static int keptForArchers(VillageManager village, Item item) {
        if (village == null) return 0;
        if (item == Items.ARROW) {
            // Each archer's own quiver ("Arrows to carry").
            int quivers = 0;
            for (var model : village.getVillagers()) {
                NpcEntity npc = model.puppet();
                if (npc != null && npc.isAlive() && npc.getProfession() == Profession.ARCHER) {
                    quivers += ArcherBowUtils.quiverSize(npc);
                }
            }
            return quivers;
        }
        int perArcher = perArcher(item);
        if (perArcher <= 0) return 0;
        int archers = archersIn(village);
        return archers * perArcher;
    }

    private static int perArcher(Item item) {
        if (item == Items.ARROW) return ProfessionSupplyRules.ARCHER_ARROW_TARGET;
        if (item == Items.FLINT || item == Items.FEATHER) return FLETCHING_KEEP_PER_ARCHER;
        if (item == Items.STRING) return STRING_KEEP_PER_ARCHER;
        if (item == Items.BOW) return BOWS_KEEP_PER_ARCHER;
        return 0;
    }

    /** Living archers of the village. */
    public static int archersIn(VillageManager village) {
        int archers = 0;
        for (var model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc != null && npc.isAlive() && npc.getProfession() == Profession.ARCHER) archers++;
        }
        return archers;
    }
}
