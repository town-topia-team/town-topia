package com.alex.test.village.building;

import com.alex.test.ui.HouseRoomMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.decoration.ItemFrame;

import javax.annotation.Nullable;

/** Opens house management, or barracks and Town Hall inspection, through the shared bed-room screen. */
public final class HouseRoomAccess {
    private HouseRoomAccess() {}

    /** A registered house and the village it belongs to. */
    public record Found(VillageManager village, Building house) {}

    @Nullable public static Found forFrame(ServerLevel level, ItemFrame frame) {
        for (VillageManager village : VillageManagerRegistry.get(level.getServer()).all()) {
            Found found = find(village, level, frame);
            if (found != null) return found;
        }
        return find(VillageManager.INSTANCE, level, frame);
    }

    @Nullable private static Found find(VillageManager village, ServerLevel level, ItemFrame frame) {
        if (village.getLevel() != level) return null;
        for (Building building : village.getBuildings()) {
            if (HouseRoomMenu.isBedRoom(building) && building.getItemFrameId().equals(frame.getUUID())) {
                return new Found(village, building);
            }
        }
        return null;
    }

    /** Opens the bed screen for a registered house's token; false when the frame is not one. */
    public static boolean openFromFrame(ServerPlayer player, ItemFrame frame) {
        if (!(player.level() instanceof ServerLevel level) || player.distanceToSqr(frame) >= 64.0) return false;
        Found found = forFrame(level, frame);
        if (found == null || found.house() instanceof TownHallBuilding && !townHallOpens(level, found.village(), frame)) {
            return false;
        }
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, ignored) -> new HouseRoomMenu(id, inventory, found.village(), found.house(), level),
                Component.literal(HouseRoomMenu.titleFor(found.house()))),
                buffer -> new HouseRoomMenu(0, player.getInventory(), found.village(), found.house(), level)
                        .writeOpeningData(buffer));
        return true;
    }

    /**
     * The Town Hall opens only while its token hangs in the frame and its village runs. A paused
     * hall stays registered, so without this the click that hangs its token back, or that has a
     * paused hall checked again, would open the screen instead.
     */
    private static boolean townHallOpens(ServerLevel level, VillageManager village, ItemFrame frame) {
        return ItemFrameTokenRegistry.tokenType(frame.getItem()) == ItemFrameTokenRegistry.TokenType.TOWN_HALL
                && !VillageManagerRegistry.get(level.getServer()).isVillageInactive(village.getVillageId());
    }
}
