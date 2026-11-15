package com.alex.test.command;

import com.alex.test.network.StuckWatchPayloads;
import com.alex.test.village.stuckwatch.StuckWatch;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * {@code /villager stuckwatch}: the stuck catcher (2026-10-18). When a villager
 * near a player gets stuck, the world freezes, a report is written, and the
 * last 15 s can be scrubbed as a ghost or played again. Not saved; off until
 * turned on.
 *
 * <pre>
 *   /villager stuckwatch                      settings and the last catch
 *   /villager stuckwatch on|off               start or stop watching
 *   /villager stuckwatch recordonly on|off    write reports but never freeze
 *   /villager stuckwatch early on|off         also catch the first signs (pressed 1 s, no closer 2 s)
 *   /villager stuckwatch catch &lt;kind&gt; on|off  stuck, rescue, gaveup, blocked, trapped
 *   /villager stuckwatch only &lt;name&gt;|everyone watch one villager (name or id start)
 *   /villager stuckwatch radius &lt;blocks&gt;      villagers this near a player are recorded (64)
 *   /villager stuckwatch cooldown &lt;seconds&gt;   same villager, kind and spot caught again after (20)
 *   /villager stuckwatch resume               unfreeze at full speed, give brains back
 *   /villager stuckwatch step [ticks]         let the frozen world run a few ticks (1)
 *   /villager stuckwatch play [tps]           run in slow motion until resume (4)
 *   /villager stuckwatch rewind [seconds]     put the caught villager back and hold it there (3)
 *   /villager stuckwatch at &lt;ticks&gt;          move the ghost to that many ticks from the catch (-40)
 *   /villager stuckwatch show                 send the last catch's ghost again
 *   /villager stuckwatch goto                 fly to the caught villager
 *   /villager stuckwatch report               the last report's file
 * </pre>
 */
public final class StuckWatchCommands {

    private StuckWatchCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        LiteralArgumentBuilder<CommandSourceStack> catchKinds = Commands.literal("catch");
        for (StuckWatch.Kind kind : StuckWatch.Kind.values()) {
            // The early trigger has its own switch (early on/off), not a second one here.
            if (kind != StuckWatch.Kind.EARLY) catchKinds.then(toggle(kind.id, kind::set));
        }
        return Commands.literal("stuckwatch")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("on").executes(ctx -> {
                    StuckWatch.turnOn();
                    status(ctx.getSource());
                    ctx.getSource().sendSuccess(() -> Component.literal("Recording now. A villager can be caught once "
                            + "it has 15 s recorded, so the ghost and the rewind always have the whole 15 s.")
                            .withStyle(ChatFormatting.GRAY), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    StuckWatch.turnOff(ctx.getSource().getServer());
                    return status(ctx.getSource());
                }))
                .then(toggle("recordonly", StuckWatch::setRecordOnly))
                .then(toggle("early", StuckWatch::setEarly))
                .then(catchKinds)
                .then(Commands.literal("only")
                        .then(Commands.literal("everyone").executes(ctx -> {
                            StuckWatch.setOnlyName(null);
                            return status(ctx.getSource());
                        }))
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                            StuckWatch.setOnlyName(StringArgumentType.getString(ctx, "name"));
                            return status(ctx.getSource());
                        })))
                .then(Commands.literal("radius")
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(8, 256)).executes(ctx -> {
                            StuckWatch.setRadius(IntegerArgumentType.getInteger(ctx, "blocks"));
                            return status(ctx.getSource());
                        })))
                .then(Commands.literal("cooldown")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 600)).executes(ctx -> {
                            StuckWatch.setCooldownSeconds(IntegerArgumentType.getInteger(ctx, "seconds"));
                            return status(ctx.getSource());
                        })))
                .then(Commands.literal("resume").executes(ctx -> {
                    StuckWatch.resume(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal("Resumed at full speed.")
                            .withStyle(ChatFormatting.GREEN), true);
                    return 1;
                }))
                .then(Commands.literal("step")
                        .executes(ctx -> step(ctx.getSource(), 1))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 1200))
                                .executes(ctx -> step(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "ticks")))))
                .then(Commands.literal("play")
                        .executes(ctx -> play(ctx.getSource(), 4.0F))
                        .then(Commands.argument("tps", FloatArgumentType.floatArg(0.5F, 20.0F))
                                .executes(ctx -> play(ctx.getSource(), FloatArgumentType.getFloat(ctx, "tps")))))
                .then(Commands.literal("rewind")
                        .executes(ctx -> rewind(ctx.getSource(), 3.0))
                        .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(0.0, 20.0))
                                .executes(ctx -> rewind(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "seconds")))))
                .then(Commands.literal("at")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(-1000, 0)).executes(ctx -> {
                            if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) return 0;
                            PacketDistributor.sendToPlayer(player,
                                    new StuckWatchPayloads.Cursor(IntegerArgumentType.getInteger(ctx, "ticks")));
                            return 1;
                        })))
                .then(Commands.literal("show").executes(ctx -> {
                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) return 0;
                    if (!StuckWatch.sendShow(ctx.getSource().getServer(), StuckWatch.current(), player)) {
                        ctx.getSource().sendFailure(Component.literal("Nothing caught yet."));
                        return 0;
                    }
                    return 1;
                }))
                .then(Commands.literal("goto").executes(ctx -> {
                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) return 0;
                    if (!StuckWatch.goTo(player)) {
                        ctx.getSource().sendFailure(Component.literal("Nothing caught yet."));
                        return 0;
                    }
                    return 1;
                }))
                .then(Commands.literal("report").executes(ctx -> report(ctx.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> toggle(String name, Consumer<Boolean> set) {
        return Commands.literal(name)
                .then(Commands.literal("on").executes(ctx -> {
                    set.accept(true);
                    return status(ctx.getSource());
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    set.accept(false);
                    return status(ctx.getSource());
                }));
    }

    private static int step(CommandSourceStack source, int ticks) {
        if (!StuckWatch.step(source.getServer(), ticks)) {
            source.sendFailure(Component.literal("The world is not frozen."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Running " + ticks + (ticks == 1 ? " tick." : " ticks."))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int play(CommandSourceStack source, float tps) {
        StuckWatch.play(source.getServer(), tps);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Playing at %.1f ticks a second. /villager stuckwatch resume for full speed.", tps))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int rewind(CommandSourceStack source, double seconds) {
        Component result = StuckWatch.rewind(source.getServer(), seconds);
        source.sendSuccess(() -> result, true);
        return 1;
    }

    private static int report(CommandSourceStack source) {
        StuckWatch.StuckCatch c = StuckWatch.current();
        if (c == null || c.reportPath == null) {
            source.sendFailure(Component.literal("No report yet."));
            return 0;
        }
        String path = c.reportPath.toAbsolutePath().toString();
        source.sendSuccess(() -> Component.literal("Report #" + c.id + ": " + path).withStyle(Style.EMPTY
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, path))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to copy")))), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        StringBuilder kinds = new StringBuilder();
        for (StuckWatch.Kind kind : StuckWatch.Kind.values()) {
            if (kind == StuckWatch.Kind.EARLY) continue;
            if (kinds.length() > 0) kinds.append(", ");
            kinds.append(kind.id).append(' ').append(onOff(kind.on()));
        }
        StuckWatch.StuckCatch last = StuckWatch.current();
        String text = "Stuck catcher " + onOff(StuckWatch.enabled())
                + ": record only " + onOff(StuckWatch.recordOnly())
                + ", early " + onOff(StuckWatch.early())
                + ", watching " + (StuckWatch.onlyName() == null ? "everyone" : "\"" + StuckWatch.onlyName() + "\"")
                + " within " + StuckWatch.radius() + " blocks, cooldown " + StuckWatch.cooldownSeconds() + " s."
                + "\nCatches: " + kinds
                + (last == null ? "" : "\nLast: #" + last.id + " " + last.name + ", " + last.at.kind().label
                + (StuckWatch.viewing() ? " (frozen now)" : ""));
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static String onOff(boolean on) {
        return on ? "ON" : "off";
    }
}
