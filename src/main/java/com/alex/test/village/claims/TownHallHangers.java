package com.alex.test.village.claims;

import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import com.alex.test.village.tokens.VillageTokenBinding;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who last hung a fresh Town Hall token in each frame, so the village it founds knows its owner. A click only
 * queues the frame; the village is founded a tick later, or many ticks later when it is set up in slices
 * (TownHallSetups), so the player is remembered by frame id until then. One per server (held by the registry),
 * not saved: a founding after a restart has no owner and is claimed at the Mayor.
 */
public final class TownHallHangers {

    /** A founding that has not used its hanger after this long (1 hour of server ticks) forgets it. */
    private static final int KEEP_TICKS = 20 * 60 * 60;

    public record Hanger(UUID playerId, String name, int atTick) {}

    private final Map<UUID, Hanger> byFrame = new HashMap<>();

    /**
     * A player right-clicked {@code frame} holding {@code held}: either an empty frame with a Town Hall token in
     * that hand, or a frame already holding a fresh (unbound) one, such as after fixing the room. Machines from
     * other mods (fake players) never found a village for anyone.
     */
    public void note(ServerPlayer player, ItemFrame frame, ItemStack held, int nowTick) {
        if (player instanceof FakePlayer) return;
        ItemStack inFrame = frame.getItem();
        boolean hanging = inFrame.isEmpty() && isTownHall(held);
        boolean freshInFrame = isTownHall(inFrame) && !VillageTokenBinding.isBound(inFrame);
        if (!hanging && !freshInFrame) return;
        byFrame.values().removeIf(hanger -> nowTick - hanger.atTick() > KEEP_TICKS);
        byFrame.put(frame.getUUID(), new Hanger(player.getUUID(), player.getGameProfile().getName(), nowTick));
    }

    /** The player who hung the token in this frame, forgotten once asked. */
    @Nullable
    public Hanger take(UUID frameId, int nowTick) {
        Hanger hanger = byFrame.remove(frameId);
        return hanger == null || nowTick - hanger.atTick() > KEEP_TICKS ? null : hanger;
    }

    private static boolean isTownHall(ItemStack stack) {
        return ItemFrameTokenRegistry.tokenType(stack) == ItemFrameTokenRegistry.TokenType.TOWN_HALL;
    }
}
