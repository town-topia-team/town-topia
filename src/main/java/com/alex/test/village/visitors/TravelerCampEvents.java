package com.alex.test.village.visitors;

import com.alex.test.block.TravelerCampfireBlock;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.TavernBuilding;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Players and the travellers' night: breaking a camp's fire or a guest's bed
 * (warned the first time, then the guests are upset), clicking a bed booked
 * for a guest, and placing a bed in a tavern, which makes it a guest bed.
 */
public final class TravelerCampEvents {
    private TravelerCampEvents() {}

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getPlayer() == null) return;
        if (event.getState().getBlock() instanceof TravelerCampfireBlock) {
            if (!TravelerCamp.onPlayerBreaksFire(level, event.getPlayer(), event.getPos())) event.setCanceled(true);
        } else if (event.getState().getBlock() instanceof BedBlock) {
            if (!TravelerCamp.onPlayerBreaksBed(level, event.getPlayer(), event.getPos())) event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(level.getBlockState(event.getPos()).getBlock() instanceof BedBlock)) return;
        // Sneaking with something in hand uses the item (placing a block
        // against the bed), as in vanilla: not a try at sleeping.
        Player player = event.getEntity();
        if (player.isSecondaryUseActive()
                && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty())) return;
        // One click can arrive once per hand; only the main hand counts as the
        // click, and the off hand is refused alongside a refused main hand.
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) {
            if (TravelerCamp.wasJustWarnedAboutBed(level, event.getEntity())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }
        if (TravelerCamp.onPlayerClicksBed(level, event.getEntity(), event.getPos())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** Keeps the tradesman tip count through death, so it stays shown only twice. */
    @SubscribeEvent
    public static void onClone(net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone event) {
        String key = VisitorSpawnManager.TRADESMAN_TIP_KEY;
        var old = event.getOriginal().getPersistentData();
        if (old.contains(key)) event.getEntity().getPersistentData().putInt(key, old.getInt(key));
    }

    /**
     * A bed placed in a tavern room is a guest bed: say so. Not when a house
     * or barracks room reaches it too: that bed is kept for villagers.
     */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Player player)) return;
        if (!(event.getPlacedBlock().getBlock() instanceof BedBlock)) return;
        // The house only lists the bed after its next scan, so ask its room.
        net.minecraft.core.BlockPos pos = event.getPos();
        net.minecraft.core.Direction facing = event.getPlacedBlock().getValue(BedBlock.FACING);
        net.minecraft.core.BlockPos otherHalf = event.getPlacedBlock().getValue(BedBlock.PART)
                == net.minecraft.world.level.block.state.properties.BedPart.FOOT
                ? pos.relative(facing) : pos.relative(facing.getOpposite());
        for (VillageManager village : VillageManagerRegistry.get(level.getServer()).all()) {
            if (village.getLevel() != level) continue;
            for (Building building : village.getBuildings()) {
                if ((building instanceof com.alex.test.village.building.HouseBuilding
                        || building instanceof com.alex.test.village.building.BarracksBuilding
                        || building instanceof com.alex.test.village.building.MageTowerBuilding)
                        && building.isValid()
                        && (building.reachesFromRoom(pos) || building.reachesFromRoom(otherHalf))) return;
            }
            for (Building building : village.getBuildings()) {
                if (building instanceof TavernBuilding tavern && tavern.isValid()
                        && tavern.holdsGuestBed(event.getPos())) {
                    player.sendSystemMessage(Component.literal("This bed is now a guest bed for travelers. "
                            + "With a free one, they are likelier to stay the night.").withStyle(ChatFormatting.GRAY));
                    return;
                }
            }
        }
    }
}
