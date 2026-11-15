package com.alex.test.testkit;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.building.Building;
import com.alex.test.village.stats.VillagerSkills;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.guard.settings.GuardSettingsChangeHandler;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.settings.ProfessionSettingsChangeHandler;
import com.alex.test.village.villagers.settings.SettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchemaRegistry;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The {@code /vtest} command tree — the control surface an external test
 * harness drives over RCON.
 *
 * <h3>Design rules</h3>
 * <ul>
 *   <li><b>No player required.</b> Every command takes explicit coordinates and
 *       works from the server console, because a test server has no players.
 *       This is what separates it from {@code /villager}, whose debug commands
 *       all resolve targets by raycasting from the caller's eyes.</li>
 *   <li><b>Replies stay short.</b> RCON truncates long responses, so anything
 *       large is written to {@code vtest-out/} and the reply is just a receipt.</li>
 *   <li><b>Machine-readable receipts.</b> Replies start with {@code OK} or
 *       {@code ERR} so a harness can branch without parsing prose.</li>
 * </ul>
 */
public final class TestCommands {

    /** Identity the fake shopper trades under, so repeat buys reuse one entity. */
    private static final com.mojang.authlib.GameProfile TEST_BUYER =
            new com.mojang.authlib.GameProfile(
                    UUID.nameUUIDFromBytes("vtest-shop-buyer".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "vtest_buyer");
    private static final com.mojang.authlib.GameProfile TEST_RECRUITER =
            new com.mojang.authlib.GameProfile(
                    UUID.nameUUIDFromBytes("vtest-recruiter".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "vtest_recruiter");
    private static final com.mojang.authlib.GameProfile TEST_CREATIVE =
            new com.mojang.authlib.GameProfile(
                    UUID.nameUUIDFromBytes("vtest-creative".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "vtest_creative");

    /** Trees planted by {@code /vtest scenario village} when no count is given. */
    private static final int DEFAULT_TREES = 6;

    /** Half-width levelled by {@code /vtest scenario level} when no radius is given. */
    private static final int DEFAULT_LEVEL_RADIUS = 24;

    private static final SuggestionProvider<CommandSourceStack> PROFESSIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    java.util.Arrays.stream(Profession.values()).map(Enum::name).toList(), builder);

    private TestCommands() {}

    // =========================================================================
    // Registration
    // =========================================================================

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("vtest")
                .requires(source -> source.hasPermission(2));

        // The player-emulation half of the surface lives in its own class; this
        // file was already long enough that a misplaced bracket was a recurring
        // bug (see the note under "Command subtrees").
        for (LiteralArgumentBuilder<CommandSourceStack> node : PlaythroughCommands.nodes()) {
            root.then(node);
        }

        dispatcher.register(root

                .then(Commands.literal("ping").executes(TestCommands::ping))

                .then(Commands.literal("headless")
                        .then(Commands.literal("on").executes(ctx -> setHeadless(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setHeadless(ctx, false))))

                .then(Commands.literal("scenario")
                        .then(scenarioVillageNode())
                        .then(scenarioShowcaseNode())
                        .then(scenarioLevelNode())
                        .then(scenarioTreeNode())
                        .then(scenarioMineNode())
                        .then(scenarioFarmNode())
                        .then(scenarioRanchNode())
                        .then(scenarioRoomNode()))

                .then(bonemealNode())

                .then(stockNode())

                .then(Commands.literal("ring_bell").then(xyz(z -> z
                        .executes(ctx -> ringBell(ctx, readXyz(ctx))))))

                .then(spawnNode())

                .then(visitorNode())

                .then(shopNode())

                .then(creativeGrabNode())

                .then(arrivalNode())

                .then(requirementsNode())

                .then(recipeNode())

                .then(leadNode())

                .then(invNode())

                .then(raidNode())

                .then(com.alex.test.village.brain.PairDanceCommands.node())

                .then(villageNode())

                .then(com.alex.test.village.economy.away.AwayPreview.previewNode())


                .then(professionNode())

                .then(Commands.literal("dump")
                        .executes(ctx -> dump(ctx, "state.json"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> dump(ctx,
                                        StringArgumentType.getString(ctx, "name") + ".json"))))

                .then(mapNode())

                .then(Commands.literal("set")
                        .then(Commands.argument("stat", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        List.of("hunger", "happiness"), b))
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                                                .executes(TestCommands::setStat)))))

                .then(Commands.literal("watch")
                        .then(Commands.literal("start")
                                .then(Commands.argument("interval", IntegerArgumentType.integer(1, 12000))
                                        .executes(ctx -> watchStart(ctx, -1, "timeline.jsonl"))
                                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 100000))
                                                .executes(ctx -> watchStart(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "limit"),
                                                        "timeline.jsonl"))
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .executes(ctx -> watchStart(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "limit"),
                                                                StringArgumentType.getString(ctx, "name")
                                                                        + ".jsonl"))))))
                        .then(Commands.literal("stop").executes(TestCommands::watchStop))
                        .then(Commands.literal("status").executes(TestCommands::watchStatus)))

                .then(Commands.literal("villagers").executes(TestCommands::listVillagers))
                .then(Commands.literal("villages").executes(TestCommands::listVillages))
                .then(Commands.literal("economy")
                        .then(Commands.literal("prices").executes(TestCommands::economyPrices)))
                .then(Commands.literal("node").then(xyz(z -> z
                        .executes(ctx -> nodeProbe(ctx, readXyz(ctx))))))

                .then(Commands.literal("path")
                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                .then(Commands.argument("y1", IntegerArgumentType.integer())
                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                        .then(Commands.argument("y2", IntegerArgumentType.integer())
                                                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                        .executes(TestCommands::pathProbe))))))))

                .then(Commands.literal("move")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(xyz(z -> z
                                        .executes(ctx -> moveVillager(ctx, false))))))

                .then(Commands.literal("walk")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(xyz(z -> z
                                        .executes(ctx -> moveVillager(ctx, true))))))

                // As if a player held this villager's screen open for <ticks>.
                .then(Commands.literal("uipause")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 24000))
                                        .executes(TestCommands::uiPause))))

                .then(Commands.literal("brain")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.literal("suspend")
                                        .executes(ctx -> setBrainSuspended(ctx, true)))
                                .then(Commands.literal("resume")
                                        .executes(ctx -> setBrainSuspended(ctx, false)))))
        );
    }

    // =========================================================================
    // Command subtrees
    //
    // Each coordinate-taking command is built in its own method. Inlining them
    // all into register() produced a nesting depth where a single misplaced
    // bracket silently reattached an optional argument to the wrong node.
    // =========================================================================

    /** {@code /vtest scenario village <x> <y> <z> [trees]} */
    private static LiteralArgumentBuilder<CommandSourceStack> scenarioVillageNode() {
        return Commands.literal("village").then(xyz(z -> z
                .executes(ctx -> scenarioVillage(ctx, readXyz(ctx), DEFAULT_TREES))
                .then(Commands.argument("trees", IntegerArgumentType.integer(0, 8))
                        .executes(ctx -> scenarioVillage(ctx, readXyz(ctx),
                                IntegerArgumentType.getInteger(ctx, "trees"))))));
    }

    /**
     * {@code /vtest scenario showcase [<x> <y> <z>] [skill]} — the whole
     * late-game village in one command.
     *
     * <p>The only command in this tree with an argument-less form, and
     * deliberately so. Everything else here is driven over RCON by a harness
     * that always knows its coordinates; this one is meant to be typed by a
     * player standing where they want the village, for whom retyping their own
     * position is pure friction. The explicit form still exists, and is what a
     * scenario uses.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> scenarioShowcaseNode() {
        return Commands.literal("showcase")
                .executes(ctx -> scenarioShowcase(ctx, null,
                        ShowcaseVillage.DEFAULT_SKILL_LEVEL))
                .then(xyz(z -> z
                        .executes(ctx -> scenarioShowcase(ctx, readXyz(ctx),
                                ShowcaseVillage.DEFAULT_SKILL_LEVEL))
                        .then(Commands.argument("skill",
                                        IntegerArgumentType.integer(0, VillagerSkills.MAX_LEVEL))
                                .executes(ctx -> scenarioShowcase(ctx, readXyz(ctx),
                                        IntegerArgumentType.getInteger(ctx, "skill"))))));
    }

    /** {@code /vtest scenario level <x> <y> <z> [radius]} */
    private static LiteralArgumentBuilder<CommandSourceStack> scenarioLevelNode() {
        return Commands.literal("level").then(xyz(z -> z
                .executes(ctx -> scenarioLevel(ctx, readXyz(ctx), DEFAULT_LEVEL_RADIUS))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> scenarioLevel(ctx, readXyz(ctx),
                                IntegerArgumentType.getInteger(ctx, "radius"))))));
    }

    /**
     * {@code /vtest scenario mine <x> <y> <z> [facing]} — a rock outcrop with a
     * Mine token on it.
     *
     * <p>{@code x/y/z} is the entrance floor at villager feet level. The marked
     * block is two blocks above it. The shaft runs away from {@code facing},
     * which defaults to north because that is the side this builder puts doors and
     * frames on.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> scenarioMineNode() {
        return Commands.literal("mine").then(xyz(z -> z
                .executes(ctx -> scenarioMine(ctx, readXyz(ctx), "north"))
                .then(Commands.argument("facing", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                List.of("north", "south", "east", "west"), b))
                        .executes(ctx -> scenarioMine(ctx, readXyz(ctx),
                                StringArgumentType.getString(ctx, "facing"))))));
    }

    /** {@code /vtest scenario tree <x> <y> <z>} */
    private static LiteralArgumentBuilder<CommandSourceStack> scenarioTreeNode() {
        return Commands.literal("tree").then(xyz(z -> z
                .executes(ctx -> scenarioTree(ctx, readXyz(ctx)))));
    }

    /** {@code /vtest bonemeal <x> <y> <z> [times]} */
    private static LiteralArgumentBuilder<CommandSourceStack> bonemealNode() {
        return Commands.literal("bonemeal").then(xyz(z -> z
                .executes(ctx -> bonemeal(ctx, readXyz(ctx), 1))
                .then(Commands.argument("times", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> bonemeal(ctx, readXyz(ctx),
                                IntegerArgumentType.getInteger(ctx, "times"))))));
    }

    /** {@code /vtest scenario farm <x> <y> <z>} — x/y/z is the plot's NW corner. */
    private static LiteralArgumentBuilder<CommandSourceStack> scenarioFarmNode() {
        return Commands.literal("farm").then(xyz(z -> z
                .executes(ctx -> scenarioFarm(ctx, readXyz(ctx)))));
    }

    /** {@code /vtest scenario ranch <x> <y> <z> <animal> [count]} */
    private static LiteralArgumentBuilder<CommandSourceStack> scenarioRanchNode() {
        return Commands.literal("ranch").then(xyz(z -> z
                .then(Commands.argument("animal", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                List.of("cow", "sheep", "pig", "chicken"), b))
                        .executes(ctx -> scenarioRanch(ctx, readXyz(ctx),
                                StringArgumentType.getString(ctx, "animal"), 2))
                        .then(Commands.argument("count", IntegerArgumentType.integer(0, 16))
                                .executes(ctx -> scenarioRanch(ctx, readXyz(ctx),
                                        StringArgumentType.getString(ctx, "animal"),
                                        IntegerArgumentType.getInteger(ctx, "count")))))));
    }

    /** {@code /vtest scenario room <kitchen|forge|mine> <x> <y> <z>} */
    private static LiteralArgumentBuilder<CommandSourceStack> scenarioRoomNode() {
        return Commands.literal("room").then(
                Commands.argument("type", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                List.of("kitchen", "butchery", "forge",
                                        "tavern", "library", "school", "stall", "barracks",
                                        "2_bed_house", "4_bed_house", "6_bed_house", "storage"), b))
                        .then(xyz(z -> z
                                .executes(ctx -> scenarioRoom(ctx,
                                        StringArgumentType.getString(ctx, "type"),
                                        readXyz(ctx))))));
    }

    /** {@code /vtest stock <x> <y> <z> <item> <count>} — fill village storage. */
    private static LiteralArgumentBuilder<CommandSourceStack> stockNode() {
        // ResourceLocationArgument, not string(): a quoted-string argument
        // chokes on the ':' in "minecraft:bread".
        return Commands.literal("stock").then(xyz(z -> z
                .then(Commands.argument("item",
                                net.minecraft.commands.arguments.ResourceLocationArgument.id())
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 512))
                                .executes(ctx -> stock(ctx, readXyz(ctx),
                                        net.minecraft.commands.arguments.ResourceLocationArgument
                                                .getId(ctx, "item").toString(),
                                        IntegerArgumentType.getInteger(ctx, "count")))))));
    }

    /** {@code /vtest spawn <profession> <x> <y> <z> [count]} */
    private static LiteralArgumentBuilder<CommandSourceStack> spawnNode() {
        return Commands.literal("spawn").then(
                Commands.argument("profession", StringArgumentType.word())
                        .suggests(PROFESSIONS)
                        .then(xyz(z -> z
                                .executes(ctx -> spawn(ctx, readXyz(ctx), 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 32))
                                        .executes(ctx -> spawn(ctx, readXyz(ctx),
                                                IntegerArgumentType.getInteger(ctx, "count")))))));
    }

    /**
     * {@code /vtest visitor <kind> <x> <y> <z>} — force a visitor to arrive,
     * or {@code /vtest visitor arrivals <x> <y> <z> on|off} to switch the
     * village's own arrival scheduler back on.
     *
     * <p>{@code ScenarioBuilder} disables arrivals on every village it builds,
     * so that a stray nomad does not wander into somebody else's run. A
     * scenario that wants to test the scheduler itself has to ask for it.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> visitorNode() {
        return Commands.literal("visitor")
                .then(Commands.literal("recruit-farmer")
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        java.util.List.of("NOMAD", "MERCHANT"), b))
                                .then(xyz(z -> z.executes(ctx -> recruitFarmer(
                                        ctx, StringArgumentType.getString(ctx, "kind"),
                                        readXyz(ctx)))))))
                .then(Commands.literal("arrivals").then(xyz(z -> z
                        .then(Commands.literal("on")
                                .executes(ctx -> arrivals(ctx, readXyz(ctx), true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> arrivals(ctx, readXyz(ctx), false))))))
                .then(
                Commands.argument("kind", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                java.util.List.of("NOMAD", "MERCHANT"), b))
                        .then(xyz(z -> z.executes(ctx -> visitor(ctx,
                                StringArgumentType.getString(ctx, "kind"), readXyz(ctx))))));
    }

    /**
     * {@code /vtest inv <id> clear} and {@code /vtest inv <id> give <item> <count> [bound]}
     * ({@code bound}: stamped with the villager's village, as a tool the village made).
     *
     * <p>Puts a villager's pack into an exact state. Some states a village
     * reaches only after days of play — a lumberjack carrying eighty logs and
     * no axe took an eighteen-day playthrough to produce — and a regression
     * test for one of those has to be able to set it up in a line.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> invNode() {
        return Commands.literal("inv").then(
                Commands.argument("id", StringArgumentType.word())
                        .then(Commands.literal("clear")
                                .executes(ctx -> inventoryClear(ctx,
                                        StringArgumentType.getString(ctx, "id"))))
                        .then(Commands.literal("give")
                                .then(Commands.argument("item", StringArgumentType.string())
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 640))
                                                .executes(ctx -> inventoryGive(ctx,
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "item"),
                                                        IntegerArgumentType.getInteger(ctx, "count"), false))
                                                // Stamped with the villager's village: a tool the village
                                                // made, not the player's (PlayerGear), so books are written for it.
                                                .then(Commands.literal("bound")
                                                        .executes(ctx -> inventoryGive(ctx,
                                                                StringArgumentType.getString(ctx, "id"),
                                                                StringArgumentType.getString(ctx, "item"),
                                                                IntegerArgumentType.getInteger(ctx, "count"), true)))))));
    }

    /**
     * {@code /vtest recipe <id>} — does the recipe manager hold this recipe?
     *
     * <p>Vanilla's {@code /recipe give} needs a player, and reports a missing
     * player before it reports a missing recipe, so it cannot answer this on a
     * headless server. The Town Hall being craftable is the first step of the
     * whole survival progression; it deserves a direct question.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> recipeNode() {
        return Commands.literal("recipe").then(
                Commands.argument("id", StringArgumentType.string())
                        .executes(ctx -> recipe(ctx, StringArgumentType.getString(ctx, "id"))));
    }

    /**
     * {@code /vtest requirements <token>} — what a structure token asks for.
     *
     * <p>The same lines the game prints in chat when a token is hung. Chat
     * needs a player and a headless server has none, so this is how a scenario
     * checks that the advice exists and matches the constants the scanners
     * actually test against.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> requirementsNode() {
        return Commands.literal("requirements").then(
                Commands.argument("token", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                java.util.Arrays.stream(
                                        com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType.values())
                                        .map(Enum::name).toList(), b))
                        .executes(ctx -> requirements(ctx,
                                StringArgumentType.getString(ctx, "token"))));
    }

    /**
     * {@code /vtest arrival <x> <y> <z>} — where a visitor could walk in from.
     *
     * <p>An arrival that silently never happens looks exactly like a arrival
     * policy that was never wired. This says which of the two it is: how many
     * safe, graph-reachable sites the village has, and where the first few are.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> arrivalNode() {
        return Commands.literal("arrival").then(
                xyz(z -> z.executes(ctx -> arrival(ctx, readXyz(ctx)))));
    }

    /**
     * {@code /vtest shop offers <x> <y> <z>} and
     * {@code /vtest shop buy <x> <y> <z> <itemId> <emeralds>}.
     *
     * <p>The Mayor's shop is normally driven by a player clicking a screen, and
     * a headless server has no player to click with. {@code offers} reads the
     * priced catalogue straight off {@link com.alex.test.town.MayorShop};
     * {@code buy} runs a real purchase against a fake player stocked with
     * {@code emeralds}, which exercises the balance check, the currency
     * deduction and the hand-over on the same code path the screen uses.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> shopNode() {
        return Commands.literal("shop")
                .then(Commands.literal("offers")
                        .then(xyz(z -> z.executes(ctx -> shopOffers(ctx, readXyz(ctx), -1))
                                .then(Commands.argument("size", IntegerArgumentType.integer(0))
                                        .executes(ctx -> shopOffers(ctx, readXyz(ctx),
                                                IntegerArgumentType.getInteger(ctx, "size")))))))
                .then(Commands.literal("buy")
                        .then(xyz(z -> z.then(Commands.argument("itemId", StringArgumentType.string())
                                .then(Commands.argument("emeralds", IntegerArgumentType.integer(0))
                                        .executes(ctx -> shopBuy(ctx, readXyz(ctx),
                                                StringArgumentType.getString(ctx, "itemId"),
                                                IntegerArgumentType.getInteger(ctx, "emeralds"))))))));
    }

    /**
     * {@code /vtest creative_grab <x> <y> <z> <itemId>} — which village a creative
     * player standing at {@code pos} would be issued {@code itemId} by.
     *
     * <p>The real hook is a mixin on the creative-slot packet handler, which a
     * headless server never receives. This runs the same
     * {@link com.alex.test.village.tokens.CreativeItemBinding} the mixin calls, and
     * reports whether the mixin handler was merged into the packet listener.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> creativeGrabNode() {
        return Commands.literal("creative_grab")
                .then(xyz(z -> z.then(Commands.argument("itemId", StringArgumentType.string())
                        .executes(ctx -> creativeGrab(ctx, readXyz(ctx),
                                StringArgumentType.getString(ctx, "itemId"))))));
    }

    /** {@code /vtest raid ...}: start, step and inspect raids (see {@link com.alex.test.village.raid.RaidCommands}). */
    private static LiteralArgumentBuilder<CommandSourceStack> raidNode() {
        return com.alex.test.village.raid.RaidCommands.node();
    }

    /**
     * {@code /vtest village dormant <villageId> <true|false>} and
     * {@code /vtest village elapse <villageId> <ticks>}.
     *
     * <p>A headless mctest scenario cannot teleport a fake player away and wait
     * out real seconds/ticks to exercise dormancy detection, so these bypass
     * the player-proximity check and directly drive
     * {@link VillageManagerRegistry}'s dormant/reconciling state.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> villageNode() {
        return Commands.literal("village")
                .then(Commands.literal("dormant")
                        .then(Commands.argument("villageId", StringArgumentType.word())
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .executes(ctx -> villageDormant(ctx,
                                                StringArgumentType.getString(ctx, "villageId"),
                                                StringArgumentType.getString(ctx, "value"))))))
                .then(Commands.literal("elapse")
                        .then(Commands.argument("villageId", StringArgumentType.word())
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(0))
                                        .executes(ctx -> villageElapse(ctx,
                                                StringArgumentType.getString(ctx, "villageId"),
                                                IntegerArgumentType.getInteger(ctx, "ticks"))))))
                .then(Commands.literal("simulate_player")
                        .then(Commands.literal("clear")
                                .executes(TestCommands::villageSimulatePlayerClear))
                        .then(xyz(z -> z.executes(ctx -> villageSimulatePlayerAt(ctx, readXyz(ctx))))))
                .then(Commands.literal("force_throw")
                        .then(Commands.argument("villageId", StringArgumentType.word())
                                .executes(ctx -> villageForceThrow(ctx,
                                        StringArgumentType.getString(ctx, "villageId")))));
    }

    /**
     * {@code /vtest village simulate_player <x> <y> <z>} and
     * {@code /vtest village simulate_player clear}.
     *
     * <p>Unlike {@code /vtest village dormant}, which forces state directly,
     * this feeds a fake position into the *real* {@code
     * processDormancyChecks} detection logic (see {@link TestKit#setSimulatedPlayer})
     * so a scenario can exercise the actual proximity check, grace-miss
     * streak, and stagger a real player would trigger -- something a headless
     * server otherwise can never do, since it has no real player entities.
     */
    private static int villageSimulatePlayerAt(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        ServerLevel level = ctx.getSource().getLevel();
        TestKit.setSimulatedPlayer(pos, level.dimension());
        return ok(ctx, String.format("simulate_player at=%s dim=%s",
                pos.toShortString(), level.dimension().location()));
    }

    private static int villageSimulatePlayerClear(CommandContext<CommandSourceStack> ctx) {
        TestKit.clearSimulatedPlayer();
        return ok(ctx, "simulate_player cleared");
    }

    /**
     * {@code /vtest profession settings <id> <settingId> <value>}
     *
     * <p>Writes one setting on
     * whatever {@link SettingsSchema} the villager's profession has
     * registered (see {@code SettingsSchemaRegistry}) and triggers the same
     * live-apply path the Update packet does, bypassing the packet/menu
     * machinery a headless test server has no player to drive.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> professionNode() {
        return Commands.literal("profession").then(
                Commands.literal("settings").then(
                        Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("settingId", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> professionSettings(ctx,
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "settingId"),
                                                        StringArgumentType.getString(ctx, "value")))))));
    }

    private static int professionSettings(
            CommandContext<CommandSourceStack> ctx, String id, String settingId, String value) {
        MinecraftServer server = ctx.getSource().getServer();
        NpcEntity npc = findVillagerById(server, id);
        if (npc == null) return err(ctx, "no villager matched '" + id + "'");
        // The farmer keeps its own settings (FarmerSettings), not a schema.
        if (npc.getProfession() == Profession.FARMER) return farmerSettings(ctx, npc, id, settingId, value);

        SettingsSchema schema = SettingsSchemaRegistry.forProfession(npc.getProfession()).orElse(null);
        if (schema == null) {
            return err(ctx, "villager " + id + " (" + npc.getProfession().name()
                    + ") has no registered settings schema");
        }
        SettingsSchema.Row row = schema.findRow(settingId).orElse(null);
        if (row == null) {
            return err(ctx, "unknown settingId '" + settingId + "' for " + npc.getProfession().name());
        }

        ProfessionSettings settings = ProfessionSettings.of(npc, schema);
        String result;
        switch (row.kind()) {
            case MASTER_TOGGLE:
            case BEHAVIOR_TOGGLE:
                settings.setBool(settingId, Boolean.parseBoolean(value));
                result = Boolean.toString(settings.getBool(settingId));
                break;
            case NUMERIC_STEPPER:
                try {
                    settings.setInt(settingId, Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    return err(ctx, "value '" + value + "' is not an integer");
                }
                result = Integer.toString(settings.getInt(settingId));
                break;
            case CHOICE:
                int choice = -1;
                for (int i = 0; i < row.tierNames().size(); i++) {
                    if (row.tierNames().get(i).equalsIgnoreCase(value)) choice = i;
                }
                if (choice < 0) return err(ctx, "unknown choice '" + value + "'");
                settings.setInt(settingId, choice);
                result = row.tierNames().get(settings.getInt(settingId));
                break;
            case TIER_TOGGLES:
            case ALLOW_LIST: {
                int split = value.lastIndexOf(':');
                if (split < 0) {
                    return err(ctx, "value '" + value + "' must be '<entryId>:true' or '<entryId>:false'");
                }
                String entryId = value.substring(0, split);
                settings.setAllowed(settingId, entryId, Boolean.parseBoolean(value.substring(split + 1)));
                result = entryId + ":" + settings.isAllowed(settingId, entryId);
                break;
            }
            default: // includes HEADER, which findRow never returns
                return err(ctx, "unhandled row kind " + row.kind());
        }

        // As the settings screen does: the archer's and mage's shift, posts
        // (and the mage's monsters) live in their GuardSettings.
        if (schema == com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema.SCHEMA) {
            com.alex.test.village.villagers.archer.ArcherSettingsBridge.applyToGuardSettings(npc);
        }
        if (schema == com.alex.test.village.villagers.mage.settings.MageSettingsSchema.SCHEMA) {
            com.alex.test.village.villagers.mage.MageSettingsBridge.applyToGuardSettings(npc);
        }
        ProfessionSettingsChangeHandler.onSettingsChanged(npc);
        return ok(ctx, String.format("villager=%s settingId=%s value=%s", id, settingId, result));
    }

    /**
     * A farmer's settings, as its settings screen sets them: {@code works true|false}
     * ({@code work_enabled} too); {@code plant_mode <item>:leave|replant|cut_only};
     * {@code allowed_crops <item>:true|false}, the old allow-list's words kept for the
     * scenarios: true = Replant, false = Leave (neither planted nor harvested).
     */
    private static int farmerSettings(CommandContext<CommandSourceStack> ctx, NpcEntity npc, String id,
                                      String settingId, String value) {
        var settings = com.alex.test.village.villagers.farmer.settings.FarmerSettings.of(npc);
        String result;
        if (settingId.equals("works") || settingId.equals("work_enabled")) {
            settings.setWorks(Boolean.parseBoolean(value));
            result = Boolean.toString(settings.works());
        } else if (settingId.equals("allowed_crops") || settingId.equals("plant_mode")) {
            int split = value.lastIndexOf(':');
            if (split < 0) return err(ctx, "value '" + value + "' must be '<item>:<mode>'");
            net.minecraft.resources.ResourceLocation itemId =
                    net.minecraft.resources.ResourceLocation.tryParse(value.substring(0, split));
            net.minecraft.world.item.Item item = itemId == null ? null
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
            if (item == null
                    || !com.alex.test.village.villagers.farmer.settings.FarmerPlantingResources.isSupported(item)) {
                return err(ctx, "no crop is planted from '" + value.substring(0, split) + "'");
            }
            int mode = switch (value.substring(split + 1).toLowerCase(java.util.Locale.ROOT)) {
                case "true", "replant" -> com.alex.test.village.villagers.farmer.settings.FarmerSettings.PLANT_REPLANT;
                case "false", "leave" -> com.alex.test.village.villagers.farmer.settings.FarmerSettings.PLANT_LEAVE;
                case "cut_only" -> com.alex.test.village.villagers.farmer.settings.FarmerSettings.PLANT_CUT_ONLY;
                default -> -1;
            };
            if (mode < 0) return err(ctx, "mode must be true|false|leave|replant|cut_only");
            settings.setPlantMode(item, mode);
            int now = settings.plantMode(item);
            result = itemId + ":" + switch (now) {
                case com.alex.test.village.villagers.farmer.settings.FarmerSettings.PLANT_LEAVE -> "false (leave)";
                case com.alex.test.village.villagers.farmer.settings.FarmerSettings.PLANT_CUT_ONLY -> "cut_only";
                default -> "true (replant)";
            };
        } else {
            return err(ctx, "unknown farmer setting '" + settingId + "' (works, plant_mode, allowed_crops)");
        }
        com.alex.test.village.villagers.farmer.settings.FarmerSettingsChangeHandler.onSettingsChanged(npc);
        return ok(ctx, String.format("villager=%s settingId=%s value=%s", id, settingId, result));
    }

    private static int visitor(CommandContext<CommandSourceStack> ctx, String kindName,
                               BlockPos pos) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null) return err(ctx, "no village contains " + pos.toShortString());

        if ("NOMAD".equalsIgnoreCase(kindName)) {
            com.alex.test.entity.visitor.NomadEntity spawned =
                    mgr.nomads().spawnNomad(level, pos);
            if (spawned == null) return err(ctx, "could not create nomad entity");
            return ok(ctx, String.format("nomad id=%s at=%s",
                    spawned.getUUID().toString().substring(0, 8), pos.toShortString()));
        }
        if (!"MERCHANT".equalsIgnoreCase(kindName)) {
            return err(ctx, "unknown traveller kind '" + kindName + "'");
        }
        com.alex.test.entity.visitor.VisitorEntity spawned =
                mgr.visitors().spawnVisitor(level, pos);
        if (spawned == null) return err(ctx, "could not create merchant visitor entity");
        return ok(ctx, String.format("visitor kind=MERCHANT id=%s at=%s",
                spawned.getUUID().toString().substring(0, 8), pos.toShortString()));
    }

    /** Invokes the real entity interaction path with a fake player's farmer token. */
    private static int recruitFarmer(CommandContext<CommandSourceStack> ctx, String kindName,
                                     BlockPos pos) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null) return err(ctx, "no village contains " + pos.toShortString());

        net.minecraft.world.entity.Mob traveller;
        if ("NOMAD".equalsIgnoreCase(kindName)) {
            traveller = mgr.nomads().spawnNomad(level, pos);
        } else if ("MERCHANT".equalsIgnoreCase(kindName)) {
            traveller = mgr.visitors().spawnVisitor(level, pos);
        } else {
            return err(ctx, "unknown traveller kind '" + kindName + "'");
        }
        if (traveller == null) return err(ctx, "could not create traveller entity");

        NpcEntity travellerNpc = (NpcEntity) traveller;
        boolean memberBefore = travellerNpc.isVillageMember();
        boolean linkBefore = travellerNpc.getVillageLink() != null;
        ServerPlayer recruiter = net.neoforged.neoforge.common.util.FakePlayerFactory
                .get(level, TEST_RECRUITER);
        recruiter.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(com.alex.test.ModItems.FARMER_TOKEN.get()));
        int membersBefore = mgr.getVillagers().size();
        traveller.interact(recruiter, net.minecraft.world.InteractionHand.MAIN_HAND);
        int tokenRemaining = recruiter.getMainHandItem().getCount();
        recruiter.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                net.minecraft.world.item.ItemStack.EMPTY);
        return ok(ctx, String.format(
                "kind=%s memberBefore=%s linkBefore=%s tokenRemaining=%d "
                        + "members=%d->%d travellerAlive=%s",
                kindName.toUpperCase(java.util.Locale.ROOT), memberBefore, linkBefore, tokenRemaining,
                membersBefore, mgr.getVillagers().size(), traveller.isAlive()));
    }

    /**
     * Quotes the shop. {@code sizeOverride >= 0} prices the catalogue for a
     * hypothetical village of that size instead of this one — the only way to
     * ask "what would a village founded one second ago charge?" without
     * founding one.
     */
    private static int inventoryClear(CommandContext<CommandSourceStack> ctx, String id) {
        NpcEntity npc = findVillagerById(ctx.getSource().getServer(), id);
        if (npc == null) return err(ctx, "no villager matched '" + id + "'");
        for (int slot = 0;
             slot < com.alex.test.entity.inventory.InventoryManager.SIZE;
             slot++) {
            npc.getInventory().setSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
        }
        return ok(ctx, "villager=" + id + " inventory=cleared");
    }

    private static int inventoryGive(CommandContext<CommandSourceStack> ctx, String id,
                                     String itemId, int count, boolean bound) {
        NpcEntity npc = findVillagerById(ctx.getSource().getServer(), id);
        if (npc == null) return err(ctx, "no villager matched '" + id + "'");
        VillageManager village = null;
        if (bound) {
            village = VillageManagerRegistry.get(ctx.getSource().getServer())
                    .findVillageAt((ServerLevel) npc.level(), npc.blockPosition());
            if (village == null) return err(ctx, "villager '" + id + "' is in no village to bind to");
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (key == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)) {
            return err(ctx, "unknown item '" + itemId + "'");
        }
        net.minecraft.world.item.Item item =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key);

        int given = 0;
        while (given < count) {
            int batch = Math.min(count - given, item.getDefaultMaxStackSize());
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item, batch);
            if (village != null) com.alex.test.village.tokens.VillageTokenBinding.bind(stack, village);
            if (!npc.getInventory().addItem(stack)) {
                break;
            }
            given += batch;
        }
        return ok(ctx, String.format("villager=%s gave=%d of %s bound=%s", id, given, itemId, bound));
    }

    private static int recipe(CommandContext<CommandSourceStack> ctx, String id) {
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.resources.ResourceLocation.tryParse(id);
        if (key == null) return err(ctx, "'" + id + "' is not a resource location");

        var holder = ctx.getSource().getServer().getRecipeManager().byKey(key);
        if (holder.isEmpty()) return ok(ctx, "recipe=" + id + " found=false");

        net.minecraft.world.item.ItemStack result = holder.get().value()
                .getResultItem(ctx.getSource().getServer().registryAccess());
        return ok(ctx, String.format("recipe=%s found=true type=%s result=%s x%d",
                id, holder.get().value().getType(),
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(result.getItem()),
                result.getCount()));
    }

    private static int requirements(CommandContext<CommandSourceStack> ctx, String tokenName) {
        com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType type;
        try {
            type = com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType
                    .valueOf(tokenName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return err(ctx, "unknown token '" + tokenName + "'");
        }
        List<String> briefing =
                com.alex.test.village.building.BuildingRequirements.briefing(type);
        return ok(ctx, "token=" + type.name()
                + " name=" + com.alex.test.village.building.BuildingRequirements.displayName(type)
                + " lines=" + briefing.size()
                + " | " + String.join(" | ", briefing));
    }

    private static int arrival(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null) return err(ctx, "no village contains " + pos.toShortString());

        List<BlockPos> sites = com.alex.test.village.visitors.NomadArrivalSites
                .candidates(level, mgr, level.random, java.util.Set.of());
        StringBuilder at = new StringBuilder();
        for (int i = 0; i < Math.min(4, sites.size()); i++) {
            if (i > 0) at.append(';');
            BlockPos site = sites.get(i);
            at.append(site.getX()).append(',').append(site.getY()).append(',').append(site.getZ());
        }
        return ok(ctx, String.format("sites=%d pending=%d visitors=%d at=%s",
                sites.size(), mgr.nomads().pendingWelcomeCount(),
                mgr.nomads().currentNomads(level).size()
                        + mgr.visitors().currentVisitors(level).size(), at));
    }

    private static int shopOffers(CommandContext<CommandSourceStack> ctx, BlockPos pos,
                                  int sizeOverride) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null) return err(ctx, "no village contains " + pos.toShortString());

        int size = sizeOverride >= 0
                ? sizeOverride : com.alex.test.town.MayorShop.villageSize(mgr);
        StringBuilder out = new StringBuilder(String.format(
                "size=%d mult=%.2f", size, com.alex.test.town.MayorShop.multiplierAt(size)));
        for (com.alex.test.town.TradeOfferEntry offer
                : com.alex.test.town.MayorShop.offers(mgr, size)) {
            out.append(' ')
                    .append(offer.itemId().substring(offer.itemId().indexOf(':') + 1))
                    .append('=').append(offer.price());
        }
        return ok(ctx, out.toString());
    }

    private static int shopBuy(CommandContext<CommandSourceStack> ctx, BlockPos pos,
                               String itemId, int emeralds) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null) return err(ctx, "no village contains " + pos.toShortString());

        ServerPlayer buyer = net.neoforged.neoforge.common.util.FakePlayerFactory
                .get(level, TEST_BUYER);
        buyer.getInventory().clearContent();
        if (emeralds > 0) {
            // The Mayor only takes this village's emeralds, so stamp them.
            net.minecraft.world.item.ItemStack money = new net.minecraft.world.item.ItemStack(
                    com.alex.test.town.MayorShop.CURRENCY, emeralds);
            com.alex.test.village.tokens.VillageTokenBinding.bind(money, mgr);
            buyer.getInventory().add(money);
        }

        com.alex.test.town.MayorShop.Purchase purchase =
                com.alex.test.town.MayorShop.buy(buyer, mgr, itemId);
        int bought = 0;
        for (int slot = 0; slot < buyer.getInventory().getContainerSize(); slot++) {
            net.minecraft.world.item.ItemStack stack = buyer.getInventory().getItem(slot);
            if (!stack.isEmpty()
                    && net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString().equals(itemId)) {
                bought += stack.getCount();
            }
        }
        int leftover = com.alex.test.town.MayorShop.villageEmeralds(buyer.getInventory(), mgr);
        buyer.getInventory().clearContent();

        return ok(ctx, String.format(
                "success=%b price=%d emeraldsLeft=%d received=%d msg=%s",
                purchase.success(), purchase.price(), leftover, bought, purchase.message()));
    }

    private static int creativeGrab(CommandContext<CommandSourceStack> ctx, BlockPos pos, String itemId) {
        ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id))
            return err(ctx, "unknown item " + itemId);
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id));

        ServerPlayer player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, TEST_CREATIVE);
        player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        try {
            com.alex.test.village.tokens.CreativeItemBinding.bindToNearestVillage(player, stack);
        } finally {
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        }

        boolean mixin = java.util.Arrays.stream(
                        net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().contains("bindCreativeStack"));
        com.alex.test.village.tokens.VillageTokenBinding binding =
                com.alex.test.village.tokens.VillageTokenBinding.read(stack);
        return ok(ctx, String.format("bound=%s mixin=%b",
                binding == null ? "none" : StateDump.shortId(binding.townHallId()), mixin));
    }

    private static int villageDormant(
            CommandContext<CommandSourceStack> ctx, String villageIdStr, String valueStr) {
        MinecraftServer server = ctx.getSource().getServer();
        VillageManager mgr = findVillageById(server, villageIdStr);
        if (mgr == null) return err(ctx, "no village matched '" + villageIdStr + "'");
        if (!valueStr.equalsIgnoreCase("true") && !valueStr.equalsIgnoreCase("false")) {
            return err(ctx, "value '" + valueStr + "' must be 'true' or 'false'");
        }
        boolean dormant = Boolean.parseBoolean(valueStr);

        VillageManagerRegistry registry = VillageManagerRegistry.get(server);
        ServerLevel level = mgr.getLevel() != null ? mgr.getLevel() : ctx.getSource().getLevel();
        registry.debugSetDormant(level, mgr.getVillageId(), dormant);
        return ok(ctx, String.format("village=%s dormant=%b reconciling=%b",
                StateDump.shortId(mgr.getVillageId()),
                registry.isVillageDormant(mgr.getVillageId()),
                registry.isVillageReconciling(mgr.getVillageId())));
    }

    private static int villageElapse(
            CommandContext<CommandSourceStack> ctx, String villageIdStr, int ticks) {
        MinecraftServer server = ctx.getSource().getServer();
        VillageManager mgr = findVillageById(server, villageIdStr);
        if (mgr == null) return err(ctx, "no village matched '" + villageIdStr + "'");

        VillageManagerRegistry registry = VillageManagerRegistry.get(server);
        if (!registry.debugElapseDormancy(mgr.getVillageId(), ticks)) {
            return err(ctx, "village " + villageIdStr
                    + " has no dormantSinceGameTime to elapse (never went dormant)");
        }
        return ok(ctx, String.format("village=%s dormantSinceGameTime=%d elapsedTicks=%d",
                StateDump.shortId(mgr.getVillageId()),
                registry.getDormantSinceGameTime(mgr.getVillageId()), ticks));
    }

    /**
     * {@code /vtest village force_throw <villageId>} -- arms
     * {@link com.alex.test.testkit.TestKit#armForcedReconciliationThrow} so
     * this village's very next catch-up ({@code AwayRun}'s first step, READ)
     * throws immediately, for testing
     * {@code VillageDormancy.processDormancyChecks}'s catch-and-recover
     * behavior.
     */
    private static int villageForceThrow(CommandContext<CommandSourceStack> ctx, String villageIdStr) {
        MinecraftServer server = ctx.getSource().getServer();
        VillageManager mgr = findVillageById(server, villageIdStr);
        if (mgr == null) return err(ctx, "no village matched '" + villageIdStr + "'");
        com.alex.test.testkit.TestKit.armForcedReconciliationThrow(mgr.getVillageId());
        return ok(ctx, "village=" + StateDump.shortId(mgr.getVillageId()) + " force_throw armed");
    }

    /** Matches a village by its full UUID or the 8-char short id used everywhere else in this command tree. */
    @Nullable
    private static VillageManager findVillageById(MinecraftServer server, String id) {
        for (VillageManager mgr : VillageManagerRegistry.get(server).all()) {
            UUID villageId = mgr.getVillageId();
            if (StateDump.shortId(villageId).equalsIgnoreCase(id)
                    || villageId.toString().equalsIgnoreCase(id)) {
                return mgr;
            }
        }
        return null;
    }

    /** {@code /vtest map <x> <y> <z> <radius> [name]} */
    private static LiteralArgumentBuilder<CommandSourceStack> mapNode() {
        return Commands.literal("map").then(xyz(z -> z
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 80))
                        .executes(ctx -> map(ctx, "map.txt"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> map(ctx,
                                        StringArgumentType.getString(ctx, "name") + ".txt"))))));
    }

    /**
     * Wraps the {@code <x> <y> <z>} chain around a terminal node.
     *
     * <p>{@code configureZ} receives the innermost {@code z} argument, so
     * {@code executes} and any optional trailing argument land at the end of the
     * chain. Returning a bare builder instead would invite callers to attach
     * them to {@code x}, which parses as a completely different command.
     */
    private static RequiredArgumentBuilder<CommandSourceStack, Integer> xyz(
            UnaryOperator<RequiredArgumentBuilder<CommandSourceStack, Integer>> configureZ) {
        return Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(configureZ.apply(
                                Commands.argument("z", IntegerArgumentType.integer()))));
    }

    // =========================================================================
    // Simple commands
    // =========================================================================

    private static int ping(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ServerLevel overworld = server.overworld();
        int villages = VillageManagerRegistry.get(server).all().size();
        return ok(ctx, String.format(
                "vtest ready | gameTime=%d dayTime=%d headless=%b villages=%d players=%d",
                overworld.getGameTime(), overworld.getDayTime(), TestKit.headless(),
                villages, server.getPlayerList().getPlayerCount()));
    }

    private static int setHeadless(CommandContext<CommandSourceStack> ctx, boolean value) {
        TestKit.setHeadless(value);
        return ok(ctx, "headless=" + value);
    }

    // =========================================================================
    // Scenario construction
    // =========================================================================

    private static int scenarioVillage(CommandContext<CommandSourceStack> ctx,
                                       BlockPos pos, int trees) {
        ServerLevel level = ctx.getSource().getLevel();
        ScenarioBuilder.VillageResult result =
                ScenarioBuilder.buildVillage(level, pos, pos.getY(), trees);

        if (result.manager() == null) {
            return err(ctx, "village not created — Town Hall room failed validation at "
                    + result.townHallDoor().toShortString());
        }
        long validBuildings = result.manager().getBuildings().stream()
                .filter(Building::isValid).count();
        return ok(ctx, String.format(
                "village=%s door=%s buildings=%d/%d trees=%d blocks=%d",
                StateDump.shortId(result.manager().getVillageId()),
                result.townHallDoor().toShortString(),
                validBuildings, result.manager().getBuildings().size(),
                result.treeBases().size(), result.blocksChanged()));
    }

    /**
     * Builds the showcase village and reports a directory of it.
     *
     * <p>The reply breaks this file's "replies stay short" rule on purpose, and
     * only here: the one thing a player needs after running this is to know
     * where everybody went, and a village this wide is not something you find
     * by walking. The machine-readable {@code OK} receipt is still the first
     * line, so a harness can branch on it and ignore the rest.
     */
    private static int scenarioShowcase(CommandContext<CommandSourceStack> ctx,
                                        @Nullable BlockPos pos, int skill) {
        CommandSourceStack source = ctx.getSource();
        if (pos == null) {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                return err(ctx, "no player to place the village on — "
                        + "pass explicit coordinates: /vtest scenario showcase <x> <y> <z>");
            }
            pos = player.blockPosition();
        }

        ServerLevel level = source.getLevel();
        ShowcaseVillage.Result result = ShowcaseVillage.build(level, pos, skill);
        if (result.manager() == null) {
            return err(ctx, "village not created — Town Hall room failed validation at "
                    + result.townHallDoor().toShortString());
        }

        ok(ctx, String.format(
                "showcase village=%s door=%s buildings=%d/%d villagers=%d trees=%d "
                        + "stocked=%d skill=%d blocks=%d forced=%d chunks",
                StateDump.shortId(result.manager().getVillageId()),
                result.townHallDoor().toShortString(),
                result.validBuildings(), result.buildings(), result.villagers(),
                result.trees(), result.stockedItems(), skill,
                result.blocksChanged(), result.forcedChunks()));

        for (var landmark : result.landmarks().entrySet()) {
            source.sendSuccess(() -> Component.literal("  " + landmark.getKey()
                    + " @ " + landmark.getValue().toShortString()), false);
        }
        for (String line : result.roster()) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        source.sendSuccess(() -> Component.literal(
                "  visitors are off; /vtest visitor arrivals "
                        + result.townHallDoor().toShortString().replace(",", "")
                        + " on to let traders in"), false);
        return 1;
    }

    private static int scenarioLevel(CommandContext<CommandSourceStack> ctx,
                                     BlockPos pos, int radius) {
        int changed = ScenarioBuilder.levelSite(
                ctx.getSource().getLevel(), pos, radius, pos.getY());
        return ok(ctx, "levelled radius=" + radius + " blocks=" + changed);
    }

    /**
     * Bonemeals a block the way a player's right-click does.
     *
     * <p>Goes through {@code BoneMealItem.applyBonemeal}, which is the entry
     * point for both a player and a dispenser and the only one that fires
     * {@code BonemealEvent}. That matters: vanilla's bonemeal path calls
     * {@code CropBlock.growCrops} directly and fires no
     * {@code CropGrowEvent.Post}, so a crop forced to maturity this way is
     * invisible to the village unless the mod listens for the bonemeal itself.
     * A {@code /setblock} of an age-7 crop would not exercise any of that.
     */
    private static int bonemeal(CommandContext<CommandSourceStack> ctx, BlockPos pos, int times) {
        ServerLevel level = ctx.getSource().getLevel();
        int applied = 0;
        for (int i = 0; i < times; i++) {
            if (net.minecraft.world.item.BoneMealItem.applyBonemeal(
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BONE_MEAL),
                    level, pos, null)) {
                applied++;
            }
        }
        BlockState state = level.getBlockState(pos);
        int age = state.getBlock() instanceof CropBlock crop ? crop.getAge(state) : -1;
        int maxAge = state.getBlock() instanceof CropBlock crop2 ? crop2.getMaxAge() : -1;
        return ok(ctx, String.format("bonemeal %s applied=%d block=%s age=%d/%d",
                pos.toShortString(), applied,
                net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(state.getBlock()), age, maxAge));
    }

    private static int arrivals(CommandContext<CommandSourceStack> ctx, BlockPos pos,
                                boolean enabled) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null) return err(ctx, "no village contains " + pos.toShortString());
        mgr.visitors().setArrivalsEnabled(enabled);
        mgr.nomads().setArrivalsEnabled(enabled);
        if (enabled) mgr.nomads().scheduleFoundingNomads(level);
        return ok(ctx, "village=" + StateDump.shortId(mgr.getVillageId())
                + " arrivals=" + enabled
                + " pendingWelcome=" + mgr.nomads().pendingWelcomeCount());
    }

    private static int scenarioTree(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        ServerLevel level = ctx.getSource().getLevel();
        int placed = ScenarioBuilder.buildOak(level, pos);

        // Register it the way a grown sapling would, so the KnowledgeBase picks
        // it up without waiting for a full village re-initialisation.
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr != null) mgr.routeTreeGrow(pos);

        return ok(ctx, "tree at " + pos.toShortString() + " blocks=" + placed
                + (mgr == null ? " (outside any village)" : " village=" + StateDump.shortId(mgr.getVillageId())));
    }

    private static int scenarioMine(CommandContext<CommandSourceStack> ctx,
                                    BlockPos pos, String facingName) {
        ServerLevel level = ctx.getSource().getLevel();
        Direction facing = switch (facingName.toLowerCase(java.util.Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
        if (facing == null) return err(ctx, "facing must be north|south|east|west");

        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null) return err(ctx, "no village contains " + pos.toShortString());

        ScenarioBuilder.Room outcrop = ScenarioBuilder.buildOutcrop(level, pos, facing);
        net.minecraft.world.item.ItemStack mineToken =
                new net.minecraft.world.item.ItemStack(com.alex.test.ModItems.MINE.get());
        // Tokens only register for the village they are bound to.
        com.alex.test.village.tokens.VillageTokenBinding.bind(mineToken, mgr);
        outcrop.frame().setItem(mineToken, false);
        mgr.rebuildNow(level);
        Building built = buildingAt(mgr, outcrop.doorPos());
        return ok(ctx, String.format("mine anchor=%s facing=%s registered=%b valid=%b rock=%d",
                outcrop.doorPos().toShortString(), facing.getName(), built != null,
                built != null && built.isValid(),
                built == null ? 0 : built.getFunctionalBlocks().size()));
    }

    private static int scenarioFarm(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null) return err(ctx, "no village contains " + pos.toShortString());
        BlockPos gate = ScenarioBuilder.buildFarm(level, mgr, pos.getX(), pos.getY(), pos.getZ());
        return ok(ctx, String.format("field gate=%s tokenless=true", gate.toShortString()));
    }

    private static int scenarioRanch(CommandContext<CommandSourceStack> ctx, BlockPos pos,
                                     String animal, int count) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null) return err(ctx, "no village contains " + pos.toShortString());
        EntityType<?> type = switch (animal.toLowerCase(java.util.Locale.ROOT)) {
            case "cow" -> EntityType.COW;
            case "sheep" -> EntityType.SHEEP;
            case "pig" -> EntityType.PIG;
            case "chicken" -> EntityType.CHICKEN;
            default -> null;
        };
        if (type == null) return err(ctx, "unknown animal '" + animal + "'");
        BlockPos gate = ScenarioBuilder.buildRanch(
                level, mgr, pos.getX(), pos.getY(), pos.getZ(), type, count);
        Building built = buildingAt(mgr, gate);
        return ok(ctx, String.format("ranch gate=%s registered=%b valid=%b animals=%d",
                gate.toShortString(), built != null,
                built != null && built.isValid(), count));
    }

    private static int scenarioRoom(CommandContext<CommandSourceStack> ctx,
                                    String type, BlockPos pos) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null) return err(ctx, "no village contains " + pos.toShortString());

        ScenarioBuilder.RoomFurniture furniture;
        net.minecraft.world.item.Item token;
        switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "kitchen" -> { furniture = ScenarioBuilder.RoomFurniture.KITCHEN;
                                token = com.alex.test.ModItems.KITCHEN.get(); }
            case "butchery" -> { furniture = ScenarioBuilder.RoomFurniture.BUTCHERY;
                                 token = com.alex.test.ModItems.BUTCHERY.get(); }
            case "forge" -> { furniture = ScenarioBuilder.RoomFurniture.FORGE;
                              token = com.alex.test.ModItems.FORGE.get(); }
            // Deliberately still listed, and deliberately an error: every
            // scenario that used to build a mine room called this, and a
            // silent "unknown room type" would have read as a typo rather
            // than as the model changing under it.
            case "mine" -> { return err(ctx, "a mine is no longer a room — "
                    + "use /vtest scenario mine <x> <y> <z>"); }
            case "tavern" -> { furniture = ScenarioBuilder.RoomFurniture.TAVERN;
                               token = com.alex.test.ModItems.TAVERN.get(); }
            case "library" -> { furniture = ScenarioBuilder.RoomFurniture.LIBRARY;
                                token = com.alex.test.ModItems.LIBRARY.get(); }
            case "school" -> { furniture = ScenarioBuilder.RoomFurniture.SCHOOL;
                               token = com.alex.test.ModItems.SCHOOL.get(); }
            case "stall" -> { furniture = ScenarioBuilder.RoomFurniture.STALL;
                              token = com.alex.test.ModItems.STALL.get(); }
            case "barracks" -> { furniture = ScenarioBuilder.RoomFurniture.BARRACKS;
                                 token = com.alex.test.ModItems.BARRACKS.get(); }
            // Housing and a second larder. A village that cannot add beds
            // cannot grow past four people, whatever else it does right, so an
            // economy bench has to be able to build them by the dozen.
            case "2_bed_house" -> { furniture = ScenarioBuilder.RoomFurniture.BEDS;
                                     token = com.alex.test.ModItems.TWO_BED_HOUSE.get(); }
            case "4_bed_house" -> { furniture = ScenarioBuilder.RoomFurniture.BEDS_4;
                                     token = com.alex.test.ModItems.FOUR_BED_HOUSE.get(); }
            case "6_bed_house" -> { furniture = ScenarioBuilder.RoomFurniture.BEDS_6;
                                     token = com.alex.test.ModItems.SIX_BED_HOUSE.get(); }
            case "storage" -> { furniture = ScenarioBuilder.RoomFurniture.STORAGE;
                                token = com.alex.test.ModItems.STORAGE_ROOM.get(); }
            default -> { return err(ctx, "unknown room type '" + type + "'"); }
        }

        ScenarioBuilder.Room room = ScenarioBuilder.buildRoom(
                level, pos.getX(), pos.getY(), pos.getZ(), furniture);
        net.minecraft.world.item.ItemStack tokenStack = new net.minecraft.world.item.ItemStack(token);
        // Room tokens only register for the village they are bound to.
        com.alex.test.village.tokens.VillageTokenBinding.bind(tokenStack, mgr);
        room.frame().setItem(tokenStack, false);
        mgr.rebuildNow(level);
        Building built = buildingAt(mgr, room.doorPos());
        return ok(ctx, String.format("%s door=%s registered=%b valid=%b",
                type, room.doorPos().toShortString(), built != null,
                built != null && built.isValid()));
    }

    /**
     * {@code /vtest ring_bell <x> <y> <z>}: a right click on the bell at that
     * spot, sent through the game's own click event by a stand-in player, so
     * every listener a real ring reaches is reached (the village alarm and its
     * reveal of hostile mobs). A headless server has no hand to ring with.
     */
    private static int ringBell(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        ServerLevel level = ctx.getSource().getLevel();
        if (!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.BellBlock)) {
            return err(ctx, "no bell at " + pos.toShortString());
        }
        net.neoforged.neoforge.common.util.FakePlayer ringer =
                net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        ringer.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1.5);
        net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.SOUTH, pos, false);
        net.neoforged.neoforge.common.CommonHooks.onRightClickBlock(
                ringer, net.minecraft.world.InteractionHand.MAIN_HAND, pos, hit);
        return ok(ctx, "rang bell at " + pos.toShortString());
    }

    private static int stock(CommandContext<CommandSourceStack> ctx, BlockPos pos,
                             String itemId, int count) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null) return err(ctx, "no village contains " + pos.toShortString());

        net.minecraft.resources.ResourceLocation id =
                net.minecraft.resources.ResourceLocation.tryParse(itemId);
        net.minecraft.world.item.Item item = id == null ? null
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) return err(ctx, "unknown item '" + itemId + "'");

        int placed = 0;
        int remaining = count;
        while (remaining > 0) {
            int batch = Math.min(remaining, item.getDefaultMaxStackSize());
            net.minecraft.world.item.ItemStack issued = new net.minecraft.world.item.ItemStack(item, batch);
            com.alex.test.village.tokens.VillageTokenBinding.bind(issued, mgr);
            int stored = ScenarioBuilder.stockStorage(level, mgr, issued);
            if (stored <= 0) break;
            placed += stored;
            remaining -= stored;
        }
        if (placed == 0) return err(ctx, "no valid storage accepted " + itemId);
        return ok(ctx, "stocked " + placed + " " + itemId);
    }

    /** Nav-graph probe: is this feet position a node, and what's nearby? */
    private static int nodeProbe(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null || mgr.getGraph() == null) return err(ctx, "no village/graph here");
        var graph = mgr.getGraph();
        var at = graph.nodeAt(pos.getX(), pos.getY(), pos.getZ());
        var near = graph.nearest(pos, 3);
        // Neighbours are the decisive datum when asking "why does this node
        // exist at all": the graph is a flood fill, so every node was reached
        // from some neighbour, and naming them says which direction the fill
        // came from. Without this the only way to ask was to reason about
        // NavGraph.build from source, which has already failed twice.
        StringBuilder nbrs = new StringBuilder();
        if (at != null) {
            for (var nb : at.neighbours()) {
                if (nbrs.length() > 0) nbrs.append(' ');
                nbrs.append(nb.feetPos().toShortString().replace(" ", ""));
            }
        }
        // Climb cost 2B: which sides of a stair floor a body steps onto as a half step.
        StringBuilder halfSteps = new StringBuilder();
        if (at != null) {
            if (at.halfStepToward(1, 0)) halfSteps.append(" +x");
            if (at.halfStepToward(-1, 0)) halfSteps.append(" -x");
            if (at.halfStepToward(0, 1)) halfSteps.append(" +z");
            if (at.halfStepToward(0, -1)) halfSteps.append(" -z");
        }
        return ok(ctx, String.format(java.util.Locale.ROOT,
                "nodeAt=%s nearest=%s degree=%s neighbors=[%s] stand=%s halfSteps=[%s] "
                        + "passableFeet=%b passableHead=%b block=%s below=%s",
                at == null ? "null" : at.feetPos().toShortString(),
                near == null ? "null" : near.feetPos().toShortString(),
                at == null ? "-" : String.valueOf(at.degree()),
                nbrs,
                at == null ? "-" : String.format(java.util.Locale.ROOT, "%.2f", at.standY),
                halfSteps.toString().trim(),
                com.alex.test.village.NavGraph.isPassable(level, pos.getX(), pos.getY(), pos.getZ()),
                com.alex.test.village.NavGraph.isPassable(level, pos.getX(), pos.getY() + 1, pos.getZ()),
                level.getBlockState(pos).getBlock().getName().getString(),
                level.getBlockState(pos.below()).getBlock().getName().getString()));
    }

    /** A* probe between two positions using the village graph. */
    private static int pathProbe(CommandContext<CommandSourceStack> ctx) {
        BlockPos from = new BlockPos(
                IntegerArgumentType.getInteger(ctx, "x1"),
                IntegerArgumentType.getInteger(ctx, "y1"),
                IntegerArgumentType.getInteger(ctx, "z1"));
        BlockPos to = new BlockPos(
                IntegerArgumentType.getInteger(ctx, "x2"),
                IntegerArgumentType.getInteger(ctx, "y2"),
                IntegerArgumentType.getInteger(ctx, "z2"));
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, from);
        if (mgr == null || mgr.getGraph() == null) return err(ctx, "no village/graph at from");
        com.alex.test.village.AStarPathfinder.Route route = com.alex.test.village.AStarPathfinder.findRoute(
                mgr.getGraph(), from, to, true, java.util.Set.of(), java.util.Set.of());
        if (route == null) return ok(ctx, "path=NULL from=" + from.toShortString()
                + " to=" + to.toShortString());
        java.util.List<BlockPos> path = route.waypoints();
        String preview = path.stream().limit(8)
                .map(BlockPos::toShortString)
                .reduce((a, b) -> a + " | " + b).orElse("");
        return ok(ctx, "path len=" + path.size() + " [" + preview
                + (path.size() > 8 ? " ..." : "") + "]" + routePrice(route.tiles()));
    }

    /**
     * Climb cost 2D: a route's price at the prices now set and what the planner expects on it
     * (full-block climbs, stair steps walked, drops off ledges, ladder entries).
     */
    private static String routePrice(java.util.List<com.alex.test.village.NavNode> tiles) {
        int climbs = 0, stairSteps = 0, drops = 0, ladders = 0;
        for (int i = 1; i < tiles.size(); i++) {
            com.alex.test.village.NavNode a = tiles.get(i - 1), b = tiles.get(i);
            if (b.climb && !a.climb) ladders++;
            if (a.climb || b.climb || a.waterDrop || b.waterDrop
                    || Math.abs(b.x - a.x) + Math.abs(b.z - a.z) != 1) continue;
            switch (com.alex.test.village.RoutePriceCheck.call(a, b)) {
                case CLIMB -> climbs++;
                case WALK_UP, WALK_DOWN -> stairSteps++;
                case DROP -> drops++;
                default -> { }
            }
        }
        boolean priced = com.alex.test.village.WalkFeel.climbCost;
        return String.format(java.util.Locale.ROOT,
                " tiles=%d price=%d (%s) climbs=%d stairSteps=%d drops=%d ladders=%d",
                tiles.size(), com.alex.test.village.AStarPathfinder.price(tiles),
                priced ? "tenths of a tile" : "old prices", climbs, stairSteps, drops, ladders);
    }

    /**
     * {@code /vtest move <id> <x> <y> <z>} — drives one villager to a destination
     * over {@link VillagerModel#requestMoveToDetailed}, the same bypass-vanilla-nav
     * path guard/archer combat AI uses for precise repositioning.
     *
     * <p>Exists so a scenario can exercise real detailed-route movement — actual
     * position over actual ticks, including the direct-segment step-up jump
     * assist — rather than only the nav-graph reachability {@code node}/{@code path}
     * probe elsewhere in this file.
     *
     * <p>{@code /vtest walk <id> <x> <y> <z>} is the same over the smoothed route
     * ordinary activities use, where vanilla navigation walks between sparse
     * waypoints.
     */
    private static int moveVillager(CommandContext<CommandSourceStack> ctx, boolean smoothed) {
        String id = StringArgumentType.getString(ctx, "id");
        BlockPos dest = readXyz(ctx);
        MinecraftServer server = ctx.getSource().getServer();

        List<VillagerModel> targets = resolveTargets(server, id);
        if (targets.isEmpty()) return err(ctx, "no villager with id " + id);
        VillagerModel model = targets.get(0);
        NpcEntity npc = model.puppet();
        if (npc == null || !(npc.level() instanceof ServerLevel level)) {
            return err(ctx, "villager " + id + " has no live puppet");
        }

        VillageManager mgr = VillageManagerRegistry.get(server).findVillageAt(level, npc.blockPosition());
        if (mgr == null || mgr.getGraph() == null) return err(ctx, "no village/graph at villager " + id);

        boolean accepted = smoothed
                ? model.requestMoveTo(mgr.getGraph(), dest, VillagerModel.DEFAULT_SPEED)
                : model.requestMoveToDetailed(mgr.getGraph(), dest, VillagerModel.DEFAULT_SPEED);
        return ok(ctx, "move id=" + id + " dest=" + dest.toShortString()
                + " detailed=" + !smoothed + " accepted=" + accepted);
    }

    /**
     * {@code /vtest brain <id> suspend|resume} — see
     * {@link VillagerModel#setBrainSuspended}. Lets a scenario drive a
     * puppet with {@code /vtest move} and read back real per-tick movement
     * without the villager's own leisure/work activities re-issuing a
     * competing move on the very next brain tick.
     */
    private static int setBrainSuspended(CommandContext<CommandSourceStack> ctx, boolean suspended) {
        String id = StringArgumentType.getString(ctx, "id");
        List<VillagerModel> targets = resolveTargets(ctx.getSource().getServer(), id);
        if (targets.isEmpty()) return err(ctx, "no villager with id " + id);
        targets.get(0).setBrainSuspended(suspended);
        return ok(ctx, "brain id=" + id + " suspended=" + suspended);
    }

    private static int uiPause(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
        List<VillagerModel> targets = resolveTargets(ctx.getSource().getServer(), id);
        if (targets.isEmpty() || targets.get(0).puppet() == null) return err(ctx, "no villager with id " + id);
        NpcEntity npc = targets.get(0).puppet();
        if (!(npc.level() instanceof ServerLevel level)) return err(ctx, "villager " + id + " is not on the server");
        npc.uiInteraction().holdForTest(level, ticks);
        return ok(ctx, "uipause id=" + id + " ticks=" + ticks);
    }

    @Nullable
    private static Building buildingAt(VillageManager mgr, @Nullable BlockPos doorPos) {
        if (doorPos == null) return null;
        for (Building building : mgr.getBuildings()) {
            if (building.getDoorPos().equals(doorPos)) return building;
        }
        return null;
    }

    // =========================================================================
    // Villagers
    // =========================================================================

    private static int spawn(CommandContext<CommandSourceStack> ctx, BlockPos pos, int count) {
        ServerLevel level = ctx.getSource().getLevel();
        String professionName = StringArgumentType.getString(ctx, "profession")
                .toUpperCase(java.util.Locale.ROOT);

        // Profession.byName falls back to NONE, which would turn a typo into a
        // silently jobless villager and a confusing test failure.
        Profession profession = null;
        for (Profession candidate : Profession.values()) {
            if (candidate.name().equals(professionName)) profession = candidate;
        }
        if (profession == null) {
            return err(ctx, "unknown profession '" + professionName + "' (NONE|LUMBERJACK|GUARD)");
        }

        VillageManager mgr = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, pos);
        if (mgr == null) {
            return err(ctx, "no village contains " + pos.toShortString()
                    + " — run /vtest scenario village first");
        }

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Fan spawns out along X so they do not stack inside one block.
            BlockPos spawnPos = pos.offset(i % 4, 0, i / 4);
            NpcEntity npc = ScenarioBuilder.spawnVillager(level, mgr, spawnPos, profession);
            if (npc != null) ids.add(StateDump.shortId(npc.getNpcId()));
        }
        if (ids.isEmpty()) return err(ctx, "spawned 0 villagers");
        return ok(ctx, "spawned " + ids.size() + " " + profession.name() + " " + String.join(",", ids));
    }

    /**
     * Forces a stat to a value so a test can reach a behaviour without waiting
     * for it to drift there naturally — e.g. drop hunger to 10 to check that
     * eating takes priority over work.
     */
    private static int setStat(CommandContext<CommandSourceStack> ctx) {
        String stat = StringArgumentType.getString(ctx, "stat").toLowerCase(java.util.Locale.ROOT);
        String target = StringArgumentType.getString(ctx, "target");
        float value = IntegerArgumentType.getInteger(ctx, "value");

        List<VillagerModel> models = resolveTargets(ctx.getSource().getServer(), target);
        if (models.isEmpty()) return err(ctx, "no villager matched '" + target + "'");

        int applied = 0;
        for (VillagerModel model : models) {
            VillagerBrain brain = model.brain();
            if (brain == null) continue;
            switch (stat) {
                case "hunger" -> brain.needs().hunger = value;
                case "wellbeing", "happiness", "motivation" -> brain.needs().wellbeing = value;
                default -> { return err(ctx, "unknown stat '" + stat
                        + "' (hunger|happiness)"); }
            }
            brain.needs().clamp();
            applied++;
        }
        return ok(ctx, "set " + stat + "=" + (int) value + " on " + applied + " villager(s)");
    }

    /**
     * {@code /vtest lead <villager> <animal>}: leashes the animal to the
     * villager and has it walk the villager's footsteps wherever the villager
     * goes, with no butcher work around it. {@code /vtest lead stop} lets
     * every such animal go.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> leadNode() {
        return Commands.literal("lead")
                .then(Commands.literal("stop").executes(ctx -> ok(ctx, "released "
                        + com.alex.test.village.villagers.butcher.lead.LedAnimals
                                .stopDebugFollows(ctx.getSource().getServer()) + " animal(s)")))
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("animal", net.minecraft.commands.arguments.EntityArgument.entity())
                                .executes(TestCommands::leadStart)));
    }

    private static int leadStart(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "id");
        NpcEntity npc = findVillagerById(ctx.getSource().getServer(), id);
        if (npc == null) return err(ctx, "no villager matched '" + id + "'");
        var entity = net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "animal");
        if (!(entity instanceof net.minecraft.world.entity.animal.Animal animal)) {
            return err(ctx, "that is not an animal");
        }
        if (animal.level() != npc.level()) return err(ctx, "the animal is in another dimension");
        if (!com.alex.test.village.villagers.butcher.lead.LedAnimals.start(animal, npc)) {
            return err(ctx, "could not lead it (already leashed or led, a baby, or dead)");
        }
        com.alex.test.village.villagers.butcher.lead.LedAnimals.setDebugFollow(animal, true);
        return ok(ctx, "villager " + id + " now leads " + animal.getType().getDescription().getString());
    }

    @Nullable
    private static NpcEntity findVillagerById(MinecraftServer server, String id) {
        for (VillageManager mgr : VillageManagerRegistry.get(server).all()) {
            for (VillagerModel model : mgr.getVillagers()) {
                if (StateDump.shortId(model.npcId).equalsIgnoreCase(id)) return model.puppet();
            }
        }
        return null;
    }

    @Nullable
    private static UUID resolvePlayerUuid(MinecraftServer server, @Nullable String playerName) {
        if (playerName == null) return null;
        ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
        if (player != null) return player.getUUID();
        try {
            return UUID.fromString(playerName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Resolves {@code all} or a villager short id to concrete models. */
    private static List<VillagerModel> resolveTargets(MinecraftServer server, String target) {
        List<VillagerModel> matches = new ArrayList<>();
        boolean all = target.equalsIgnoreCase("all");
        for (VillageManager mgr : VillageManagerRegistry.get(server).all()) {
            for (VillagerModel model : mgr.getVillagers()) {
                if (all || StateDump.shortId(model.npcId).equalsIgnoreCase(target)) {
                    matches.add(model);
                }
            }
        }
        return matches;
    }

    // =========================================================================
    // Observation
    // =========================================================================

    private static int dump(CommandContext<CommandSourceStack> ctx, String fileName) {
        MinecraftServer server = ctx.getSource().getServer();
        JsonObject state = StateDump.full(server);
        return ok(ctx, TestKit.write(server, fileName, StateDump.toJson(state)));
    }

    private static int map(CommandContext<CommandSourceStack> ctx, String fileName) {
        MinecraftServer server = ctx.getSource().getServer();
        BlockPos center = readXyz(ctx);
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        // The given Y is the scan ceiling, not a hint. Rendering from above the
        // roofs would show nothing but roofs; passing feet level instead reveals
        // floors, doors, beds and chests, which is what tests actually ask about.
        String rendered = AsciiMap.render(
                ctx.getSource().getLevel(), center, radius, center.getY());
        return ok(ctx, TestKit.write(server, fileName, rendered));
    }

    /** One line per villager — small enough to read straight from an RCON reply. */
    /**
     * The merchant's price table: every good a visit could ask for, dearest
     * first, with its lot size, then the book prices. Run from inside a
     * village to see today's live prices next to the base ones.
     */
    private static int economyPrices(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageManager village = VillageManagerRegistry.get(ctx.getSource().getServer())
                .findVillageAt(level, net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition()));
        java.util.List<net.minecraft.world.item.Item> goods =
                new java.util.ArrayList<>(com.alex.test.village.visitors.MerchantCatalog.everyCandidate());
        goods.sort(java.util.Comparator.comparingInt(
                (net.minecraft.world.item.Item item) -> -com.alex.test.village.visitors.MerchantPricing.fullStackPrice(item))
                .thenComparing(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString()));
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "Prices per full stack, max %d. %s", com.alex.test.village.economy.MarketPrices.maxPrice(),
                village == null ? "Stand in a village for live prices." : "Live prices for this village after base.")), false);
        var tuning = com.alex.test.village.economy.MarketPrices.tuning();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "Market: drop_per_stack=%s rise_per_emerald=%s max_rise=%s lowest_share=%s keep_each_morning=%s",
                tuning.dropPerStack(), tuning.risePerEmerald(), tuning.maxRise(), tuning.lowestShare(),
                tuning.keepEachMorning())), false);
        var market = com.alex.test.village.economy.MarketMemory.get(ctx.getSource().getServer());
        long today = com.alex.test.village.visitors.MerchantPricing.today(level);
        for (net.minecraft.world.item.Item item : goods) {
            int base = com.alex.test.village.visitors.MerchantPricing.fullStackPrice(item);
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
            String live = "";
            if (village != null) {
                // The good's own drop and the rise the other goods' sales give it, as the price multiplies them.
                String key = com.alex.test.village.visitors.MerchantPricing.marketKey(item);
                double share = com.alex.test.village.economy.MarketMemory.ownShare(
                        market.soldLots(village.getVillageId(), key, today), tuning);
                double rise = com.alex.test.village.economy.MarketMemory.rise(
                        market.otherPaidCents(village.getVillageId(), key, today), tuning);
                live = String.format(" live=%s own=%.0f%% rise=+%.1f%%",
                        com.alex.test.village.visitors.MerchantPricing.format(
                                com.alex.test.village.visitors.MerchantPricing.liveCents(level, village, null, item, base)),
                        share * 100.0, rise * 100.0);
            }
            String line = String.format("%s base=%d lot=%d%s", id, base,
                    com.alex.test.village.visitors.MerchantPricing.lotSize(item), live);
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        for (var kind : com.alex.test.village.villagers.enchanter.EnchantBooks.KINDS) {
            String line = "book " + kind.label() + " top level = "
                    + com.alex.test.village.villagers.enchanter.EnchantBooks.price(kind);
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        if (village != null) {
            String credit = "village saved change (tradesman, enchanter) = " + com.alex.test.village.visitors.MerchantPricing.format(
                    com.alex.test.village.visitors.MerchantPricing.creditCents(level, village));
            ctx.getSource().sendSuccess(() -> Component.literal(credit), false);
        }
        return goods.size();
    }

    private static int listVillagers(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        int found = 0;
        for (VillageManager mgr : VillageManagerRegistry.get(server).all()) {
            for (VillagerModel model : mgr.getVillagers()) {
                NpcEntity npc = model.puppet();
                VillagerBrain brain = model.brain();
                Activity current = brain == null ? null : brain.currentActivity();
                String line = String.format(
                        "%s %s %s act=%s hunger=%.0f happiness=%.0f moving=%b",
                        StateDump.shortId(model.npcId),
                        npc == null ? "UNLOADED" : npc.getProfession().name(),
                        npc == null ? "-" : npc.blockPosition().toShortString(),
                        current == null ? "none" : current.name(),
                        brain == null ? -1f : brain.needs().hunger,
                        brain == null ? -1f : brain.needs().wellbeing,
                        model.isMoving());
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                found++;
            }
        }
        if (found == 0) return ok(ctx, "no villagers");
        return found;
    }

    private static int listVillages(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        VillageManagerRegistry registry = VillageManagerRegistry.get(server);
        int found = 0;
        for (VillageManager mgr : registry.all()) {
            long valid = mgr.getBuildings().stream().filter(Building::isValid).count();
            String line = String.format(
                    "%s bell=%s inactive=%b buildings=%d/%d villagers=%d trees=%d nodes=%d",
                    StateDump.shortId(mgr.getVillageId()),
                    mgr.getBellPos().toShortString(),
                    registry.isVillageInactive(mgr.getVillageId()),
                    valid, mgr.getBuildings().size(),
                    mgr.getVillagers().size(),
                    mgr.getKnowledge().count(
                            com.alex.test.village.knowledge.KnowledgeBase.Category.TREE),
                    mgr.getGraph() == null ? 0 : mgr.getGraph().allNodes().size());
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            found++;
        }
        if (found == 0) return ok(ctx, "no villages");
        return found;
    }

    // =========================================================================
    // Watch
    // =========================================================================

    private static int watchStart(CommandContext<CommandSourceStack> ctx, int limit, String name) {
        MinecraftServer server = ctx.getSource().getServer();
        int interval = IntegerArgumentType.getInteger(ctx, "interval");
        BehaviorWatcher.start(server, name, interval, limit);
        return ok(ctx, "watch " + name + " interval=" + interval
                + " limit=" + (limit < 0 ? "none" : limit));
    }

    private static int watchStop(CommandContext<CommandSourceStack> ctx) {
        int samples = BehaviorWatcher.samples();
        BehaviorWatcher.stop();
        return ok(ctx, "watch stopped samples=" + samples
                + " file=vtest-out/" + BehaviorWatcher.currentFile());
    }

    private static int watchStatus(CommandContext<CommandSourceStack> ctx) {
        return ok(ctx, "watch active=" + BehaviorWatcher.isActive()
                + " samples=" + BehaviorWatcher.samples()
                + " file=vtest-out/" + BehaviorWatcher.currentFile());
    }

    // =========================================================================
    // Argument plumbing
    // =========================================================================

    /**
     * Reads the shared {@code <x> <y> <z>} arguments.
     *
     * <p>Plain integers rather than {@code BlockPosArgument} on purpose: an RCON
     * console has no meaningful position, so relative and caret coordinates
     * would silently resolve against the world origin.
     */
    private static BlockPos readXyz(CommandContext<CommandSourceStack> ctx) {
        return new BlockPos(
                IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z"));
    }

    // =========================================================================
    // Replies — always prefixed so a harness can branch without parsing prose
    // =========================================================================

    private static int ok(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal("OK " + message), false);
        return 1;
    }

    private static int err(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal("ERR " + message));
        return 0;
    }
}
