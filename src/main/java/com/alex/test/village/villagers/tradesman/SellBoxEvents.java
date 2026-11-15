package com.alex.test.village.villagers.tradesman;

import com.alex.test.entity.visitor.VisitorEntity;
import com.alex.test.network.SellBoxNotePayload;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.building.MerchantStallBuilding;
import com.alex.test.village.visitors.MerchantCatalog;
import com.alex.test.village.visitors.MerchantOffer;
import com.alex.test.village.visitors.MerchantPricing;
import com.alex.test.village.visitors.MerchantTransactions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Tells a player who opens a merchant stall's barrel that it is a sell box, on
 * the barrel's own screen: while a merchant is visiting, how much of what is
 * inside it buys today and how much goes to storage instead, and always how
 * many items are not the village's and are left alone. The barrel opens as
 * normal; the hint stays while it is open and follows the counts.
 */
public final class SellBoxEvents {
    private SellBoxEvents() {}

    /** How often an open sell box's hint is recounted. */
    private static final int REFRESH_TICKS = 20;

    /** A sell box right-clicked this tick, waiting to see whether its menu opens. */
    private record Clicked(BlockPos pos, long tick) {}

    /** A sell box a player has open, and the hint they were last sent. */
    private record Open(AbstractContainerMenu menu, BlockPos pos, List<String> lines) {}

    private static final Map<ServerPlayer, Clicked> CLICKED = new WeakHashMap<>();
    private static final Map<ServerPlayer, Open> OPEN = new WeakHashMap<>();

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player)) return;
        BlockPos pos = event.getPos();
        if (!level.getBlockState(pos).is(Blocks.BARREL)) return;
        VillageManager village = VillageManagerRegistry.get(level.getServer()).findVillageAt(level, pos);
        if (village == null || !SellBox.isSellBox(level, village, pos)) return;
        // The hint waits for the barrel's menu: a sneak click that places a block opens nothing.
        CLICKED.put(player, new Clicked(pos.immutable(), level.getGameTime()));
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Clicked clicked = CLICKED.remove(player);
        if (clicked == null || clicked.tick() != player.level().getGameTime()) return;
        List<String> lines = lines(player, clicked.pos());
        if (lines == null) return;
        AbstractContainerMenu menu = event.getContainer();
        OPEN.put(player, new Open(menu, clicked.pos(), lines));
        PacketDistributor.sendToPlayer(player, new SellBoxNotePayload(menu.containerId, lines));
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) OPEN.remove(player);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Open open = OPEN.get(player);
        if (open == null || player.tickCount % REFRESH_TICKS != 0) return;
        if (player.containerMenu != open.menu()) {
            OPEN.remove(player);
            return;
        }
        List<String> lines = lines(player, open.pos());
        if (lines == null || lines.equals(open.lines())) return;
        OPEN.put(player, new Open(open.menu(), open.pos(), lines));
        PacketDistributor.sendToPlayer(player, new SellBoxNotePayload(open.menu().containerId, lines));
    }

    /** The hint for the sell box at {@code pos}, heading first; null when it is no longer one. */
    @javax.annotation.Nullable
    private static List<String> lines(ServerPlayer player, BlockPos pos) {
        if (!(player.level() instanceof ServerLevel level)) return null;
        VillageManager village = VillageManagerRegistry.get(level.getServer()).findVillageAt(level, pos);
        if (village == null || !SellBox.isSellBox(level, village, pos)) return null;

        List<String> lines = new ArrayList<>();
        lines.add("Sell Box");
        VisitorEntity merchant = MerchantTransactions.openMerchant(level, village);
        MerchantStallBuilding stall = SellBox.stall(village);
        if (merchant != null && stall != null) {
            // The barrel's emeralds are its takings, not goods.
            Map<Item, Integer> goods = SellBox.goods(level, village, stall);
            goods.remove(Items.EMERALD);
            int wanted = wantedToday(merchant, goods);
            int rest = goods.values().stream().mapToInt(Integer::intValue).sum() - wanted;
            if (wanted > 0) {
                lines.add("The merchant buys " + wanted + " of these today.");
                lines.add("The emeralds come back here.");
            } else {
                lines.add("The merchant wants none of these today.");
            }
            // The tradesman banks these (TradesmanActivity.boxLeftovers).
            if (rest > 0) lines.add(rest + (wanted > 0 ? " more" : "") + " go to storage.");
        } else {
            lines.add("Put village goods here. When a merchant visits, your tradesman sells them "
                    + "in half stacks.");
            lines.add("The emeralds come back here.");
        }
        int foreign = stall == null ? 0 : SellBox.foreignCount(level, village, stall);
        if (foreign > 0) lines.add(foreign + (foreign == 1 ? " is" : " are") + " not from this village and stay.");
        return lines;
    }

    /** Units in whole lots that the merchant's list would take. */
    private static int wantedToday(VisitorEntity merchant, Map<Item, Integer> inBox) {
        int wanted = 0;
        Set<Item> counted = new HashSet<>();
        for (MerchantOffer offer : merchant.getOffers()) {
            if (!MerchantPricing.isTradable(offer)) continue;
            Item item = MerchantCatalog.item(offer);
            if (!counted.add(item)) continue;
            int lot = MerchantPricing.lotSize(item);
            int units = inBox.getOrDefault(item, 0);
            wanted += units - units % lot;
        }
        return wanted;
    }
}
