package com.alex.test.testkit;

import com.alex.test.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders a top-down text map of a region.
 *
 * <p>A headless server has no screenshots, but most of what you want to see
 * while testing this mod is layout: did the tree get chopped, is the villager
 * inside the house, did the storage room end up where it should. A character
 * grid answers all of that and can be read directly out of a file.
 *
 * <p>The map scans downward from {@code topY} and reports the first
 * non-air block in each column, so it reads like an overhead view.
 */
public final class AsciiMap {

    /** How far below {@code topY} a column is searched before giving up. */
    private static final int SCAN_DEPTH = 24;

    private AsciiMap() {}

    /**
     * @param center centre of the square region
     * @param radius half-width in blocks (the map is {@code 2*radius+1} wide)
     * @param topY   Y to start scanning down from. Pass villager feet level to
     *               see inside buildings; pass roof level to see their outlines.
     *               Villagers are marked whatever height they are at.
     */
    public static String render(ServerLevel level, BlockPos center, int radius, int topY) {
        Map<Long, Character> entities = entityMarkers(level, center, radius);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Map centre=%s radius=%d topY=%d%n",
                center.toShortString(), radius, topY));
        sb.append(legend());
        sb.append(String.format("        Z %d .. %d%n",
                center.getZ() - radius, center.getZ() + radius));

        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            sb.append(String.format("%7d ", x));
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                Character entityMark = entities.get(BlockPos.asLong(x, 0, z));
                sb.append(entityMark != null ? entityMark : columnChar(level, x, z, topY));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Villagers are drawn from their own pass because they matter more than
     * whatever block they happen to be standing on.
     */
    private static Map<Long, Character> entityMarkers(ServerLevel level, BlockPos center,
                                                      int radius) {
        Map<Long, Character> marks = new HashMap<>();
        AABB box = new AABB(
                center.getX() - radius, level.getMinBuildHeight(), center.getZ() - radius,
                center.getX() + radius + 1, level.getMaxBuildHeight(), center.getZ() + radius + 1);
        for (NpcEntity npc : level.getEntitiesOfClass(NpcEntity.class, box)) {
            BlockPos p = npc.blockPosition();
            char mark = switch (npc.getProfession()) {
                case LUMBERJACK -> 'L';
                case GUARD -> 'G';
                case FARMER -> 'F';
                case RANCHER -> 'R';
                case COOK -> 'C';
                case BLACKSMITH -> 'S';
                case BUTCHER -> 'B';
                case MINER -> 'M';
                case BUILDER -> 'A';
                case BARD -> 'b';
                case CLERIC -> 'c';
                case DRUID -> 'D';
                case ENCHANTER -> 'E';
                case TEACHER -> 'T';
                case TRADESMAN -> 't';
                case ARCHER -> 'a';
                case CHILD -> 'k';
                case CAPTAIN -> 'P';
                case MAGE -> 'm';
                case NONE -> 'V';
            };
            marks.put(BlockPos.asLong(p.getX(), 0, p.getZ()), mark);
        }
        return marks;
    }

    /** First non-air block scanning down from {@code topY}, as one character. */
    private static char columnChar(ServerLevel level, int x, int z, int topY) {
        for (int y = topY; y > topY - SCAN_DEPTH; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;

            if (state.getBlock() instanceof DoorBlock) return '+';
            if (state.getBlock() instanceof FenceGateBlock) return 'g';
            if (state.getBlock() instanceof FenceBlock) return 'f';
            if (state.getBlock() instanceof BedBlock) return 'b';
            if (state.getBlock() instanceof ChestBlock) return 'c';
            if (state.getBlock() instanceof CropBlock crop) {
                return crop.getAge(state) >= crop.getMaxAge() ? 'X' : 'x';
            }
            if (state.is(Blocks.CRAFTING_TABLE)) return 't';
            if (state.is(Blocks.FURNACE) || state.is(Blocks.SMOKER)) return 'u';
            if (state.is(Blocks.ANVIL)) return 'a';
            if (state.is(Blocks.FARMLAND)) return '~';
            if (!state.getFluidState().isEmpty()) return 'w';
            if (state.is(BlockTags.LOGS)) return 'T';
            if (state.is(BlockTags.LEAVES)) return '*';
            if (state.is(Blocks.STONE_BRICKS)) return '#';
            if (state.is(Blocks.STONE)) return 'o';
            if (state.is(Blocks.OAK_PLANKS)) return '=';
            if (state.is(Blocks.GRASS_BLOCK)) return '.';
            if (state.is(Blocks.DIRT)) return ',';
            if (state.is(BlockTags.SAPLINGS)) return 'y';
            return '?';
        }
        return ' ';
    }

    private static String legend() {
        return "  legend: . grass  , dirt  # wall  o stone  = planks/roof  + door  g gate  f fence\n"
                + "          b bed  c chest  t table  u furnace  a anvil  ~ farmland  w water\n"
                + "          x crop  X mature crop  T log  * leaves  y sapling  ? other  (space) void\n"
                + "          L lumberjack  G guard  F farmer  R rancher  C cook  S smith  "
                + "B butcher  M miner  V villager\n";
    }
}
