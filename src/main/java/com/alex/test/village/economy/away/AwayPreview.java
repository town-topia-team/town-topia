package com.alex.test.village.economy.away;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.economy.AwayClock;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * The two commands of the new away calculator:
 * <pre>
 *   /vtest away preview [here|villageId] [days]   what a catch-up from now would do; changes nothing
 *   /villager awaycalc [old|new]                  says the new one runs; "old" says it was removed (round 12).
 *                                                 The whole switch goes once the author has played with it.
 * </pre>
 * The preview's chat is a few lines; the {@code [AwayRun]} / {@code [AwayDay]}
 * log lines are the same a real catch-up writes.
 */
public final class AwayPreview {

    private AwayPreview() {}

    /** Under {@code /vtest}. */
    public static LiteralArgumentBuilder<CommandSourceStack> previewNode() {
        return Commands.literal("away")
                .then(Commands.literal("preview")
                        .executes(ctx -> preview(ctx, "here", AwayClock.MAX_AWAY_DAYS))
                        .then(Commands.argument("village", StringArgumentType.word())
                                .executes(ctx -> preview(ctx, StringArgumentType.getString(ctx, "village"),
                                        AwayClock.MAX_AWAY_DAYS))
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, AwayClock.MAX_AWAY_DAYS))
                                        .executes(ctx -> preview(ctx, StringArgumentType.getString(ctx, "village"),
                                                IntegerArgumentType.getInteger(ctx, "days"))))));
    }

    /** Under {@code /villager}. */
    public static LiteralArgumentBuilder<CommandSourceStack> switchNode() {
        return Commands.literal("awaycalc")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> reportSwitch(ctx.getSource()))
                .then(Commands.literal("old").executes(ctx -> setSwitch(ctx.getSource(), false)))
                .then(Commands.literal("new").executes(ctx -> setSwitch(ctx.getSource(), true)));
    }

    private static int setSwitch(CommandSourceStack source, boolean on) {
        if (!on) {
            source.sendFailure(Component.literal("The old away calculator was removed (round 12 of the rebuild);"
                    + " its files are in backups/away-calculator-2026-10-22/round-12."));
            return 0;
        }
        return reportSwitch(source);
    }

    private static int reportSwitch(CommandSourceStack source) {
        String text = "Away calculator: new, the only one since round 12. While away villagers eat, get hungry,"
                + " sleep and can starve; fields grow and farmers work them; cooks cook; ranchers tend and breed"
                + " the pens; butchers cull what is spare; lumberjacks fell and replant trees;"
                + " miners dig on in their mines; smiths forge what is missing;"
                + " enchanters write the books the tools want; teachers teach the school days;"
                + " the other jobs only eat and sleep.";
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int preview(CommandContext<CommandSourceStack> ctx, String which, int dayCount) {
        CommandSourceStack source = ctx.getSource();
        VillageManager mgr = find(source, which);
        if (mgr == null) {
            source.sendFailure(Component.literal("No village found. Stand inside one, or give its id"
                    + " (/vtest villages lists them)."));
            return 0;
        }
        ServerLevel level = mgr.getLevel() != null ? mgr.getLevel() : source.getLevel();
        AwayRun run = AwayRun.previewAll(level, mgr, dayCount);
        AwayBook book = run.book();
        send(source, ChatFormatting.GOLD, "Away preview, village " + AwayRun.shortId(mgr.getVillageId())
                + ", " + dayCount + " day(s) from now. Nothing was changed.");
        send(source, ChatFormatting.GRAY, run.villagers().size() + " villager(s)"
                + (run.noBody() > 0 ? " (" + run.noBody() + " more with no body, skipped)" : "")
                + "; " + book.containers() + " container(s) in " + book.rooms() + " storage room(s) holding "
                + book.items() + " item(s); " + run.days().size() + " day part(s) worked out.");
        send(source, ChatFormatting.GRAY, "Made: " + AwayRun.counts(run.produced)
                + ". Used: " + AwayRun.counts(run.consumed) + ".");
        send(source, ChatFormatting.GRAY, "Meals: " + run.mealsEaten + " serving(s); " + run.wentHungry()
                + " villager(s) at hunger 0 at some time, " + run.died + " would starve to death ("
                + run.starvationHits + " starving hit(s)); " + book.food() + " food left in storage.");
        send(source, ChatFormatting.GRAY, "Fields: " + run.farm().describe() + ".");
        send(source, ChatFormatting.GRAY, "Kitchen: " + run.kitchen().describe() + ".");
        send(source, ChatFormatting.GRAY, "Pens: " + run.pens().describe() + ".");
        send(source, ChatFormatting.GRAY, "Trees: " + run.trees().describe() + ".");
        send(source, ChatFormatting.GRAY, "Mines: " + run.mines().describe() + ".");
        send(source, ChatFormatting.GRAY, "Stock tab orders: " + run.orders().describe() + ".");
        send(source, ChatFormatting.GRAY, "Did not work: " + reasons(run.stopped) + ".");
        send(source, ChatFormatting.DARK_GRAY, "Per day lines: [AwayDay], [AwayFarmer], [AwayCook], [AwayRancher],"
                + " [AwayButcher], [AwayLumberjack], [AwayMiner], [AwayBlacksmith], [AwayEnchanter], [AwayTeacher]"
                + " in the log.");
        return 1;
    }

    /** "no away work yet x30, no body x2", or "nobody". */
    private static String reasons(Map<String, Integer> stopped) {
        if (stopped.isEmpty()) return "nobody";
        StringBuilder text = new StringBuilder();
        stopped.forEach((reason, count) -> {
            if (!text.isEmpty()) text.append(", ");
            text.append(reason).append(" x").append(count);
        });
        return text.toString();
    }

    private static void send(CommandSourceStack source, ChatFormatting style, String text) {
        source.sendSuccess(() -> Component.literal(text).withStyle(style), false);
    }

    @Nullable
    private static VillageManager find(CommandSourceStack source, String which) {
        MinecraftServer server = source.getServer();
        VillageManagerRegistry registry = VillageManagerRegistry.get(server);
        if ("here".equalsIgnoreCase(which)) {
            return registry.findVillageAt(source.getLevel(), BlockPos.containing(source.getPosition()));
        }
        for (VillageManager mgr : registry.all()) {
            UUID id = mgr.getVillageId();
            if (AwayRun.shortId(id).equalsIgnoreCase(which) || id.toString().equalsIgnoreCase(which)) return mgr;
        }
        return null;
    }
}
