package com.alex.test.testkit;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The half of {@code /vtest} that exists so a harness can play the game the way
 * a person does: survey real terrain, put down blocks, hang a token, hire a
 * villager, and spend what the village earned.
 *
 * <p>The rest of the test kit builds prefabricated villages on a superflat
 * world. That is the right tool for "does the lumberjack fell a tree", and the
 * wrong one for "does a village founded on a hillside behave sanely over twenty
 * days". These commands cover the second question.
 *
 * <p>Two of them exist purely because RCON is not a player:
 * <ul>
 *   <li>{@link #touch} — vanilla {@code /setblock} and {@code /fill} never fire
 *       {@code BlockEvent.EntityPlaceEvent}, so the nav graph never learns that
 *       the world changed. A harness that builds with {@code /fill} and skips
 *       this is testing a village that cannot see its own houses.</li>
 *   <li>{@link #job} — profession tokens are applied by right-clicking a
 *       villager, and a headless server has no hand to click with. This calls
 *       the same assignment handlers that a right-click would.</li>
 * </ul>
 */
public final class PlaythroughCommands {

    /** Hard cap on {@code /vtest site}, which is O(radius^2) heightmap probes. */
    private static final int MAX_SITE_RADIUS = 64;

    /** Hard cap on {@code /vtest touch}, which is O(volume) and runs in one tick. */
    private static final int MAX_TOUCH_VOLUME = 400_000;

    private PlaythroughCommands() {}

    // =========================================================================
    // Registration
    // =========================================================================

    /** Every subtree this class contributes to {@code /vtest}. */
    public static List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(groundNode(), siteNode(), touchNode(), rebuildNode(),
                frameNode(), withdrawNode(), jobNode(), nodesNode(), tickerNode(),
                blocksNode());
    }

    /**
     * {@code /vtest ticker add <x1> <y1> <z1> <x2> <y2> <z2>} and
     * {@code /vtest ticker clear} — extra ground for the headless random ticker.
     *
     * <p>See {@link TestKit#addRandomTickBox}: without this, nothing outside a
     * registered farm plot ever grows on a playerless server.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> tickerNode() {
        return Commands.literal("ticker")
                .then(Commands.literal("clear").executes(ctx -> {
                    TestKit.clearRandomTickBoxes();
                    return ok(ctx, "ticker cleared");
                }))
                .then(Commands.literal("add")
                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                        .then(Commands.argument("y1", IntegerArgumentType.integer())
                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                        .then(Commands.argument("y2", IntegerArgumentType.integer())
                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                .executes(PlaythroughCommands::tickerAdd))))))));
    }

    private static int tickerAdd(CommandContext<CommandSourceStack> ctx) {
        int volume = TestKit.addRandomTickBox(
                IntegerArgumentType.getInteger(ctx, "x1"),
                IntegerArgumentType.getInteger(ctx, "y1"),
                IntegerArgumentType.getInteger(ctx, "z1"),
                IntegerArgumentType.getInteger(ctx, "x2"),
                IntegerArgumentType.getInteger(ctx, "y2"),
                IntegerArgumentType.getInteger(ctx, "z2"));
        if (volume < 0) {
            return err(ctx, "box too large; max "
                    + TestKit.MAX_TICK_BOX_VOLUME + " blocks");
        }
        return ok(ctx, "ticker box added volume=" + volume);
    }

    /**
     * {@code /vtest blocks <x1> <y1> <z1> <x2> <y2> <z2> <block>} — how many of
     * a block a box holds, and where the first few of them are.
     *
     * <p>Vanilla can ask whether a single block matches, and it can count
     * entities, but it cannot count blocks without changing them: the only
     * counting form is {@code /fill ... replace}, which is no use for reading a
     * region a test is about to make further assertions on. Asking block by
     * block over RCON is one command per block, so a forty-block tunnel through
     * a modest rock body is nearly a thousand round trips.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> blocksNode() {
        return Commands.literal("blocks")
                .then(Commands.argument("x1", IntegerArgumentType.integer())
                .then(Commands.argument("y1", IntegerArgumentType.integer())
                .then(Commands.argument("z1", IntegerArgumentType.integer())
                .then(Commands.argument("x2", IntegerArgumentType.integer())
                .then(Commands.argument("y2", IntegerArgumentType.integer())
                .then(Commands.argument("z2", IntegerArgumentType.integer())
                .then(Commands.argument("block", StringArgumentType.word())
                        .executes(PlaythroughCommands::blocks))))))));
    }

    /** Blocks whose positions are listed back, past which only the count is. */
    private static final int MAX_LISTED_POSITIONS = 64;

    /** Blocks a single census may look at, so a typo cannot stall the server. */
    private static final int MAX_SCANNED_BLOCKS = 200_000;

    private static int blocks(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        int x1 = IntegerArgumentType.getInteger(ctx, "x1");
        int y1 = IntegerArgumentType.getInteger(ctx, "y1");
        int z1 = IntegerArgumentType.getInteger(ctx, "z1");
        int x2 = IntegerArgumentType.getInteger(ctx, "x2");
        int y2 = IntegerArgumentType.getInteger(ctx, "y2");
        int z2 = IntegerArgumentType.getInteger(ctx, "z2");
        String wanted = StringArgumentType.getString(ctx, "block");

        int lox = Math.min(x1, x2), hix = Math.max(x1, x2);
        int loy = Math.min(y1, y2), hiy = Math.max(y1, y2);
        int loz = Math.min(z1, z2), hiz = Math.max(z1, z2);
        long volume = (long) (hix - lox + 1) * (hiy - loy + 1) * (hiz - loz + 1);
        if (volume > MAX_SCANNED_BLOCKS) {
            return err(ctx, "box of " + volume + " blocks is over the "
                    + MAX_SCANNED_BLOCKS + " limit");
        }

        boolean air = "air".equals(wanted) || "minecraft:air".equals(wanted);
        StringBuilder positions = new StringBuilder();
        int found = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = lox; x <= hix; x++) {
            for (int y = loy; y <= hiy; y++) {
                for (int z = loz; z <= hiz; z++) {
                    cursor.set(x, y, z);
                    if (!level.hasChunkAt(cursor)) continue;
                    BlockState state = level.getBlockState(cursor);
                    boolean match = air ? state.isAir() : id(state).equals(normalise(wanted));
                    if (!match) continue;
                    if (found < MAX_LISTED_POSITIONS) {
                        if (positions.length() > 0) positions.append(';');
                        positions.append(x).append(',').append(y).append(',').append(z);
                    }
                    found++;
                }
            }
        }
        return ok(ctx, "blocks " + normalise(wanted) + " count=" + found
                + " at=" + positions);
    }

    /** Accepts {@code stone} as well as {@code minecraft:stone}. */
    private static String normalise(String block) {
        return block.contains(":") ? block : "minecraft:" + block;
    }

    /** {@code /vtest ground <x> <z>} — surface height at one column. */
    private static LiteralArgumentBuilder<CommandSourceStack> groundNode() {
        return Commands.literal("ground")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> ground(ctx,
                                        IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "z")))));
    }

    /** {@code /vtest site <x> <z> <radius>} — is this a place you could build? */
    private static LiteralArgumentBuilder<CommandSourceStack> siteNode() {
        return Commands.literal("site")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .then(Commands.argument("radius",
                                                IntegerArgumentType.integer(1, MAX_SITE_RADIUS))
                                        .executes(ctx -> site(ctx,
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                IntegerArgumentType.getInteger(ctx, "radius"))))));
    }

    /** {@code /vtest touch <x1> <y1> <z1> <x2> <y2> <z2>} — "a player built here". */
    private static LiteralArgumentBuilder<CommandSourceStack> touchNode() {
        return Commands.literal("touch").then(box(b -> b.executes(ctx -> touch(ctx, false))
                .then(Commands.literal("now").executes(ctx -> touch(ctx, true)))));
    }

    /** {@code /vtest rebuild <x> <y> <z>} — force the nav rebuild now, no debounce. */
    private static LiteralArgumentBuilder<CommandSourceStack> rebuildNode() {
        return Commands.literal("rebuild").then(xyz(z -> z.executes(
                ctx -> rebuild(ctx, readXyz(ctx)))));
    }

    /** {@code /vtest frame <x> <y> <z> <facing> <token>} — hang a marker. */
    private static LiteralArgumentBuilder<CommandSourceStack> frameNode() {
        return Commands.literal("frame").then(xyz(z -> z
                .then(Commands.argument("facing", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                List.of("north", "south", "east", "west", "up", "down"), b))
                        .then(Commands.argument("token", ResourceLocationArgument.id())
                                .executes(ctx -> frame(ctx, readXyz(ctx),
                                        StringArgumentType.getString(ctx, "facing"),
                                        ResourceLocationArgument.getId(ctx, "token")))))));
    }

    /** {@code /vtest withdraw <x> <y> <z> <item> <count>} — spend village stock. */
    private static LiteralArgumentBuilder<CommandSourceStack> withdrawNode() {
        return Commands.literal("withdraw").then(xyz(z -> z
                .then(Commands.argument("item", ResourceLocationArgument.id())
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 4096))
                                .executes(ctx -> withdraw(ctx, readXyz(ctx),
                                        ResourceLocationArgument.getId(ctx, "item"),
                                        IntegerArgumentType.getInteger(ctx, "count")))))));
    }

    /** {@code /vtest nodes <x> <y> <z> <radius> <dy>} — what the graph holds nearby. */
    private static LiteralArgumentBuilder<CommandSourceStack> nodesNode() {
        return Commands.literal("nodes").then(xyz(z -> z
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                        .then(Commands.argument("dy", IntegerArgumentType.integer(0, 64))
                                .executes(ctx -> nodes(ctx, readXyz(ctx),
                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                        IntegerArgumentType.getInteger(ctx, "dy")))))));
    }

    /** {@code /vtest job <id> <profession>} — the right-click a headless server cannot do. */
    private static LiteralArgumentBuilder<CommandSourceStack> jobNode() {
        return Commands.literal("job").then(
                Commands.argument("target", StringArgumentType.word())
                        .then(Commands.argument("profession", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        java.util.Arrays.stream(Profession.values())
                                                .map(Enum::name).toList(), b))
                                .executes(PlaythroughCommands::job)));
    }

    // =========================================================================
    // Terrain survey
    // =========================================================================

    /**
     * Reports where the ground is at one column.
     *
     * <p>Three heightmaps rather than one, because on generated terrain they
     * disagree in exactly the ways that matter: {@code surface} counts leaves
     * and water, {@code solid} is what you would stand on, and {@code floor}
     * is what is under a lake. A site whose {@code solid} and {@code floor}
     * differ is a site with water on it.
     */
    private static int ground(CommandContext<CommandSourceStack> ctx, int x, int z) {
        ServerLevel level = ctx.getSource().getLevel();
        level.getChunk(x >> 4, z >> 4); // heightmaps are per-chunk; make sure it exists

        int solid = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
        BlockPos top = new BlockPos(x, solid - 1, z);

        return ok(ctx, "ground x=" + x + " z=" + z
                + " feetY=" + solid
                + " topY=" + (solid - 1)
                + " top=" + id(level.getBlockState(top))
                + " surfaceY=" + (surface - 1)
                + " floorY=" + (floor - 1)
                + " fluid=" + (!level.getBlockState(new BlockPos(x, solid, z))
                        .getFluidState().isEmpty()));
    }

    /**
     * Scores a square of terrain for "could a village go here".
     *
     * <p>A harness picking build sites off a generated world needs one number
     * for flatness and one for wetness, and it needs them without shipping
     * 9000 {@code /execute if block} probes over RCON.
     */
    private static int site(CommandContext<CommandSourceStack> ctx, int cx, int cz, int radius) {
        ServerLevel level = ctx.getSource().getLevel();

        Map<Integer, Integer> heightHistogram = new HashMap<>();
        Map<String, Integer> topBlocks = new HashMap<>();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int wet = 0;
        int columns = 0;

        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                level.getChunk(x >> 4, z >> 4);
                int feetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int floorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                columns++;
                minY = Math.min(minY, feetY);
                maxY = Math.max(maxY, feetY);
                heightHistogram.merge(feetY, 1, Integer::sum);
                if (floorY != feetY) wet++;
                topBlocks.merge(id(level.getBlockState(new BlockPos(x, feetY - 1, z))),
                        1, Integer::sum);
            }
        }

        int modeY = heightHistogram.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(minY);
        int within1 = 0;
        for (Map.Entry<Integer, Integer> entry : heightHistogram.entrySet()) {
            if (Math.abs(entry.getKey() - modeY) <= 1) within1 += entry.getValue();
        }

        String tops = topBlocks.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(4)
                .map(e -> e.getKey() + ":" + e.getValue())
                .reduce((a, b) -> a + "," + b).orElse("none");

        return ok(ctx, "site x=" + cx + " z=" + cz + " r=" + radius
                + " columns=" + columns
                + " modeY=" + modeY + " minY=" + minY + " maxY=" + maxY
                + " spread=" + (maxY - minY)
                + " flat=" + pct(within1, columns)
                + " wet=" + pct(wet, columns)
                + " tops=" + tops);
    }

    // =========================================================================
    // "A player built here"
    // =========================================================================

    /**
     * Tells the village that every column in this box changed, exactly as
     * {@code BlockEvent.EntityPlaceEvent} would have.
     *
     * <p>Without it a {@code /fill}-built house is invisible: the nav graph is
     * only rebuilt from break/place events, both of which require an entity to
     * have done the placing, and neither of which a command block or an RCON
     * console counts as. The building auto-scan runs off the back of that
     * rebuild, so no rebuild means no registration and no navigation into the
     * room you just built.
     *
     * @param immediate rebuild in this tick instead of after the 2-second
     *                  debounce — worth it when the next command needs the
     *                  village to already know
     */
    private static int touch(CommandContext<CommandSourceStack> ctx, boolean immediate) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos a = new BlockPos(
                IntegerArgumentType.getInteger(ctx, "x1"),
                IntegerArgumentType.getInteger(ctx, "y1"),
                IntegerArgumentType.getInteger(ctx, "z1"));
        BlockPos b = new BlockPos(
                IntegerArgumentType.getInteger(ctx, "x2"),
                IntegerArgumentType.getInteger(ctx, "y2"),
                IntegerArgumentType.getInteger(ctx, "z2"));

        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());

        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_TOUCH_VOLUME) {
            return err(ctx, "box of " + volume + " blocks exceeds the "
                    + MAX_TOUCH_VOLUME + " limit; split it");
        }

        // Every village in this dimension hears about every block, exactly as
        // it would from the real event bus; each one filters to its own claim.
        // Levelling ground before the town hall exists is normal, so a box that
        // reaches nobody is a success with zero villages, not an error.
        List<VillageManager> local = new ArrayList<>();
        for (VillageManager mgr : VillageManagerRegistry.get(ctx.getSource().getServer()).all()) {
            if (mgr.getLevel() != null && level.dimension().equals(mgr.getLevel().dimension())) {
                local.add(mgr);
            }
        }

        int columns = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                columns++;
                for (VillageManager mgr : local) {
                    for (int y = minY; y <= maxY; y++) {
                        mgr.routeBlockPlace(new BlockPos(x, y, z));
                    }
                }
            }
        }

        if (immediate) {
            for (VillageManager mgr : local) mgr.rebuildNow(level);
        }
        return ok(ctx, "touched columns=" + columns
                + " villages=" + local.size()
                + " rebuilt=" + immediate);
    }

    /** Forces the nav rebuild and building auto-scan for the village at {@code pos}. */
    private static int rebuild(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null) return err(ctx, "no village contains " + pos.toShortString());
        mgr.rebuildNow(level);
        int valid = 0;
        for (Building building : mgr.getBuildings()) if (building.isValid()) valid++;
        return ok(ctx, "rebuilt village=" + shortId(mgr)
                + " buildings=" + valid + "/" + mgr.getBuildings().size());
    }

    // =========================================================================
    // Hanging a token
    // =========================================================================

    /**
     * Hangs an item frame holding a building token, the way a player would.
     *
     * <p>{@code /summon} can technically do this, but the item-component syntax
     * for a modded item inside a frame is fiddly enough that a typo produces an
     * empty frame and a scenario that fails for the wrong reason. It also has
     * to route the town-hall creation explicitly: the join event fires before
     * the entity is queryable, so the auto-scan that normally finds frames
     * cannot see the one that just triggered it.
     *
     * @param facing the direction the frame looks; its support block is the
     *               neighbour behind it, which is where the wall must be
     */
    private static int frame(CommandContext<CommandSourceStack> ctx, BlockPos pos,
                             String facingName, ResourceLocation tokenId) {
        ServerLevel level = ctx.getSource().getLevel();

        Direction facing = Direction.byName(facingName.toLowerCase(java.util.Locale.ROOT));
        if (facing == null) return err(ctx, "unknown facing '" + facingName + "'");

        Item item = BuiltInRegistries.ITEM.getOptional(tokenId).orElse(null);
        if (item == null) return err(ctx, "unknown item '" + tokenId + "'");
        ItemStack token = new ItemStack(item);
        if (!ItemFrameTokenRegistry.isToken(token)) {
            return err(ctx, tokenId + " is not a building token");
        }

        BlockPos support = pos.relative(facing.getOpposite());
        if (level.getBlockState(support).isAir()) {
            return err(ctx, "nothing to hang on at " + support.toShortString()
                    + " (frame at " + pos.toShortString() + " facing " + facing + ")");
        }
        if (!level.getBlockState(pos).isAir()) {
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }

        // Clear anything already hanging there, or the second frame silently
        // wins and the scenario registers a building it did not mean to.
        for (ItemFrame existing : level.getEntitiesOfClass(ItemFrame.class,
                new AABB(pos).inflate(0.4))) {
            existing.discard();
        }

        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, support);
        // Test-built room tokens must have the same Town Hall identity as tokens
        // issued to a player. Otherwise the ownership guard rejects the frame
        // before a headless room-registration test can exercise the scanner.
        if (mgr != null && ItemFrameTokenRegistry.tokenType(token)
                != ItemFrameTokenRegistry.TokenType.TOWN_HALL) {
            com.alex.test.village.tokens.VillageTokenBinding.bind(token, mgr);
        }

        ItemFrame frame = new ItemFrame(level, pos, facing);
        frame.setInvulnerable(true);
        level.addFreshEntity(frame);
        frame.setItem(token, false);

        boolean createdVillage = false;
        if (ItemFrameTokenRegistry.tokenType(token)
                == ItemFrameTokenRegistry.TokenType.TOWN_HALL) {
            VillageManager created = VillageManagerRegistry.get(ctx.getSource().getServer())
                    .createFromTownHall(level, frame);
            if (created != null) {
                created.rebuildNow(level);
                mgr = created;
                createdVillage = true;
            }
        } else if (mgr != null) {
            mgr.rebuildNow(level);
            // A player interaction queues the frame independently of the door
            // scan. Reproduce that path when the door itself is off the graph.
            mgr.routeItemFrameInteract(pos);
        }

        return ok(ctx, "frame pos=" + pos.toShortString()
                + " facing=" + facing.getName()
                + " token=" + tokenId
                + " uuid=" + frame.getUUID().toString().substring(0, 8)
                + " village=" + (mgr == null ? "none" : shortId(mgr))
                + " created=" + createdVillage
                + " buildings=" + (mgr == null ? 0 : validCount(mgr))
                + "/" + (mgr == null ? 0 : mgr.getBuildings().size()));
    }

    // =========================================================================
    // Spending what the village earned
    // =========================================================================

    /**
     * Takes items back out of village storage — the inverse of {@code stock}.
     *
     * <p>A playthrough that expands "on village resources only" needs to
     * actually pay: build the next house out of the logs the lumberjack banked,
     * and let the shortfall stop construction the way it would for a player.
     */
    private static int withdraw(CommandContext<CommandSourceStack> ctx, BlockPos pos,
                                ResourceLocation itemId, int count) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null) return err(ctx, "no village contains " + pos.toShortString());

        Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        if (item == null) return err(ctx, "unknown item '" + itemId + "'");
        ItemStack wanted = new ItemStack(item);

        // Count first, take second. Taking as we go and only noticing the
        // shortfall at the end leaves the village lighter by whatever was
        // already removed, with the caller told the withdrawal failed -- so a
        // playthrough that cannot afford a building silently pays part of the
        // price for it anyway.
        List<StorageBuilding> stores = new ArrayList<>();
        int available = 0;
        for (Building building : mgr.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            stores.add(storage);
            for (BlockPos chest : storage.findChestsWithItem(level, wanted)) {
                available += countIn(level, chest, wanted);
            }
        }
        if (available < count) {
            return err(ctx, "short: only " + available + "/" + count + " " + itemId
                    + " in storage");
        }

        int taken = 0;
        for (StorageBuilding storage : stores) {
            for (BlockPos chest : new ArrayList<>(storage.findChestsWithItem(level, wanted))) {
                while (taken < count) {
                    ItemStack got = storage.withdrawItemFromChest(
                            level, chest, wanted, count - taken);
                    if (got.isEmpty()) break;
                    taken += got.getCount();
                }
                if (taken >= count) break;
            }
            storage.invalidateContentIndex();
            if (taken >= count) break;
        }

        if (taken < count) {
            return err(ctx, "storage moved underneath the withdrawal: took "
                    + taken + " of " + count + " " + itemId);
        }
        return ok(ctx, "withdrew " + taken + " " + itemId);
    }

    // =========================================================================
    // Reading the graph back
    // =========================================================================

    /**
     * Summarises the nav nodes in a box, by height and by kind.
     *
     * <p>The question this exists to answer is "what could a villager standing
     * here pick as a wander target". On a superflat world the answer is always
     * "the one floor it is standing on". On real terrain a box six blocks deep
     * also contains cave roofs, lake beds and overhangs, and every one of them
     * is a node -- which is only interesting if something is choosing targets
     * out of that set without checking it can walk there.
     */
    private static int nodes(CommandContext<CommandSourceStack> ctx, BlockPos pos,
                             int radius, int dy) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null || mgr.getGraph() == null) return err(ctx, "no village/graph here");

        Map<Integer, Integer> byY = new java.util.TreeMap<>();
        int total = 0, avoided = 0, waterDrop = 0, below = 0, portal = 0, climb = 0;
        for (com.alex.test.village.NavNode node : mgr.getGraph().allNodes()) {
            BlockPos at = node.feetPos();
            if (Math.abs(at.getX() - pos.getX()) > radius) continue;
            if (Math.abs(at.getZ() - pos.getZ()) > radius) continue;
            if (Math.abs(at.getY() - pos.getY()) > dy) continue;
            total++;
            byY.merge(at.getY(), 1, Integer::sum);
            if (node.isAvoidedSurface()) avoided++;
            if (node.waterDrop) waterDrop++;
            if (node.portal) portal++;
            if (node.climb) climb++;
            if (at.getY() < pos.getY()) below++;
        }

        String histogram = byY.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .reduce((a, b) -> a + "," + b).orElse("none");
        return ok(ctx, "nodes at=" + pos.toShortString() + " r=" + radius + " dy=" + dy
                + " total=" + total
                + " below=" + below
                + " avoided=" + avoided
                + " waterDrop=" + waterDrop
                + " portal=" + portal
                + " climb=" + climb
                + " byY=" + histogram);
    }

    /** How many of {@code wanted} one chest holds right now. */
    private static int countIn(ServerLevel level, BlockPos chest, ItemStack wanted) {
        if (!(level.getBlockEntity(chest)
                instanceof net.minecraft.world.level.block.entity.ChestBlockEntity be)) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < be.getContainerSize(); slot++) {
            ItemStack stack = be.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, wanted)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // =========================================================================
    // Hiring
    // =========================================================================

    /**
     * Gives an existing villager a profession, running the same first-time
     * assignment (starter tool, settings defaults) a token right-click runs.
     */
    private static int job(CommandContext<CommandSourceStack> ctx) {
        String target = StringArgumentType.getString(ctx, "target");
        String name = StringArgumentType.getString(ctx, "profession")
                .toUpperCase(java.util.Locale.ROOT);

        Profession profession = null;
        for (Profession candidate : Profession.values()) {
            if (candidate.name().equals(name)) profession = candidate;
        }
        if (profession == null) return err(ctx, "unknown profession '" + name + "'");

        List<NpcEntity> matched = new ArrayList<>();
        for (VillageManager mgr : VillageManagerRegistry.get(ctx.getSource().getServer()).all()) {
            for (VillagerModel model : mgr.getVillagers()) {
                NpcEntity npc = model.puppet();
                if (npc == null) continue;
                if (target.equals("all")
                        || StateDump.shortId(model.npcId).equals(target)
                        || model.npcId.toString().equals(target)) {
                    matched.add(npc);
                }
            }
        }
        if (matched.isEmpty()) return err(ctx, "no villager matched '" + target + "'");

        List<String> ids = new ArrayList<>();
        for (NpcEntity npc : matched) {
            ScenarioBuilder.applyProfession(npc, profession);
            ids.add(StateDump.shortId(npc.getNpcId()));
        }
        return ok(ctx, "job " + profession.name() + " -> " + String.join(",", ids));
    }

    // =========================================================================
    // Plumbing
    // =========================================================================

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer>
            xyz(java.util.function.UnaryOperator<
                    com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer>> configureZ) {
        return Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(configureZ.apply(
                                Commands.argument("z", IntegerArgumentType.integer()))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer>
            box(java.util.function.UnaryOperator<
                    com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer>> configureLast) {
        return Commands.argument("x1", IntegerArgumentType.integer())
                .then(Commands.argument("y1", IntegerArgumentType.integer())
                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                        .then(Commands.argument("y2", IntegerArgumentType.integer())
                                                .then(configureLast.apply(
                                                        Commands.argument("z2",
                                                                IntegerArgumentType.integer())))))));
    }

    private static BlockPos readXyz(CommandContext<CommandSourceStack> ctx) {
        return new BlockPos(
                IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z"));
    }

    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String pct(int part, int whole) {
        return whole == 0 ? "0.00" : String.format(java.util.Locale.ROOT, "%.2f",
                (double) part / whole);
    }

    private static String shortId(VillageManager mgr) {
        return mgr.getVillageId().toString().substring(0, 8);
    }

    private static int validCount(VillageManager mgr) {
        int valid = 0;
        for (Building building : mgr.getBuildings()) if (building.isValid()) valid++;
        return valid;
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal("OK " + message), false);
        return 1;
    }

    private static int err(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal("ERR " + message));
        return 0;
    }
}
