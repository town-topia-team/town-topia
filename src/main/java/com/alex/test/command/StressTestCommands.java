package com.alex.test.command;

import com.alex.test.DynamicVillages;
import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.testkit.ScenarioBuilder;
import com.alex.test.village.VillageFounding;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.GuardSwordUtils;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /villager stresstest}: a crowd for measuring what the game costs with hundreds of
 * villagers on screen. Guards in iron armor, an iron sword and a shield stand in a block in
 * front of you; zombies in unbreakable iron helmets (no burning in daylight) stand across a
 * field from them, and the two sides meet in the middle. Read the cost with
 * {@code /villagerfps log on} (client, {@code [ClientCost]} lines in the game log).
 *
 * <pre>
 *   /villager stresstest                               300 guards, 200 zombies, a real fight
 *   /villager stresstest &lt;guards&gt; &lt;zombies&gt;            other counts (up to 600 each)
 *   /villager stresstest &lt;guards&gt; &lt;zombies&gt; endless    hits land but take no health: the fight never ends
 *   /villager stresstest &lt;guards&gt; &lt;zombies&gt; still      nobody moves: drawing cost only
 *   /villager stresstest clear                         the crowd, its drops and its village members go
 * </pre>
 *
 * <p>Guards only think inside a village, so the first run levels a field where you stand
 * (grass floor, air above: use a test world) and puts up a small Town Hall hut behind you,
 * a village of its own, and a stone pillar to watch from. Later runs within 256 blocks reuse
 * that field, so every run is seen from the same spot. Each run clears the last crowd first.
 * {@code still} guards are frozen puppets that belong to no village.
 */
public final class StressTestCommands {

    /** On every guard, zombie and the hut's Town Hall frame. */
    public static final String TAG = "dynamicvillages.stresstest";
    /** Also on every guard and zombie of an {@code endless} crowd ({@link #keepEndlessAlive}). */
    public static final String ENDLESS_TAG = "dynamicvillages.stresstest.endless";
    /** The field's spot and facing, kept on the Town Hall frame. */
    private static final String ORIGIN_KEY = "dynamicvillages_stresstest";

    private static final int MAX = 600;
    /** Blocks between neighbours in a block of guards or zombies. */
    private static final double SPACING = 1.5;
    /** The first row of guards, blocks ahead of the watching spot's line. */
    private static final double FRONT = 5;
    /** Open ground between the guards' last row and the zombies' first (a zombie notices within 35). */
    private static final double GAP = 20;
    /** Where the Town Hall hut stands: behind, to the left. */
    private static final int HALL_AHEAD = -11, HALL_RIGHT = -12;
    /** The pillar you watch from: behind the guards, in the middle. */
    private static final int TOWER_AHEAD = -10, TOWER_HEIGHT = 6;
    /** Ground cleared above the floor. */
    private static final int CLEAR_HEIGHT = 12;

    private enum Mode { FIGHT, ENDLESS, STILL }

    private StressTestCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("stresstest")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> run(ctx, 300, 200, Mode.FIGHT))
                .then(Commands.literal("clear").executes(StressTestCommands::clear))
                .then(Commands.argument("guards", IntegerArgumentType.integer(0, MAX))
                        .then(Commands.argument("zombies", IntegerArgumentType.integer(0, MAX))
                                .executes(ctx -> run(ctx, Mode.FIGHT))
                                .then(Commands.literal("fight").executes(ctx -> run(ctx, Mode.FIGHT)))
                                .then(Commands.literal("endless").executes(ctx -> run(ctx, Mode.ENDLESS)))
                                .then(Commands.literal("still").executes(ctx -> run(ctx, Mode.STILL)))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Mode mode) {
        return run(ctx, IntegerArgumentType.getInteger(ctx, "guards"),
                IntegerArgumentType.getInteger(ctx, "zombies"), mode);
    }

    // -------------------------------------------------------------------------
    // The field
    // -------------------------------------------------------------------------

    /**
     * The field in its own frame: {@code ahead} blocks away from the watching side,
     * {@code right} blocks to the right, at the floor's standing height.
     */
    private record Field(ServerLevel level, int x, int feetY, int z, Direction ahead) {

        Direction right() {
            return ahead.getClockWise();
        }

        /** A standing spot; whole numbers land on block centres. */
        Vec3 at(double aheadBy, double rightBy) {
            return new Vec3(x + 0.5 + ahead.getStepX() * aheadBy + right().getStepX() * rightBy, feetY,
                    z + 0.5 + ahead.getStepZ() * aheadBy + right().getStepZ() * rightBy);
        }

        BlockPos column(int aheadBy, int rightBy) {
            return new BlockPos(x + ahead.getStepX() * aheadBy + right().getStepX() * rightBy, feetY,
                    z + ahead.getStepZ() * aheadBy + right().getStepZ() * rightBy);
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("x", x);
            tag.putInt("y", feetY);
            tag.putInt("z", z);
            tag.putInt("facing", ahead.get2DDataValue());
            return tag;
        }

        static Field load(ServerLevel level, CompoundTag tag) {
            return new Field(level, tag.getInt("x"), tag.getInt("y"), tag.getInt("z"),
                    Direction.from2DDataValue(tag.getInt("facing")));
        }
    }

    /** A block of {@code count} standing spots, {@code columns} wide, starting {@code firstRow} ahead. */
    private static List<Vec3> block(Field field, int count, int columns, double firstRow) {
        List<Vec3> spots = new ArrayList<>(count);
        double halfWidth = (columns - 1) * SPACING / 2;
        for (int i = 0; i < count; i++) {
            int row = i / columns, column = i % columns;
            spots.add(field.at(firstRow + row * SPACING, column * SPACING - halfWidth));
        }
        return spots;
    }

    private static int columns(int count) {
        return count > 400 ? 30 : 20;
    }

    private static double rowsLength(int count) {
        int rows = (count + columns(count) - 1) / columns(count);
        return Math.max(0, rows - 1) * SPACING;
    }

    // -------------------------------------------------------------------------
    // Run
    // -------------------------------------------------------------------------

    private static int run(CommandContext<CommandSourceStack> ctx, int guards, int zombies, Mode mode) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Run this as a player: the crowd is set out where you stand."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        long started = System.nanoTime();
        int cleared = clearCrowd(level);

        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        VillageManager village = null;
        Field field = null;
        ItemFrame hallFrame = findHall(level, player.position());
        if (hallFrame != null) {
            village = registry.findVillageAt(level, hallFrame.blockPosition());
            if (village != null) field = Field.load(level, hallFrame.getPersistentData().getCompound(ORIGIN_KEY));
        }

        double zombieRow = FRONT + rowsLength(guards) + GAP;
        double fieldEnd = zombieRow + rowsLength(zombies) + 4;
        int halfWidth = (int) Math.ceil(Math.max(columns(guards), columns(zombies)) * SPACING / 2) + 4;

        if (field == null) {
            BlockPos feet = player.blockPosition();
            int feetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
            field = new Field(level, feet.getX(), feetY, feet.getZ(), player.getDirection());
            if (registry.overlapsActiveVillage(level, field.column(HALL_AHEAD, HALL_RIGHT))) {
                source.sendFailure(Component.literal("Too close to a village: the test hut founds a village of"
                        + " its own, and villages need about 300 blocks between them. A flat test world is best."));
                return 0;
            }
            int changed = levelGround(field, -18, (int) Math.ceil(fieldEnd), -Math.max(20, halfWidth), Math.max(20, halfWidth));
            village = foundVillage(field);
            if (village == null) {
                source.sendFailure(Component.literal("The test village could not be founded here (see the log)."));
                return 0;
            }
            buildTower(field);
            DynamicVillages.LOGGER.info("[StressTest] Field levelled at {} {} {} facing {} ({} blocks changed), village {}.",
                    field.x(), field.feetY(), field.z(), field.ahead(), changed, village.getVillageId());
        } else {
            // The hut and the pillar stand behind the line; only the field ahead is levelled again.
            levelGround(field, 0, (int) Math.ceil(fieldEnd), -halfWidth, halfWidth);
        }

        float guardYaw = field.ahead().toYRot();
        float zombieYaw = field.ahead().getOpposite().toYRot();
        int guardsMade = 0;
        for (Vec3 spot : block(field, guards, columns(guards), FRONT)) {
            if (spawnGuard(level, village, spot, guardYaw, mode)) guardsMade++;
        }
        int zombiesMade = 0;
        for (Vec3 spot : block(field, zombies, columns(zombies), zombieRow)) {
            if (spawnZombie(level, spot, zombieYaw, mode)) zombiesMade++;
        }

        Vec3 watch = field.at(TOWER_AHEAD, 0);
        player.teleportTo(level, watch.x, field.feetY() + TOWER_HEIGHT, watch.z, guardYaw, 17.0F);

        long ms = (System.nanoTime() - started) / 1_000_000L;
        DynamicVillages.LOGGER.info("[StressTest] {} guards and {} zombies, mode {}, {} cleared first, {} ms.",
                guardsMade, zombiesMade, mode, cleared, ms);
        String how = switch (mode) {
            case FIGHT -> "they fight for real";
            case ENDLESS -> "hits land but take no health, so the fight never ends";
            case STILL -> "nobody moves: drawing cost only";
        };
        int g = guardsMade, z = zombiesMade;
        source.sendSuccess(() -> Component.literal("Stress test: " + g + " guards and " + z + " zombies, " + how
                + " (" + ms + " ms on the server)."), true);
        source.sendSuccess(() -> Component.literal("Cost log: /villagerfps log on. Remove the crowd: /villager stresstest clear"), false);
        if (!level.isDay() && mode != Mode.STILL) {
            source.sendSuccess(() -> Component.literal("It is night: guards may head for bed. /time set day first."), false);
        }
        return guardsMade + zombiesMade;
    }

    /** The nearest stress test Town Hall frame within 256 blocks whose village still stands. */
    @Nullable
    private static ItemFrame findHall(ServerLevel level, Vec3 near) {
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        ItemFrame best = null;
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, new AABB(near, near).inflate(256),
                frame -> frame.getTags().contains(TAG) && frame.getPersistentData().contains(ORIGIN_KEY))) {
            if (registry.findVillageAt(level, frame.blockPosition()) == null) continue;
            if (best == null || frame.distanceToSqr(near) < best.distanceToSqr(near)) best = frame;
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Building
    // -------------------------------------------------------------------------

    /** Grass floor, dirt under it, air above, over the given part of the field. */
    private static int levelGround(Field field, int aheadFrom, int aheadTo, int rightFrom, int rightTo) {
        ServerLevel level = field.level();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        int changed = 0;
        for (int a = aheadFrom; a <= aheadTo; a++) {
            for (int r = rightFrom; r <= rightTo; r++) {
                BlockPos column = field.column(a, r);
                changed += set(level, column.below(), grass);
                for (int dy = 2; dy <= 4; dy++) changed += set(level, column.below(dy), dirt);
                for (int dy = 0; dy < CLEAR_HEIGHT; dy++) changed += set(level, column.above(dy), air);
            }
        }
        return changed;
    }

    private static int set(ServerLevel level, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos) == state) return 0;
        level.setBlock(pos, state, 3);
        return 1;
    }

    /** The Town Hall hut (as the test kit builds one) and its village; arrivals off. */
    @Nullable
    private static VillageManager foundVillage(Field field) {
        ServerLevel level = field.level();
        BlockPos centre = field.column(HALL_AHEAD, HALL_RIGHT);
        int half = ScenarioBuilder.DEFAULT_INTERIOR / 2;
        ScenarioBuilder.Room hall = ScenarioBuilder.buildRoom(level, centre.getX() - half, field.feetY(),
                centre.getZ() - half, ScenarioBuilder.RoomFurniture.NONE);
        hall.frame().addTag(TAG);
        hall.frame().getPersistentData().put(ORIGIN_KEY, field.save());
        hall.frame().setItem(new ItemStack(ModItems.TOWN_HALL.get()), false);
        VillageManager village;
        // Scenery, not a founding: no gift chest, no welcome merchants.
        try (AutoCloseable ignored = VillageFounding.suppress()) {
            village = VillageManagerRegistry.get(level.getServer()).createFromTownHall(level, hall.frame());
        } catch (Exception e) {
            DynamicVillages.LOGGER.error("[StressTest] Founding the test village failed.", e);
            return null;
        }
        if (village != null) {
            village.visitors().setArrivalsEnabled(false);
            village.nomads().setArrivalsEnabled(false);
        }
        return village;
    }

    /** A stone pillar behind the guards; you are put on top of it to watch. */
    private static void buildTower(Field field) {
        BlockPos base = field.column(TOWER_AHEAD, 0);
        for (int dy = 0; dy < TOWER_HEIGHT; dy++) {
            field.level().setBlock(base.above(dy), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        }
    }

    // -------------------------------------------------------------------------
    // The crowd
    // -------------------------------------------------------------------------

    private static boolean spawnGuard(ServerLevel level, VillageManager village, Vec3 spot, float yaw, Mode mode) {
        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        if (npc == null) return false;
        npc.moveTo(spot.x, spot.y, spot.z, yaw, 0.0F);
        npc.setYHeadRot(yaw);
        npc.setYBodyRot(yaw);
        npc.addTag(TAG);
        if (mode == Mode.STILL) {
            // A frozen puppet that belongs to no village (as the Warlord show's dummies).
            npc.setNoAi(true);
            npc.addTag(NpcEntity.MOCAP_PREVIEW_TAG);
            if (!level.addFreshEntity(npc)) return false;
            npc.setProfession(Profession.GUARD);
            for (EquipmentSlot slot : ARMOR) npc.setItemSlot(slot, new ItemStack(armorFor(slot)));
            npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            return true;
        }
        if (!level.addFreshEntity(npc)) return false;
        village.bindVillager(npc);
        ScenarioBuilder.applyProfession(npc, Profession.GUARD);
        // The village's own goods, so the guard may wear and use them.
        for (EquipmentSlot slot : ARMOR) {
            ItemStack piece = VillageItemOwnership.produce(new ItemStack(armorFor(slot)), village);
            if (!piece.isEmpty()) npc.setItemSlot(slot, piece);
        }
        npc.getInventory().addItem(VillageItemOwnership.produce(new ItemStack(Items.IRON_SWORD), village));
        npc.getInventory().addItem(VillageItemOwnership.produce(new ItemStack(Items.SHIELD), village));
        GuardSwordUtils.equipBestAllowedSword(npc, GuardSettings.of(npc).getAllowedSwords());
        if (mode == Mode.ENDLESS) npc.addTag(ENDLESS_TAG);
        return true;
    }

    /**
     * An {@code endless} crowd takes its hits (the swing, the flinch, the knockback) but
     * loses no health, so the fight goes on for as long as the test runs. Not
     * {@code setInvulnerable}: Minecraft never picks an invulnerable mob as a target
     * ({@code LivingEntity.canBeSeenAsEnemy}), so neither side went for the other (10-19).
     * Registered at the lowest priority, so nothing raises the damage again after it.
     */
    public static void keepEndlessAlive(LivingDamageEvent.Pre event) {
        if (event.getEntity().getTags().contains(ENDLESS_TAG)) event.setNewDamage(0.0F);
    }

    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static net.minecraft.world.item.Item armorFor(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> Items.IRON_HELMET;
            case CHEST -> Items.IRON_CHESTPLATE;
            case LEGS -> Items.IRON_LEGGINGS;
            default -> Items.IRON_BOOTS;
        };
    }

    private static boolean spawnZombie(ServerLevel level, Vec3 spot, float yaw, Mode mode) {
        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie == null) return false;
        zombie.moveTo(spot.x, spot.y, spot.z, yaw, 0.0F);
        zombie.setYHeadRot(yaw);
        zombie.setYBodyRot(yaw);
        // A zombie's helmet wears out in the sun (about 16 s for iron); this one never does.
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        helmet.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
        zombie.setItemSlot(EquipmentSlot.HEAD, helmet);
        zombie.setDropChance(EquipmentSlot.HEAD, 0.0F);
        zombie.setPersistenceRequired();
        zombie.addTag(TAG);
        if (mode == Mode.STILL) zombie.setNoAi(true);
        if (mode == Mode.ENDLESS) zombie.addTag(ENDLESS_TAG);
        return level.addFreshEntity(zombie);
    }

    // -------------------------------------------------------------------------
    // Clear
    // -------------------------------------------------------------------------

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        int removed = clearCrowd(ctx.getSource().getLevel());
        ctx.getSource().sendSuccess(() -> Component.literal("Stress test cleared: " + removed
                + " guards and zombies (and the drops on the field). The hut stays, for the next run."), true);
        return removed;
    }

    /**
     * Every tagged guard and zombie in {@code level}: guards leave their village for good
     * first, as a villager taken away does. Drops lying on a test field go too.
     */
    private static int clearCrowd(ServerLevel level) {
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        List<Entity> crowd = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity.getTags().contains(TAG) && !(entity instanceof ItemFrame)) crowd.add(entity);
        }
        for (Entity entity : crowd) {
            if (entity instanceof NpcEntity npc && npc.getVillageLink() != null) {
                VillageManager village = registry.get(npc.getVillageLink().villageId());
                if (village != null) registry.forgetVillager(village, npc.getNpcId());
            }
            entity.discard();
        }
        for (ItemFrame frame : hallFrames(level)) {
            Field field = Field.load(level, frame.getPersistentData().getCompound(ORIGIN_KEY));
            Vec3 a = field.at(-20, -30), b = field.at(110, 30);
            AABB area = new AABB(a.x, field.feetY() - 3, a.z, b.x, field.feetY() + 20, b.z);
            for (Entity drop : level.getEntitiesOfClass(Entity.class, area,
                    e -> e instanceof ItemEntity || e instanceof ExperienceOrb)) {
                drop.discard();
            }
        }
        return crowd.size();
    }

    private static List<ItemFrame> hallFrames(ServerLevel level) {
        List<ItemFrame> frames = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemFrame frame && frame.getTags().contains(TAG)
                    && frame.getPersistentData().contains(ORIGIN_KEY)) frames.add(frame);
        }
        return frames;
    }
}
