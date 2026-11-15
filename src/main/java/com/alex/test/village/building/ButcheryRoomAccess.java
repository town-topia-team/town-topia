package com.alex.test.village.building;

import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.ui.ButcheryRoomMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.decoration.ItemFrame;

import javax.annotation.Nullable;

/** Opens a Butchery's screen, from its token or from the Mayor. */
public final class ButcheryRoomAccess {
    private ButcheryRoomAccess() {}

    /** A registered Butchery and the village it belongs to. */
    public record Found(VillageManager village, ButcheryBuilding butchery) {}

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
            if (building instanceof ButcheryBuilding butchery && butchery.getItemFrameId().equals(frame.getUUID())) {
                return new Found(village, butchery);
            }
        }
        return null;
    }

    /** Opens the Butchery screen for a registered Butchery's token; false when the frame is not one. */
    public static boolean openFromFrame(ServerPlayer player, ItemFrame frame) {
        if (!(player.level() instanceof ServerLevel level) || player.distanceToSqr(frame) >= 64.0) return false;
        Found found = forFrame(level, frame);
        if (found == null) return false;
        open(player, found.village(), found.butchery(), null);
        return true;
    }

    /** Opens the Butchery screen; {@code mayor} is the Mayor it was opened through (round 7), or null. */
    public static void open(ServerPlayer player, VillageManager village, ButcheryBuilding butchery,
                            @Nullable MayorEntity mayor) {
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, ignored) -> new ButcheryRoomMenu(id, inventory, village, butchery, mayor),
                Component.literal(ButcheryRoomMenu.titleFor(butchery))),
                buffer -> new ButcheryRoomMenu(0, player.getInventory(), village, butchery, mayor)
                        .writeOpeningData(buffer));
    }
}
