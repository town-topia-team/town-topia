package com.alex.test.village.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

/**
 * Every place a villager drops items on the floor, or loses them outright,
 * goes through here so it shows up as a WARN in the console and a line in the
 * villager's diary.
 *
 * <p>Items on the floor were turning up with no way to tell which code path
 * put them there. Each call names the path in {@code reason}.
 */
public final class ItemLossLog {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ItemLossLog() {}

    /** Drops {@code stack} at the villager's feet and logs it. */
    public static void drop(Activity.Context ctx, ItemStack stack, String reason) {
        if (stack.isEmpty()) return;
        log(ctx, "dropped", stack, ctx.npc().blockPosition(), reason);
        ctx.npc().spawnAtLocation(stack);
    }

    /** Pops {@code stack} out of the block at {@code pos} and logs it. */
    public static void pop(Activity.Context ctx, BlockPos pos, ItemStack stack, String reason) {
        if (stack.isEmpty()) return;
        log(ctx, "dropped", stack, pos, reason);
        Block.popResource(ctx.level(), pos, stack);
    }

    /** Logs items that are about to be lost without a trace (nowhere left to put them). */
    public static void destroyed(Activity.Context ctx, ItemStack stack, String reason) {
        if (stack.isEmpty()) return;
        log(ctx, "LOST", stack, ctx.npc().blockPosition(), reason);
    }

    /** For callers without an activity context: drops and logs to the console only. */
    public static void drop(NpcEntity npc, ItemStack stack, String reason) {
        if (stack.isEmpty()) return;
        LOGGER.warn("[ItemLoss] {} ({}) dropped {} at {}: {}",
                npc.getName().getString(), npc.getProfession(), describe(stack),
                npc.blockPosition().toShortString(), reason);
        npc.spawnAtLocation(stack);
    }

    private static void log(Activity.Context ctx, String verb, ItemStack stack, BlockPos pos, String reason) {
        String what = describe(stack);
        LOGGER.warn("[ItemLoss] {} ({}) {} {} at {}: {}",
                ctx.npc().getName().getString(), ctx.npc().getProfession(), verb, what,
                pos.toShortString(), reason);
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " ITEM LOSS " + verb + " "
                + what + " at " + pos.toShortString() + ": " + reason);
    }

    /** Count, item and whether it carries a village binding (bound and unbound stacks don't stack). */
    private static String describe(ItemStack stack) {
        return stack.getCount() + "x " + stack.getItem().getDescriptionId()
                + (VillageTokenBinding.isBound(stack) ? " [bound]" : " [unbound]");
    }
}
