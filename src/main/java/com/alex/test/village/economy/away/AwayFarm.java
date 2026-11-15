package com.alex.test.village.economy.away;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.economy.AwayClock;
import com.alex.test.village.tokens.PlayerGear;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.druid.DruidBalance;
import com.alex.test.village.villagers.druid.settings.DruidSettingsSchema;
import com.alex.test.village.villagers.farmer.FarmerHoeUtils;
import com.alex.test.village.villagers.farmer.FarmerManager;
import com.alex.test.village.villagers.farmer.crops.CropDefinition;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry;
import com.alex.test.village.villagers.farmer.crops.CropHarvestStrategy;
import com.alex.test.village.villagers.farmer.crops.CropPlantingStrategy;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.BeetrootBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TorchflowerCropBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The village's fields for one catch-up: a copy of every farm tile in a
 * loaded chunk, grown day by day by vanilla's averages ({@link FarmGrowth}),
 * worked by the away farmers ({@code jobs/FarmerAway}), and written to the
 * world at WRITE as one "if still X, make Y" per tile ({@link AwayWorld}).
 *
 * <p>Read from what the farms saved ({@link FarmerManager#awayField}): crop
 * tiles, stems and their fruit cells, cane, berry bushes, glow berries and
 * cocoa spots. A tile whose chunk (or a neighbour it reads) is not loaded is
 * left out: it neither grows nor is worked.
 *
 * <p>Each day: the work hours grow first ({@link #startDay}, with the
 * druids' help), the farmers take their turns, then the rest of the day grows
 * ({@link #endDay}). A druid's help is live's: an age a step on a growing
 * {@code CropBlock}, steps at its dance time.
 */
public final class AwayFarm {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Druid crop steps in a work session at most (live {@code DruidActivity.MAX_CROP_STEPS_PER_SESSION}); one session a day (my call). */
    static final int DRUID_STEPS_PER_DAY = 60;
    /** Walk to the next crop between druid steps (my call). */
    static final int DRUID_WALK_TICKS = 40;
    /** Seeded loot rolls averaged for a crop's drops: the same every run. */
    private static final int LOOT_SAMPLES = 32;
    private static final long LOOT_SEED = 0x5EEDL;
    private static final Direction[] SIDES = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };

    /** The vanilla stems: StemBlock keeps its fruit and attached stem to itself. A modded stem grows, makes no fruit. */
    private record StemKind(Block stem, Block attached, Block fruit) {}

    private static final List<StemKind> STEMS = List.of(
            new StemKind(Blocks.PUMPKIN_STEM, Blocks.ATTACHED_PUMPKIN_STEM, Blocks.PUMPKIN),
            new StemKind(Blocks.MELON_STEM, Blocks.ATTACHED_MELON_STEM, Blocks.MELON));

    private final ServerLevel level;
    private final int randomTickSpeed;
    private final List<Plot> plots = new ArrayList<>();
    private final Map<BlockPos, Plot> plotAt = new HashMap<>();
    /** Fruit cell to the stem whose fruit stands there now. */
    private final Map<BlockPos, Plot> fruitOwner = new HashMap<>();
    private final List<Cane> cane = new ArrayList<>();
    private final List<Bush> bushes = new ArrayList<>();
    private final List<Pod> pods = new ArrayList<>();
    private final List<Glow> glows = new ArrayList<>();
    private final List<AwayVillager> druids = new ArrayList<>();
    private int druidTurn;
    // The Tilling switch (round 4 of its plan), read at READ while every body still holds
    // the real villager: other farmers' packs mid-run would be their away copies or not.
    /** Farmers that till for the village (Planting off, Tilling on) with a hoe when you left. */
    private final List<AwayVillager> tillers = new ArrayList<>();
    /** Seeds some working farmer with Planting on has on Replant, and those it carried when you left. */
    private final Set<Item> plantersReplant = new HashSet<>();
    private final Set<Item> plantersCarry = new HashSet<>();
    private final Map<LootKey, Map<Item, Double>> dropCache = new HashMap<>();

    // For the log and the preview.
    int notLoaded;
    long stagesGrown;
    int fruitsGrown;
    int druidSteps;
    int written;

    private AwayFarm(ServerLevel level) {
        this.level = level;
        this.randomTickSpeed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
    }

    // =========================================================================
    // READ
    // =========================================================================

    static AwayFarm read(ServerLevel level, VillageManager mgr, List<AwayVillager> villagers) {
        AwayFarm farm = new AwayFarm(level);
        FarmerManager.AwayField field = mgr.farmers().awayField();
        for (BlockPos pos : field.tiles()) farm.readPlot(pos, field.crops());
        for (Plot plot : farm.plots) {
            if (plot.state.getBlock() instanceof StemBlock || plot.state.getBlock() instanceof AttachedStemBlock) {
                farm.readFruitSide(plot, field.fruitSpaces().get(plot.pos));
            }
        }
        for (BlockPos pos : field.cane()) farm.readCane(pos);
        for (BlockPos pos : field.berries()) farm.readBush(pos);
        field.pods().forEach(farm::readPod);
        for (BlockPos pos : field.glowBerries()) farm.readGlow(pos);
        for (AwayVillager villager : villagers) {
            if (villager.profession() == Profession.DRUID) farm.druids.add(villager);
            if (villager.profession() == Profession.FARMER) farm.readFarmer(villager);
        }
        return farm;
    }

    /** A tiller on duty, or what a planting farmer can sow (live {@code FarmerManager.villagePlanting}). */
    private void readFarmer(AwayVillager villager) {
        NpcEntity body = villager.body();
        if (body == null) return;
        FarmerSettings settings = FarmerSettings.of(body);
        if (!settings.works()) return;
        if (settings.tillsForVillage()) {
            if (FarmerHoeUtils.findUsableHoeSlot(body.getInventory(), settings) >= 0) tillers.add(villager);
            return;
        }
        for (CropDefinition definition : CropDefinitionRegistry.all()) {
            if (!(definition.planting() instanceof CropPlantingStrategy.Farmland)) continue;
            Item seed = definition.representativePlantingItem().orElse(null);
            if (seed == null || !settings.shouldPlant(seed)) continue;
            plantersReplant.add(seed);
            if (body.getInventory().contains(seed)) plantersCarry.add(seed);
        }
    }

    // =========================================================================
    // Tillers (round 4 of the Tilling plan)
    // =========================================================================

    /** Some working farmer with Planting on has a farmland crop on Replant. */
    public boolean hasPlanters() {
        return !plantersReplant.isEmpty();
    }

    /** A planting farmer could sow {@code seed}: on its Replant list, carried when you left or in storage now. */
    public boolean plantersSow(Item seed, AwayContext ctx) {
        return plantersReplant.contains(seed)
                && (plantersCarry.contains(seed) || ctx.countInStorage(seed, Profession.FARMER) > 0);
    }

    /**
     * Whether a planter leaves this dirt plot to the tillers: the live rule
     * ({@code FarmerManager.tillsNow}), while at most {@link FarmerManager#tillShare}
     * plots per tiller wait, this one counted. A plot no tiller would till is never left.
     */
    public boolean leftToTillers(Plot plot, AwayContext ctx) {
        int share = FarmerManager.tillShare();
        int onDuty = 0;
        for (AwayVillager tiller : tillers) {
            if (!tiller.starved()) onDuty++;
        }
        Map<Item, Boolean> sows = new HashMap<>();
        if (share == 0 || onDuty == 0 || !tillerTills(plot, ctx, sows)) return false;
        int waiting = 0;
        for (Plot other : plots) {
            if (tillerTills(other, ctx, sows) && ++waiting > share * onDuty) return false;
        }
        return true;
    }

    /** Soil gone back to dirt that a tiller tills again: water near, nothing on it, a crop the planters sow. */
    private boolean tillerTills(Plot plot, AwayContext ctx, Map<Item, Boolean> sows) {
        if (plot.tilled() || !plot.empty() || !plot.wet || plot.crop == null) return false;
        Item seed = plot.crop.representativePlantingItem().orElse(null);
        return seed != null && sows.computeIfAbsent(seed, item -> plantersSow(item, ctx));
    }

    private void readPlot(BlockPos pos, Map<BlockPos, CropDefinition> lastCrops) {
        if (plotAt.containsKey(pos)) return;
        if (!loadedAround(pos, 1)) {
            notLoaded++;
            return;
        }
        BlockState state = level.getBlockState(pos);
        BlockState soil = level.getBlockState(pos.below());
        CropDefinition crop = CropDefinitionRegistry.byCropBlock(state.getBlock());
        if (crop != null) {
            if (!(crop.planting() instanceof CropPlantingStrategy.Farmland)) return;
            if (state.hasProperty(DoublePlantBlock.HALF) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) return;
        } else if (state.isAir() && (soil.getBlock() instanceof FarmBlock || isTillable(soil))) {
            crop = lastCrops.get(pos);
        } else {
            return;
        }
        Plot plot = new Plot(this, pos, crop, state, soil);
        plot.lit = level.getRawBrightness(pos, 0) >= 9;
        plot.wet = soil.getBlock() instanceof FarmBlock ? soil.getValue(FarmBlock.MOISTURE) > 0 || hydrated(pos.below())
                : hydrated(pos.below());
        if (state.getBlock() instanceof PitcherCropBlock) {
            BlockState above = level.getBlockState(pos.above());
            plot.readUpper = above;
            plot.upper = above;
            plot.roomAbove = above.isAir() || above.is(Blocks.PITCHER_CROP);
        } else {
            BlockState above = level.getBlockState(pos.above());
            plot.roomAbove = above.isAir();
        }
        if (!state.isAir()) plot.chance = chanceFor(state, pos);
        plots.add(plot);
        plotAt.put(pos, plot);
    }

    private void readFruitSide(Plot stem, @Nullable BlockPos designated) {
        StemKind kind = stemKind(stem.state.getBlock());
        if (kind == null) return;
        stem.stemKind = kind;
        Direction attached = stem.state.getBlock() instanceof AttachedStemBlock
                ? stem.state.getValue(AttachedStemBlock.FACING) : null;
        if (attached != null) {
            BlockPos cell = stem.pos.relative(attached);
            if (!level.isLoaded(cell) || !level.getBlockState(cell).is(kind.fruit())) {
                // The fruit it points at is gone: vanilla puts it back to a stem at 7.
                attached = null;
                stem.state = kind.stem().defaultBlockState().setValue(StemBlock.AGE, StemBlock.MAX_AGE);
            }
        }
        Direction chosen = attached;
        int open = 0;
        for (Direction side : SIDES) {
            BlockPos cell = stem.pos.relative(side);
            if (!level.isLoaded(cell) || plotAt.containsKey(cell)) continue;
            if (side == attached) {
                open++;
                continue;
            }
            BlockState there = level.getBlockState(cell);
            BlockState below = level.getBlockState(cell.below());
            if (!there.isAir() || !(below.getBlock() instanceof FarmBlock || below.is(BlockTags.DIRT))) continue;
            open++;
            // The farm's own fruit cell when it is open, else the first open side.
            if (attached == null && (chosen == null || cell.equals(designated))) chosen = side;
        }
        if (chosen == null) return;
        stem.fruitSide = chosen;
        stem.openSides = open;
        stem.fruitPos = stem.pos.relative(chosen);
        stem.readFruit = level.getBlockState(stem.fruitPos);
        stem.fruit = stem.readFruit;
        if (stem.fruit.is(kind.fruit())) fruitOwner.put(stem.fruitPos, stem);
    }

    private void readCane(BlockPos pos) {
        if (!level.isLoaded(pos) || !level.isLoaded(pos.below())) return;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof SugarCaneBlock) || level.getBlockState(pos.below()).is(state.getBlock())) return;
        List<BlockState> column = new ArrayList<>();
        int height = 0;
        for (int i = 0; i < 16; i++) {
            BlockPos cell = pos.above(i);
            if (!level.isLoaded(cell)) break;
            BlockState there = level.getBlockState(cell);
            if (there.is(state.getBlock()) && height == i) {
                column.add(there);
                height++;
            } else if (there.isAir() && column.size() < FarmGrowth.CANE_MAX_HEIGHT) {
                column.add(there);
            } else {
                break;
            }
        }
        if (height == 0) return;
        Cane stack = new Cane(pos, column, height, column.get(height - 1).getValue(SugarCaneBlock.AGE));
        stack.carry = FarmGrowth.startCarry(pos.asLong());
        cane.add(stack);
    }

    private void readBush(BlockPos pos) {
        if (!level.isLoaded(pos) || !level.isLoaded(pos.above())) return;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof SweetBerryBushBlock)) return;
        Bush bush = new Bush(pos, state);
        bush.lit = level.getRawBrightness(pos.above(), 0) >= 9;
        bush.carry = FarmGrowth.startCarry(pos.asLong());
        bushes.add(bush);
    }

    private void readPod(BlockPos pos, Direction facing) {
        if (!level.isLoaded(pos) || !level.isLoaded(pos.relative(facing))) return;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CocoaBlock)) {
            BlockState fresh = Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, facing);
            if (!state.isAir() || !fresh.canSurvive(level, pos)) return;
        }
        Pod pod = new Pod(pos, facing, state);
        pod.carry = FarmGrowth.startCarry(pos.asLong());
        pods.add(pod);
    }

    private void readGlow(BlockPos pos) {
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(CaveVines.BERRIES)) return;
        glows.add(new Glow(pos, state));
    }

    // =========================================================================
    // Growth
    // =========================================================================

    /** The day's work hours, before the farmers' turns: the fields grow, the druids help. */
    void startDay(AwayDays.Day day) {
        grow(day.workTicks());
        druidHelp(day);
    }

    /** The rest of the day, after every turn. */
    void endDay(AwayDays.Day day) {
        grow(day.length() - day.workTicks());
    }

    private void grow(long ticks) {
        double randomTicks = FarmGrowth.randomTicks(ticks, randomTickSpeed);
        if (randomTicks <= 0.0) return;
        for (Plot plot : plots) plot.grow(randomTicks);
        for (Cane stack : cane) stack.grow(randomTicks);
        for (Bush bush : bushes) bush.grow(randomTicks);
        for (Pod pod : pods) pod.grow(randomTicks);
    }

    /**
     * Each working druid with Crop Growing on: one session's steps, an age a
     * step on a growing crop (live {@code finishCropStep}), the crops taken in
     * turn so no one tile gets them all. Druids do no other away work yet.
     */
    private void druidHelp(AwayDays.Day day) {
        List<Plot> growing = new ArrayList<>();
        for (Plot plot : plots) {
            if (plot.state.getBlock() instanceof CropBlock crop && crop.getAge(plot.state) < crop.getMaxAge()) {
                growing.add(plot);
            }
        }
        if (growing.isEmpty()) return;
        for (AwayVillager druid : druids) {
            if (druid.starved() || !druid.bodyHere()) continue;
            NpcEntity npc = druid.body();
            if (!AwayVillager.workEnabled(npc)
                    || !ProfessionSettings.of(npc, DruidSettingsSchema.SCHEMA).getBool(DruidSettingsSchema.TEND_CROPS_ENABLED)) {
                continue;
            }
            int skill = druid.model().brain().druidSkillLevel();
            int stepTicks = DruidBalance.dancesPerStep(skill) * DruidBalance.DANCE_TICKS + DRUID_WALK_TICKS;
            int steps = (int) Math.min(DRUID_STEPS_PER_DAY, Math.floor(day.workTicks() * AwayClock.AWAY_PACE / stepTicks));
            // The top band's second age on 1 step in 20, as an average.
            if (DruidBalance.band(skill) == 5) steps += (int) Math.floor(steps * DruidBalance.DOUBLE_STEP_CHANCE);
            for (int done = 0; done < steps && !growing.isEmpty(); done++) {
                Plot plot = growing.get(druidTurn++ % growing.size());
                CropBlock crop = (CropBlock) plot.state.getBlock();
                int age = crop.getAge(plot.state);
                plot.state = crop.getStateForAge(age + 1);
                druidSteps++;
                if (!(plot.state.getBlock() instanceof CropBlock grown) || grown.getAge(plot.state) >= grown.getMaxAge()) {
                    growing.remove(plot);
                }
            }
        }
    }

    /** A crop's chance of a stage on one random tick at {@code pos}: vanilla's growth speed, beetroot and torchflower slower. */
    private double chanceFor(BlockState crop, BlockPos pos) {
        Block block = crop.getBlock();
        if (block instanceof AttachedStemBlock) {
            // Waits for its fruit to go, then grows as its stem.
            StemKind kind = stemKind(block);
            if (kind == null) return 0.0;
            block = kind.stem();
            crop = block.defaultBlockState();
        }
        if (!(block instanceof CropBlock || block instanceof StemBlock || block instanceof PitcherCropBlock)) return 0.0;
        double chance = FarmGrowth.cropChance(GrowthSpeed.of(crop, level, pos));
        if (block instanceof BeetrootBlock || block instanceof TorchflowerCropBlock) chance *= FarmGrowth.ONE_IN_THREE_SKIPPED;
        return chance;
    }

    // =========================================================================
    // For the away farmers
    // =========================================================================

    public List<Plot> plots() { return plots; }

    public List<Cane> cane() { return cane; }

    public List<Bush> bushes() { return bushes; }

    public List<Pod> pods() { return pods; }

    public List<Glow> glows() { return glows; }

    /** Nothing at all to work: no tile in a loaded chunk. */
    public boolean isEmpty() {
        return plots.isEmpty() && cane.isEmpty() && bushes.isEmpty() && pods.isEmpty() && glows.isEmpty();
    }

    /**
     * What breaking {@code state} drops on average, with {@code tool} (its
     * Fortune counts; a player's tool at its share, as live), from the block's
     * own loot table: the average of a fixed set of seeded rolls, so a preview
     * repeats. Kept per state and tool.
     */
    public Map<Item, Double> drops(BlockState state, BlockPos pos, ItemStack tool, NpcEntity npc) {
        LootKey key = new LootKey(state, tool.getItem(), tool.getEnchantments());
        Map<Item, Double> known = dropCache.get(key);
        if (known != null) return known;
        Map<Item, Double> average = new LinkedHashMap<>();
        ResourceKey<LootTable> tableKey = state.getBlock().getLootTable();
        if (tableKey != BuiltInLootTables.EMPTY) {
            LootTable table = level.getServer().reloadableRegistries().getLootTable(tableKey);
            for (int sample = 0; sample < LOOT_SAMPLES; sample++) {
                LootParams params = new LootParams.Builder(level)
                        .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                        .withParameter(LootContextParams.TOOL, PlayerGear.effective(tool, RandomSource.create(LOOT_SEED + sample)))
                        .withOptionalParameter(LootContextParams.THIS_ENTITY, npc)
                        .withParameter(LootContextParams.BLOCK_STATE, state)
                        .create(LootContextParamSets.BLOCK);
                for (ItemStack drop : table.getRandomItems(params, LOOT_SEED + sample)) {
                    if (!drop.isEmpty()) average.merge(drop.getItem(), drop.getCount() / (double) LOOT_SAMPLES, Double::sum);
                }
            }
        }
        dropCache.put(key, average);
        return average;
    }

    /** A crop just planted on {@code plot}: its growth chance there. */
    void planted(Plot plot) {
        plot.chance = chanceFor(plot.state, plot.pos);
    }

    private record LootKey(BlockState state, Item tool, ItemEnchantments enchantments) {}

    // =========================================================================
    // WRITE
    // =========================================================================

    /** Puts every tile that changed on the world list, as "if still X, make Y". */
    void writeTo(AwayWorld world) {
        for (Plot plot : plots) {
            List<Cell> cells = new ArrayList<>();
            cells.add(new Cell(plot.pos.below(), plot.readSoil, plot.soil));
            cells.add(new Cell(plot.pos, plot.readState, plot.state));
            if (plot.readUpper != null || plot.upper != null) {
                BlockState was = plot.readUpper != null ? plot.readUpper : Blocks.AIR.defaultBlockState();
                cells.add(new Cell(plot.pos.above(), was, plot.upper != null ? plot.upper : was));
            }
            if (plot.fruitPos != null) cells.add(new Cell(plot.fruitPos, plot.readFruit, plot.fruit));
            add(world, "crop tile", cells);
        }
        for (Cane stack : cane) {
            List<Cell> cells = new ArrayList<>();
            Block block = stack.column.get(0).getBlock();
            for (int i = 0; i < stack.column.size(); i++) {
                BlockState now = i < stack.height
                        ? block.defaultBlockState().setValue(SugarCaneBlock.AGE, i == stack.height - 1 ? stack.age : 0)
                        : Blocks.AIR.defaultBlockState();
                cells.add(new Cell(stack.base.above(i), stack.column.get(i), now));
            }
            add(world, "sugar cane", cells);
        }
        for (Bush bush : bushes) add(world, "berry bush", List.of(new Cell(bush.pos, bush.readState, bush.state)));
        for (Pod pod : pods) add(world, "cocoa pod", List.of(new Cell(pod.pos, pod.readState, pod.state)));
        for (Glow glow : glows) add(world, "glow berries", List.of(new Cell(glow.pos, glow.readState, glow.state)));
        LOGGER.info("[AwayRun] farm: {} tile change(s) for the world list", written);
    }

    private void add(AwayWorld world, String what, List<Cell> cells) {
        List<Cell> changed = new ArrayList<>();
        for (Cell cell : cells) if (cell.was != cell.now) changed.add(cell);
        if (changed.isEmpty()) return;
        world.add(new TileChange(what, changed));
        written++;
    }

    /**
     * One cell: "if this is still {@code was} (or the same plant, grown on a
     * little since), make it {@code now}".
     */
    private record Cell(BlockPos pos, BlockState was, BlockState now) {
        boolean holds(ServerLevel level) {
            if (!level.isLoaded(pos)) return false;
            BlockState current = level.getBlockState(pos);
            if (was.isAir()) return current.isAir();
            return current == was || current.is(was.getBlock());
        }
    }

    /**
     * One tile's change, all its cells or none. Removals first, top down and
     * with no shape updates, so nothing left half standing (cane, a tall
     * pitcher) breaks and drops loot the farmer was already paid; then the
     * rest bottom up, the soil before what grows on it, and only a plant that
     * can stand there.
     */
    private record TileChange(String what, List<Cell> cells) implements AwayWorld.Change {
        @Override
        public boolean stillHolds(ServerLevel level) {
            for (Cell cell : cells) if (!cell.holds(level)) return false;
            return true;
        }

        @Override
        public void apply(ServerLevel level) {
            List<Cell> ordered = new ArrayList<>(cells);
            ordered.sort(Comparator.comparingInt((Cell cell) -> cell.pos.getY()).reversed());
            for (Cell cell : ordered) {
                if (cell.now.isAir()) level.setBlock(cell.pos, cell.now, Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE);
            }
            ordered.sort(Comparator.comparingInt((Cell cell) -> cell.pos.getY()));
            for (Cell cell : ordered) {
                if (cell.now.isAir() || !cell.now.canSurvive(level, cell.pos)) continue;
                BlockState current = level.getBlockState(cell.pos);
                level.setBlock(cell.pos, Block.pushEntitiesUp(current, cell.now, level, cell.pos), Block.UPDATE_ALL);
            }
        }

        @Override
        public String describe() {
            return what + " at " + cells.get(0).pos.toShortString();
        }
    }

    // =========================================================================
    // Tiles
    // =========================================================================

    /** A farmland tile: a crop or stem growing, or an empty plot (farmland, or soil gone back to dirt). */
    public static final class Plot {
        private final AwayFarm farm;
        public final BlockPos pos;
        /** What grows or last grew here; null for a blank plot. */
        @Nullable private CropDefinition crop;
        private final BlockState readState;
        private final BlockState readSoil;
        private BlockState state;
        private BlockState soil;
        double carry;
        double chance;
        boolean lit;
        boolean wet;
        boolean roomAbove;
        // A pitcher's top half.
        @Nullable BlockState readUpper;
        @Nullable BlockState upper;
        // A stem's fruit.
        @Nullable StemKind stemKind;
        @Nullable Direction fruitSide;
        @Nullable BlockPos fruitPos;
        @Nullable BlockState readFruit;
        @Nullable BlockState fruit;
        int openSides;
        double fruitCarry;

        private Plot(AwayFarm farm, BlockPos pos, @Nullable CropDefinition crop, BlockState state, BlockState soil) {
            this.farm = farm;
            this.pos = pos.immutable();
            this.crop = crop;
            this.readState = state;
            this.readSoil = soil;
            this.state = state;
            this.soil = soil;
            this.carry = FarmGrowth.startCarry(pos.asLong());
        }

        @Nullable public CropDefinition crop() { return crop; }

        public BlockState state() { return state; }

        /** Nothing grows here. */
        public boolean empty() { return state.isAir(); }

        /** The soil is farmland (not dirt). */
        public boolean tilled() { return soil.getBlock() instanceof FarmBlock; }

        /** Water near enough to till it (read once). */
        public boolean wet() { return wet; }

        /** Ripe for the farmer's harvest (break and replant crops only; stems give fruit instead). */
        public boolean ripe() {
            return crop != null && crop.harvest() instanceof CropHarvestStrategy.BreakAndReplant
                    && crop.matchesCropBlock(state.getBlock()) && CropDefinitionRegistry.isMature(state, crop);
        }

        /** A melon or pumpkin stands on this stem's fruit cell. */
        public boolean hasFruit() {
            return stemKind != null && fruit != null && fruit.is(stemKind.fruit());
        }

        @Nullable public BlockState fruit() { return fruit; }

        @Nullable public BlockPos fruitPos() { return fruitPos; }

        /** The crop is broken off; the soil stays. */
        public void harvest() {
            state = Blocks.AIR.defaultBlockState();
            if (upper != null) upper = Blocks.AIR.defaultBlockState();
        }

        /** The soil goes back to dirt, as live's soil roll after a harvest. */
        public void toDirt() {
            soil = Blocks.DIRT.defaultBlockState();
        }

        /** Tilled again: farmland, wet when water is near. */
        public void till() {
            soil = Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, wet ? FarmBlock.MAX_MOISTURE : 0);
        }

        /** {@code planted} (the seed's block) now grows here. */
        public void plant(BlockState planted, CropDefinition definition) {
            state = planted;
            crop = definition;
            farm.planted(this);
        }

        /** The fruit is cut; the stem stands at its full age again. */
        public void cutFruit() {
            if (!hasFruit() || stemKind == null) return;
            fruit = Blocks.AIR.defaultBlockState();
            farm.fruitOwner.remove(fruitPos);
            state = stemKind.stem().defaultBlockState().setValue(StemBlock.AGE, StemBlock.MAX_AGE);
        }

        private void grow(double randomTicks) {
            if (!lit || chance <= 0.0) return;
            Block block = state.getBlock();
            double expected = randomTicks * chance;
            if (block instanceof CropBlock cropBlock) {
                int age = cropBlock.getAge(state);
                int max = cropBlock.getMaxAge();
                FarmGrowth.Step step = FarmGrowth.step(carry, expected, max - age);
                carry = step.carry();
                if (step.stages() > 0) {
                    state = cropBlock.getStateForAge(age + step.stages());
                    farm.stagesGrown += step.stages();
                }
            } else if (block instanceof StemBlock) {
                growStem(expected);
            } else if (block instanceof PitcherCropBlock) {
                int age = state.getValue(PitcherCropBlock.AGE);
                // Ages 3 and 4 stand two blocks high: no room above, it waits at 2.
                int max = roomAbove ? PitcherCropBlock.MAX_AGE : 2;
                FarmGrowth.Step step = FarmGrowth.step(carry, expected, max - age);
                carry = step.carry();
                if (step.stages() > 0) {
                    state = state.setValue(PitcherCropBlock.AGE, age + step.stages());
                    if (state.getValue(PitcherCropBlock.AGE) >= 3) {
                        upper = state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
                    }
                    farm.stagesGrown += step.stages();
                }
            }
        }

        /** Ages to 7, then the growth tries a side for the fruit. */
        private void growStem(double expected) {
            int age = state.getValue(StemBlock.AGE);
            double total = carry + expected;
            if (age < StemBlock.MAX_AGE) {
                int need = StemBlock.MAX_AGE - age;
                if (total < need) {
                    int whole = (int) Math.floor(total);
                    if (whole > 0) {
                        state = state.setValue(StemBlock.AGE, age + whole);
                        farm.stagesGrown += whole;
                    }
                    carry = total - whole;
                    return;
                }
                state = state.setValue(StemBlock.AGE, StemBlock.MAX_AGE);
                farm.stagesGrown += need;
                total -= need;
            }
            carry = 0.0;
            if (stemKind == null || fruitSide == null || fruitPos == null) return;
            Plot owner = farm.fruitOwner.get(fruitPos);
            if (owner != null && owner != this) return;
            FarmGrowth.Step fruitStep = FarmGrowth.stemFruit(fruitCarry, total, openSides);
            fruitCarry = fruitStep.carry();
            if (fruitStep.stages() > 0) {
                fruit = stemKind.fruit().defaultBlockState();
                state = stemKind.attached().defaultBlockState().setValue(AttachedStemBlock.FACING, fruitSide);
                farm.fruitOwner.put(fruitPos, this);
                farm.fruitsGrown++;
            }
        }
    }

    /** A sugar cane stack, from its base. */
    public static final class Cane {
        public final BlockPos base;
        /** The cells read, from the base up: cane, then air it may grow into. */
        private final List<BlockState> column;
        private int height;
        private int age;
        double carry;
        private int grownBlocks;

        private Cane(BlockPos base, List<BlockState> column, int height, int age) {
            this.base = base.immutable();
            this.column = column;
            this.height = height;
            this.age = age;
        }

        public int height() { return height; }

        /** Standing on ground live cuts cane on (sand or dirt kinds; the name is from when it was sand only). */
        public boolean onSand(ServerLevel level) {
            return com.alex.test.village.villagers.farmer.FarmerManager.isCaneGround(level.getBlockState(base.below()));
        }

        /** Cut to its base, as live: every block above it. */
        public void cut() {
            height = 1;
            age = 0;
        }

        private void grow(double randomTicks) {
            FarmGrowth.Step step = FarmGrowth.step(carry, randomTicks, Integer.MAX_VALUE);
            carry = step.carry();
            int room = column.size() - height;
            int before = height;
            int[] grown = FarmGrowth.cane(height, age, step.stages(), room);
            height = grown[0];
            age = grown[1];
            grownBlocks += Math.max(0, height - before);
        }
    }

    /** A sweet berry bush. */
    public static final class Bush {
        public final BlockPos pos;
        private final BlockState readState;
        private BlockState state;
        boolean lit;
        double carry;

        private Bush(BlockPos pos, BlockState state) {
            this.pos = pos.immutable();
            this.readState = state;
            this.state = state;
        }

        public int age() { return state.getValue(SweetBerryBushBlock.AGE); }

        public BlockState state() { return state; }

        /** Picked: back to age 1, as live. */
        public void pick() {
            state = state.setValue(SweetBerryBushBlock.AGE, 1);
        }

        private void grow(double randomTicks) {
            int age = age();
            if (!lit || age >= SweetBerryBushBlock.MAX_AGE) return;
            FarmGrowth.Step step = FarmGrowth.step(carry, randomTicks * FarmGrowth.BUSH_CHANCE,
                    SweetBerryBushBlock.MAX_AGE - age);
            carry = step.carry();
            if (step.stages() > 0) state = state.setValue(SweetBerryBushBlock.AGE, age + step.stages());
        }
    }

    /** A cocoa spot on a jungle log, a pod on it or not. */
    public static final class Pod {
        public final BlockPos pos;
        public final Direction facing;
        private final BlockState readState;
        private BlockState state;
        double carry;

        private Pod(BlockPos pos, Direction facing, BlockState state) {
            this.pos = pos.immutable();
            this.facing = facing;
            this.readState = state;
            this.state = state;
        }

        public BlockState state() { return state; }

        public boolean empty() { return !(state.getBlock() instanceof CocoaBlock); }

        public boolean ripe() {
            return !empty() && state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
        }

        public void harvest() {
            state = Blocks.AIR.defaultBlockState();
        }

        /** A bean set on the log: a new pod. */
        public void plant() {
            state = Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, facing);
        }

        private void grow(double randomTicks) {
            if (empty()) return;
            int age = state.getValue(CocoaBlock.AGE);
            if (age >= CocoaBlock.MAX_AGE) return;
            FarmGrowth.Step step = FarmGrowth.step(carry, randomTicks * FarmGrowth.BUSH_CHANCE, CocoaBlock.MAX_AGE - age);
            carry = step.carry();
            if (step.stages() > 0) state = state.setValue(CocoaBlock.AGE, age + step.stages());
        }
    }

    /**
     * A glow berry vine. Picked only: new berries come with new vine, which
     * grows down into the air, and that is left out (my call).
     */
    public static final class Glow {
        public final BlockPos pos;
        private final BlockState readState;
        private BlockState state;

        private Glow(BlockPos pos, BlockState state) {
            this.pos = pos.immutable();
            this.readState = state;
            this.state = state;
        }

        public boolean ripe() { return state.getValue(CaveVines.BERRIES); }

        public void pick() {
            state = state.setValue(CaveVines.BERRIES, false);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** For the log and the preview. */
    int caneGrown() {
        int blocks = 0;
        for (Cane stack : cane) blocks += stack.grownBlocks;
        return blocks;
    }

    String describe() {
        return plots.size() + " crop tile(s), " + cane.size() + " cane, " + bushes.size() + " berry bush(es), "
                + pods.size() + " cocoa spot(s), " + glows.size() + " glow berry vine(s)"
                + (notLoaded > 0 ? " (" + notLoaded + " tile(s) not loaded, left out)" : "")
                + "; grew " + stagesGrown + " stage(s), " + fruitsGrown + " fruit(s), " + caneGrown()
                + " cane block(s); druids " + druidSteps + " step(s)";
    }

    @Nullable
    private static StemKind stemKind(Block block) {
        for (StemKind kind : STEMS) {
            if (kind.stem() == block || kind.attached() == block) return kind;
        }
        return null;
    }

    private static boolean isTillable(BlockState soil) {
        return soil.is(Blocks.DIRT) || soil.is(Blocks.GRASS_BLOCK);
    }

    /** Water close enough to keep farmland wet, when every chunk it reads is loaded. */
    private boolean hydrated(BlockPos soil) {
        return loadedAround(soil, 4) && FarmerManager.isActuallyHydrated(level, soil);
    }

    /** The cells {@code reach} around {@code pos} (same height) are in loaded chunks: reading them loads nothing. */
    private boolean loadedAround(BlockPos pos, int reach) {
        return level.isLoaded(pos.offset(-reach, 0, -reach)) && level.isLoaded(pos.offset(reach, 0, -reach))
                && level.isLoaded(pos.offset(-reach, 0, reach)) && level.isLoaded(pos.offset(reach, 0, reach));
    }

    /**
     * Calls vanilla's {@code CropBlock.getGrowthSpeed}, which is protected,
     * so the soil, water and row rules (NeoForge's soil hook too) are the
     * game's own. Never made: only its static call is used.
     */
    private abstract static class GrowthSpeed extends CropBlock {
        private GrowthSpeed() {
            super(BlockBehaviour.Properties.of());
        }

        static float of(BlockState crop, BlockGetter level, BlockPos pos) {
            return getGrowthSpeed(crop, level, pos);
        }
    }
}
