package com.alex.test.village.building;

import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.ui.RanchRoomMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.decoration.ItemFrame;

import javax.annotation.Nullable;

/** Opens a Ranch's screen, from its token or from the Mayor. */
public final class RanchRoomAccess {
    private RanchRoomAccess() {}

    /** A registered Ranch and the village it belongs to. */
    public record Found(VillageManager village, RanchBuilding ranch) {}

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
            if (building instanceof RanchBuilding ranch && ranch.getItemFrameId().equals(frame.getUUID())) {
                return new Found(village, ranch);
            }
        }
        return null;
    }

    /** Opens the Ranch screen for a registered Ranch's token; false when the frame is not one. */
    public static boolean openFromFrame(ServerPlayer player, ItemFrame frame) {
        if (!(player.level() instanceof ServerLevel level) || player.distanceToSqr(frame) >= 64.0) return false;
        Found found = forFrame(level, frame);
        if (found == null) return false;
        open(player, found.village(), found.ranch(), null);
        return true;
    }

    /** Opens the Ranch screen; {@code mayor} is the Mayor it was opened through (round 8), or null. */
    public static void open(ServerPlayer player, VillageManager village, RanchBuilding ranch, @Nullable MayorEntity mayor) {
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, ignored) -> new RanchRoomMenu(id, inventory, village, ranch, mayor),
                Component.literal(RanchRoomMenu.titleFor(ranch))),
                buffer -> new RanchRoomMenu(0, player.getInventory(), village, ranch, mayor)
                        .writeOpeningData(buffer));
    }
}
