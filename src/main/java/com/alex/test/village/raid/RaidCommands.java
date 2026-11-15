package com.alex.test.village.raid;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.function.BiFunction;

/**
 * {@code /vtest raid ...} for the village the command is run in:
 * <ul>
 * <li>{@code start [warband|horde|riders|kidnappers] [budget]}: warning, camp and attack at once</li>
 * <li>{@code warn [warband|horde|riders|kidnappers]}: start the warning stage</li>
 * <li>{@code omen 1..5}: the Bad Omen countdown, as if you had Bad Omen of that level</li>
 * <li>{@code next}: move the raid on one stage (a Bad Omen countdown ends at once)</li>
 * <li>{@code lastwave}: a fighting Bad Omen raid skips to its last wave, the Warlord's</li>
 * <li>{@code end win|lose}: end it with that result</li>
 * <li>{@code status}: stage, schedule, counts</li>
 * <li>{@code threat}: threat, defense and budget as the village is now</li>
 * <li>{@code warlord [1..5]}: an Illager Warlord in front of you, in no raid (works anywhere)</li>
 * <li>{@code warlord showcase [stop]}: a scripted 36-second scene of everything the Warlord does</li>
 * </ul>
 */
public final class RaidCommands {

    private RaidCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        LiteralArgumentBuilder<CommandSourceStack> start = Commands.literal("start")
                .executes(ctx -> act(ctx, (village, level) -> village.raids().startNow(level, null, -1), "raid started"));
        LiteralArgumentBuilder<CommandSourceStack> warn = Commands.literal("warn")
                .executes(ctx -> act(ctx, (village, level) -> village.raids().warnNow(level, null), "warning started"));
        for (RaidFaction faction : RaidFaction.values()) {
            // The challenge is Minecraft's own raid: it only comes with Bad Omen.
            if (faction == RaidFaction.CHALLENGE) continue;
            String name = faction.name().toLowerCase(java.util.Locale.ROOT);
            start.then(Commands.literal(name)
                    .executes(ctx -> act(ctx, (village, level) -> village.raids().startNow(level, faction, -1),
                            "raid started"))
                    .then(Commands.argument("budget", IntegerArgumentType.integer(RaidMath.MIN_BUDGET, 200))
                            .executes(ctx -> act(ctx, (village, level) -> village.raids().startNow(level, faction,
                                    IntegerArgumentType.getInteger(ctx, "budget")), "raid started"))));
            warn.then(Commands.literal(name)
                    .executes(ctx -> act(ctx, (village, level) -> village.raids().warnNow(level, faction),
                            "warning started")));
        }
        return Commands.literal("raid")
                .then(start)
                .then(warn)
                .then(Commands.literal("omen")
                        .then(Commands.argument("level", IntegerArgumentType.integer(RaidOmenMath.MIN_LEVEL, RaidOmenMath.MAX_LEVEL))
                                .executes(ctx -> omen(ctx, IntegerArgumentType.getInteger(ctx, "level")))))
                .then(Commands.literal("next")
                        .executes(ctx -> act(ctx, (village, level) -> village.raids().nextStage(level), "moved on")))
                .then(Commands.literal("lastwave")
                        .executes(ctx -> act(ctx, (village, level) -> village.raids().skipToLastWave(level),
                                "skipped to the last wave")))
                .then(Commands.literal("end")
                        .then(Commands.literal("win")
                                .executes(ctx -> act(ctx, (village, level) -> village.raids().endNow(level, true), "raid ended")))
                        .then(Commands.literal("lose")
                                .executes(ctx -> act(ctx, (village, level) -> village.raids().endNow(level, false), "raid ended"))))
                .then(Commands.literal("status")
                        .executes(ctx -> report(ctx, (village, level) -> village.raids().status())))
                .then(Commands.literal("threat")
                        .executes(ctx -> report(ctx, (village, level) -> village.raids().threatReport(level))))
                .then(Commands.literal("warlord")
                        .executes(ctx -> warlord(ctx, RaidOmenMath.MIN_LEVEL))
                        .then(Commands.literal("showcase")
                                .executes(RaidCommands::showcase)
                                .then(Commands.literal("stop")
                                        .executes(ctx -> {
                                            int stopped = com.alex.test.village.raid.warlord.WarlordShowcase.stop(ctx.getSource().getLevel());
                                            ctx.getSource().sendSuccess(() -> Component.literal(stopped > 0
                                                    ? "Warlord showcase stopped and its stage taken down."
                                                    : "No Warlord showcase is running."), false);
                                            return stopped;
                                        })))
                        .then(Commands.argument("level", IntegerArgumentType.integer(RaidOmenMath.MIN_LEVEL, RaidOmenMath.MAX_LEVEL))
                                .executes(ctx -> warlord(ctx, IntegerArgumentType.getInteger(ctx, "level")))));
    }

    /** The scripted showcase of everything the Warlord does, in front of the player. */
    private static int showcase(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("raid: the showcase needs a player to run it"));
            return 0;
        }
        Component reply = com.alex.test.village.raid.warlord.WarlordShowcase.start(ctx.getSource().getLevel(), player);
        ctx.getSource().sendSuccess(() -> reply, false);
        return 1;
    }

    /**
     * An Illager Warlord of that omen level 3 blocks in front of the player, in
     * no raid (works anywhere): for trying its looks, moves and sounds.
     */
    private static int warlord(CommandContext<CommandSourceStack> ctx, int omenLevel) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("raid: warlord needs a player to run it"));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        com.alex.test.village.raid.warlord.RaidWarlord warlord = com.alex.test.DynamicVillages.RAID_WARLORD.get().create(level);
        if (warlord == null) return 0;
        net.minecraft.world.phys.Vec3 ahead = player.getLookAngle().multiply(1, 0, 1).normalize().scale(3);
        warlord.moveTo(player.getX() + ahead.x, player.getY(), player.getZ() + ahead.z, player.getYRot() + 180.0F, 0.0F);
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(warlord, level, level.getCurrentDifficultyAt(warlord.blockPosition()),
                net.minecraft.world.entity.MobSpawnType.COMMAND, null);
        warlord.setOmenLevel(omenLevel);
        warlord.setPersistenceRequired();
        level.addFreshEntity(warlord);
        ctx.getSource().sendSuccess(() -> Component.literal("Illager Warlord (omen " + RaidOmenMath.roman(omenLevel)
                + ", " + (int) warlord.getMaxHealth() + " health) spawned"), false);
        return 1;
    }

    /** Starts the Bad Omen countdown as if the player running the command had Bad Omen of that level. */
    private static int omen(CommandContext<CommandSourceStack> ctx, int level) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("raid: omen needs a player to run it"));
            return 0;
        }
        VillageManager village = villageHere(ctx);
        if (village == null) return 0;
        if (!village.raids().onBadOmen(ctx.getSource().getLevel(), player, level)) {
            ctx.getSource().sendFailure(Component.literal("raid: the Bad Omen was refused (see the line in chat, "
                    + "or a Bad Omen raid is already fighting)"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Bad Omen " + RaidOmenMath.roman(level) + ": "
                + village.raids().status()), false);
        return 1;
    }

    /** Runs an action that answers null on success or why it could not. */
    private static int act(CommandContext<CommandSourceStack> ctx,
                           BiFunction<VillageManager, ServerLevel, String> action, String done) {
        VillageManager village = villageHere(ctx);
        if (village == null) return 0;
        String problem = action.apply(village, ctx.getSource().getLevel());
        if (problem != null) {
            ctx.getSource().sendFailure(Component.literal("raid: " + problem));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(done + ": " + village.raids().status()), false);
        return 1;
    }

    private static int report(CommandContext<CommandSourceStack> ctx,
                              BiFunction<VillageManager, ServerLevel, String> text) {
        VillageManager village = villageHere(ctx);
        if (village == null) return 0;
        String line = text.apply(village, ctx.getSource().getLevel());
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    @Nullable
    private static VillageManager villageHere(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos pos = BlockPos.containing(ctx.getSource().getPosition());
        VillageManager village = VillageManagerRegistry.get(ctx.getSource().getServer()).findVillageAt(level, pos);
        if (village == null) ctx.getSource().sendFailure(Component.literal("raid: no village contains " + pos.toShortString()));
        return village;
    }
}
