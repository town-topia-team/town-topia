package com.alex.test.village.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What each village sold to the merchant lately, and what that does to prices.
 *
 * <p>Prices are always whole emeralds for a stack (kept in hundredths, so
 * 240 would be 2.4, but a live price is rounded to the nearest emerald, half
 * up) and never go below one. A half stack of an odd price is paid in whole
 * emeralds too: rounded down, the part left over saved as change and paid
 * with the next sale ({@link #payOut}, {@link #payOutPlayer}). Each player
 * keeps their own change per village; the tradesman and enchanter share the
 * village's. Two things are kept per good, both fading each morning:
 * the lots sold (half a stack each, or two for an enchanted book, which is
 * priced like a full stack) and the cents they were worth.
 *
 * <p>Selling a good lowers its own price slowly and smoothly: its share of
 * the base is {@code 1 / (1 + dropPerStack * stacks)}, so with 0.08 a good
 * sits at 93% after one stack, 71% after five and 50% after twelve and a
 * half. Every other good rises a little for each emerald the merchant spent
 * on sales lately, up to a small ceiling: selling oak for 13 emeralds lifts
 * everything else by about 2.6%. Many goods share that rise while one good
 * takes the whole drop, so the oversold good falls visibly faster than the
 * rest climb, and it pushes players to sell a mix. A good's own sales never
 * raise its own price. A share never falls under the lowest share.
 *
 * <p>Each village morning the counts fade to a share of themselves, so a good
 * left alone is back near its base price in about a week. Days are village
 * days ({@code VillageDays}), so the fade also happens for days the village
 * spent unloaded, but only the days the away catch-up counts (at most 3,
 * none with nobody on the server; {@link #skipUncountedDays}). All five
 * numbers are {@link MarketTuning}, from the price file.
 *
 * <p>Shared by everything that quotes a price: the merchant screen, the
 * tradesman, the sell box and the enchanter's book sale read the same
 * numbers here, and every sale, whoever made it, is recorded here.
 */
public final class MarketMemory extends SavedData {
    private static final String DATA_NAME = "dynamicvillages_market";
    private static final SavedData.Factory<MarketMemory> FACTORY =
            new SavedData.Factory<>(MarketMemory::new, MarketMemory::load, null);

    /** Lot counts below this are forgotten. */
    private static final double FORGET_BELOW = 0.02;
    /** Paid amounts below this many cents are forgotten. */
    private static final double FORGET_BELOW_CENTS = 1.0;
    /** Lots looked ahead when asking how many more can sell before a floor (about 75 reach the 25% share). */
    public static final int MAX_LOOKAHEAD_LOTS = 128;

    /** One village's recent sales. */
    private static final class VillageMarket {
        long day = Long.MIN_VALUE;
        final Map<String, Double> sold = new HashMap<>();
        /** What those sales were worth, in cents, per good: what lifts the other goods. */
        final Map<String, Double> paid = new HashMap<>();
        /** The last merchant left with an empty purse; the next one brings more. */
        boolean ranDry;
        /** The village's own saved change (tradesman, enchanter), in cents, 0 to 99. */
        int creditCents;
        /** Each player's saved change here, in cents, 0 to 99: never shared with the village or each other. */
        final Map<UUID, Integer> playerChange = new HashMap<>();
        /** The merchant (one per visit) that last rounded a player's change up: at most once per visit. */
        final Map<UUID, UUID> roundedUpBy = new HashMap<>();
    }

    private final Map<UUID, VillageMarket> villages = new HashMap<>();

    public static MarketMemory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    // -------------------------------------------------------------------------
    // Prices
    // -------------------------------------------------------------------------

    /** Lots of {@code key} sold lately by {@code village}, after today's fade. */
    public double soldLots(UUID village, String key, long today) {
        VillageMarket market = villages.get(village);
        if (market == null) return 0.0;
        fade(market, today);
        return market.sold.getOrDefault(key, 0.0);
    }

    /** Cents the merchant paid {@code village} for {@code key} lately, after today's fade. */
    public double paidCents(UUID village, String key, long today) {
        VillageMarket market = villages.get(village);
        if (market == null) return 0.0;
        fade(market, today);
        return market.paid.getOrDefault(key, 0.0);
    }

    /** Cents the merchant paid {@code village} for everything lately, after today's fade. */
    public double totalPaidCents(UUID village, long today) {
        VillageMarket market = villages.get(village);
        if (market == null) return 0.0;
        fade(market, today);
        double total = 0.0;
        for (double cents : market.paid.values()) total += cents;
        return total;
    }

    /** Cents paid for every good but {@code key}: the part of the sales that lifts its price. */
    public double otherPaidCents(UUID village, String key, long today) {
        return Math.max(0.0, totalPaidCents(village, today) - paidCents(village, key, today));
    }

    /** The share of its base price {@code key} fetches today, lowest share to the biggest rise. */
    public double factor(UUID village, String key, long today) {
        return factor(soldLots(village, key, today), otherPaidCents(village, key, today), MarketPrices.tuning());
    }

    /**
     * What selling {@code ownLots} half stacks of a good does to its own price:
     * 1 at nothing sold, 0.5 at twelve and a half stacks. A lot is half a stack.
     */
    public static double ownShare(double ownLots, MarketTuning tuning) {
        return 1.0 / (1.0 + tuning.dropPerStack() * ownLots / 2.0);
    }

    /** How much dearer a good is for the {@code otherPaidCents} the merchant spent on other goods lately. */
    public static double rise(double otherPaidCents, MarketTuning tuning) {
        return Math.min(tuning.maxRise(), tuning.risePerEmerald() * (otherPaidCents / 100.0));
    }

    /** The share of its base price a good fetches, from raw numbers: also what the client previews with. */
    public static double factor(double ownLots, double otherPaidCents, MarketTuning tuning) {
        double share = ownShare(ownLots, tuning) * (1.0 + rise(otherPaidCents, tuning));
        return Math.clamp(share, tuning.lowestShare(), 1.0 + tuning.maxRise());
    }

    /**
     * Today's price for a full stack (or one book) of {@code key} whose base is
     * {@code basePrice}, in hundredths of an emerald ("cents"), always a whole
     * number of emeralds: 2.4 is 2 and 2.5 is 3. Never below one emerald,
     * never above the table's ceiling.
     */
    public int liveCents(UUID village, String key, int basePrice, long today) {
        return centsAt(basePrice, factor(village, key, today));
    }

    /**
     * {@code basePrice} moved by {@code factor}, rounded to the nearest whole
     * emerald (half up), in cents, 1 emerald to the ceiling.
     */
    public static int centsAt(int basePrice, double factor) {
        // The nudge keeps an exact half (3.5) from falling to 3 on floating point noise.
        int emeralds = (int) Math.floor(basePrice * factor + 0.5 + 1.0e-9);
        return Math.clamp(emeralds * 100, MIN_CENTS, MarketPrices.ceiling(basePrice) * 100);
    }

    /** No price, for a stack or a book, ever goes below one whole emerald. */
    public static final int MIN_CENTS = 100;

    /** Today's price in cents from raw counts, for the client's previews. */
    public static int centsFrom(double ownLots, double otherPaidCents, int basePrice, MarketTuning tuning) {
        return centsAt(basePrice, factor(ownLots, otherPaidCents, tuning));
    }

    /**
     * How many more lots of {@code key} can sell today before its price falls
     * under {@code floorPercent} of its base: 0 when it is already there,
     * never more than {@link #MAX_LOOKAHEAD_LOTS}. Each lot counted drops the
     * price as a real sale would. A good's own sales never raise its own
     * price, so only the rise other goods' sales give it later, which cannot
     * be foretold, is left out.
     */
    public int lotsUntilFloor(UUID village, String key, int basePrice, int floorPercent, long today) {
        return lotsUntilFloor(soldLots(village, key, today), otherPaidCents(village, key, today),
                basePrice, floorPercent, MarketPrices.tuning());
    }

    /**
     * The same count from raw numbers, for the client: the Sale items tab gets
     * {@code ownLots} and the cents paid for other goods with its rows, plus
     * the tuning, and works out the answer for whatever floor the player has
     * just set.
     */
    public static int lotsUntilFloor(double ownLots, double otherPaidCents, int basePrice, int floorPercent,
                                     MarketTuning tuning) {
        for (int lots = 0; lots < MAX_LOOKAHEAD_LOTS; lots++) {
            int cents = centsAt(basePrice, factor(ownLots + lots, otherPaidCents, tuning));
            if (underFloor(cents, basePrice, floorPercent)) return lots;
        }
        return MAX_LOOKAHEAD_LOTS;
    }

    /**
     * Whether a good priced at {@code cents} today is under the floor: below
     * {@code floorPercent} of its base, or fallen all the way to the
     * one-emerald minimum. A good whose base is 1 is always at the minimum, so
     * it never counts as fallen and only the merchant's purse limits it.
     */
    public static boolean underFloor(int cents, int basePrice, int floorPercent) {
        int floorCents = (int) Math.ceil(basePrice * (double) Math.clamp(floorPercent, 0, 100));
        return cents < floorCents || (basePrice > 1 && cents <= MIN_CENTS);
    }

    /**
     * Records {@code lots} of {@code key} sold by {@code village} today, worth
     * {@code cents}: the sale's worth, not the whole emeralds handed over, so
     * village credit does not matter.
     */
    public void recordSale(UUID village, String key, double lots, int cents, long today) {
        if (lots <= 0.0) return;
        VillageMarket market = villages.computeIfAbsent(village, ignored -> new VillageMarket());
        fade(market, today);
        market.sold.merge(key, lots, Double::sum);
        if (cents > 0) market.paid.merge(key, (double) cents, Double::sum);
        setDirty();
    }

    /** Forgets everything {@code village} sold. */
    public void forget(UUID village) {
        if (villages.remove(village) != null) setDirty();
    }

    // -------------------------------------------------------------------------
    // Paying in whole emeralds: round down, save the change
    // -------------------------------------------------------------------------

    /**
     * Whole emeralds a sale worth {@code cents} pays when {@code changeCents}
     * is already saved: the two added up, rounded down. Static so the merchant
     * screen shows exactly what will be paid.
     */
    public static int payoutFor(int changeCents, int cents) {
        return (Math.max(0, changeCents) + Math.max(0, cents)) / 100;
    }

    /** The change left saved after that sale, in cents, 0 to 99. */
    public static int changeAfter(int changeCents, int cents) {
        return (Math.max(0, changeCents) + Math.max(0, cents)) % 100;
    }

    /**
     * Whole emeralds a sale worth {@code cents} by the village itself
     * (tradesman, enchanter) pays right now, without changing anything. For
     * purse checks.
     */
    public int wouldPay(UUID village, int cents) {
        return payoutFor(creditCents(village), cents);
    }

    /**
     * Pays a sale worth {@code cents} by the village itself in whole emeralds
     * and saves the part of an emerald left over: a half stack worth 1.5 pays
     * 1 and saves 0.5, so the next one pays 2. Two halves always add up to the
     * stack price. Returns the whole emeralds to hand over.
     */
    public int payOut(UUID village, int cents) {
        VillageMarket market = villages.computeIfAbsent(village, ignored -> new VillageMarket());
        int paid = payoutFor(market.creditCents, cents);
        market.creditCents = changeAfter(market.creditCents, cents);
        setDirty();
        return paid;
    }

    /** The village's own saved change, in cents, 0 to 99. */
    public int creditCents(UUID village) {
        VillageMarket market = villages.get(village);
        return market == null ? 0 : market.creditCents;
    }

    /** {@code player}'s saved change in {@code village}, in cents, 0 to 99. */
    public int playerChange(UUID village, UUID player) {
        VillageMarket market = villages.get(village);
        return market == null ? 0 : market.playerChange.getOrDefault(player, 0);
    }

    /**
     * Pays {@code player}'s sale worth {@code cents} in {@code village}: their
     * saved change plus the sale, rounded down; the rest stays saved for their
     * next sale here, with whichever merchant comes. Returns the emeralds to
     * hand over.
     */
    public int payOutPlayer(UUID village, UUID player, int cents) {
        VillageMarket market = villages.computeIfAbsent(village, ignored -> new VillageMarket());
        int change = market.playerChange.getOrDefault(player, 0);
        int paid = payoutFor(change, cents);
        setPlayerChange(market, player, changeAfter(change, cents));
        setDirty();
        return paid;
    }

    /** Whether {@code merchant} already rounded {@code player}'s change up on this visit. */
    public boolean roundedUpThisVisit(UUID village, UUID player, UUID merchant) {
        VillageMarket market = villages.get(village);
        return market != null && merchant.equals(market.roundedUpBy.get(player));
    }

    /**
     * {@code merchant} rounded {@code player}'s saved change up to a whole
     * emerald and paid it: the change is used up, and this merchant will not
     * do it again.
     */
    public void roundUpChange(UUID village, UUID player, UUID merchant) {
        VillageMarket market = villages.computeIfAbsent(village, ignored -> new VillageMarket());
        setPlayerChange(market, player, 0);
        market.roundedUpBy.put(player, merchant);
        setDirty();
    }

    private static void setPlayerChange(VillageMarket market, UUID player, int cents) {
        if (cents > 0) market.playerChange.put(player, cents);
        else market.playerChange.remove(player);
    }

    // -------------------------------------------------------------------------
    // The merchant's purse
    // -------------------------------------------------------------------------

    /** Emeralds every merchant brings, before the village's size. */
    public static final int PURSE_BASE = 30;
    /** Emeralds more per villager. */
    public static final int PURSE_PER_VILLAGER = 3;
    /** The purse after a merchant ran dry here: this much bigger. */
    public static final double PURSE_DRY_BONUS = 1.5;

    /** What a merchant arriving at a village of {@code villagers} carries. */
    public static int purseFor(int villagers, boolean lastRanDry) {
        int purse = PURSE_BASE + PURSE_PER_VILLAGER * Math.max(0, villagers);
        return lastRanDry ? (int) Math.round(purse * PURSE_DRY_BONUS) : purse;
    }

    /**
     * Fills a newly arriving merchant's purse for {@code village}: bigger if the
     * last one ran dry, and that note is used up.
     */
    public int newPurse(UUID village, int villagers) {
        boolean dry = ranDry(village);
        if (dry) setRanDry(village, false);
        return purseFor(villagers, dry);
    }

    /** The last merchant to visit {@code village} left with nothing to pay with. */
    public boolean ranDry(UUID village) {
        VillageMarket market = villages.get(village);
        return market != null && market.ranDry;
    }

    public void setRanDry(UUID village, boolean ranDry) {
        VillageMarket market = villages.computeIfAbsent(village, ignored -> new VillageMarket());
        if (market.ranDry != ranDry) {
            market.ranDry = ranDry;
            setDirty();
        }
    }

    // -------------------------------------------------------------------------
    // Fade
    // -------------------------------------------------------------------------

    /**
     * Away time the village did not live through (past the
     * {@link AwayClock} cap, or with nobody on the server): prices do not
     * recover through it. Moves the market's last morning forward by those
     * whole days, never past today, so only the counted days fade.
     */
    public void skipUncountedDays(UUID village, long days, long today) {
        VillageMarket market = villages.get(village);
        if (market == null || days <= 0L || market.day == Long.MIN_VALUE) return;
        long moved = Math.min(today, market.day + days);
        if (moved <= market.day) return;
        market.day = moved;
        setDirty();
    }

    /** Applies the mornings since the market was last touched. */
    private void fade(VillageMarket market, long today) {
        if (market.day == Long.MIN_VALUE || today < market.day) {
            market.day = today;
            return;
        }
        long mornings = today - market.day;
        if (mornings <= 0) return;
        market.day = today;
        double keep = Math.pow(MarketPrices.tuning().keepEachMorning(), Math.min(mornings, 60));
        market.sold.replaceAll((key, lots) -> lots * keep);
        market.sold.values().removeIf(lots -> lots < FORGET_BELOW);
        market.paid.replaceAll((key, cents) -> cents * keep);
        market.paid.values().removeIf(cents -> cents < FORGET_BELOW_CENTS);
        setDirty();
    }

    // -------------------------------------------------------------------------
    // Saving
    // -------------------------------------------------------------------------

    private static MarketMemory load(CompoundTag tag, HolderLookup.Provider registries) {
        MarketMemory memory = new MarketMemory();
        ListTag list = tag.getList("Villages", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("Id")) continue;
            VillageMarket market = new VillageMarket();
            market.day = entry.contains("Day") ? entry.getLong("Day") : Long.MIN_VALUE;
            market.ranDry = entry.getBoolean("RanDry");
            // Older saves rounded up first and could be below zero: that is forgiven.
            market.creditCents = Math.clamp(entry.getInt("Credit"), 0, 99);
            CompoundTag change = entry.getCompound("Change");
            for (String key : change.getAllKeys()) {
                int cents = Math.clamp(change.getInt(key), 0, 99);
                UUID player = parseUuid(key);
                if (player != null && cents > 0) market.playerChange.put(player, cents);
            }
            CompoundTag roundedUp = entry.getCompound("RoundedUp");
            for (String key : roundedUp.getAllKeys()) {
                UUID player = parseUuid(key);
                if (player != null && roundedUp.hasUUID(key)) market.roundedUpBy.put(player, roundedUp.getUUID(key));
            }
            CompoundTag sold = entry.getCompound("Sold");
            for (String key : sold.getAllKeys()) {
                double lots = sold.getDouble(key);
                if (lots >= FORGET_BELOW) market.sold.put(key, lots);
            }
            // Older saves have no "Paid": other goods simply rise 0 until new sales.
            CompoundTag paid = entry.getCompound("Paid");
            for (String key : paid.getAllKeys()) {
                double cents = paid.getDouble(key);
                if (cents >= FORGET_BELOW_CENTS) market.paid.put(key, cents);
            }
            memory.villages.put(entry.getUUID("Id"), market);
        }
        return memory;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, VillageMarket> entry : villages.entrySet()) {
            VillageMarket market = entry.getValue();
            CompoundTag saved = new CompoundTag();
            saved.putUUID("Id", entry.getKey());
            if (market.day != Long.MIN_VALUE) saved.putLong("Day", market.day);
            if (market.ranDry) saved.putBoolean("RanDry", true);
            if (market.creditCents != 0) saved.putInt("Credit", market.creditCents);
            CompoundTag change = new CompoundTag();
            for (Map.Entry<UUID, Integer> cents : market.playerChange.entrySet())
                change.putInt(cents.getKey().toString(), cents.getValue());
            if (!change.isEmpty()) saved.put("Change", change);
            CompoundTag roundedUp = new CompoundTag();
            for (Map.Entry<UUID, UUID> by : market.roundedUpBy.entrySet())
                roundedUp.putUUID(by.getKey().toString(), by.getValue());
            if (!roundedUp.isEmpty()) saved.put("RoundedUp", roundedUp);
            CompoundTag sold = new CompoundTag();
            for (Map.Entry<String, Double> lots : market.sold.entrySet()) sold.putDouble(lots.getKey(), lots.getValue());
            saved.put("Sold", sold);
            CompoundTag paid = new CompoundTag();
            for (Map.Entry<String, Double> cents : market.paid.entrySet()) paid.putDouble(cents.getKey(), cents.getValue());
            saved.put("Paid", paid);
            list.add(saved);
        }
        tag.put("Villages", list);
        return tag;
    }

    @javax.annotation.Nullable
    private static UUID parseUuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }
}
