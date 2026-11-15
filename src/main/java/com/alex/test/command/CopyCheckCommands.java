package com.alex.test.command;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.villagers.builder.copy.CopyLandCheck;
import com.alex.test.village.villagers.builder.copy.LandTrust;
import com.alex.test.village.villagers.builder.copy.LandView;
import com.alex.test.village.villagers.builder.copy.PlacedJudge;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * What the Builder's copy makes of one block: the old rule, the land test
 * (docs/plans/2026-10-23-builder-copy-placed-blocks-design.md) and what a copy takes now with the
 * {@code /villager walkfeel copy*} switches as they are. Read only: nothing in the world changes.
 *
 * <pre>
 *   /villager copycheck [x y z]   the block looked at, or that block; each trust sample goes to
 *                                 the debug log as [CopyCheck]
 * </pre>
 */
public final class CopyCheckCommands {

    private static final int LOOK_REACH = 20;

    private CopyCheckCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("copycheck")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> checkLookedAt(ctx.getSource()))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> check(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"))));
    }

    private static int checkLookedAt(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Look at a block, or give its x y z."));
            return 0;
        }
        HitResult hit = player.pick(LOOK_REACH, 1.0f, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Look at a block within " + LOOK_REACH + " blocks."));
            return 0;
        }
        return check(source, blockHit.getBlockPos());
    }

    private static int check(CommandSourceStack source, BlockPos pos) {
        ServerLevel level = source.getLevel();
        VillageManager village = VillageManagerRegistry.get(level.getServer()).findVillageAt(level, pos);
        CopyLandCheck.Inspection check = CopyLandCheck.inspect(level, village, pos);

        say(source, Component.literal("Copy check at " + pos.toShortString() + " (" + check.blockName() + ")")
                .withStyle(ChatFormatting.GOLD));
        say(source, Component.literal("What it is: " + groupWords(check.group())));
        if (check.building() == null) {
            say(source, Component.literal(village == null ? "No village here: copies only come from a village."
                    : "No registered building near it: copies of it keep today's rule.").withStyle(ChatFormatting.GRAY));
        } else if (check.mine()) {
            say(source, Component.literal("Building: " + check.building()
                    + ". Mines always keep today's rule: the land test is off for them."));
        } else if (check.lowestFloor() == PlacedJudge.NO_FLOOR) {
            say(source, Component.literal("Building: " + check.building()
                    + ", but its room scan found no floor: its copies keep today's rule."));
        } else {
            say(source, Component.literal("Building: " + check.building() + ". Lowest floor at y " + check.lowestFloor()
                    + " (the floor under its door: y " + check.doorFloor() + ")."));
        }
        say(source, Component.literal("Bare land here: " + bareWords(check.bare())
                + (check.bareTop() == LandTrust.NONE ? "" : ". Its top in this column: y " + check.bareTop()) + "."));
        say(source, Component.literal("Old rule (before 10-23): " + (check.builtToday() ? "takes it." : "leaves it out."))
                .withStyle(ChatFormatting.GRAY));
        PlacedJudge.Judgement judgement = check.judgement();
        String verdict = switch (judgement.verdict()) {
            case PLACED -> "placed, part of the building";
            case WORLD -> "the world made it";
            case TODAY -> "no opinion, the old rule decides";
        };
        say(source, Component.literal("Land test: " + verdict + ". Why: " + judgement.reason().words() + "."
                        + (check.remembered() ? " (Remembered as placed.)" : ""))
                .withStyle(switch (judgement.verdict()) {
                    case PLACED -> ChatFormatting.GREEN;
                    case WORLD -> ChatFormatting.RED;
                    case TODAY -> ChatFormatting.WHITE;
                }));
        say(source, Component.literal("A copy of " + (check.building() == null ? "it" : "this building") + " now: "
                        + (check.builtNow() ? "takes it." : "leaves it out."))
                .withStyle(check.builtNow() ? ChatFormatting.GREEN : ChatFormatting.RED));
        String off = switchesOff(check.rules(), check.logs());
        if (!off.isEmpty()) {
            say(source, Component.literal("Switched off: " + off + ".").withStyle(ChatFormatting.YELLOW));
        }

        LandTrust.Vote vote = check.vote();
        LandTrust.Thresholds limits = check.thresholds();
        say(source, Component.literal("Land around it: " + vote.usable() + " usable of " + vote.looked()
                        + " columns looked at; " + vote.within() + " within " + limits.tolerance() + " block of the bare land, "
                        + vote.higher() + " higher, " + vote.lower() + " lower. "
                        + (vote.trusted() ? "Trusted." : "Not trusted.")
                        + " (Needs " + limits.minUsable() + " usable and at most " + limits.maxHigherPercent()
                        + "% higher.)")
                .withStyle(vote.trusted() ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        int[] gaps = vote.byGap();
        say(source, Component.literal("Gaps 0/1/2/3/4+ blocks: " + gaps[0] + " / " + gaps[1] + " / " + gaps[2]
                + " / " + gaps[3] + " / " + gaps[4] + ".").withStyle(ChatFormatting.GRAY));
        say(source, Component.literal("World generator: " + check.bareColumns() + " columns, " + ms(check.msPerColumn())
                + " each (top height only: " + ms(check.msPerTopHeight()) + " each). Whole check "
                + String.format("%.1f", check.totalMs()) + " ms. Samples are in the debug log as [CopyCheck].")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static void say(CommandSourceStack source, Component line) {
        source.sendSuccess(() -> line, false);
    }

    /** The {@code /villager walkfeel copy*} switches that are off, like "copytrust, copylogs"; "" when none. */
    private static String switchesOff(PlacedJudge.Rules rules, boolean logs) {
        StringBuilder off = new StringBuilder();
        if (!rules.land()) off.append(", copyland");
        if (!rules.soil()) off.append(", copysoil");
        if (!rules.trust()) off.append(", copytrust");
        if (!rules.memory()) off.append(", copymemory");
        if (!rules.lookalikes()) off.append(", copylookalikes");
        if (!logs) off.append(", copylogs");
        return off.isEmpty() ? "" : off.substring(2);
    }

    private static String ms(double ms) {
        return ms < 0 ? "none" : String.format("%.2f ms", ms);
    }

    private static String groupWords(LandView.Group group) {
        return switch (group) {
            case BUILT -> "a building block. Copies always take it.";
            case NEVER -> "never part of a copy (air, water, plants, tree leaves, dripstone...).";
            case LOG -> "a log: a tree or a build, by today's rule.";
            case SANDSTONE -> "sandstone, a land block.";
            case ROCK -> "rock, a land block.";
            case SOIL -> "soil, a land block.";
            case FROZEN -> "ice or snow, a land block.";
            case LOOKALIKE -> "terracotta or infested stone: building, unless the world made it (a mesa, a mountain).";
            case HARD_ICE -> "packed or blue ice: building, unless the world made it (an iceberg, a glacier).";
            case UNLOADED -> "in a chunk that is not loaded.";
        };
    }

    private static String bareWords(LandView.Bare bare) {
        return switch (bare) {
            case SOLID -> "solid (the world's land)";
            case AIR -> "air";
            case FLUID -> "water or lava";
            case UNKNOWN -> "no answer from the world generator";
        };
    }
}
