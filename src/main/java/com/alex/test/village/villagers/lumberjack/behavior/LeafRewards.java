package com.alex.test.village.villagers.lumberjack.behavior;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * Sticks and apples from the leaves a lumberjack cuts -- felling a canopy,
 * cutting its way in to a trunk, or pushing through boughs on the walk there.
 * Every leaf block gets the same roll, whichever tree it belongs to.
 *
 * <p>One instance per tree job, so the village's stick stock is read once
 * per tree rather than once per leaf.
 */
public final class LeafRewards {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Sticks the village will keep before a lumberjack stops bringing more.
     *
     * <p>Sticks fall from leaves at two percent a block, which is about thirty
     * a day from one lumberjack -- and the only thing that ever spends them is
     * the smith, a handful at a time. A week in, the storage room was mostly
     * sticks. Past this the lumberjack simply leaves them in the canopy.
     */
    public static final int STICK_STOCK_CAP = 32;

    /**
     * Apple drop chance per leaf block cleared.
     *
     * <p>Sized so a lumberjack feeds itself and no one else. A tree carries
     * roughly seventy leaf blocks and a lumberjack fells around twenty a day,
     * so this is about four apples a day; at four nutrition each, and eight
     * hunger to the nutrition point, that is a bit over one villager's daily
     * intake. It was 0.005 -- half again as much -- which quietly made the
     * lumberjack a second food profession.
     */
    public static final float APPLE_LEAF_CHANCE = 0.003f;

    /** Stick drop chance per leaf block cleared. */
    public static final float STICK_LEAF_CHANCE = 0.02f;

    /** Village stick stock as of this tree, or -1 before it has been read. */
    private int stickStock = -1;
    private int sticks;
    private int apples;

    /**
     * Cuts the leaf at {@code pos} and rolls its rewards. The caller has
     * already decided the lumberjack may cut it.
     *
     * @return false when the block would not break
     */
    boolean cut(Activity.Context ctx, BlockPos pos) {
        BlockState leaf = ctx.level().getBlockState(pos);
        if (!ctx.level().destroyBlock(pos, false, ctx.npc())) return false;
        ctx.mgr().markNavigationDirtyAt(pos);
        award(ctx, leaf);
        return true;
    }

    /** Rolls for a stick and, from the leaves vanilla drops them from, an apple. */
    void award(Activity.Context ctx, BlockState leaf) {
        if (ctx.level().random.nextFloat() < STICK_LEAF_CHANCE
                && wantsMoreSticks(ctx)
                && addReward(ctx, new ItemStack(Items.STICK))) {
            sticks++;
            stickStock++; // this one is on its way to the chest too
        }
        if (dropsApples(leaf)
                && ctx.level().random.nextFloat() < APPLE_LEAF_CHANCE
                && addReward(ctx, new ItemStack(Items.APPLE))) {
            apples++;
        }
    }

    int sticks() { return sticks; }

    int apples() { return apples; }

    public static boolean dropsApples(BlockState leaf) {
        return leaf.is(Blocks.OAK_LEAVES) || leaf.is(Blocks.DARK_OAK_LEAVES);
    }

    /**
     * Whether the village could use another stick.
     *
     * <p>The village's stock is read once when the tree is claimed, not per
     * leaf block: counting every chest for each of seventy leaves would make
     * felling a tree cost more than the tree is worth. Sticks awarded since
     * are added on, so a single long session still stops at the cap.
     */
    private boolean wantsMoreSticks(Activity.Context ctx) {
        if (stickStock < 0) stickStock = countSticksInVillage(ctx);
        return stickStock < STICK_STOCK_CAP;
    }

    private static int countSticksInVillage(Activity.Context ctx) {
        int total = 0;
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid()) {
                total += storage.countItem(ctx.level(), Items.STICK);
            }
        }
        return total;
    }

    private static boolean addReward(Activity.Context ctx, ItemStack reward) {
        reward = VillageItemOwnership.produce(reward, ctx.mgr());
        if (ctx.npc().getInventory() != null
                && ctx.npc().getInventory().addItem(reward)) {
            return true;
        }
        LOGGER.debug(
                "[LeafRewards] Lumberjack {} had no inventory room for leaf reward {}.",
                ctx.model().npcId, reward);
        return false;
    }
}
