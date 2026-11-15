package com.alex.test.village.visitors;

import com.alex.test.entity.visitor.VisitorEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.economy.MarketMemory;
import com.alex.test.village.tokens.VillageItemOwnership;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans a complete exchange in copies of inventory slots, then commits it on the server thread.
 *
 * <p>The merchant buys the village's goods from the player and carries them off; it pays in
 * newly minted emeralds bound to the village, as the Mayor does for surplus. Paying out of
 * village storage and depositing the goods back there made every sale a no-op for the village.
 */
public final class MerchantTransactions {
    private MerchantTransactions() {}
    public record Outcome(boolean success, String message) {}
    private static final class Slot {
        final Container container;
        final int index;
        final ItemStack before;
        ItemStack after;
        Slot(Container container, int index) {
            this.container = container;
            this.index = index;
            before = container.getItem(index).copy();
            after = before.copy();
        }
    }

    /** How close a seller, player or tradesman, must stand to the merchant. */
    public static final double SELL_RANGE_SQ = 64.0;

    /**
     * Whether {@code merchant} is still trading for {@code village}: alive, not
     * leaving, the village awake, and its visit still on the books.
     */
    public static boolean isOpenForTrade(ServerLevel level, VillageManager village, VisitorEntity merchant) {
        return village != null && village.getLevel() == level && merchant.isAlive()
                && !merchant.isReturningHome()
                && village.getVillageId().equals(merchant.getVillageId())
                && !VillageManagerRegistry.get(level.getServer())
                        .isVillageDormantOrReconciling(merchant.getVillageId())
                && village.visitors().departureFor(merchant.getUUID()) != null;
    }

    /** The merchant visiting {@code village} right now, if it is loaded and still trading. */
    @javax.annotation.Nullable
    public static VisitorEntity openMerchant(ServerLevel level, VillageManager village) {
        if (village == null) return null;
        java.util.UUID id = village.visitors().activeMerchantId();
        if (id == null) return null;
        return level.getEntity(id) instanceof VisitorEntity merchant
                && isOpenForTrade(level, village, merchant) ? merchant : null;
    }

    public static Outcome sell(ServerPlayer player, VisitorEntity merchant, int offerIndex, boolean half) {
        ServerLevel level = player.serverLevel();
        VillageManager village = merchant.getVillageId() == null ? null
                : VillageManagerRegistry.get(level.getServer()).get(merchant.getVillageId());
        if (!isOpenForTrade(level, village, merchant) || player.distanceToSqr(merchant) >= SELL_RANGE_SQ)
            return new Outcome(false, "This merchant is no longer trading.");
        if (offerIndex < 0 || offerIndex >= merchant.getOffers().size())
            return new Outcome(false, "That offer is unavailable.");
        MerchantOffer offer = merchant.getOffers().get(offerIndex);
        if (!MerchantPricing.isTradable(offer))
            return new Outcome(false, "That offer is unavailable.");
        Item item = MerchantCatalog.item(offer);
        // Today's price in cents: the base moved by what the village sold lately.
        int priceCents = MerchantPricing.liveCents(level, village, merchant, offer);
        int stack = new ItemStack(item).getMaxStackSize();
        int amount = half ? stack / 2 : stack;
        int worthCents = half ? MerchantPricing.lotCents(priceCents) : priceCents;
        // Whole emeralds this sale hands over: the player's own saved change
        // plus the sale, rounded down; the rest stays saved for their next sale.
        MarketMemory market = MarketMemory.get(level.getServer());
        java.util.UUID villageId = village.getVillageId();
        int change = market.playerChange(villageId, player.getUUID());
        int payout = MarketMemory.payoutFor(change, worthCents);
        int changeLeft = MarketMemory.changeAfter(change, worthCents);
        // A merchant with an empty purse buys nothing, not even for change.
        if (merchant.purse() == 0 || !merchant.canPay(payout)) {
            market.setRanDry(villageId, true);
            return new Outcome(false, merchant.purse() == 0
                    ? "The merchant is out of emeralds for today."
                    : "The merchant doesn't have enough emeralds left for this.");
        }
        // Now and then the merchant rounds the change up and pays it right
        // away, once per visit, if the purse allows. Never shown beforehand.
        boolean roundUp = changeLeft > 0
                && !market.roundedUpThisVisit(villageId, player.getUUID(), merchant.getUUID())
                && merchant.canPay(payout + 1)
                && level.getRandom().nextFloat() < ROUND_UP_CHANCE;

        Inventory inventory = player.getInventory();
        List<Slot> playerSlots = new ArrayList<>();
        for (int i = 0; i < 36; i++) playerSlots.add(new Slot(inventory, i));

        if (takeOwned(playerSlots, item, amount, village) == null)
            return new Outcome(false, "You need " + amount + " goods marked with this village's ID.");
        // A half stack can pay nothing now and save its half for the next sale.
        if (payout > 0) {
            ItemStack coins = VillageItemOwnership.produce(new ItemStack(Items.EMERALD, payout), village);
            if (coins.isEmpty()) return new Outcome(false, "Trade could not be completed.");
            if (!put(playerSlots, coins, coins.getCount()))
                return new Outcome(false, "Make room in your inventory for the emeralds.");
        }
        if (roundUp) {
            // No room for one more emerald: the change simply stays saved.
            ItemStack bonus = VillageItemOwnership.produce(new ItemStack(Items.EMERALD, 1), village);
            roundUp = !bonus.isEmpty() && put(playerSlots, bonus, 1);
        }

        List<Slot> changed = new ArrayList<>(playerSlots);
        changed.removeIf(slot -> slot.before.getCount() == slot.after.getCount()
                && ItemStack.isSameItemSameComponents(slot.before, slot.after));
        try {
            for (Slot slot : changed) {
                slot.container.setItem(slot.index, slot.after);
                slot.container.setChanged();
            }
        } catch (RuntimeException failure) {
            for (Slot slot : changed) {
                slot.container.setItem(slot.index, slot.before);
                slot.container.setChanged();
            }
            return new Outcome(false, "Trade could not be completed.");
        }
        // A full resend: while the merchant screen is open the client drops partial
        // inventory-menu updates for the 27 main slots, leaving ghost stacks.
        player.inventoryMenu.sendAllDataToRemote();
        int paid = market.payOutPlayer(villageId, player.getUUID(), worthCents);
        if (roundUp) {
            market.roundUpChange(villageId, player.getUUID(), merchant.getUUID());
            paid++;
        }
        merchant.spend(paid);
        // The sale moves tomorrow's price, for the player and the tradesman alike.
        MerchantPricing.recordSale(level, village, item, amount, worthCents);
        String sold = "Sold " + amount + " for " + paid + (paid == 1 ? " emerald." : " emeralds.");
        if (roundUp) {
            sold += " The merchant rounded up: keep the change!";
        } else if (changeLeft > 0) {
            sold += " " + MerchantPricing.formatChange(changeLeft) + " emerald saved for your next sale.";
        } else if (change > 0) {
            sold += " That includes your saved change.";
        }
        return new Outcome(true, sold);
    }

    /** How often a sale that leaves change has the merchant round it up and pay it now. */
    public static final float ROUND_UP_CHANCE = 0.2f;

    private static List<ItemStack> takeOwned(List<Slot> slots, Item item, int amount,
                                            VillageManager village) {
        int left = amount;
        List<ItemStack> takenStacks = new ArrayList<>();
        for (Slot slot : slots) {
            if (!slot.after.is(item) || !VillageItemOwnership.mayTrade(slot.after, village)) continue;
            int taken = Math.min(left, slot.after.getCount());
            takenStacks.add(slot.after.copyWithCount(taken));
            slot.after.shrink(taken);
            left -= taken;
            if (left == 0) return takenStacks;
        }
        return null;
    }

    private static boolean put(List<Slot> slots, ItemStack wanted, int amount) {
        int left = amount;
        for (Slot slot : slots) {
            if (!ItemStack.isSameItemSameComponents(slot.after, wanted)) continue;
            int space = Math.min(slot.after.getMaxStackSize(), slot.container.getMaxStackSize())
                    - slot.after.getCount();
            if (space <= 0) continue;
            int added = Math.min(left, space);
            slot.after.grow(added);
            left -= added;
            if (left == 0) return true;
        }
        for (Slot slot : slots) {
            if (!slot.after.isEmpty() || !slot.container.canPlaceItem(slot.index, wanted)) continue;
            int added = Math.min(left, Math.min(wanted.getMaxStackSize(), slot.container.getMaxStackSize()));
            slot.after = wanted.copyWithCount(added);
            left -= added;
            if (left == 0) return true;
        }
        return false;
    }
}
