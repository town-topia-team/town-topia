package com.alex.test.village.visitors;

import com.alex.test.entity.visitor.VisitorEntity;
import com.alex.test.village.VillageDays;
import com.alex.test.village.VillageManager;
import com.alex.test.village.economy.MarketMemory;
import com.alex.test.village.economy.MarketPrices;
import com.alex.test.village.villagers.enchanter.EnchantBooks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The merchant's prices, in one place: the trade screen, the tradesman
 * selling on the village's behalf, the sell box and the enchanter's book
 * sale all go through here, so they can never quote different numbers for
 * the same good.
 *
 * <p>A good's base price is whole emeralds for a full stack, from the data
 * file ({@link MarketPrices}), never above {@link MarketPrices#maxPrice()}
 * unless the file names the good itself (a diamond stack is 24).
 * Today's price is the base moved by recent sales, rounded to a whole number
 * of emeralds (2.4 is 2, 2.5 is 3) and kept in hundredths of an emerald,
 * "cents": 300 is 3 emeralds a stack. It never goes below one emerald. Goods
 * change hands in half-stack lots, each worth half the stack price; a half
 * of an odd price is paid in whole emeralds, rounding down and saving the
 * rest as change for the next sale ({@link MarketMemory#payOut}).
 */
public final class MerchantPricing {
    private MerchantPricing() {}

    /** Base emeralds for a full stack of {@code item}, from the price table. */
    public static int fullStackPrice(Item item) {
        return MarketPrices.basePrice(item);
    }

    /** Units in one lot of {@code item}: always half a stack (32, or 8 for eggs). */
    public static int lotSize(Item item) {
        return Math.max(1, new ItemStack(item).getMaxStackSize() / 2);
    }

    /** What one half-stack lot is worth at {@code stackCents} a full stack, in cents. */
    public static int lotCents(int stackCents) {
        return stackCents / 2;
    }

    /** "2.4", "3", "0.5": cents as emeralds, one decimal at most. */
    public static String format(int cents) {
        int tenths = Math.round(cents / 10f);
        return tenths % 10 == 0 ? Integer.toString(tenths / 10) : (tenths / 10) + "." + (tenths % 10);
    }

    // -------------------------------------------------------------------------
    // Live prices: the base moved by what the village sold lately
    // -------------------------------------------------------------------------

    /** The market memory's key for a good. */
    public static String marketKey(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** The village day prices fade on. */
    public static long today(ServerLevel level) {
        return VillageDays.dayIndex(level.getDayTime());
    }

    /**
     * Today's price in cents for a full stack of {@code item} sold to
     * {@code merchant} by {@code village}: the base moved by recent sales,
     * doubled for a founding visit's welcome prices, never above the table's
     * ceiling and never below one emerald.
     */
    public static int liveCents(ServerLevel level, VillageManager village, @Nullable VisitorEntity merchant,
                                Item item, int basePrice) {
        int cents = MarketMemory.get(level.getServer())
                .liveCents(village.getVillageId(), marketKey(item), basePrice, today(level));
        return welcome(merchant, cents, basePrice);
    }

    /** Today's price of {@code offer} in cents, see {@link #liveCents(ServerLevel, VillageManager, VisitorEntity, Item, int)}. */
    public static int liveCents(ServerLevel level, VillageManager village, @Nullable VisitorEntity merchant,
                                MerchantOffer offer) {
        return liveCents(level, village, merchant, MerchantCatalog.item(offer), offer.fullPrice());
    }

    /** Today's price in cents of every offer on {@code merchant}'s list, in list order. */
    public static List<Integer> liveCentsList(ServerLevel level, @Nullable VillageManager village,
                                              VisitorEntity merchant) {
        List<Integer> prices = new ArrayList<>(merchant.getOffers().size());
        for (MerchantOffer offer : merchant.getOffers()) {
            prices.add(village == null ? offer.fullPrice() * 100 : liveCents(level, village, merchant, offer));
        }
        return List.copyOf(prices);
    }

    private static int welcome(@Nullable VisitorEntity merchant, int cents, int basePrice) {
        if (merchant != null && merchant.isWelcomeVisit())
            return Math.min(MarketPrices.ceiling(basePrice) * 100, cents * 2);
        return cents;
    }

    /** Lots for the market memory: half stacks, so a drop is per 32 goods. */
    public static double memoryLots(Item item, int units) {
        return units / (double) lotSize(item);
    }

    /**
     * Records {@code units} of {@code item} sold by {@code village} today, whoever sold them,
     * worth {@code worthCents} (what the sale was worth, not the whole emeralds paid out).
     */
    public static void recordSale(ServerLevel level, VillageManager village, Item item, int units,
                                  int worthCents) {
        MarketMemory.get(level.getServer()).recordSale(village.getVillageId(), marketKey(item),
                memoryLots(item, units), worthCents, today(level));
    }

    /**
     * How many more half-stack lots of {@code item} {@code village} can sell
     * today before the price falls under {@code floorPercent} of its base.
     */
    public static int lotsUntilFloor(ServerLevel level, VillageManager village, Item item, int basePrice,
                                     int floorPercent) {
        return MarketMemory.get(level.getServer()).lotsUntilFloor(village.getVillageId(), marketKey(item),
                basePrice, floorPercent, today(level));
    }

    // -------------------------------------------------------------------------
    // Paying: whole emeralds, rounding evened out between sales
    // -------------------------------------------------------------------------

    /** Whole emeralds a sale worth {@code cents} would pay now, rounding evened out; nothing changes. */
    public static int wouldPay(ServerLevel level, VillageManager village, int cents) {
        return MarketMemory.get(level.getServer()).wouldPay(village.getVillageId(), cents);
    }

    /** Pays a sale worth {@code cents}: returns the whole emeralds to hand over, keeps the rounding to even out. */
    public static int payOut(ServerLevel level, VillageManager village, int cents) {
        return MarketMemory.get(level.getServer()).payOut(village.getVillageId(), cents);
    }

    /** The village's own saved change (tradesman, enchanter), in cents, 0 to 99. */
    public static int creditCents(ServerLevel level, VillageManager village) {
        return MarketMemory.get(level.getServer()).creditCents(village.getVillageId());
    }

    /** {@code player}'s own saved change in {@code village}, in cents, 0 to 99. */
    public static int playerChange(ServerLevel level, VillageManager village, java.util.UUID player) {
        return MarketMemory.get(level.getServer()).playerChange(village.getVillageId(), player);
    }

    /** Saved change as emeralds: "0.5". */
    public static String formatChange(int cents) {
        return format(cents);
    }

    // -------------------------------------------------------------------------
    // Enchanted books
    // -------------------------------------------------------------------------

    /** Today's price in cents for a top-level enchanted {@code book}, 0 if the merchant does not buy it. */
    public static int liveBookCents(ServerLevel level, VillageManager village, @Nullable VisitorEntity merchant,
                                    ItemStack book) {
        int base = EnchantBooks.price(book);
        if (base <= 0) return 0;
        int cents = MarketMemory.get(level.getServer())
                .liveCents(village.getVillageId(), EnchantBooks.marketKey(book), base, today(level));
        return welcome(merchant, cents, base);
    }

    /** Lots one enchanted book counts for: it is priced like a full stack, so two half-stack lots. */
    public static final double BOOK_LOTS = 2.0;

    /**
     * Records one enchanted book sold by {@code village} today, worth {@code worthCents}:
     * {@link #BOOK_LOTS} lots, for every book alike.
     */
    public static void recordBookSale(ServerLevel level, VillageManager village, ItemStack book, int worthCents) {
        MarketMemory.get(level.getServer()).recordSale(village.getVillageId(), EnchantBooks.marketKey(book),
                BOOK_LOTS, worthCents, today(level));
    }

    /** Whether {@code offer} is a good the merchant buys today at a price the rules allow. */
    public static boolean isTradable(MerchantOffer offer) {
        Item item = MerchantCatalog.item(offer);
        int full = new ItemStack(item).getMaxStackSize();
        // Checked against today's rules too, so an offer saved by an older
        // version (raw ore, a job list since dropped) is not bought: only a
        // good some profession's visit could ask for today.
        return item != Items.AIR && MerchantCatalog.sellable(item) && MerchantCatalog.mightAskFor(item)
                && full >= 2 && full % 2 == 0
                && offer.fullPrice() >= 1
                && offer.fullPrice() <= MarketPrices.ceiling(MarketPrices.basePrice(item));
    }
}
