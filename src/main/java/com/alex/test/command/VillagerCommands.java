package com.alex.test.command;

import com.alex.test.DynamicVillages;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.block.WoodenChairBlock;
import com.alex.test.entity.ChairSeatEntity;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.visitor.VisitorEntity;
import com.alex.test.village.AStarPathfinder;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavReachability;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.HouseBuilding;
import com.alex.test.village.building.MerchantStallBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.brain.WallSleepSpots;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.visitors.MerchantLogic;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.*;

/**
 * The {@code /villager} debug command tree, built in {@link #register}.
 *
 * <p>This class handles the village, nav-graph and building debugging
 * subcommands. The villager-appearance previews ({@code npc_anim},
 * {@code mocap}, {@code animdebug}, {@code thoughts}) are handled by
 * {@link NpcPreviewCommands}.
 */
public class VillagerCommands {

    private static final DustParticleOptions RED_DUST =
            new DustParticleOptions(new Vector3f(1f, 0f, 0f), 1.5f);
    private static final DustParticleOptions YELLOW_DUST =
            new DustParticleOptions(new Vector3f(1f, 0.85f, 0f), 1.5f);
    private static final DustParticleOptions GREEN_DUST =
            new DustParticleOptions(new Vector3f(0.2f, 1f, 0.2f), 1.5f);


    /**
     * Wool palette used to colour each building's floor tiles distinctly.
     * Cycles if there are more buildings than palette entries.
     */
    private static final Block[] BUILDING_WOOL_PALETTE = {
        Blocks.LIME_WOOL,       // 0
        Blocks.CYAN_WOOL,       // 1
        Blocks.LIGHT_BLUE_WOOL, // 2
        Blocks.BLUE_WOOL,       // 3
        Blocks.PURPLE_WOOL,     // 4
        Blocks.MAGENTA_WOOL,    // 5
        Blocks.PINK_WOOL,       // 6
        Blocks.ORANGE_WOOL,     // 7
        Blocks.GREEN_WOOL,      // 8
        Blocks.BROWN_WOOL       // 9
    };


    // =========================================================================

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Every subcommand is a debug or spawning tool (stress, maze, graph scan can
        // stall the server), so the whole tree is op-only on a multiplayer server.
        dispatcher.register(Commands.literal("villager")
                .requires(src -> src.hasPermission(2))
                .then(VillageOwnershipRecoveryCommands.commandTree())
                .then(ClaimCommands.commandTree())
                .then(WalkFeelCommands.commandTree())
                .then(com.alex.test.village.economy.away.AwayPreview.switchNode())
                .then(FarAwakeCommands.commandTree())
                .then(StuckWatchCommands.commandTree())
                .then(GuideBookCommand.commandTree())
                .then(OreHeightCommand.commandTree())
                .then(TreeCheckCommands.commandTree())
                .then(TreeCheckCommands.scanTree())
                .then(CopyCheckCommands.commandTree())
                .then(CraftCheckCommands.commandTree())
                .then(TillShareCommands.commandTree())
                .then(DruidSoundCommands.commandTree())
                .then(StressTestCommands.commandTree())
                .then(com.alex.test.performance.ServerCostLog.commandTree())
                .then(com.alex.test.village.brain.VillagerDiary.commandTree())
                .then(Commands.literal("spawn")
                        .executes(ctx -> spawnNpcs(ctx, 1, false))
                        .then(Commands.literal("clear")
                                .executes(VillagerCommands::clearSpawnedNpcs))
                        .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                .executes(ctx -> spawnNpcs(ctx,
                                        IntegerArgumentType.getInteger(ctx, "number"), false))
                                .then(Commands.literal("random")
                                        .executes(ctx -> spawnNpcs(ctx,
                                                IntegerArgumentType.getInteger(ctx, "number"), true)))))
                .then(Commands.literal("sit")
                        .executes(VillagerCommands::spawnSeatedNpc))
                .then(Commands.literal("preview")
                        .executes(NpcPreviewCommands::professionPreviewUsage)
                        .then(Commands.literal("clear")
                                .executes(NpcPreviewCommands::clearProfessionPreviews))
                        .then(Commands.literal("minimerchant")
                                .executes(NpcPreviewCommands::spawnMiniMerchant))
                        .then(Commands.literal("midmerchant")
                                .executes(NpcPreviewCommands::spawnMidMerchant))
                        .then(Commands.literal("bass")
                                .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                        com.alex.test.animation.VillagerAnimationState.BASS_PLAY))
                                .then(Commands.literal("play")
                                        .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                                com.alex.test.animation.VillagerAnimationState.BASS_PLAY)))
                                .then(Commands.literal("jam")
                                        .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                                com.alex.test.animation.VillagerAnimationState.BASS_JAM))))
                        .then(Commands.literal("bongos")
                                .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                        com.alex.test.animation.VillagerAnimationState.BONGOS_PLAY))
                                .then(Commands.literal("play")
                                        .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                                com.alex.test.animation.VillagerAnimationState.BONGOS_PLAY)))
                                .then(Commands.literal("jam")
                                        .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                                com.alex.test.animation.VillagerAnimationState.BONGOS_JAM))))
                        .then(Commands.literal("violin")
                                .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                        com.alex.test.animation.VillagerAnimationState.VIOLIN_PLAY))
                                .then(Commands.literal("play")
                                        .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                                com.alex.test.animation.VillagerAnimationState.VIOLIN_PLAY)))
                                .then(Commands.literal("walk")
                                        .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                                com.alex.test.animation.VillagerAnimationState.PLAY_VIOLIN)))
                                .then(Commands.literal("dance")
                                        .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                                com.alex.test.animation.VillagerAnimationState.VIOLIN_DANCE))))
                        .then(Commands.literal("flute")
                                .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                        com.alex.test.animation.VillagerAnimationState.FLUTE_PLAY))
                                .then(Commands.literal("play")
                                        .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                                com.alex.test.animation.VillagerAnimationState.FLUTE_PLAY)))
                                .then(Commands.literal("walk")
                                        .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                                com.alex.test.animation.VillagerAnimationState.PLAY_FLUTE)))
                                .then(Commands.literal("dance")
                                        .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                                com.alex.test.animation.VillagerAnimationState.FLUTE_DANCE))))
                        .then(Commands.literal("guitar")
                                .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                        com.alex.test.animation.VillagerAnimationState.GUITAR_PLAY))
                                .then(Commands.literal("play")
                                        .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                                com.alex.test.animation.VillagerAnimationState.GUITAR_PLAY)))
                                .then(Commands.literal("walk")
                                        .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                                com.alex.test.animation.VillagerAnimationState.PLAY_GUITAR)))
                                .then(Commands.literal("jam")
                                        .executes(c -> NpcPreviewCommands.spawnInstrumentPreview(c,
                                                com.alex.test.animation.VillagerAnimationState.GUITAR_JAM))))
                        .then(Commands.argument("profession", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        NpcPreviewCommands.professionPreviewNames(), b))
                                .then(Commands.argument("item", ResourceLocationArgument.id())
                                        .suggests(NpcPreviewCommands::suggestPreviewItems)
                                        .then(Commands.argument("speed", FloatArgumentType.floatArg(
                                                com.alex.test.animation.ProfessionAnimationPreview.MIN_SPEED,
                                                com.alex.test.animation.ProfessionAnimationPreview.MAX_SPEED))
                                                .executes(NpcPreviewCommands::spawnProfessionPreview)))))
                .then(Commands.literal("merchant")
                        .then(Commands.literal("spawn")
                                .executes(VillagerCommands::spawnMerchant)
                                .then(Commands.argument("stay", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                java.util.List.of("night", "evening", "dusk", "random"), b))
                                        .executes(VillagerCommands::spawnMerchant)))
                        .then(Commands.literal("stall")
                                .executes(VillagerCommands::debugMerchantStall)))
                .then(Commands.literal("wallspots")
                        .executes(VillagerCommands::debugWallSpots))
                .then(Commands.literal("npc_anim")
                        .executes(NpcPreviewCommands::npcAnimationUsage)
                        .then(Commands.literal("stop")
                                .executes(NpcPreviewCommands::stopNpcAnimation))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        NpcPreviewCommands.npcAnimationNames(), b))
                                .executes(ctx -> NpcPreviewCommands.triggerNpcAnimation(ctx, -1))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
                                        .executes(ctx -> NpcPreviewCommands.triggerNpcAnimation(ctx,
                                                IntegerArgumentType.getInteger(ctx, "seconds"))))))
                .then(Commands.literal("stress")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                                .executes(VillagerCommands::stressTest)))
                .then(Commands.literal("mocap")
                        .executes(NpcPreviewCommands::mocapUsage)
                        .then(Commands.literal("stop").executes(NpcPreviewCommands::mocapStop))
                        .then(Commands.literal("lineup")
                                .executes(ctx -> NpcPreviewCommands.mocapLineup(ctx, 3))
                                .then(Commands.argument("spacing", IntegerArgumentType.integer(2, 10))
                                        .executes(ctx -> NpcPreviewCommands.mocapLineup(ctx,
                                                IntegerArgumentType.getInteger(ctx, "spacing")))))
                        .then(Commands.argument("clip", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(NpcEntity.MOCAP_CLIPS).skip(1), b))
                                .executes(ctx -> NpcPreviewCommands.mocapSpawn(ctx,
                                        StringArgumentType.getString(ctx, "clip")))))
                .then(Commands.literal("clericcast")
                        .executes(ctx -> NpcPreviewCommands.clericCast(ctx, 1, false))
                        .then(Commands.literal("stop").executes(NpcPreviewCommands::clericCastStop))
                        .then(Commands.literal("dummy")
                                .executes(ctx -> NpcPreviewCommands.clericCast(ctx, 1, true))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> NpcPreviewCommands.clericCast(ctx,
                                                IntegerArgumentType.getInteger(ctx, "level"), true))))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> NpcPreviewCommands.clericCast(ctx,
                                        IntegerArgumentType.getInteger(ctx, "level"), false))))
                .then(Commands.literal("clericcast_raw")
                        .executes(ctx -> NpcPreviewCommands.clericCastRaw(ctx, 1, false))
                        .then(Commands.literal("stop").executes(NpcPreviewCommands::clericCastStop))
                        .then(Commands.literal("dummy")
                                .executes(ctx -> NpcPreviewCommands.clericCastRaw(ctx, 1, true))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> NpcPreviewCommands.clericCastRaw(ctx,
                                                IntegerArgumentType.getInteger(ctx, "level"), true))))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> NpcPreviewCommands.clericCastRaw(ctx,
                                        IntegerArgumentType.getInteger(ctx, "level"), false))))
                .then(Commands.literal("magecast")
                        .executes(ctx -> NpcPreviewCommands.mageCast(ctx, "all", 100))
                        .then(Commands.literal("stop").executes(NpcPreviewCommands::mageCastStop))
                        .then(Commands.argument("spell", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        com.alex.test.village.villagers.mage.behavior.MageCastDemo.SPELLS, b))
                                .executes(ctx -> NpcPreviewCommands.mageCast(ctx,
                                        StringArgumentType.getString(ctx, "spell"), 100))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> NpcPreviewCommands.mageCast(ctx,
                                                StringArgumentType.getString(ctx, "spell"),
                                                IntegerArgumentType.getInteger(ctx, "level"))))))
                .then(Commands.literal("sharpen")
                        .executes(NpcPreviewCommands::butcherSharpen)
                        .then(Commands.literal("stop").executes(NpcPreviewCommands::butcherSharpenStop)))
                .then(Commands.literal("flyguys")
                        .executes(ctx -> NpcPreviewCommands.flyguys(ctx, "all"))
                        .then(Commands.literal("stop").executes(NpcPreviewCommands::flyguysStop))
                        .then(Commands.literal("all").executes(ctx -> NpcPreviewCommands.flyguys(ctx, "all")))
                        .then(Commands.literal("walk").executes(ctx -> NpcPreviewCommands.flyguys(ctx, "walk")))
                        .then(Commands.literal("stand").executes(ctx -> NpcPreviewCommands.flyguys(ctx, "stand")))
                        .then(Commands.literal("seed").executes(ctx -> NpcPreviewCommands.flyguys(ctx, "seed"))))
                .then(Commands.literal("animdebug").executes(NpcPreviewCommands::animDebug))
                .then(Commands.literal("dirt")
                        .executes(NpcPreviewCommands::dirtShow)
                        .then(Commands.argument("amount", FloatArgumentType.floatArg(0f, 100f))
                                .executes(ctx -> NpcPreviewCommands.dirtSet(ctx,
                                        FloatArgumentType.getFloat(ctx, "amount")))))
                .then(Commands.literal("cauldrons").executes(VillagerCommands::listCauldrons))
                .then(Commands.literal("ranch").executes(RanchReport::run))
                .then(Commands.literal("day").executes(VillagerCommands::showDay))
                .then(Commands.literal("thoughts")
                        .then(Commands.literal("memory").executes(NpcPreviewCommands::thoughtsMemory))
                        .then(Commands.literal("talk").executes(NpcPreviewCommands::thoughtsTalk))
                        .then(Commands.literal("lineup")
                                .executes(ctx -> NpcPreviewCommands.thoughtsLineup(ctx, 2))
                                .then(Commands.argument("spacing", IntegerArgumentType.integer(1, 10))
                                        .executes(ctx -> NpcPreviewCommands.thoughtsLineup(ctx,
                                                IntegerArgumentType.getInteger(ctx, "spacing")))))
                        .then(Commands.literal("clear").executes(NpcPreviewCommands::thoughtsClear))
                        .then(Commands.argument("icon", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        ThoughtIcon.ids(), b))
                                .executes(ctx -> NpcPreviewCommands.thoughtsShow(ctx,
                                        StringArgumentType.getString(ctx, "icon")))))
                .then(Commands.literal("maze")
                        .then(Commands.argument("size", IntegerArgumentType.integer(2, 40))
                                .executes(VillagerCommands::generateMaze)))
                .then(Commands.literal("goto")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(VillagerCommands::npcGoto))))
                .then(Commands.literal("debug")
                        .then(Commands.literal("mark").executes(VillagerCommands::debugMark))
                        .then(Commands.literal("wool").executes(VillagerCommands::debugWool))
                        .then(Commands.literal("clear").executes(VillagerCommands::debugClear))
                        .then(Commands.literal("console")
                                .requires(src -> src.hasPermission(2))
                                .executes(VillagerCommands::consoleStatus)
                                .then(Commands.literal("off").executes(VillagerCommands::consoleOff))
                                .then(Commands.literal("on")
                                        .executes(ctx -> consoleOn(ctx, "debug", null))
                                        .then(Commands.argument("level", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                        CONSOLE_LEVELS, b))
                                                .executes(ctx -> consoleOn(ctx,
                                                        StringArgumentType.getString(ctx, "level"), null))
                                                .then(Commands.argument("filter", StringArgumentType.greedyString())
                                                        .executes(ctx -> consoleOn(ctx,
                                                                StringArgumentType.getString(ctx, "level"),
                                                                StringArgumentType.getString(ctx, "filter"))))))))
                .then(Commands.literal("beds")
                        .then(Commands.literal("why").executes(VillagerCommands::bedsWhy)))
                .then(Commands.literal("building")
                        .then(Commands.literal("list").executes(VillagerCommands::buildingList))
                        .then(Commands.literal("wool").executes(VillagerCommands::buildingWool))
                        .then(Commands.literal("clear").executes(VillagerCommands::buildingClear)))
                .then(Commands.literal("graph")
                        .then(Commands.literal("scan")
                                .executes(ctx -> graphScan(ctx, NavGraph.DEFAULT_CHUNK_RADIUS))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 9))
                                        .executes(ctx -> graphScan(ctx,
                                                IntegerArgumentType.getInteger(ctx, "radius")))))
                        .then(Commands.literal("dump").executes(VillagerCommands::graphDump))
                        .then(Commands.literal("check").executes(VillagerCommands::graphCheck))
                        .then(Commands.literal("nav")
                                .then(Commands.argument("halfsize", IntegerArgumentType.integer(1, 64))
                                        .executes(VillagerCommands::graphNav))))
        );
    }

    // =========================================================================
    // NPC spawn
    // =========================================================================

    private static final List<String> CONSOLE_LEVELS = List.of("debug", "noinfo", "info", "warn", "error");

    private static int consoleOn(CommandContext<CommandSourceStack> ctx, String levelName, String filter) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Must be run by a player; the server console already shows its own log."));
            return 0;
        }
        if (!CONSOLE_LEVELS.contains(levelName.toLowerCase(Locale.ROOT))) {
            source.sendFailure(Component.literal("Unknown level '" + levelName + "'. Use one of " + CONSOLE_LEVELS + "."));
            return 0;
        }
        // "noinfo" = DEBUG and up minus INFO: debug detail plus warnings/errors.
        boolean hideInfo = levelName.equalsIgnoreCase("noinfo");
        org.apache.logging.log4j.Level level = hideInfo
                ? org.apache.logging.log4j.Level.DEBUG
                : org.apache.logging.log4j.Level.toLevel(levelName);
        ConsoleChatRelay.subscribe(player, level, filter, hideInfo);
        source.sendSuccess(() -> Component.literal("Mirroring dynamicvillages log (" + level.name() + " and up"
                + (hideInfo ? " except INFO" : "")
                + (filter == null ? "" : ", containing \"" + filter + "\"")
                + ") to your chat. /villager debug console off to stop.").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int consoleOff(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        boolean was = ConsoleChatRelay.unsubscribe(player);
        source.sendSuccess(() -> Component.literal(was ? "Stopped mirroring the log to your chat."
                : "You weren't receiving the log."), false);
        return 1;
    }

    private static int consoleStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        ConsoleChatRelay.Subscription sub = ConsoleChatRelay.subscription(player);
        source.sendSuccess(() -> Component.literal(sub == null
                ? "Log mirroring is off. /villager debug console on [debug|noinfo|info|warn|error] [filter]"
                : "Log mirroring is on: " + sub.level().name() + " and up"
                        + (sub.hideInfo() ? " except INFO" : "")
                        + (sub.filter() == null ? "" : ", containing \"" + sub.filter() + "\"")), false);
        return 1;
    }

    // =========================================================================
    // /villager spawn [number] [random] | clear
    // =========================================================================

    /** Jobs {@code random} picks from: every adult job but Captain (one per Barracks). */
    private static final Profession[] RANDOM_JOBS = Arrays.stream(Profession.values())
            .filter(p -> p != Profession.NONE && p != Profession.CHILD && p != Profession.CAPTAIN)
            .toArray(Profession[]::new);

    /**
     * Spawns {@code count} members of the village you stand in, or else of the nearest
     * village in this dimension (they then appear at its Town Hall). {@code randomJobs}:
     * each gets a random job, set up as a job token would (starter tool and all).
     */
    private static int spawnNpcs(CommandContext<CommandSourceStack> ctx, int count, boolean randomJobs) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null) { source.sendFailure(Component.literal("Must be run by a player.")); return 0; }
        if (!(source.getLevel() instanceof ServerLevel sl)) { source.sendFailure(Component.literal("Server-side only.")); return 0; }

        VillageManagerRegistry registry = VillageManagerRegistry.get(sl.getServer());
        BlockPos feet = caller.blockPosition();
        VillageManager mgr = registry.findNearestVillage(sl, feet, Integer.MAX_VALUE);
        if (mgr == null) {
            source.sendFailure(Component.literal(
                    "There is no village in this dimension yet. Found one with a Town Hall first."));
            return 0;
        }
        boolean inside = mgr.contains(feet);
        BlockPos centre = inside ? feet : townHallSpot(mgr);

        Random rand = new Random();
        int radius = Math.max(2, (int) Math.ceil(Math.sqrt(count)));
        int made = 0;
        Map<Profession, Integer> jobs = new EnumMap<>(Profession.class);
        for (int i = 0; i < count; i++) {
            NpcEntity npc = mgr.spawnVillager(freeSpotNear(sl, mgr, centre, radius, rand));
            if (npc == null) continue;
            SpawnedVillagers.remember(sl.getServer(), npc);
            if (randomJobs) {
                Profession job = RANDOM_JOBS[rand.nextInt(RANDOM_JOBS.length)];
                com.alex.test.testkit.ScenarioBuilder.applyProfession(npc, job);
                jobs.merge(npc.getProfession(), 1, Integer::sum);
            }
            made++;
        }
        registry.setDirty();

        int total = made;
        String where = inside ? "around you"
                : "at its Town Hall, " + (int) Math.sqrt(mgr.getBellPos().distSqr(feet)) + " blocks away";
        StringBuilder jobLine = new StringBuilder();
        jobs.forEach((job, n) -> jobLine.append(jobLine.length() == 0 ? "" : ", ").append(n).append(' ').append(job.shownName()));
        source.sendSuccess(() -> Component.literal("Spawned " + total + (total == 1 ? " villager" : " villagers")
                + " into village " + mgr.getVillageId().toString().substring(0, 8) + " " + where + "."
                + (jobLine.length() > 0 ? " Jobs: " + jobLine + "." : "")
                + " Remove them with /villager spawn clear"), false);
        return total;
    }

    /** Just inside the Town Hall door (as the frame's spawn), else the bell. */
    private static BlockPos townHallSpot(VillageManager mgr) {
        for (Building building : mgr.getBuildings()) {
            if (building instanceof com.alex.test.village.building.TownHallBuilding && building.isValid()) {
                return building.getDoorPos().relative(building.getInteriorDirection());
            }
        }
        return mgr.getBellPos();
    }

    /**
     * A random free standing spot inside the village within {@code radius} of {@code centre}
     * (two clear blocks over a solid floor, up to 3 up or down); {@code centre} if none of
     * 12 tries finds one.
     */
    private static BlockPos freeSpotNear(ServerLevel level, VillageManager mgr, BlockPos centre, int radius, Random rand) {
        for (int attempt = 0; attempt < 12; attempt++) {
            int dx = rand.nextInt(radius * 2 + 1) - radius;
            int dz = rand.nextInt(radius * 2 + 1) - radius;
            for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3}) {
                BlockPos spot = centre.offset(dx, dy, dz);
                if (!mgr.contains(spot)) continue;
                if (!level.getBlockState(spot).getCollisionShape(level, spot).isEmpty()) continue;
                if (!level.getBlockState(spot.above()).getCollisionShape(level, spot.above()).isEmpty()) continue;
                if (!level.getFluidState(spot).isEmpty()) continue;
                BlockPos floor = spot.below();
                if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue;
                return spot;
            }
        }
        return centre;
    }

    /**
     * Removes every villager {@code /villager spawn} made, also across reloads: the ones
     * in unloaded chunks leave their village now and vanish when their chunk loads.
     */
    private static int clearSpawnedNpcs(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int[] counts = SpawnedVillagers.clear(source.getServer());
        int removed = counts[0], away = counts[1];
        source.sendSuccess(() -> Component.literal(removed + away == 0
                ? "No spawned villagers found."
                : "Removed " + removed + (removed == 1 ? " spawned villager" : " spawned villagers")
                        + (away == 0 ? "." : ", and " + away + " more in unloaded chunks left their village"
                                + " (they vanish when their chunk loads).")), true);
        return removed + away;
    }

    /** Spawn a stationary villager preview on the wooden chair in the player's crosshair. */
    private static int spawnSeatedNpc(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Must be run by a player looking at a wooden chair."));
            return 0;
        }

        HitResult target = player.pick(6.0D, 1.0F, false);
        if (!(target instanceof BlockHitResult hit) || target.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Look at a wooden chair within 6 blocks."));
            return 0;
        }

        ServerLevel level = player.serverLevel();
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof WoodenChairBlock chair)) {
            source.sendFailure(Component.literal("Look at a wooden chair within 6 blocks."));
            return 0;
        }

        for (ChairSeatEntity existing : level.getEntitiesOfClass(
                ChairSeatEntity.class, new AABB(pos).inflate(0.15))) {
            if (existing.isVehicle()) {
                source.sendFailure(Component.literal("That chair is already occupied."));
                return 0;
            }
            existing.discard();
        }

        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        ChairSeatEntity seat = DynamicVillages.CHAIR_SEAT.get().create(level);
        if (npc == null || seat == null) {
            source.sendFailure(Component.literal("Could not create the seated villager."));
            return 0;
        }

        Direction front = state.getValue(WoodenChairBlock.FACING);
        float yaw = front.toYRot();
        double x = pos.getX() + 0.5;
        double y = pos.getY() + chair.seatMountHeight();
        double z = pos.getZ() + 0.5;
        seat.moveTo(x, y, z, yaw, 0.0F);
        npc.moveTo(x, y, z, yaw, 0.0F);
        npc.setYBodyRot(yaw);
        npc.setYHeadRot(yaw);
        npc.addTag(NpcEntity.CHAIR_PREVIEW_TAG);
        npc.setNoAi(true);
        npc.setInvulnerable(true);
        npc.setPersistenceRequired();
        npc.setAnimation(VillagerAnimationState.CHAIR_SIT);

        if (!level.addFreshEntity(seat)) {
            source.sendFailure(Component.literal("Could not place a seat on that chair."));
            return 0;
        }
        if (!level.addFreshEntity(npc) || !npc.startRiding(seat)) {
            npc.discard();
            seat.discard();
            source.sendFailure(Component.literal("Could not seat the villager."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Spawned a villager sitting on the chair at " + pos.toShortString()), false);
        return 1;
    }

    private static int spawnMerchant(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        VillageManager village = VillageManagerRegistry.get(level.getServer()).findVillageAt(level, pos);
        if (village == null) {
            source.sendFailure(Component.literal("No registered village at this position."));
            return 0;
        }
        if (village.visitors().hasActiveMerchantRecord()) {
            source.sendFailure(Component.literal("A merchant visit is already active."));
            return 0;
        }
        // Optional: how long it stays. Left out (or "random"), it is rolled as
        // for a real arrival at this time of day.
        String stay;
        try {
            stay = StringArgumentType.getString(ctx, "stay").toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException noArgument) {
            stay = "random";
        }
        com.alex.test.village.visitors.TravelerLifecycleRules.StayPlan plan = switch (stay) {
            case "night" -> com.alex.test.village.visitors.TravelerLifecycleRules.StayPlan.NIGHT;
            case "evening" -> com.alex.test.village.visitors.TravelerLifecycleRules.StayPlan.EVENING;
            case "dusk" -> com.alex.test.village.visitors.TravelerLifecycleRules.StayPlan.DUSK;
            case "random" -> null;
            default -> com.alex.test.village.visitors.TravelerLifecycleRules.StayPlan.DUSK;
        };
        if (!List.of("night", "evening", "dusk", "random").contains(stay)) {
            source.sendFailure(Component.literal("Use night, evening, dusk or random."));
            return 0;
        }
        VisitorEntity merchant = village.visitors().spawnVisitor(level, pos, plan);
        if (merchant == null) {
            source.sendFailure(Component.literal("Merchant could not spawn here; try an open spot in the village."));
            return 0;
        }
        long now = level.getGameTime();
        long dayTime = level.getDayTime();
        long leaves = merchant.getDepartureAtGameTime();
        String stays = com.alex.test.village.visitors.TravelerLifecycleRules.staysPastSunrise(leaves, now, dayTime)
                ? "stays the night and leaves tomorrow morning"
                : String.format(Locale.ROOT, "leaves at %s", clockText(dayTime + (leaves - now)));
        source.sendSuccess(() -> Component.literal("Spawned merchant for village "
                + village.getVillageId().toString().substring(0, 8)
                + " with " + merchant.getOffers().size() + " offers. It " + stays + "."), false);
        return 1;
    }

    /** A day-clock value as a time on the Minecraft clock, like "6:30 PM". */
    private static String clockText(long dayTime) {
        long clock = Math.floorMod(dayTime, 24_000L);
        long minutes = (clock * 60L / 1_000L + 6L * 60L) % (24L * 60L);
        long hour = minutes / 60L;
        return String.format(Locale.ROOT, "%d:%02d %s", hour % 12L == 0L ? 12L : hour % 12L,
                minutes % 60L, hour < 12L ? "AM" : "PM");
    }

    /**
     * Which Merchant Stall the village has, and what shape it was detected as.
     *
     * <p>Lists every stall -- building (room) or open-air square, valid or
     * not -- marks the one the merchant uses, explains any Stall token that
     * did not register, and says where the merchant is. Flashes the tiles
     * once for the calling player: green where the merchant walks, yellow
     * over the counter, red for a square too small to be a stall. Run it again
     * to flash again.
     */
    /**
     * {@code /villager day}: the village day next to Minecraft's own, and, in a
     * village, the nomad and merchant mornings, to check skipped nights by hand.
     */
    private static int showDay(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        long dayTime = level.getDayTime();
        long now = level.getGameTime();
        long villageDay = com.alex.test.village.VillageDays.dayIndex(dayTime);
        long minecraftDay = Math.floorDiv(dayTime, 24_000L);
        long into = com.alex.test.village.VillageDays.ticksIntoDay(now);
        source.sendSuccess(() -> Component.literal("── Days ──").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Village day %d (began %d s ago) | Minecraft day %d | clock %d",
                villageDay, into / 20L, minecraftDay, Math.floorMod(dayTime, 24_000L))), false);

        VillageManager village = VillageManagerRegistry.get(level.getServer())
                .findVillageAt(level, BlockPos.containing(source.getPosition()));
        if (village == null) {
            source.sendSuccess(() -> Component.literal("Stand in a village for its nomad and merchant mornings.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        com.alex.test.village.visitors.NomadSpawnManager nomads = village.nomads();
        int mornings = nomads.morningsSinceFounding();
        // The next morning handled is day mornings + 2; day 3 brings one
        // nomad, day 4 a group of three.
        String scripted = mornings < 0 ? "no scripted days (old village)"
                : mornings == 0 ? "1 nomad in 2 mornings, 3 in 3"
                : mornings == 1 ? "1 nomad next morning, 3 the one after"
                : mornings == 2 ? "3 nomads next morning"
                : "scripted days done";
        // Nomads past their leave time are walking out; they no longer hold a morning back.
        int staying = (int) nomads.savedActiveNomads().values().stream().filter(at -> at > now).count();
        int leaving = nomads.savedActiveNomads().size() - staying;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Nomads: last morning day %d, %d morning(s) since founding, %s | next roll %.0f%%, "
                        + "%d failed, %d on the way, %d staying, %d walking out",
                nomads.lastMorningDay(), mornings, scripted, nomads.nextArrivalChance() * 100.0,
                nomads.failedMornings(), nomads.pendingWelcomeCount(), staying, leaving)), false);

        com.alex.test.village.visitors.VisitorSpawnManager merchants = village.visitors();
        com.alex.test.village.visitors.VisitorSpawnManager.SavedState state = merchants.savedState();
        double chance = com.alex.test.village.visitors.TravelerLifecycleRules.merchantChanceForMorning(
                state.morningsSinceVisit() + 1, village.getVillagerIds().size(), state.foundingVisitsLeft());
        boolean walkingOut = state.departureAt() >= 0L && state.departureAt() <= now;
        String status = merchants.hasActiveMerchantRecord()
                ? (walkingOut ? "walking out" : "visiting now")
                        + (state.pendingArrivalAt() >= 0L ? ", the next one on the way" : "")
                : state.pendingArrivalAt() >= 0L ? "on the way" : "none";
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Merchant: %s | last morning day %d, %d morning(s) since the last visit, "
                        + "%d first visit(s) left | next roll %.0f%%",
                status, state.lastMorningDay(), state.morningsSinceVisit(), state.foundingVisitsLeft(),
                chance * 100.0)), false);

        com.alex.test.village.visitors.TravelerCamp camp = village.travelerCamp();
        BlockPos fire = camp.fire();
        String campLine = fire != null
                ? String.format(Locale.ROOT, "camp at %d, %d, %d with %d camper(s)",
                        fire.getX(), fire.getY(), fire.getZ(), camp.camperCount())
                : camp.noSpotTonight() ? "no camp spot tonight" : "no camp";
        String snub = camp.snubDay() == Long.MIN_VALUE ? ""
                : String.format(Locale.ROOT, " | no visitors on day %d (fire broken)", camp.snubDay());
        String beds = camp.guestBedSummary(level);
        source.sendSuccess(() -> Component.literal("Night: " + campLine + " | guest beds " + beds + snub), false);
        return 1;
    }

    /**
     * {@code /villager cauldrons}: the cauldrons the village's farmers wash in,
     * with water level, muddy mark, who holds them, the last refill day and the
     * water source a refill would use.
     */
    private static int listCauldrons(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        VillageManager village = VillageManagerRegistry.get(level.getServer()).findVillageAt(level, pos);
        if (village == null) {
            source.sendFailure(Component.literal("No registered village at this position."));
            return 0;
        }
        com.alex.test.village.building.CauldronIndex index = village.cauldrons();
        List<com.alex.test.village.building.CauldronIndex.Spot> spots = index.spots(village, level);
        source.sendSuccess(() -> Component.literal("── Cauldrons (" + spots.size() + ") ──")
                .withStyle(ChatFormatting.GOLD), false);
        long today = com.alex.test.village.brain.EveningSchedule.dayIndex(level.getDayTime());
        for (com.alex.test.village.building.CauldronIndex.Spot spot : spots) {
            BlockPos at = spot.pos();
            int water = com.alex.test.village.building.CauldronIndex.waterLevel(level, at);
            boolean muddy = com.alex.test.village.building.MuddyCauldrons.isMarked(level, at);
            com.alex.test.village.building.CauldronClaims.Claim claim =
                    com.alex.test.village.building.CauldronClaims.holder(level, at);
            String holder = "free";
            if (claim != null) {
                com.alex.test.village.VillagerModel model = village.getModel(claim.owner());
                String name = model != null && model.puppet() != null
                        ? model.puppet().getName().getString() : claim.owner().toString().substring(0, 8);
                holder = claim.kind().name().toLowerCase(java.util.Locale.ROOT) + " by " + name;
            }
            Long refilled = index.refilledDay(at);
            String refill = refilled == null ? "never" : refilled == today ? "today" : "day " + refilled;
            com.alex.test.village.building.CauldronIndex.WaterSpot waterSpot = index.waterSource(at);
            String line = String.format("%s %s (%s) lvl=%d%s | %s | refilled %s | water %s",
                    at.toShortString(), spot.building().getDisplayName(),
                    spot.kind().name().toLowerCase(java.util.Locale.ROOT), water,
                    muddy && water > 0 && water < 3 ? " muddy" : "", holder, refill,
                    waterSpot == null ? "not searched" : waterSpot.water().toShortString());
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return spots.size();
    }

    private static int debugMerchantStall(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        VillageManager village = VillageManagerRegistry.get(level.getServer()).findVillageAt(level, pos);
        if (village == null) {
            source.sendFailure(Component.literal("No registered village at this position."));
            return 0;
        }
        ServerPlayer viewer = source.getEntity() instanceof ServerPlayer player ? player : null;
        MerchantStallBuilding used = MerchantLogic.stallFor(village);

        List<MerchantStallBuilding> stalls = new ArrayList<>();
        Set<UUID> registeredFrames = new HashSet<>();
        for (Building building : village.getBuildings()) {
            registeredFrames.add(building.getItemFrameId());
            if (building instanceof MerchantStallBuilding stall) stalls.add(stall);
        }

        source.sendSuccess(() -> Component.literal("── Merchant Stalls (" + stalls.size() + ") ──")
                .withStyle(ChatFormatting.GOLD), false);
        for (int i = 0; i < stalls.size(); i++) {
            MerchantStallBuilding stall = stalls.get(i);
            BlockPos counter = stall.getCounterPos();
            String where = stall.isOpenAir()
                    ? "area centre=" + MerchantStallBuilding.openAreaCenter(
                            stall.getItemFramePos(), stall.getInteriorDirection()).toShortString()
                    : "door=" + stall.getDoorPos().toShortString();
            String counterText = counter == null ? "none (needs a barrel)"
                    : (stall.getFunctionalBlocks().isEmpty() ? "centre tile " : "barrel ")
                            + counter.toShortString();
            MutableComponent line = Component.literal("  #" + i + " [").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(stall.isOpenAir()
                                    ? "OPEN-AIR " + MerchantStallBuilding.OPEN_AREA_SIZE + "x"
                                            + MerchantStallBuilding.OPEN_AREA_SIZE
                                    : "BUILDING")
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("] "))
                    .append(stall.isValid()
                            ? Component.literal("VALID").withStyle(ChatFormatting.GREEN)
                            : Component.literal("INVALID").withStyle(ChatFormatting.RED))
                    .append(Component.literal(String.format(" | frame=%s  %s  tiles=%d  counter=%s",
                                    stall.getItemFramePos().toShortString(), where,
                                    stall.getNavMeshBlocks().size(), counterText))
                            .withStyle(ChatFormatting.WHITE));
            if (stall == used) {
                line.append(Component.literal("  <- merchant uses this").withStyle(ChatFormatting.GREEN));
            }
            source.sendSuccess(() -> line, false);
            if (viewer != null) {
                flashTiles(level, viewer, stall.getNavMeshBlocks(), GREEN_DUST);
                if (counter != null) flashColumn(level, viewer, counter, YELLOW_DUST);
            }
        }

        // Stall tokens the village did not take, and why.
        NavGraph graph = village.getGraph();
        int unregistered = 0;
        if (graph != null && !graph.isEmpty()) {
            AABB bounds = new AABB(
                    graph.getBoundsMinX(), graph.getBoundsMinY() - 4, graph.getBoundsMinZ(),
                    graph.getBoundsMaxX() + 1, graph.getBoundsMaxY() + 4, graph.getBoundsMaxZ() + 1);
            for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, bounds)) {
                if (registeredFrames.contains(frame.getUUID())
                        || ItemFrameTokenRegistry.tokenType(frame.getItem())
                                != ItemFrameTokenRegistry.TokenType.STALL) continue;
                unregistered++;
                Set<BlockPos> tiles = MerchantStallBuilding.openAreaTiles(
                        graph, frame.blockPosition(), frame.getDirection());
                boolean bigEnough = tiles.size() >= MerchantStallBuilding.MIN_OPEN_TILES;
                String reason = VillageTokenBinding.belongsToAnotherVillage(frame.getItem(), village)
                        ? "the token belongs to another village"
                        : bigEnough ? "not picked up yet; take the token out and put it back"
                        : "no sealed room behind a door, and only " + tiles.size() + "/"
                                + MerchantStallBuilding.MIN_OPEN_TILES
                                + " walkable tiles in the open-air square";
                source.sendSuccess(() -> Component.literal("  [NOT REGISTERED] ")
                        .withStyle(ChatFormatting.RED)
                        .append(Component.literal("frame=" + frame.blockPosition().toShortString()
                                + " | " + reason).withStyle(ChatFormatting.WHITE)), false);
                if (viewer != null) flashTiles(level, viewer, tiles, bigEnough ? GREEN_DUST : RED_DUST);
            }
        }

        if (used == null) {
            String why = stalls.isEmpty() && unregistered == 0
                    ? "No Merchant Stall token in this village."
                    : "No valid Merchant Stall.";
            source.sendSuccess(() -> Component.literal(why + " The merchant waits at the gathering spot "
                    + village.getVisitorGatheringSpot().toShortString() + ".")
                    .withStyle(ChatFormatting.GRAY), false);
        }

        for (VisitorEntity merchant : level.getEntities(DynamicVillages.VISITOR.get(),
                visitor -> village.getVillageId().equals(visitor.getVillageId()))) {
            String status = merchant.isReturningHome() ? "heading home"
                    : merchant.hasArrivedForTrading() ? "trading" : "walking in";
            String area = used == null || !used.isOpenAir() ? ""
                    : used.isInOpenArea(merchant.blockPosition())
                            ? ", inside the stall area" : ", outside the stall area";
            source.sendSuccess(() -> Component.literal("Merchant at "
                    + merchant.blockPosition().toShortString() + ": " + status + area)
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return stalls.size();
    }

    /**
     * {@code /villager wallspots}: the outdoor places a homeless villager could sleep
     * sitting against a building wall ({@link WallSleepSpots}, found fresh), as dust
     * columns within 64 blocks: green lit, yellow dark. Nothing sends villagers there yet.
     */
    private static int debugWallSpots(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        VillageManager village = VillageManagerRegistry.get(level.getServer()).findVillageAt(level, pos);
        if (village == null) {
            source.sendFailure(Component.literal("No registered village at this position."));
            return 0;
        }
        List<WallSleepSpots.Spot> spots = WallSleepSpots.spots(village, level, true);
        long lit = spots.stream().filter(WallSleepSpots.Spot::lit).count();
        source.sendSuccess(() -> Component.literal(spots.size() + " wall sleep spots (" + lit
                + " lit). Shown within 64 blocks: green lit, yellow dark.")
                .withStyle(ChatFormatting.YELLOW), false);
        List<WallSleepSpots.Spot> near = new ArrayList<>(spots);
        near.sort(Comparator.comparingDouble(s -> s.tile().distSqr(pos)));
        for (WallSleepSpots.Spot s : near.subList(0, Math.min(5, near.size()))) {
            source.sendSuccess(() -> Component.literal("  " + s.tile().toShortString() + ", wall to the "
                    + s.back().getName() + (s.lit() ? ", lit" : "")).withStyle(ChatFormatting.GRAY), false);
        }
        if (source.getEntity() instanceof ServerPlayer viewer) {
            for (WallSleepSpots.Spot s : spots) {
                if (s.tile().distSqr(pos) <= 64 * 64) {
                    flashColumn(level, viewer, s.tile().below(), s.lit() ? GREEN_DUST : YELLOW_DUST);
                }
            }
        }
        return spots.size();
    }

    /** One flash of dust on each tile's floor, four specks per tile like {@code debug mark}. */
    private static void flashTiles(ServerLevel level, ServerPlayer player,
                                   Collection<BlockPos> tiles, DustParticleOptions dust) {
        double[] ox = {0.2, 0.8, 0.2, 0.8}, oz = {0.2, 0.2, 0.8, 0.8};
        for (BlockPos tile : tiles) {
            for (int i = 0; i < 4; i++) {
                level.sendParticles(player, dust, true,
                        tile.getX() + ox[i], tile.getY() + 0.1, tile.getZ() + oz[i], 1, 0, 0, 0, 0);
            }
        }
    }

    /** A short column of dust standing on {@code pos}, for a single block worth pointing at. */
    private static void flashColumn(ServerLevel level, ServerPlayer player,
                                    BlockPos pos, DustParticleOptions dust) {
        for (int i = 0; i < 4; i++) {
            level.sendParticles(player, dust, true,
                    pos.getX() + 0.5, pos.getY() + 1.1 + i * 0.4, pos.getZ() + 0.5, 1, 0, 0, 0, 0);
        }
    }

    // =========================================================================
    // goto
    // =========================================================================

    private static int npcGoto(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null) return 0;

        int goalX = IntegerArgumentType.getInteger(ctx, "x");
        int goalZ = IntegerArgumentType.getInteger(ctx, "z");
        NavGraph graph = VillageManager.INSTANCE.graph();
        if (graph == null) {
            source.sendFailure(Component.literal("No graph — place a Village Center or run /villager graph scan first."));
            return 0;
        }
        NavNode goalNode = graph.nearest(new BlockPos(goalX, 0, goalZ), 3);
        if (goalNode == null) {
            source.sendFailure(Component.literal("Goal (" + goalX + ", " + goalZ + ") not in nav graph."));
            return 0;
        }
        double range = 20.0;
        Vec3 eye = caller.getEyePosition(), look = caller.getViewVector(1.0F);
        AABB box = caller.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        var hit = ProjectileUtil.getEntityHitResult(
                caller, eye, eye.add(look.scale(range)), box,
                e -> e instanceof NpcEntity, range * range);
        if (hit == null || !(hit.getEntity() instanceof NpcEntity npc)) {
            source.sendFailure(Component.literal("Look at an NPC within " + (int) range + " blocks."));
            return 0;
        }
        VillagerModel model = VillageManager.INSTANCE.getOrCreateModel(npc.getNpcId());
        if (model.puppet() == null) model.bind(npc);
        model.requestMoveTo(graph, goalNode.feetPos(), VillagerModel.DEFAULT_SPEED);
        source.sendSuccess(() -> Component.literal(
                "Pathing NPC to node at (" + goalNode.x + ", " + goalNode.feetY + ", " + goalNode.z + ")…"),
                false);
        return 1;
    }

    // =========================================================================
    // Stress test
    // =========================================================================

    private static int stressTest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null) return 0;
        if (!(source.getLevel() instanceof ServerLevel sl)) { source.sendFailure(Component.literal("Server-side only.")); return 0; }

        NavGraph graph = VillageManager.INSTANCE.graph();
        if (graph == null) { source.sendFailure(Component.literal("No graph.")); return 0; }

        int count = IntegerArgumentType.getInteger(ctx, "count");
        Collection<NavNode> all = graph.allNodes();
        if (all.size() < count) {
            source.sendFailure(Component.literal("Not enough nodes (" + all.size() + ") for " + count + " NPCs."));
            return 0;
        }
        List<BlockPos> dests = new ArrayList<>(all.size());
        for (NavNode n : all) dests.add(n.feetPos());
        Collections.shuffle(dests, new Random());

        int gridW = (int) Math.ceil(Math.sqrt(count));
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            int ox = (i % gridW) * 2 - gridW;
            int oz = (i / gridW) * 2 - gridW;
            BlockPos spawnPos = caller.blockPosition().offset(ox, 0, oz).above();
            NpcEntity npc = DynamicVillages.NPC.get().create(sl);
            if (npc == null) continue;
            npc.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0, 0);
            sl.addFreshEntity(npc);
            VillagerModel model = VillageManager.INSTANCE.getOrCreateModel(npc.getNpcId());
            model.bind(npc);
            model.requestMoveTo(graph, dests.get(i), VillagerModel.DEFAULT_SPEED);
            spawned++;
        }
        int fs = spawned;
        source.sendSuccess(() -> Component.literal("Stress test: spawned " + fs + " NPCs."), false);
        return 1;
    }

    // =========================================================================
    // Maze
    // =========================================================================

    private static int generateMaze(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null) return 0;
        if (!(source.getLevel() instanceof ServerLevel sl)) return 0;

        int cells = IntegerArgumentType.getInteger(ctx, "size");
        int mazeW = 2 * cells + 1, mazeD = 2 * cells + 1;
        boolean[][] isWall  = new boolean[mazeW][mazeD];
        for (boolean[] row : isWall) Arrays.fill(row, true);
        boolean[][] visited = new boolean[cells][cells];
        Deque<int[]> stack  = new ArrayDeque<>();
        Random rand = new Random();
        int[] dx = {0, 0, 1, -1}, dz = {1, -1, 0, 0};
        visited[0][0] = true; isWall[1][1] = false; stack.push(new int[]{0, 0});
        while (!stack.isEmpty()) {
            int[] cur = stack.peek(); int cx = cur[0], cz = cur[1];
            List<Integer> dirs = new ArrayList<>(List.of(0, 1, 2, 3));
            Collections.shuffle(dirs, rand);
            boolean found = false;
            for (int d : dirs) {
                int nx = cx + dx[d], nz = cz + dz[d];
                if (nx >= 0 && nx < cells && nz >= 0 && nz < cells && !visited[nx][nz]) {
                    isWall[2*cx+1+dx[d]][2*cz+1+dz[d]] = false;
                    isWall[2*nx+1][2*nz+1] = false;
                    visited[nx][nz] = true; stack.push(new int[]{nx, nz}); found = true; break;
                }
            }
            if (!found) stack.pop();
        }
        isWall[0][1] = false; isWall[mazeW-1][mazeD-2] = false;

        BlockPos origin = caller.blockPosition(); int surfY = origin.getY() - 1; int placed = 0;
        for (int mz = 0; mz < mazeD; mz++) {
            for (int mx = 0; mx < mazeW; mx++) {
                BlockPos base = new BlockPos(origin.getX()+mx, surfY, origin.getZ()+mz);
                if (isWall[mx][mz]) {
                    sl.setBlock(base.above(1), Blocks.DIRT.defaultBlockState(), 3);
                    sl.setBlock(base.above(2), Blocks.DIRT.defaultBlockState(), 3);
                    placed += 2;
                } else {
                    sl.setBlock(base.above(1), Blocks.AIR.defaultBlockState(), 3);
                    sl.setBlock(base.above(2), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        VillageManager.INSTANCE.rebuildNow(sl);
        int fp = placed;
        source.sendSuccess(() -> Component.literal(String.format(
                "Maze: %dx%d cells, %d blocks placed. Graph rebuilt.", cells, cells, fp)), false);
        return 1;
    }

    // =========================================================================
    // Graph commands
    // =========================================================================

    private static int graphScan(CommandContext<CommandSourceStack> ctx, int chunkRadius) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null) { source.sendFailure(Component.literal("Must be run by a player.")); return 0; }
        if (!(source.getLevel() instanceof ServerLevel sl)) return 0;

        BlockPos center = caller.blockPosition();
        VillageManager realVillage = VillageManagerRegistry.get(source.getServer())
                .findVillageAt(sl, center);
        if (realVillage != null) {
            long start = System.currentTimeMillis();
            realVillage.rebuildNow(sl);
            long elapsed = System.currentTimeMillis() - start;
            NavGraph graph = realVillage.graph();
            source.sendSuccess(() -> Component.literal(String.format(
                    "Village %s NavGraph rebuilt in %dms (radius=%d chunks) — %s",
                    realVillage.getVillageId().toString().substring(0, 8), elapsed,
                    realVillage.getGraphChunkRadius(), graph.dumpStats())), false);
            return 1;
        }

        int boxBlocks = (chunkRadius * 2 + 1) * 16;
        long start = System.currentTimeMillis();
        VillageManager.INSTANCE.initialize(sl, center, chunkRadius);
        long elapsed = System.currentTimeMillis() - start;
        NavGraph graph = VillageManager.INSTANCE.graph();
        source.sendSuccess(() -> Component.literal(String.format(
                "NavGraph built in %dms (radius=%d → %dx%d) — %s",
                elapsed, chunkRadius, boxBlocks, boxBlocks, graph.dumpStats())), false);
        return 1;
    }

    private static int graphDump(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        NavGraph graph = VillageManager.INSTANCE.graph();
        if (graph == null) { source.sendFailure(Component.literal("No graph.")); return 0; }
        String stats = graph.dumpStats();
        DynamicVillages.LOGGER.info(stats);
        source.sendSuccess(() -> Component.literal(stats), false);
        return 1;
    }

    // =========================================================================
    // Debug overlay
    // =========================================================================

    private static int debugMark(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        NavGraph graph = VillageManager.INSTANCE.graph();
        if (graph == null || graph.isEmpty()) { source.sendFailure(Component.literal("No graph.")); return 0; }
        if (!(source.getLevel() instanceof ServerLevel level)) return 0;
        if (!(source.getEntity() instanceof ServerPlayer player)) { source.sendFailure(Component.literal("Must be a player.")); return 0; }

        double[] ox = {0.1, 0.9, 0.1, 0.9}, oz = {0.1, 0.1, 0.9, 0.9};
        int count = 0;
        for (NavNode node : graph.allNodes()) {
            double py = node.surfaceY + 0.97;
            DustParticleOptions dust = node.isTransient() ? YELLOW_DUST : RED_DUST;
            for (int i = 0; i < 4; i++)
                level.sendParticles(player, dust, true, node.x+ox[i], py, node.z+oz[i], 1, 0, 0, 0, 0);
            count++;
        }
        int fc = count;
        source.sendSuccess(() -> Component.literal("Marked " + fc + " nodes."), false);
        return 1;
    }

    private static int debugWool(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        NavGraph graph = VillageManager.INSTANCE.graph();
        if (graph == null || graph.isEmpty()) { source.sendFailure(Component.literal("No graph.")); return 0; }
        if (!(source.getLevel() instanceof ServerLevel level)) return 0;
        DebugWoolStore wool = DebugWoolStore.get(level);

        restoreDebugWool(level, wool.nodes);
        int placed = 0;
        for (NavNode node : graph.allNodes()) {
            BlockPos sp = new BlockPos(node.x, node.surfaceY, node.z);
            rememberOriginal(level, wool.nodes, wool.buildings, sp);
            level.setBlock(sp, (node.isTransient() ? Blocks.YELLOW_WOOL : Blocks.RED_WOOL).defaultBlockState(), 3);
            placed++;
        }
        wool.setDirty();
        int fp = placed;
        source.sendSuccess(() -> Component.literal("Placed wool on " + fp + " nodes."), false);
        return 1;
    }

    private static int debugClear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getLevel() instanceof ServerLevel level)) return 0;
        DebugWoolStore wool = DebugWoolStore.get(level);
        int restored = restoreDebugWool(level, wool.nodes)
                     + restoreDebugWool(level, wool.buildings);
        wool.setDirty();
        source.sendSuccess(() -> Component.literal("Restored " + restored + " blocks."), false);
        return 1;
    }

    /**
     * Records what stands at {@code pos} before debug wool replaces it. The
     * first record wins, and a block the other wool command already covered is
     * recorded as what that one found — so a tile coloured twice, or by both
     * commands, still goes back to the real block and not to wool.
     */
    private static void rememberOriginal(ServerLevel level, Map<BlockPos, BlockState> map,
                                         Map<BlockPos, BlockState> other, BlockPos pos) {
        if (map.containsKey(pos)) return;
        BlockState original = other.get(pos);
        map.put(pos, original != null ? original : level.getBlockState(pos));
    }

    /** Restores blocks saved in {@code map} that are currently a wool block. */
    private static int restoreDebugWool(ServerLevel level, Map<BlockPos, BlockState> map) {
        int count = 0;
        for (var e : map.entrySet()) {
            BlockState cur = level.getBlockState(e.getKey());
            if (cur.is(BlockTags.WOOL)) {
                level.setBlock(e.getKey(), e.getValue(), 3); count++;
            }
        }
        map.clear();
        return count;
    }

    private static int graphNav(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        NavGraph graph = VillageManager.INSTANCE.graph();
        if (graph == null) { source.sendFailure(Component.literal("No graph.")); return 0; }
        Entity caller = source.getEntity();
        if (caller == null) return 0;
        int half = IntegerArgumentType.getInteger(ctx, "halfsize");
        BlockPos p = caller.blockPosition();
        String slice = graph.dumpSlice(p.getX()-half, p.getZ()-half, p.getX()+half, p.getZ()+half);
        DynamicVillages.LOGGER.info(slice);
        source.sendSuccess(() -> Component.literal("Nav slice (" + (half*2+1) + "×" + (half*2+1) + ") logged."), false);
        return 1;
    }

    /**
     * /villager graph check
     *
     * <p>Compares the piece map's answers with the old whole-village floods,
     * run once on demand: a diagnostic for the cell map, not a way back to
     * the old passes. Prints how many tiles disagree per check and where the
     * first few are. The 200 route checks each run a full A* search, so the
     * command can take a few seconds in a large village.
     */
    private static int graphCheck(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getLevel() instanceof ServerLevel level)) return 0;
        Entity caller = source.getEntity();
        if (caller == null) { source.sendFailure(Component.literal("Must be run by a player.")); return 0; }
        VillageManager village = VillageManagerRegistry.get(level.getServer())
                .findVillageAt(level, caller.blockPosition());
        NavGraph graph = village != null ? village.getGraph() : VillageManager.INSTANCE.graph();
        if (graph == null || graph.isEmpty()) { source.sendFailure(Component.literal("No graph here.")); return 0; }
        BlockPos origin = graph.origin();
        long started = System.nanoTime();

        // The island sweep the old way: everything the Town Hall door reaches
        // by any step the walker could take, water landings and chest tops
        // included.
        Set<NavNode> reachedFromRoot = Collections.newSetFromMap(new IdentityHashMap<>());
        NavNode root = graph.rootNode();
        if (root != null) {
            ArrayDeque<NavNode> stack = new ArrayDeque<>();
            reachedFromRoot.add(root);
            stack.push(root);
            while (!stack.isEmpty()) {
                NavNode cur = stack.pop();
                for (NavNode next : cur.neighbours()) {
                    if (!cur.canStepTo(next) || !reachedFromRoot.add(next)) continue;
                    stack.push(next);
                }
            }
        }
        // The access flood the old way: routable tiles only, from the routable
        // tile nearest the bell.
        Set<NavNode> accessSet = Collections.newSetFromMap(new IdentityHashMap<>());
        NavNode accessStart = graph.nearestRoutable(origin, 3, 3);
        if (accessStart != null) {
            ArrayDeque<NavNode> queue = new ArrayDeque<>();
            accessSet.add(accessStart);
            queue.add(accessStart);
            while (!queue.isEmpty()) {
                NavNode cur = queue.poll();
                for (NavNode next : cur.neighbours()) {
                    if (!next.isRoutable() || !cur.canStepTo(next) || !accessSet.add(next)) continue;
                    queue.add(next);
                }
            }
        }

        int accessWrong = 0, islandWrong = 0, noPiece = 0;
        List<String> accessSamples = new ArrayList<>(), islandSamples = new ArrayList<>();
        for (NavNode tile : graph.allNodes()) {
            if (tile.piece() == null) {
                noPiece++;
                continue;
            }
            boolean oldAccess = accessSet.contains(tile);
            boolean newAccess = graph.cells().accessible(tile);
            if (oldAccess != newAccess) {
                accessWrong++;
                if (accessSamples.size() < 5) accessSamples.add(tile.feetPos().toShortString() + (oldAccess ? " lost" : " gained"));
            }
            boolean oldReached = root == null || reachedFromRoot.contains(tile);
            boolean newIsland = graph.cells().isIsland(tile);
            if (oldReached == newIsland) {
                islandWrong++;
                if (islandSamples.size() < 5) islandSamples.add(tile.feetPos().toShortString() + (newIsland ? " island" : " kept"));
            }
        }
        // Every link must point at a live tile that has a piece, or the map
        // was built from a stale edge.
        int linkWrong = 0;
        List<String> linkSamples = new ArrayList<>();
        for (com.alex.test.village.nav.Cell<NavNode> cell : graph.cells().cells()) {
            for (com.alex.test.village.nav.Piece<NavNode> piece : cell.pieces()) {
                for (com.alex.test.village.nav.Piece.Link<NavNode> link : piece.links) {
                    NavNode to = link.to();
                    if (graph.nodeAt(to.x, to.feetY, to.z) == to && to.piece() != null) continue;
                    linkWrong++;
                    if (linkSamples.size() < 5) linkSamples.add(to.feetPos().toShortString());
                }
            }
        }
        // "Can I get there" against the route planner itself, for random pairs.
        List<NavNode> all = new ArrayList<>(graph.allNodes());
        Random rng = new Random(42L);
        int reachWrong = 0;
        List<String> reachSamples = new ArrayList<>();
        for (int i = 0; i < 200 && all.size() >= 2; i++) {
            NavNode a = all.get(rng.nextInt(all.size()));
            NavNode b = all.get(rng.nextInt(all.size()));
            boolean fast = NavReachability.canReach(graph, a.feetPos(), b.feetPos());
            boolean slow = AStarPathfinder.findPath(graph, a.feetPos(), b.feetPos()) != null;
            if (fast == slow) continue;
            reachWrong++;
            if (reachSamples.size() < 5) reachSamples.add(a.feetPos().toShortString() + " -> " + b.feetPos().toShortString()
                    + (fast ? " map yes, route no" : " map no, route yes"));
        }

        String report = String.format(Locale.ROOT,
                "Graph check (%d tiles, %d pieces, %d ms): access disagree %d %s | island disagree %d %s"
                        + " | bad links %d %s | route disagree %d of 200 %s | tiles without a piece %d | stale queries so far %d",
                graph.size(), graph.cells().cells().stream().mapToInt(c -> c.pieces().size()).sum(),
                (System.nanoTime() - started) / 1_000_000L,
                accessWrong, accessSamples, islandWrong, islandSamples, linkWrong, linkSamples,
                reachWrong, reachSamples, noPiece, graph.cells().staleQueries());
        DynamicVillages.LOGGER.info(report);
        boolean clean = accessWrong == 0 && islandWrong == 0 && linkWrong == 0 && reachWrong == 0 && noPiece == 0;
        source.sendSuccess(() -> Component.literal(report)
                .withStyle(clean ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        return 1;
    }

    // =========================================================================
    // Building debug commands
    // =========================================================================

    /**
     * /villager beds why
     *
     * For the villager the caller is looking at: its bed claim, and every house
     * and bed with the first check that rules it out tonight. Read-only. The
     * same lines go to the server log, where long ones are not cut short.
     */
    private static int bedsWhy(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        NpcEntity npc = NpcPreviewCommands.lookedAtNpc(source, 20.0);
        if (npc == null) return 0;
        VillageManager manager = npc.resolveVillageManager(source.getLevel());
        VillagerModel model = manager != null ? manager.getModel(npc.getNpcId()) : null;
        if (model == null) {
            source.sendFailure(Component.literal("That NPC is not a member of any village."));
            return 0;
        }
        String label = npc.getName().getString() + " (" + npc.getNpcId().toString().substring(0, 8) + ")";
        BlockPos bed = manager.getAssignedBed(npc.getNpcId());
        source.sendSuccess(() -> Component.literal("── Beds: " + label + " ──").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("usable bed now: " + (bed != null ? bed.toShortString() : "none")
                + ", village has a free bed: " + manager.hasFreeBed()).withStyle(ChatFormatting.YELLOW), false);
        DynamicVillages.LOGGER.info("[Beds] /villager beds why {}: usable bed {}", label, bed);
        for (String line : manager.explainNoBed(model)) {
            ChatFormatting colour = line.contains("OK") ? ChatFormatting.GREEN
                    : line.startsWith("  ") ? ChatFormatting.GRAY : ChatFormatting.WHITE;
            source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
            DynamicVillages.LOGGER.info("[Beds]   {}", line);
        }
        return 1;
    }

    /**
     * /villager building list
     *
     * Prints every registered building to chat (type, validity, positions, room size,
     * and type-specific details).  Also logs a summary line to the server log.
     */
    private static int buildingList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var buildings = allBuildings(source);

        if (buildings.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No buildings registered.")
                    .withStyle(ChatFormatting.GRAY), false);
            DynamicVillages.LOGGER.info("[Buildings] No buildings registered.");
            return 0;
        }

        MutableComponent header = Component.literal(
                "── Buildings (" + buildings.size() + ") ──")
                .withStyle(ChatFormatting.GOLD);
        source.sendSuccess(() -> header, false);
        DynamicVillages.LOGGER.info("[Buildings] ── {} building(s) ──", buildings.size());

        for (int i = 0; i < buildings.size(); i++) {
            Building b = buildings.get(i);
            final int idx = i;

            // Type label
            String typeName;
            if      (b instanceof HouseBuilding)   typeName = "House";
            else if (b instanceof StorageBuilding) typeName = "Storage";
            else                                   typeName = "TownHall";

            // Valid / Invalid badge
            MutableComponent badge = b.isValid()
                    ? Component.literal("VALID").withStyle(ChatFormatting.GREEN)
                    : Component.literal("INVALID").withStyle(ChatFormatting.RED);

            // Position summary
            BlockPos fp = b.getItemFramePos(), dp = b.getDoorPos();
            String posSummary = String.format("frame=%s  door=%s  room=%d blocks",
                    fp.toShortString(), dp.toShortString(), b.getNavMeshBlocks().size());

            // Type-specific details
            String details = buildingDetails(b, source);

            MutableComponent line = Component.literal(
                    String.format("  #%d [", idx))
                    .withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(typeName)
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("] "))
                    .append(badge)
                    .append(Component.literal(" | " + posSummary)
                            .withStyle(ChatFormatting.WHITE));
            if (!details.isEmpty()) {
                line.append(Component.literal(" | " + details)
                        .withStyle(ChatFormatting.YELLOW));
            }

            // Wool palette preview ▪
            Block palBlock = BUILDING_WOOL_PALETTE[idx % BUILDING_WOOL_PALETTE.length];
            line.append(Component.literal(" ▪").withStyle(
                    s -> s.withColor(woolToRgb(palBlock))));

            source.sendSuccess(() -> line, false);

            // Server log
            DynamicVillages.LOGGER.info("[Buildings] #{} {} {} | {} | {}",
                    idx, typeName, b.isValid() ? "VALID" : "INVALID", posSummary, details);
        }
        return buildings.size();
    }

    /** Returns a short type-specific details string for a building. */
    private static String buildingDetails(Building b, CommandSourceStack source) {
        if (b instanceof HouseBuilding house) {
            int beds = house.getFunctionalBlocks().size();
            // Count occupied beds if we have a server level
            int occupied = 0;
            if (source.getLevel() instanceof ServerLevel sl) {
                for (int i = 0; i < beds; i++)
                    if (house.isBedOccupied(sl, i)) occupied++;
            }
            return String.format("beds=%d (occupied=%d)", beds, occupied);
        }
        if (b instanceof StorageBuilding storage) {
            int chests = storage.getFunctionalBlocks().size();
            return "chests=" + chests;
        }
        // TownHall — no extra details
        return "";
    }

    /**
     * /villager building wool
     *
     * Places differently-coloured wool on the surface blocks of every building's
     * nav-mesh floor tiles.  Valid buildings use their palette colour; invalid ones
     * use their palette colour at 50% brightness (darker variants when available,
     * otherwise the same colour with a border of RED_WOOL at the door).
     *
     * Also places a WHITE_WOOL marker at each building's item-frame block and a
     * BLACK_WOOL marker at the door base.
     */
    private static int buildingWool(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getLevel() instanceof ServerLevel level)) return 0;

        var buildings = allBuildings(source);
        if (buildings.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No buildings to visualise.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        // Clear any previous building wool
        DebugWoolStore wool = DebugWoolStore.get(level);
        restoreDebugWool(level, wool.buildings);

        int totalPlaced = 0;
        for (int i = 0; i < buildings.size(); i++) {
            Building b   = buildings.get(i);
            Block floorWool = BUILDING_WOOL_PALETTE[i % BUILDING_WOOL_PALETTE.length];
            // Invalid buildings swap to a contrasting dark/gray variant
            Block actualWool = b.isValid() ? floorWool : Blocks.GRAY_WOOL;

            // Nav-mesh floor tiles (feetY − 1 = surface block)
            for (BlockPos feetPos : b.getNavMeshBlocks()) {
                BlockPos surface = buildingWoolMarkerPos(level, feetPos.below());
                rememberOriginal(level, wool.buildings, wool.nodes, surface);
                level.setBlock(surface, actualWool.defaultBlockState(), 3);
                totalPlaced++;
            }

            // Door base marker — WHITE for valid, RED for invalid
            BlockPos door = b.getDoorPos().below(); // block under the door
            rememberOriginal(level, wool.buildings, wool.nodes, door);
            level.setBlock(door, (b.isValid() ? Blocks.WHITE_WOOL : Blocks.RED_WOOL)
                    .defaultBlockState(), 3);
            totalPlaced++;

            // Item-frame support block marker — always LIME so it stands out
            BlockPos frameMarker = b.getItemFramePos()
                    .relative(b.getInteriorDirection()); // block behind the frame
            rememberOriginal(level, wool.buildings, wool.nodes, frameMarker);
            level.setBlock(frameMarker, Blocks.LIME_WOOL.defaultBlockState(), 3);
            totalPlaced++;
        }

        wool.setDirty();
        final int fp = totalPlaced;
        source.sendSuccess(() -> Component.literal(
                "Placed " + fp + " wool blocks for " + buildings.size() + " building(s). "
                + "Nav-mesh = palette colour (gray=invalid), door=white/red, frame-side=lime.")
                .withStyle(ChatFormatting.GREEN), false);
        return buildings.size();
    }

    private static BlockPos buildingWoolMarkerPos(ServerLevel level, BlockPos surface) {
        return isProtectedBuildingDebugSurface(level.getBlockState(surface))
                ? surface.below()
                : surface;
    }

    private static boolean isProtectedBuildingDebugSurface(BlockState state) {
        return state.getBlock() instanceof BedBlock
                || state.getBlock() instanceof ChestBlock
                || state.is(Blocks.CRAFTING_TABLE);
    }

    /**
     * /villager building clear
     *
     * Restores all blocks placed by /villager building wool.
     */
    private static int buildingClear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getLevel() instanceof ServerLevel level)) return 0;
        DebugWoolStore wool = DebugWoolStore.get(level);
        int restored = restoreDebugWool(level, wool.buildings);
        wool.setDirty();
        source.sendSuccess(() -> Component.literal("Restored " + restored + " building-wool block(s).")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Collects all registered buildings from every active village manager:
     * the INSTANCE (command/debug village) plus every bell-placed registry village.
     */
    private static List<Building> allBuildings(CommandSourceStack source) {
        List<Building> all = new ArrayList<>(VillageManager.INSTANCE.getBuildings());
        net.minecraft.server.MinecraftServer server = source.getServer();
        if (server != null) {
            for (VillageManager mgr : VillageManagerRegistry.get(server).all()) {
                all.addAll(mgr.getBuildings());
            }
        }
        return all;
    }

    /** Maps a wool block to an RGB int for use in chat component colour. */
    private static int woolToRgb(Block wool) {
        if (wool == Blocks.LIME_WOOL)        return 0x5aad00;
        if (wool == Blocks.CYAN_WOOL)        return 0x009999;
        if (wool == Blocks.LIGHT_BLUE_WOOL)  return 0x3ab3da;
        if (wool == Blocks.BLUE_WOOL)        return 0x3c44aa;
        if (wool == Blocks.PURPLE_WOOL)      return 0x8932b8;
        if (wool == Blocks.MAGENTA_WOOL)     return 0xc74ebd;
        if (wool == Blocks.PINK_WOOL)        return 0xf38baa;
        if (wool == Blocks.ORANGE_WOOL)      return 0xf9801d;
        if (wool == Blocks.GREEN_WOOL)       return 0x5e7c16;
        if (wool == Blocks.BROWN_WOOL)       return 0x835432;
        return 0xffffff;
    }
}
