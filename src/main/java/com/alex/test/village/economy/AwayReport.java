package com.alex.test.village.economy;

import com.alex.test.Tr;
import com.alex.test.village.VillageManager;
import com.alex.test.village.claims.VillageClaims;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The welcome-back lines a player gets on walking into a village that
 * caught up: a greeting, the goods it made most, and how many villagers are
 * starving; after an error of ours, one line that some work was not counted.
 * Sent once, to the players inside the border when the catch-up ends; in an
 * owned village only to its owner, Officers and Friends.
 */
public final class AwayReport {

    /** Shorter catch-ups (a quarter day) are not worth a message. */
    private static final long MIN_COUNTED_TICKS = 6_000L;
    /** Goods named in the "Made" line, most first. */
    private static final int TOP_GOODS = 5;

    private AwayReport() {}

    public static void send(ServerLevel level, VillageManager mgr, CatchUp away) {
        List<Component> lines = away.countedTicks() < MIN_COUNTED_TICKS ? new ArrayList<>() : lines(away);
        // Only after an error of ours, whatever the length (leftovers round 2).
        if (away.somethingBroke()) lines.add(brokeLine());
        sendTo(level, mgr, lines);
    }

    /** A catch-up that failed outright ({@code VillageDormancy}): only the line that some work was not counted. */
    public static void sendBroke(ServerLevel level, VillageManager mgr) {
        sendTo(level, mgr, List.of(brokeLine()));
    }

    private static void sendTo(ServerLevel level, VillageManager mgr, List<Component> lines) {
        if (lines.isEmpty()) return;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !mgr.contains(player.blockPosition())) continue;
            // Claims (10-25): in an owned village only its owner, Officers and Friends are welcomed back.
            if (!VillageClaims.hearsNews(player, mgr)) continue;
            for (Component line : lines) player.sendSystemMessage(line);
        }
    }

    private static Component brokeLine() {
        return Tr.c("chat.dynamicvillages.away.broke",
                "Some of the village's work while you were away could not be counted (see the log).")
                .withStyle(ChatFormatting.YELLOW);
    }

    /** The greeting, what was made and who is starving (user, 10-18): no days away, no days worked. */
    private static List<Component> lines(CatchUp away) {
        List<Component> lines = new ArrayList<>();
        lines.add(Tr.c("chat.dynamicvillages.away.welcome", "Welcome back!").withStyle(ChatFormatting.GOLD));
        Component made = goods(away.made());
        if (made != null) {
            lines.add(Tr.c("chat.dynamicvillages.away.made", "While you were away, the village made: %s",
                    made).withStyle(ChatFormatting.GRAY));
        }
        // Alive and at hunger zero at the end of the catch-up: starving now. The dead are not counted
        // (no death line, user 10-19).
        if (away.starvingNow() == 1) {
            lines.add(Tr.c("chat.dynamicvillages.away.starving_one", "1 villager is starving.")
                    .withStyle(ChatFormatting.RED));
        } else if (away.starvingNow() > 1) {
            lines.add(Tr.c("chat.dynamicvillages.away.starving", "%s villagers are starving.",
                    away.starvingNow()).withStyle(ChatFormatting.RED));
        }
        return lines;
    }

    /** "120 Bread, 64 Cobblestone, ..." for the goods the village has most more of; null when none. */
    private static Component goods(Map<Item, Integer> made) {
        List<Map.Entry<Item, Integer>> top = made.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<Item, Integer>comparingByValue().reversed())
                .limit(TOP_GOODS)
                .toList();
        if (top.isEmpty()) return null;
        MutableComponent list = Component.empty();
        for (int i = 0; i < top.size(); i++) {
            if (i > 0) list.append(", ");
            list.append(top.get(i).getValue() + " ").append(top.get(i).getKey().getDescription());
        }
        return list;
    }

}
