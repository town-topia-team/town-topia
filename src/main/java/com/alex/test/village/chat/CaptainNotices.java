package com.alex.test.village.chat;

import com.alex.test.Tr;
import com.alex.test.ModItems;
import com.alex.test.village.VillageManager;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.villagers.guard.CaptainAssignmentHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Tells the player when a Barracks is waiting for its captain: when one is
 * first ready, and when its captain has died. Nothing else in the game points
 * at the Captain token, so without these a bought token is easy to forget.
 */
public final class CaptainNotices {

    private static final double MESSAGE_RADIUS = 48.0;

    private CaptainNotices() {}

    /** A Barracks just became valid; say so if it still needs a captain. */
    public static void barracksReady(ServerLevel level, VillageManager village, BlockPos framePos) {
        if (!CaptainAssignmentHandler.hasOpenBarracks(village, null)) return;
        boolean hasToken = level.players().stream()
                .filter(player -> player.distanceToSqr(framePos.getX() + 0.5, framePos.getY() + 0.5,
                        framePos.getZ() + 0.5) <= MESSAGE_RADIUS * MESSAGE_RADIUS)
                .anyMatch(player -> carriesCaptainToken(player, village));
        TokenFeedback.announce(level, framePos, Component.literal(
                "[Village] The Barracks is ready. It can be led by one Captain.")
                .withStyle(ChatFormatting.GOLD));
        TokenFeedback.announce(level, framePos, Component.literal(
                "Give a Captain token to a guard to promote it (it keeps its guard level),"
                        + " or to a jobless villager.")
                .withStyle(ChatFormatting.GRAY));
        TokenFeedback.announce(level, framePos, Component.literal(hasToken
                ? "You received a Captain token with the Barracks. No token? The Mayor sells one"
                        + " while this Barracks has no captain."
                : "The Mayor sells a Captain token while this Barracks has no captain.")
                .withStyle(ChatFormatting.GRAY));
    }

    /** A captain died and left a Barracks without one; tells everyone in the village. */
    public static void captainLost(ServerLevel level, VillageManager village) {
        if (!CaptainAssignmentHandler.hasOpenBarracks(village, null)) return;
        Component message = Tr.c("chat.dynamicvillages.captain.village_the_barracks_has_lost_its", "[Village] The Barracks has lost its Captain.")
                .withStyle(ChatFormatting.GOLD)
                .append(Tr.c("chat.dynamicvillages.captain.the_mayor_sells_a_new_captain", " The Mayor sells a new Captain token.")
                        .withStyle(ChatFormatting.GRAY));
        for (ServerPlayer player : level.players()) {
            if (village.contains(player.blockPosition())) player.sendSystemMessage(message);
        }
    }

    private static boolean carriesCaptainToken(ServerPlayer player, VillageManager village) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(ModItems.CAPTAIN_TOKEN.get()) && VillageTokenBinding.matches(stack, village)) {
                return true;
            }
        }
        return false;
    }
}
