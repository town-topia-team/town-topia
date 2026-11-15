package com.alex.test.village.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * {@code /vtest dance [loops]}: the two villagers nearest you walk to the 2x2
 * whose corner is the block you are looking at, the first waits for the
 * second, and they dance the kick line facing you. Any time of day, no
 * tavern or bard needed. {@code /vtest dance stop} ends every dance here.
 */
public final class PairDanceCommands {

    /** How far the two dancers may be from you. */
    private static final double REACH = 24.0;
    private static final int DEFAULT_LOOPS = 6;

    private PairDanceCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("dance")
                .executes(ctx -> dance(ctx, DEFAULT_LOOPS))
                .then(Commands.argument("loops", IntegerArgumentType.integer(1, 60))
                        .executes(ctx -> dance(ctx, IntegerArgumentType.getInteger(ctx, "loops"))))
                .then(Commands.literal("stop").executes(PairDanceCommands::stop));
    }

    private static int dance(CommandContext<CommandSourceStack> ctx, int loops) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return fail(ctx, "needs a player to run it");
        ServerLevel level = ctx.getSource().getLevel();
        HitResult hit = player.pick(20.0, 1f, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            return fail(ctx, "look at the floor where they should dance");
        }
        // The floor block looked at: the dancers stand on top of it.
        BlockPos corner = block.getBlockPos().above();
        VillageManager village = VillageManagerRegistry.get(ctx.getSource().getServer()).findVillageAt(level, corner);
        if (village == null) return fail(ctx, "no village contains " + corner.toShortString());

        List<VillagerModel> near = new ArrayList<>();
        for (VillagerModel m : village.getVillagers()) {
            NpcEntity npc = m.puppet();
            if (npc == null || !npc.isAlive() || npc.isSleeping()) continue;
            if (npc.distanceToSqr(player) > REACH * REACH) continue;
            if (PairDance.of(level, npc.getUUID()) != null) continue;
            near.add(m);
        }
        near.sort(Comparator.comparingDouble(m -> m.puppet().distanceToSqr(player)));
        if (near.size() < 2) return fail(ctx, "needs two awake villagers within " + (int) REACH + " blocks");
        VillagerModel a = near.get(0), b = near.get(1);

        List<BlockPos> tiles = PairDance.square(corner);
        List<UUID> dancers = List.of(a.npcId, b.npcId);
        for (BlockPos t : tiles) {
            if (!PairDance.tileFree(level, t, dancers)) {
                return fail(ctx, "the 2x2 from " + corner.toShortString()
                        + " is not clear (needs open floor, 2 high, nobody on it)");
            }
        }
        Vec3 to = player.position().subtract(PairDance.centre(tiles));
        Direction facing = Direction.getNearest(to.x, 0, to.z);
        PairDance dance = TavernDances.start(level, new PairDance.Spot(tiles, facing), a, b, loops, null);
        ctx.getSource().sendSuccess(() -> Component.literal("dance: " + name(a) + " and " + name(b)
                + " to " + corner.toShortString() + " facing " + facing.getName() + ", " + loops
                + " loops (" + dance.id.toString().substring(0, 8) + ")"), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        List<PairDance> all = PairDance.active(level);
        for (PairDance d : all) d.end("stopped by command");
        ctx.getSource().sendSuccess(() -> Component.literal("dance: stopped " + all.size()), false);
        return all.size();
    }

    private static String name(VillagerModel m) {
        NpcEntity npc = m.puppet();
        return npc != null ? npc.getName().getString() : m.npcId.toString().substring(0, 8);
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String why) {
        ctx.getSource().sendFailure(Component.literal("dance: " + why));
        return 0;
    }
}
