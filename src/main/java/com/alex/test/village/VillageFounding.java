package com.alex.test.village;

import com.alex.test.ModItems;
import com.alex.test.Tr;
import com.alex.test.village.chat.TokenFeedback;
import com.alex.test.village.log.VillageLog;
import com.alex.test.village.takeover.VanillaVillageTakeover;
import com.alex.test.village.tokens.VillageTokenBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The moment a Town Hall turns a room into a village.
 *
 * <p>Founding used to produce a Mayor and nothing else, which left a survival
 * player holding a building and no way to reach any of the rest of the mod: the
 * profession and structure tokens existed only in the creative tab, and no
 * villager would ever turn up to use them. So founding now also leaves a supply
 * chest with food and the tokens needed to establish the first house and jobs,
 * and arms the welcome party that brings the first settlers and a trader within
 * the minute.
 *
 * <p>Called from exactly one place: the branch of
 * {@link VillageManagerRegistry#createFromTownHall} that creates a genuinely
 * new village. Restoring an orphan or reloading a world must not re-fund it.
 */
public final class VillageFounding {

    /** Food for the walk from founding to the first harvest. */
    public static final int STARTER_BREAD = 32;

    /** How far from the door the chest may be placed before we give up. */
    private static final int CHEST_SEARCH_RADIUS = 3;

    /**
     * Set while a prefab test village is being stamped out.
     *
     * <p>{@code /vtest scenario village} founds its village through the same
     * registry call a player's Town Hall goes through, so without this every
     * one of the eighty-odd behaviour scenarios would silently gain a chest of
     * emeralds and three visitors — and the nomads among them would join, which
     * changes the roster the scenario is asserting on. Hand-built villages
     * ({@code /vtest frame}, and every real player) are unaffected.
     */
    private static boolean suppressed;

    private VillageFounding() {}

    /** Suppresses founding gifts for the duration of the returned scope. */
    public static AutoCloseable suppress() {
        suppressed = true;
        return () -> suppressed = false;
    }

    /**
     * The village log's first row, written before the new village looks for its buildings, so it comes
     * before their "registered" rows and the settlers who join a generated village. A founding that then
     * fails throws the village away, row and all.
     */
    static void logFounded(ServerLevel level, VillageManager village, BlockPos doorPos) {
        if (suppressed) return;
        VillageLog.record(village, level, VillageLog.Kind.TOWN_HALL,
                Tr.c("log.dynamicvillages.town_hall.founded", "The village was founded"), null, doorPos);
    }

    public static void onFounded(ServerLevel level, VillageManager village,
                                 BlockPos doorPos, Direction interiorDirection) {
        if (suppressed) return;
        long foundedAt = level.getGameTime();
        village.visitors().scheduleFoundingMerchants(foundedAt, level.getDayTime());

        // A brand-new generated village founding itself marks its houses, takes
        // in its settlers and leaves its own gift, instead of the usual ones.
        if (VanillaVillageTakeover.begin(level, village, doorPos, interiorDirection)) return;

        placeGiftChest(level, village, doorPos, interiorDirection, starterGoods(village));

        TokenFeedback.announceFounded(level, doorPos);

        village.nomads().scheduleFoundingNomads(level);
    }

    /**
     * Puts a founding gift in a chest beside the Town Hall the player can actually walk to.
     *
     * <p>Never in the tile directly outside the door: a chest there is a solid
     * block across the only way in and out of the Town Hall.
     */
    @Nullable
    public static BlockPos placeGiftChest(ServerLevel level, VillageManager village,
                                          BlockPos doorPos, Direction interiorDirection,
                                          List<ItemStack> goods) {
        BlockPos spot = findChestSpot(level, doorPos, interiorDirection);
        if (spot == null) return null;
        stockChest(level, village, spot, Blocks.CHEST.defaultBlockState(), goods);
        return spot;
    }

    /** Places {@code chest} at {@code spot}, ties it to the village and fills it with {@code goods}. */
    public static void stockChest(ServerLevel level, VillageManager village, BlockPos spot,
                                  BlockState chest, List<ItemStack> goods) {
        level.setBlockAndUpdate(spot, chest);
        // /setblock-style placement fires no placement event, so the nav graph
        // would keep a walkable node where a solid chest now stands.
        village.noteBlockChanged(spot);

        BlockEntity blockEntity = level.getBlockEntity(spot);
        if (!(blockEntity instanceof Container container)) return;
        VillageTokenBinding.bindChest(blockEntity, village);
        for (int slot = 0; slot < goods.size() && slot < container.getContainerSize(); slot++) {
            container.setItem(slot, goods.get(slot));
        }
        container.setChanged();
    }

    /**
     * The founding gift, in chest-slot order: food, storage, 2-bed house,
     * lumberjack, farmer. No merchant stall: the player buys it from the Mayor.
     */
    private static List<ItemStack> starterGoods(VillageManager village) {
        List<ItemStack> goods = new ArrayList<>();
        goods.add(new ItemStack(Items.BREAD, STARTER_BREAD));
        goods.add(new ItemStack(ModItems.STORAGE_ROOM.get()));
        goods.add(new ItemStack(ModItems.TWO_BED_HOUSE.get()));
        goods.add(new ItemStack(ModItems.LUMBERJACK_TOKEN.get()));
        goods.add(new ItemStack(ModItems.FARMER_TOKEN.get()));
        for (ItemStack stack : goods) {
            // No cook made it: Poor Man's Bread (VillageFood).
            com.alex.test.village.tokens.VillageFood.markVillagerMade(stack);
            VillageTokenBinding.bind(stack, village);
        }
        return goods;
    }

    @Nullable
    private static BlockPos findChestSpot(ServerLevel level, BlockPos doorPos,
                                          Direction interiorDirection) {
        Direction out = interiorDirection.getOpposite();
        Direction side = out.getClockWise();
        BlockPos outside = doorPos.relative(out);

        for (int forward = 0; forward <= CHEST_SEARCH_RADIUS; forward++) {
            for (int lateral = 1; lateral <= CHEST_SEARCH_RADIUS; lateral++) {
                for (int sign = 1; sign >= -1; sign -= 2) {
                    BlockPos candidate = outside
                            .relative(out, forward)
                            .relative(side, lateral * sign);
                    for (int dy = 0; dy >= -2; dy--) {
                        BlockPos at = candidate.above(dy);
                        if (isChestSpot(level, at)) return at;
                    }
                }
            }
        }
        return null;
    }

    /** Free space with something solid under it, and nothing valuable in the way. */
    private static boolean isChestSpot(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) return false;
        BlockState below = level.getBlockState(pos.below());
        if (!below.isFaceSturdy(level, pos.below(), Direction.UP)) return false;
        return level.getBlockState(pos.above()).canBeReplaced();
    }
}
