package com.alex.test.village.economy.away;

import com.alex.test.village.VillageManager;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.villagers.blacksmith.behavior.BlacksmithActivity;
import com.alex.test.village.villagers.enchanter.BookOrders;
import com.alex.test.village.villagers.enchanter.behavior.EnchanterActivity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Stock tab once orders the smiths forged while away (round 9), and the
 * enchanted books the enchanters wrote for the player's book orders (round 10),
 * told to the Stock tab only at WRITE, as the kitchen's ({@link AwayKitchen#writeTo}):
 * a preview, or a run begun again after a restart, changes no order. An order kept
 * in the smith's pack is made and on its way (live, right after the anvil; the
 * smith banks it once you are back); one put in storage is delivered too. An
 * ordered book is in storage already, marked as the player's when it was written.
 */
public final class AwayOrders implements BlacksmithActivity.AwayOrderBook, EnchanterActivity.AwayBookOrders {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final class Made {
        final Item item;
        final UUID maker;
        int count;
        int stored;

        Made(Item item, UUID maker) {
            this.item = item;
            this.maker = maker;
        }
    }

    private final Map<UUID, Made> rows = new LinkedHashMap<>();
    /** Book order id -> books written and marked for it. */
    private final Map<UUID, Integer> books = new LinkedHashMap<>();

    @Override
    public int madeAway(UUID row) {
        Made made = rows.get(row);
        return made == null ? 0 : made.count;
    }

    @Override
    public void made(UUID row, Item item, UUID maker, int count, boolean stored) {
        if (count <= 0) return;
        Made made = rows.computeIfAbsent(row, key -> new Made(item, maker));
        made.count += count;
        if (stored) made.stored += count;
    }

    @Override
    public int booksAway(UUID order) {
        return books.getOrDefault(order, 0);
    }

    @Override
    public void bookWritten(UUID order) {
        books.merge(order, 1, Integer::sum);
    }

    /** WRITE, after the villagers (the pack's orders are in the real smith's pack now) and the chests. */
    void writeTo(ServerLevel level, VillageManager mgr) {
        if (!rows.isEmpty()) {
            StockReserves reserves = StockReserves.get(level.getServer());
            for (Map.Entry<UUID, Made> entry : rows.entrySet()) {
                Made made = entry.getValue();
                reserves.onMade(mgr.getVillageId(), entry.getKey(), made.maker, made.item, made.count);
                if (made.stored > 0) reserves.onDelivered(mgr, level, made.item, made.stored);
                LOGGER.info("[AwayBlacksmith] Stock tab order {}: {} {} forged while away ({} in storage, {} in the pack)",
                        entry.getKey().toString().substring(0, 8), made.count,
                        BuiltInRegistries.ITEM.getKey(made.item), made.stored, made.count - made.stored);
            }
        }
        if (!books.isEmpty()) {
            BookOrders orders = BookOrders.get(level.getServer());
            books.forEach((order, count) -> {
                orders.onMadeAway(mgr.getVillageId(), order, count);
                LOGGER.info("[AwayEnchanter] book order {}: {} book(s) written while away",
                        order.toString().substring(0, 8), count);
            });
        }
    }

    /** "2 forged, 1 book written", or "none". */
    String describe() {
        int units = 0;
        for (Made made : rows.values()) units += made.count;
        int written = 0;
        for (int count : books.values()) written += count;
        List<String> parts = new ArrayList<>();
        if (units > 0) parts.add(units + " forged");
        if (written > 0) parts.add(written + " book(s) written");
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }
}
