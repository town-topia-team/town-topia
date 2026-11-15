package com.alex.test.ui;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.visitor.VisitorEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.visitors.MerchantCatalog;
import com.alex.test.village.visitors.MerchantOffer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/** Slotless merchant menu; the server owns the catalog and all transactions. */
public final class MerchantMenu extends AbstractContainerMenu implements NpcUiMenu {
    @Nullable private final VisitorEntity merchant;
    private final int merchantId;
    private final VillageTokenBinding owner;
    private final List<MerchantOffer> offers;
    private List<Integer> counts;
    /** Today's price per offer in cents (300 = 3 emeralds a stack, always whole): the base moved by recent sales. */
    private List<Integer> prices;
    /** Emeralds the merchant can still pay today; -1 for no limit. */
    private int purse;
    /** The player's own saved change in this village, in cents, 0 to 99: added to their next sale. */
    private int change;
    private String status = "";
    private boolean statusSuccess;

    public MerchantMenu(int id, Inventory inventory, VisitorEntity merchant, VillageManager village) {
        super(DynamicVillages.MERCHANT_MENU.get(), id);
        this.merchant = merchant;
        merchantId = merchant.getId();
        owner = VillageTokenBinding.of(village);
        offers = merchant.getOffers();
        var level = (net.minecraft.server.level.ServerLevel) merchant.level();
        var storage = MerchantCatalog.containers(level, village);
        counts = MerchantCatalog.counts(offers, storage, village);
        prices = com.alex.test.village.visitors.MerchantPricing.liveCentsList(level, village, merchant);
        purse = merchant.purse();
        change = com.alex.test.village.visitors.MerchantPricing.playerChange(level, village, inventory.player.getUUID());
    }

    public MerchantMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        super(DynamicVillages.MERCHANT_MENU.get(), id);
        merchant = null;
        merchantId = buffer.readVarInt();
        owner = new VillageTokenBinding(buffer.readUUID(), buffer.readUtf());
        offers = List.copyOf(buffer.readList(buf -> MerchantOffer.read((RegistryFriendlyByteBuf) buf)));
        counts = List.copyOf(buffer.readList(net.minecraft.network.FriendlyByteBuf::readVarInt));
        prices = List.copyOf(buffer.readList(net.minecraft.network.FriendlyByteBuf::readVarInt));
        purse = buffer.readInt();
        change = buffer.readInt();
    }

    public static void writeOpeningData(RegistryFriendlyByteBuf buffer, VisitorEntity merchant,
                                        VillageManager village, Player player) {
        var level = (net.minecraft.server.level.ServerLevel) merchant.level();
        var storage = MerchantCatalog.containers(level, village);
        buffer.writeVarInt(merchant.getId());
        VillageTokenBinding owner = VillageTokenBinding.of(village);
        buffer.writeUUID(owner.townHallId());
        buffer.writeUtf(owner.dimension());
        buffer.writeCollection(merchant.getOffers(), (buf, offer) -> offer.write((RegistryFriendlyByteBuf) buf));
        buffer.writeCollection(MerchantCatalog.counts(merchant.getOffers(), storage, village),
                (buf, count) -> buf.writeVarInt(count));
        buffer.writeCollection(com.alex.test.village.visitors.MerchantPricing.liveCentsList(level, village, merchant),
                (buf, price) -> buf.writeVarInt(price));
        buffer.writeInt(merchant.purse());
        buffer.writeInt(com.alex.test.village.visitors.MerchantPricing.playerChange(level, village, player.getUUID()));
    }

    public int merchantId() { return merchantId; }
    public VillageTokenBinding owner() { return owner; }
    public List<MerchantOffer> offers() { return offers; }
    public int count(int index) { return index < counts.size() ? counts.get(index) : 0; }
    /** Today's full-stack price of offer {@code index} in cents; its base when none was sent. */
    public int priceCents(int index) {
        return index < prices.size() ? prices.get(index) : offers.get(index).fullPrice() * 100;
    }
    public int purse() { return purse; }

    /**
     * Whole emeralds a sale worth {@code cents} pays right now: the player's
     * saved change plus the sale, rounded down.
     */
    public int payout(int cents) {
        return com.alex.test.village.economy.MarketMemory.payoutFor(change, cents);
    }

    /** The change a sale worth {@code cents} would leave saved, in cents. */
    public int changeAfter(int cents) {
        return com.alex.test.village.economy.MarketMemory.changeAfter(change, cents);
    }

    /** The player's saved change here right now, in cents. */
    public int change() { return change; }
    public String status() { return status; }
    public boolean statusSuccess() { return statusSuccess; }

    public void applyResult(boolean success, String message, List<Integer> updated, List<Integer> updatedPrices,
                            int updatedPurse, int updatedChange) {
        purse = updatedPurse;
        change = updatedChange;
        if (updated.size() == offers.size()) counts = List.copyOf(updated);
        if (updatedPrices.size() == offers.size()) prices = List.copyOf(updatedPrices);
        status = message;
        statusSuccess = success;
    }

    public boolean isFor(VisitorEntity visitor) { return merchant == visitor; }

    @Override public boolean isForNpc(NpcEntity npc) { return merchant == npc; }

    @Override public boolean stillValid(Player player) {
        return merchant == null || (merchant.isAlive() && !merchant.isReturningHome()
                && player.distanceToSqr(merchant) < 64.0);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
}
