package com.alex.test.command;

import com.alex.test.village.villagers.druid.behavior.DruidSounds;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * {@code /villager druidsound [loop <0-100> | finish <0-100> | lead <ticks> | rustle <0-100>
 * | crops|mine loop <0-100> | crops|mine finish <0-100> | crops|mine lead <ticks>]}:
 * how loud the druid's growing hum and tree finish sound are, how long
 * before the tree the finish starts, and how loud the one leaf rustle is as
 * it cuts its way out; and the same hum, finish and lead for tending crops
 * and regrowing a mine ({@link DruidSounds}). The hums are played by the
 * client, so {@code loop} works in single player only. Live tuning only:
 * not saved, restart = the defaults.
 */
public final class DruidSoundCommands {
    private DruidSoundCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("druidsound")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> show(ctx.getSource()))
                .then(Commands.literal("loop")
                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                .executes(ctx -> {
                                    DruidSounds.setLoopPercent(IntegerArgumentType.getInteger(ctx, "percent"));
                                    return show(ctx.getSource());
                                })))
                .then(Commands.literal("finish")
                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                .executes(ctx -> {
                                    DruidSounds.setFinishPercent(IntegerArgumentType.getInteger(ctx, "percent"));
                                    return show(ctx.getSource());
                                })))
                .then(Commands.literal("lead")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(0, DruidSounds.MAX_LEAD_TICKS))
                                .executes(ctx -> {
                                    DruidSounds.setLeadTicks(IntegerArgumentType.getInteger(ctx, "ticks"));
                                    return show(ctx.getSource());
                                })))
                .then(Commands.literal("rustle")
                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                .executes(ctx -> {
                                    DruidSounds.setRustlePercent(IntegerArgumentType.getInteger(ctx, "percent"));
                                    return show(ctx.getSource());
                                })))
                .then(jobWords("crops", DruidSounds::setCropLoopPercent,
                        DruidSounds::setCropFinishPercent, DruidSounds::setCropLeadTicks))
                .then(jobWords("mine", DruidSounds::setMineLoopPercent,
                        DruidSounds::setMineFinishPercent, DruidSounds::setMineLeadTicks));
    }

    /** {@code <job> loop <0-100>}, {@code <job> finish <0-100>} and {@code <job> lead <ticks>}. */
    private static LiteralArgumentBuilder<CommandSourceStack> jobWords(
            String job, IntConsumer setLoop, IntConsumer setFinish, IntConsumer setLead) {
        return Commands.literal(job)
                .then(Commands.literal("loop")
                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                .executes(ctx -> {
                                    setLoop.accept(IntegerArgumentType.getInteger(ctx, "percent"));
                                    return show(ctx.getSource());
                                })))
                .then(Commands.literal("finish")
                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                .executes(ctx -> {
                                    setFinish.accept(IntegerArgumentType.getInteger(ctx, "percent"));
                                    return show(ctx.getSource());
                                })))
                .then(Commands.literal("lead")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(0, DruidSounds.MAX_LEAD_TICKS))
                                .executes(ctx -> {
                                    setLead.accept(IntegerArgumentType.getInteger(ctx, "ticks"));
                                    return show(ctx.getSource());
                                })));
    }

    private static int show(CommandSourceStack source) {
        int loop = DruidSounds.loopPercent();
        int percent = DruidSounds.finishPercent();
        int lead = DruidSounds.leadTicks();
        int rustle = DruidSounds.rustlePercent();
        int cropLoop = DruidSounds.cropLoopPercent();
        int mineLoop = DruidSounds.mineLoopPercent();
        int cropFinish = DruidSounds.cropFinishPercent();
        int cropLead = DruidSounds.cropLeadTicks();
        int mineFinish = DruidSounds.mineFinishPercent();
        int mineLead = DruidSounds.mineLeadTicks();
        source.sendSuccess(() -> Component.literal("Druid tree hum: " + loop + "% (default "
                + DruidSounds.DEFAULT_LOOP_PERCENT + "%, single player only). Finish sound: " + percent
                + "% (default " + DruidSounds.DEFAULT_FINISH_PERCENT + "%), starts " + lead + " ticks = "
                + (lead / 20.0) + " s before the tree (default " + DruidSounds.DEFAULT_LEAD_TICKS
                + "). Leaf rustle cutting out of the new tree: " + rustle + "% (default "
                + DruidSounds.DEFAULT_RUSTLE_PERCENT + "%). Crop hum: " + cropLoop + "% (default "
                + DruidSounds.DEFAULT_CROP_LOOP_PERCENT + "%, single player only). Crop ripening: " + cropFinish
                + "% (default " + DruidSounds.DEFAULT_CROP_FINISH_PERCENT + "%), " + cropLead + " ticks early (default "
                + DruidSounds.DEFAULT_CROP_LEAD_TICKS + "). Mine hum: " + mineLoop + "% (default "
                + DruidSounds.DEFAULT_MINE_LOOP_PERCENT + "%, single player only). Mine block: " + mineFinish + "% (default "
                + DruidSounds.DEFAULT_MINE_FINISH_PERCENT + "%), " + mineLead + " ticks early (default "
                + DruidSounds.DEFAULT_MINE_LEAD_TICKS + "). Not saved."), false);
        return percent;
    }
}
