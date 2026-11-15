package com.alex.test.village.building;

import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.ui.MineRoomMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.decoration.ItemFrame;

import javax.annotation.Nullable;

/** Opens a mine's Mine screen, from its token or from the Mayor. */
public final class MineRoomAccess {
    private MineRoomAccess() {}

    /** A registered mine and the village it belongs to. */
    public record Found(VillageManager village, MineBuilding mine) {}

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
            if (building instanceof MineBuilding mine && mine.getItemFrameId().equals(frame.getUUID())) {
                return new Found(village, mine);
            }
        }
        return null;
    }

    /** Opens the Mine screen for a registered mine's token; false when the frame is not one. */
    public static boolean openFromFrame(ServerPlayer player, ItemFrame frame) {
        if (!(player.level() instanceof ServerLevel level) || player.distanceToSqr(frame) >= 64.0) return false;
        Found found = forFrame(level, frame);
        if (found == null) return false;
        open(player, found.village(), found.mine(), null);
        return true;
    }

    /** Opens the Mine screen; {@code mayor} is the Mayor it was opened through, or null. */
    public static void open(ServerPlayer player, VillageManager village, MineBuilding mine,
                            @Nullable MayorEntity mayor) {
        ServerLevel level = player.serverLevel();
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, ignored) -> new MineRoomMenu(id, inventory, village, mine, level, mayor),
                Component.literal(MineRoomMenu.titleFor(mine))),
                buffer -> new MineRoomMenu(0, player.getInventory(), village, mine, level, mayor)
                        .writeOpeningData(buffer));
    }
}
