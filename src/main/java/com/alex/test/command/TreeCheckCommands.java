package com.alex.test.command;

import com.alex.test.DynamicVillages;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.villagers.lumberjack.data.TreeJudge;
import com.alex.test.village.villagers.lumberjack.data.TreeStructure;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What the lumberjack's tree judge makes of the trees round the player.
 * Read-only: nothing in the world changes.
 *
 * <pre>
 *   /villager treecheck [x y z]   the log looked at (its column's bottom), or that exact block:
 *                                 tree or not and why, under the rules now and before 10-19
 *   /villager treescan [radius]   every tree base round the player (24, at most 48): verdicts
 *                                 counted by reason, and what the new rules take or refuse
 *                                 differently; the whole list goes to the log as [TreeScan]
 * </pre>
 */
public final class TreeCheckCommands {

    private static final int LOOK_REACH = 20;
    private static final int DEFAULT_SCAN_RADIUS = 24;
    private static final int MAX_SCAN_RADIUS = 48;
    private static final int SCAN_BELOW = 20;
    private static final int SCAN_ABOVE = 40;
    private static final int LISTED_IN_CHAT = 10;

    private TreeCheckCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("treecheck")
                .executes(ctx -> checkLookedAt(ctx.getSource()))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> check(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> scanTree() {
        return Commands.literal("treescan")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> scan(ctx.getSource(), DEFAULT_SCAN_RADIUS))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_SCAN_RADIUS))
                        .executes(ctx -> scan(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"))));
    }

    // -------------------------------------------------------------------------
    // treecheck
    // -------------------------------------------------------------------------

    private static int checkLookedAt(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Look at a log, or give its x y z."));
            return 0;
        }
        HitResult hit = player.pick(LOOK_REACH, 1.0f, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Look at a log within " + LOOK_REACH + " blocks."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos pos = blockHit.getBlockPos();
        if (!level.getBlockState(pos).is(BlockTags.LOGS)) {
            source.sendFailure(Component.literal("That is not a log."));
            return 0;
        }
        // The lumberjack judges a tree from the bottom log of its column.
        for (int i = 0; i < 64 && isStandingLog(level.getBlockState(pos))
                && isStandingLog(level.getBlockState(pos.below())); i++) {
            pos = pos.below();
        }
        return check(source, pos);
    }

    private static boolean isStandingLog(BlockState state) {
        return state.is(BlockTags.LOGS) && TreeStructure.isUpright(state);
    }

    private static int check(CommandSourceStack source, BlockPos pos) {
        ServerLevel level = source.getLevel();
        VillageManager village = VillageManagerRegistry.get(level.getServer()).findVillageAt(level, pos);
        Predicate<BlockPos> keepOut = village != null ? village::isProtectedTreePosition : p -> false;

        source.sendSuccess(() -> Component.literal("Tree check at " + pos.toShortString() + " ("
                        + level.getBlockState(pos).getBlock().getName().getString() + ")")
                .withStyle(ChatFormatting.GOLD), false);
        if (village == null) {
            source.sendSuccess(() -> Component.literal("No village here: buildings and the border are not checked.")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        TreeStructure.Inspection now = TreeStructure.judge(level, pos, keepOut, TreeStructure.currentRules());
        TreeStructure.Inspection before = TreeStructure.judge(level, pos, keepOut, TreeJudge.Rules.old());
        source.sendSuccess(() -> describe("Now: ", now), false);
        source.sendSuccess(() -> describe("Before 10-19: ", before).withStyle(ChatFormatting.GRAY), false);

        if (now.suggestedFoot() != null) {
            BlockPos foot = now.suggestedFoot();
            TreeStructure.Inspection atFoot = TreeStructure.judge(level, foot, keepOut, TreeStructure.currentRules());
            source.sendSuccess(() -> describe("Its trunk's foot " + foot.toShortString() + ": ", atFoot)
                    .withStyle(style -> style.withClickEvent(checkClick(foot))), false);
        }
        return now.isTree() ? 1 : 0;
    }

    private static MutableComponent describe(String label, TreeStructure.Inspection inspection) {
        if (inspection.isTree()) {
            TreeStructure tree = inspection.tree();
            String crown = inspection.crownSides() < 0 ? ""
                    : ", leaves on " + inspection.crownSides() + " of 5 sides of its top";
            String shared = inspection.groupLogs() > tree.logs().size()
                    ? " (of " + inspection.groupLogs() + " joined)" : "";
            return Component.literal(label + "a tree: " + tree.treeTypeId().getPath()
                            + (tree.twoByTwo() ? " (big 2x2)" : "")
                            + ", " + tree.logs().size() + " logs" + shared + ", "
                            + tree.leaves().size() + " leaves" + crown)
                    .withStyle(ChatFormatting.GREEN);
        }
        String where = inspection.where() != null ? " at " + inspection.where().toShortString() : "";
        String crown = inspection.reason() == TreeJudge.Reason.NO_CROWN && inspection.crownSides() >= 0
                ? " (best top has " + inspection.crownSides() + ")" : "";
        ChatFormatting colour = inspection.cantSee() ? ChatFormatting.YELLOW : ChatFormatting.RED;
        return Component.literal(label + "not a tree: " + words(inspection.reason()) + crown + where
                        + " [" + inspection.reason() + ", " + inspection.logCount() + " logs]")
                .withStyle(colour);
    }

    /** The judge's reason in plain words. */
    public static String words(TreeJudge.Reason reason) {
        return switch (reason) {
            case OK -> "a tree";
            case NOT_A_LOG -> "not a standing log of a tree's wood (stripped logs and bark blocks are built)";
            case KEEP_OUT -> "protected: part of a building, a cocoa farm or outside the village";
            case TOO_SMALL -> "only one log";
            case TOO_BIG -> "more than " + TreeJudge.MAX_LOGS + " logs, or wider than the lumberjack looks";
            case TOO_TALL -> "the trunk goes on higher than the lumberjack looks";
            case NO_LEAVES -> "no grown leaves of its kind within 2 blocks";
            case TOO_MANY_LEAVES -> "more than " + TreeStructure.MAX_LEAVES + " leaves";
            case BUILT_GROUND -> "it stands on a built block";
            case NOT_SOIL -> "it stands on stone, sand, ore or the like, not on dirt (tag tree_soil)";
            case BUILD_MARK -> "a built block, stripped log or placed leaves right on or under one of its logs";
            case NO_GROUND -> "it stands on nothing solid (a branch?)";
            case NO_TRUNK -> "nothing goes up from it";
            case BUILT_ON_TOP -> "something built or another wood sits on top of the trunk";
            case NO_CROWN -> "no top has its own leaves round it on 3 of 5 sides";
            case BRANCH -> "a short branch leaning on a lower trunk of the same wood";
            case BUILD_RESTS_ON_TREE -> "a build joined to it has no ground of its own (treehouse, lantern on a branch)";
            case UNLOADED -> "part of it is in a chunk that is not loaded";
        };
    }

    private static ClickEvent checkClick(BlockPos pos) {
        return new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                "/villager treecheck " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    // -------------------------------------------------------------------------
    // treescan
    // -------------------------------------------------------------------------

    private record Difference(BlockPos base, String text) {}

    private static int scan(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        BlockPos centre = BlockPos.containing(source.getPosition());
        VillageManager village = VillageManagerRegistry.get(level.getServer()).findVillageAt(level, centre);
        Predicate<BlockPos> keepOut = village != null ? village::isProtectedTreePosition : p -> false;
        TreeJudge.Rules rules = TreeStructure.currentRules();

        Map<TreeJudge.Reason, Integer> counts = new EnumMap<>(TreeJudge.Reason.class);
        List<Difference> nowRefused = new ArrayList<>();
        List<Difference> nowTaken = new ArrayList<>();
        Set<Long> inTrees = new HashSet<>();
        int bases = 0;
        int minY = Math.max(level.getMinBuildHeight(), centre.getY() - SCAN_BELOW);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, centre.getY() + SCAN_ABOVE);

        for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
            for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (inTrees.contains(pos.asLong()) || !isBaseCandidate(level, pos)) continue;
                    bases++;
                    TreeStructure.Inspection now = TreeStructure.judge(level, pos, keepOut, rules);
                    TreeStructure.Inspection before = TreeStructure.judge(level, pos, keepOut, TreeJudge.Rules.old());
                    counts.merge(now.reason(), 1, Integer::sum);
                    if (now.isTree()) {
                        for (BlockPos log : now.tree().logs()) inTrees.add(log.asLong());
                    }
                    String line = pos.toShortString() + " now " + now.reason() + " before " + before.reason();
                    if (now.where() != null) line += " at " + now.where().toShortString();
                    if (now.suggestedFoot() != null) line += " foot " + now.suggestedFoot().toShortString();
                    DynamicVillages.LOGGER.info("[TreeScan] {}", line);
                    if (before.isTree() && !now.isTree()) {
                        nowRefused.add(new Difference(pos, words(now.reason())));
                    } else if (!before.isTree() && now.isTree()) {
                        nowTaken.add(new Difference(pos, "was " + words(before.reason())));
                    }
                }
            }
        }

        int looked = bases;
        source.sendSuccess(() -> Component.literal("Tree scan, " + radius + " blocks round "
                        + centre.toShortString() + ": " + looked + " bases")
                .withStyle(ChatFormatting.GOLD), false);
        StringBuilder tally = new StringBuilder();
        counts.forEach((reason, count) -> tally.append(tally.isEmpty() ? "" : ", ").append(reason).append(' ').append(count));
        source.sendSuccess(() -> Component.literal(tally.isEmpty() ? "No tree bases here." : tally.toString())
                .withStyle(ChatFormatting.GRAY), false);
        list(source, "Taken before, left now", nowRefused, ChatFormatting.RED);
        list(source, "Left before, taken now", nowTaken, ChatFormatting.GREEN);
        source.sendSuccess(() -> Component.literal("Click a line to check it. Every base is in the log as [TreeScan].")
                .withStyle(ChatFormatting.GRAY), false);
        return bases;
    }

    /** Where the village's tree scan would try a base: a standing log on solid ground that is not a log. */
    private static boolean isBaseCandidate(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(BlockTags.LOGS) || !TreeStructure.isUpright(state)) return false;
        BlockState below = level.getBlockState(pos.below());
        return below.blocksMotion() && !below.is(BlockTags.LOGS);
    }

    private static void list(CommandSourceStack source, String title, List<Difference> differences,
                             ChatFormatting colour) {
        if (differences.isEmpty()) return;
        source.sendSuccess(() -> Component.literal(title + " (" + differences.size() + "):")
                .withStyle(colour), false);
        for (int i = 0; i < Math.min(LISTED_IN_CHAT, differences.size()); i++) {
            Difference difference = differences.get(i);
            Style style = Style.EMPTY.withColor(ChatFormatting.GRAY)
                    .withClickEvent(checkClick(difference.base()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.literal("Check this one")));
            source.sendSuccess(() -> Component.literal("  " + difference.base().toShortString() + ": "
                    + difference.text()).withStyle(style), false);
        }
    }
}
