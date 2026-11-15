package com.alex.test.village.villagers.archer.behavior;

import com.alex.test.village.VillageManager;
import com.alex.test.village.brain.Activity.Context;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The storage room's Stock tab orders for arrows and bows (see {@link StockReserves}):
 * the next one this archer can make now, as a trip to make it and put it into
 * storage ({@link FletchArrowsActivity#forOrder}). Archers take orders in quiet
 * duty time and in their off-duty range time, before practice.
 */
final class ArcherOrders {

    /** Most arrows one order trip makes: a quiver's worth. Bows one at a time. */
    private static final int ARROWS_PER_TRIP = 32;

    private ArcherOrders() {}

    /** The next order trip for this archer, its units claimed; null when there is none it can make. */
    @Nullable
    static FletchArrowsActivity next(Context ctx) {
        StockReserves reserves = StockReserves.get(ctx.level().getServer());
        UUID self = ctx.model().npcId;
        VillageManager village = ctx.mgr();
        ServerLevel level = ctx.level();
        for (StockReserves.Want want : reserves.wants(village, level, Profession.ARCHER, self)) {
            Item item = want.item();
            FletchArrowsActivity.Product product = item == Items.BOW
                    ? FletchArrowsActivity.Product.BOW : FletchArrowsActivity.Product.ARROWS;
            int items = Math.min(want.units(), product == FletchArrowsActivity.Product.BOW ? 1 : ARROWS_PER_TRIP);
            if (items <= 0 || !FletchArrowsActivity.canMake(ctx, product, items)) continue;
            UUID villageId = village.getVillageId();
            UUID row = want.row();
            if (row != null) reserves.claim(villageId, row, self, items, level.getGameTime());
            return FletchArrowsActivity.forOrder(product, items,
                    stored -> {
                        reserves.onMade(villageId, row, self, item, stored);
                        reserves.onDelivered(village, level, item, stored);
                    },
                    () -> reserves.release(villageId, row, self));
        }
        return null;
    }
}
