package com.alex.test.village.villagers.builder.project;

import com.alex.test.block.PianoLock;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.villagers.builder.blueprint.Blueprint;
import com.alex.test.village.villagers.builder.blueprint.BlueprintMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order the Builder works through a project's cells, and what each cell needs.
 * No list is stored: a cursor index turns into a cell by formula. Clearing goes top layer
 * down; placing goes bottom layer up, solid blocks first, then the rest (torches, doors,
 * glass panes...). Each layer runs in rows that snake back and forth so the next cell is
 * usually beside the last one.
 */
public final class BuildSite {
    private BuildSite() {}

    /** What a cell needs now. */
    public enum Need { NOTHING, CLEAR, PLACE }

    /** Cells in one phase: the whole box. */
    public static int cells(Blueprint blueprint) {
        return blueprint.volume();
    }

    /** The cell (blueprint coordinates) at {@code index} of {@code phase}. */
    public static BlockPos local(Blueprint blueprint, int phase, int index) {
        int sx = blueprint.sizeX(), sz = blueprint.sizeZ(), perLayer = sx * sz;
        int layer = index / perLayer, inLayer = index % perLayer;
        int y = phase == BuildProjects.Project.CLEAR ? blueprint.sizeY() - 1 - layer : layer;
        int z = inLayer / sx, step = inLayer % sx;
        // Snake rows; odd layers also run the other way, so a layer starts where the last ended.
        if (layer % 2 == 1) z = sz - 1 - z;
        int x = (z + layer) % 2 == 0 ? step : sx - 1 - step;
        return new BlockPos(x, y, z);
    }

    /** The layer of {@code index}, to notice when a layer ends. */
    public static int layer(Blueprint blueprint, int index) {
        return index / (blueprint.sizeX() * blueprint.sizeZ());
    }

    /**
     * Keeps {@code project.back} on the cursor's layer: at the layer's end when unset or left on another
     * layer. Once the cursor has passed it the layer is done and the cursor goes to the next layer's
     * first cell. With one Builder back stays at the end, so the cursor just runs on. True when it changed anything.
     */
    public static boolean settleBack(Blueprint blueprint, BuildProjects.Project project) {
        int perLayer = blueprint.sizeX() * blueprint.sizeZ();
        int layer = project.cursor / perLayer;
        boolean changed = false;
        if (project.back >= 0 && project.back / perLayer == layer && project.cursor > project.back) {
            project.cursor = (layer + 1) * perLayer;
            layer++;
            changed = true;
        }
        if (project.back < 0 || project.back / perLayer != layer) {
            project.back = (layer + 1) * perLayer - 1;
            changed = true;
        }
        return changed;
    }

    /** True for a cell of the current phase already done from the far end (none with one Builder). */
    public static boolean doneFromBack(Blueprint blueprint, BuildProjects.Project project, int index) {
        int perLayer = blueprint.sizeX() * blueprint.sizeZ();
        // A back left on another layer (just after the cursor moved up, or an old save) means nothing.
        return project.back >= 0 && project.back / perLayer == project.cursor / perLayer
                && index > project.back && index / perLayer == project.back / perLayer;
    }

    /** How many cells of the cursor's layer the far end has done (0 with one Builder). */
    public static int cellsDoneFromBack(Blueprint blueprint, BuildProjects.Project project) {
        int perLayer = blueprint.sizeX() * blueprint.sizeZ();
        if (project.back < 0 || project.back / perLayer != project.cursor / perLayer) return 0;
        return (project.back / perLayer + 1) * perLayer - 1 - project.back;
    }

    /** The blueprint's state for this cell, or null when it leaves the world alone. */
    @Nullable
    public static BlockState wanted(Blueprint blueprint, BlockPos local) {
        if (!blueprint.contains(local.getX(), local.getY(), local.getZ())) return null;
        if (blueprint.paletteIndexAt(local.getX(), local.getY(), local.getZ()) == Blueprint.VOID) return null;
        return blueprint.stateAt(local.getX(), local.getY(), local.getZ());
    }

    /** Solid, light-blocking blocks go up in the first placing pass. */
    public static boolean solid(BlockState state) {
        return state.canOcclude();
    }

    /**
     * What this cell needs in {@code phase}. Clearing: anything standing where the blueprint
     * wants something else (fluids are left alone). Placing: the blueprint's block where it
     * is missing, in the pass it belongs to; a block in the way is cleared first.
     */
    public static Need need(Level level, BlockPos pos, @Nullable BlockState wanted, int phase) {
        if (wanted == null) return Need.NOTHING;
        BlockState world = level.getBlockState(pos);
        if (world.equals(wanted)) return Need.NOTHING;
        // The right block, only changed by use (a door opened, a furnace gone out, a fence joined to a
        // new neighbour): built. Clearing it would only put it back, and every pass would do it again.
        if (WalkFeel.buildLive && builtAsWanted(world, wanted)) return Need.NOTHING;
        // ZalithYT's Piano: never built (an older copy or a file may hold one) and never cleared away.
        if (PianoLock.isLocked(wanted) || PianoLock.isLocked(world)) return Need.NOTHING;
        // No item brings back grass, podzol, mycelium, a path or farmland (Silk Touch aside), so any
        // dirt already there will do. Digging it out would leave a hole: copied floors are full of these.
        if (isDirtCover(wanted) && isDirtLike(world)) return Need.NOTHING;
        // The other way round: grass (or mycelium, podzol) grows over dirt by itself. Digging it out would only
        // grow back, and every pass would do it again.
        if (WalkFeel.buildDirtGrass && wanted.is(Blocks.DIRT)
                && (world.is(Blocks.GRASS_BLOCK) || world.is(Blocks.MYCELIUM) || world.is(Blocks.PODZOL))) {
            return Need.NOTHING;
        }
        boolean worldFluid = world.getBlock() instanceof LiquidBlock;
        boolean blocking = !world.isAir() && !worldFluid && (wanted.isAir() || !world.canBeReplaced());
        if (phase == BuildProjects.Project.CLEAR) return blocking ? Need.CLEAR : Need.NOTHING;
        if (wanted.isAir() || wanted.getBlock() instanceof LiquidBlock) return Need.NOTHING;
        if (!placedOnItsOwn(wanted)) return Need.NOTHING;
        if (solid(wanted) != (phase == BuildProjects.Project.SOLID)) return Need.NOTHING;
        if (wanted.getBlock().asItem() == net.minecraft.world.item.Items.AIR) return Need.NOTHING;
        return blocking ? Need.CLEAR : Need.PLACE;
    }

    /**
     * Details the game or villagers change on their own once a block stands: opened, powered, lit,
     * joined to a neighbour, grown, filled, slept in. Facing, half, slab type, axis, hinge and
     * waterlogged are not here (nothing changes them by itself), so a block the wrong way round is
     * still put right. Copied from a list, so a repeat can't throw.
     */
    private static final Set<Property<?>> LIVE = Set.copyOf(List.of(
            BlockStateProperties.OPEN, BlockStateProperties.POWERED, BlockStateProperties.LIT,
            BlockStateProperties.POWER, BlockStateProperties.TRIGGERED, BlockStateProperties.ENABLED,
            BlockStateProperties.LOCKED, BlockStateProperties.IN_WALL, BlockStateProperties.OCCUPIED,
            BlockStateProperties.SNOWY, BlockStateProperties.SIGNAL_FIRE,
            BlockStateProperties.ATTACHED, BlockStateProperties.DISARMED,
            BlockStateProperties.NORTH, BlockStateProperties.EAST, BlockStateProperties.SOUTH,
            BlockStateProperties.WEST, BlockStateProperties.UP, BlockStateProperties.DOWN,
            BlockStateProperties.NORTH_WALL, BlockStateProperties.EAST_WALL, BlockStateProperties.SOUTH_WALL,
            BlockStateProperties.WEST_WALL,
            BlockStateProperties.NORTH_REDSTONE, BlockStateProperties.EAST_REDSTONE,
            BlockStateProperties.SOUTH_REDSTONE, BlockStateProperties.WEST_REDSTONE,
            BlockStateProperties.STAIRS_SHAPE, BlockStateProperties.RAIL_SHAPE, BlockStateProperties.RAIL_SHAPE_STRAIGHT,
            BlockStateProperties.HAS_BOTTLE_0, BlockStateProperties.HAS_BOTTLE_1, BlockStateProperties.HAS_BOTTLE_2,
            BlockStateProperties.HAS_BOOK, BlockStateProperties.HAS_RECORD,
            BlockStateProperties.CHISELED_BOOKSHELF_SLOT_0_OCCUPIED, BlockStateProperties.CHISELED_BOOKSHELF_SLOT_1_OCCUPIED,
            BlockStateProperties.CHISELED_BOOKSHELF_SLOT_2_OCCUPIED, BlockStateProperties.CHISELED_BOOKSHELF_SLOT_3_OCCUPIED,
            BlockStateProperties.CHISELED_BOOKSHELF_SLOT_4_OCCUPIED, BlockStateProperties.CHISELED_BOOKSHELF_SLOT_5_OCCUPIED,
            BlockStateProperties.LEVEL_COMPOSTER, BlockStateProperties.LEVEL_CAULDRON, BlockStateProperties.LEVEL_HONEY,
            BlockStateProperties.BITES, BlockStateProperties.NOTE, BlockStateProperties.NOTEBLOCK_INSTRUMENT,
            BlockStateProperties.DISTANCE, BlockStateProperties.STABILITY_DISTANCE, BlockStateProperties.BOTTOM,
            BlockStateProperties.AGE_1, BlockStateProperties.AGE_2, BlockStateProperties.AGE_3, BlockStateProperties.AGE_4,
            BlockStateProperties.AGE_5, BlockStateProperties.AGE_7, BlockStateProperties.AGE_15, BlockStateProperties.AGE_25,
            BlockStateProperties.STAGE, BlockStateProperties.BAMBOO_LEAVES, BlockStateProperties.BERRIES,
            BlockStateProperties.HATCH, BlockStateProperties.DRIPSTONE_THICKNESS));

    /** Same block as the blueprint's, every difference one of the {@link #LIVE} details. */
    private static boolean builtAsWanted(BlockState world, BlockState wanted) {
        if (!world.is(wanted.getBlock())) return false;
        Map<Property<?>, Comparable<?>> there = world.getValues();
        for (Map.Entry<Property<?>, Comparable<?>> entry : wanted.getValues().entrySet()) {
            if (!LIVE.contains(entry.getKey()) && !entry.getValue().equals(there.get(entry.getKey()))) return false;
        }
        return true;
    }

    /** Dirt with something on top that the Builder can't get as an item. */
    private static boolean isDirtCover(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND);
    }

    private static boolean isDirtLike(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND);
    }

    /** Upper door halves and bed heads come with their other half. */
    public static boolean placedOnItsOwn(BlockState state) {
        return BlueprintMaterials.itemsPerBlock(state) > 0;
    }

    /** The other half placed together with this one (door top, bed head), or null. */
    @Nullable
    public static BlockPos partner(BlockState state, BlockPos pos) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return pos.above();
        }
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
            return pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        return null;
    }

    /** The other half of the door, tall flower or bed at {@code pos}, when it stands there too; else null. */
    @Nullable
    public static BlockPos otherHalf(Level level, BlockPos pos, BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            BlockPos other = half == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState there = level.getBlockState(other);
            return there.is(state.getBlock()) && there.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && there.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != half ? other : null;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            BedPart part = state.getValue(BlockStateProperties.BED_PART);
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            // The head lies the way the bed faces, as in partner().
            BlockPos other = pos.relative(part == BedPart.FOOT ? facing : facing.getOpposite());
            BlockState there = level.getBlockState(other);
            return there.is(state.getBlock()) && there.hasProperty(BlockStateProperties.BED_PART)
                    && there.getValue(BlockStateProperties.BED_PART) != part ? other : null;
        }
        return null;
    }

    /** A chest, barrel or other container that still holds something. */
    public static boolean holdsItems(Level level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        return entity instanceof Container container && !container.isEmpty();
    }

    /**
     * Items of {@code item} this project still places from where the Builder is: the rest of the
     * placing phase it is in and every one after. Read from the blueprint only, so blocks already
     * standing right count too, and cells put off earlier in the pass don't.
     */
    public static int stillNeeds(Blueprint blueprint, BuildProjects.Project project, net.minecraft.world.item.Item item) {
        int count = 0, cells = cells(blueprint);
        for (int phase = BuildProjects.Project.SOLID; phase <= BuildProjects.Project.REST; phase++) {
            if (phase < project.phase) continue;
            for (int i = phase == project.phase ? project.cursor : 0; i < cells; i++) {
                if (phase == project.phase && doneFromBack(blueprint, project, i)) continue;
                BlockState wanted = wanted(blueprint, local(blueprint, phase, i));
                if (wanted == null || wanted.getBlock().asItem() != item
                        || solid(wanted) != (phase == BuildProjects.Project.SOLID)) continue;
                count += BlueprintMaterials.itemsPerBlock(wanted);
            }
        }
        return count;
    }

    /** {@link #stillNeeds} for every item at once, in one pass over the cells. */
    public static java.util.Map<net.minecraft.world.item.Item, Integer> stillNeeded(Blueprint blueprint,
                                                                                     BuildProjects.Project project) {
        java.util.Map<net.minecraft.world.item.Item, Integer> counts = new java.util.HashMap<>();
        int cells = cells(blueprint);
        for (int phase = BuildProjects.Project.SOLID; phase <= BuildProjects.Project.REST; phase++) {
            if (phase < project.phase) continue;
            for (int i = phase == project.phase ? project.cursor : 0; i < cells; i++) {
                if (phase == project.phase && doneFromBack(blueprint, project, i)) continue;
                BlockState wanted = wanted(blueprint, local(blueprint, phase, i));
                if (wanted == null || solid(wanted) != (phase == BuildProjects.Project.SOLID)) continue;
                int each = BlueprintMaterials.itemsPerBlock(wanted);
                net.minecraft.world.item.Item item = wanted.getBlock().asItem();
                if (each > 0 && item != net.minecraft.world.item.Items.AIR) counts.merge(item, each, Integer::sum);
            }
        }
        return counts;
    }

    /** 0 to 100: clearing is the first tenth, solid blocks up to 75, the rest after. */
    public static int progress(Blueprint blueprint, int phase, int cursor) {
        float part = Math.min(1F, cursor / (float) Math.max(1, cells(blueprint)));
        float done = switch (phase) {
            case BuildProjects.Project.CLEAR -> part * 0.10F;
            case BuildProjects.Project.SOLID -> 0.10F + part * 0.65F;
            default -> 0.75F + part * 0.25F;
        };
        return Math.min(99, Math.round(done * 100));
    }
}
