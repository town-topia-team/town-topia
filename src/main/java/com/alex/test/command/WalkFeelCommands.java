package com.alex.test.command;

import com.alex.test.village.WalkFeel;
import com.alex.test.village.nav.StepPrice;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * {@code /villager walkfeel}: turns each walking change of 2026-10-15 on or
 * off in game, so one that makes walking worse can be ruled out without a
 * rebuild. Not saved; a restart turns them all back on. The drawing-only
 * body turn is on the client's {@code /walkfeel}.
 *
 * <p>The old walker's switches (backward, routestart, airgap, stuckcheck,
 * cornertile, stephop, levelleg, lipstep, follower) went with it on 2026-10-18.
 *
 * <pre>
 *   /villager walkfeel                       which are on
 *   /villager walkfeel spylog on|off         the [WalkBack] lines in the log
 *   /villager walkfeel legstart on|off       Minecraft's own paths (outside the village map) skip turning back to their own block
 *   /villager walkfeel ease on|off           gentle start, slower sharp turns, slowing to a stop
 *   /villager walkfeel handover on|off       defenders keep running while a new chase route is planned
 *   /villager walkfeel guardspot on|off      guards strike from their own side, keep their spot
 *   /villager walkfeel guardmargin on|off    guards keep running straight at a mob a little longer
 *   /villager walkfeel ladderlog on|off      the [LadderLeg] lines in the log
 *   /villager walkfeel laddercross on|off    crossing a ladder sideways keeps the villager's height
 *   /villager walkfeel ladderside on|off     a step to the ladder beside it keeps the villager's height
 *   /villager walkfeel ladderlanding on|off  stepping off a ladder onto a bottom slab counts as arrived
 *   /villager walkfeel ladderperch on|off    a villager stood on a ladder's top is nudged off it
 *   /villager walkfeel ladderchain on|off    a ladder's top onto the floor-standing ladder beside it, a block up: climbs out first, then steps across
 *   /villager walkfeel ladderledge on|off    a ladder standing on a block one up from the floor beside it is jumped onto and walked off
 *   /villager walkfeel ladderslab on|off     a ladder standing on a bottom slab is reached and climbed from the slab
 *   /villager walkfeel ladderblame on|off    two ladder stucks in a row on one ladder step: that step marked failed for 10 min
 *   /villager walkfeel ladderface on|off     a climbing villager faces the ladder instead of spinning round
 *   /villager walkfeel stuckdetailed on|off  a second stuck on a smoothed route re-plans step by step
 *   /villager walkfeel produce on|off        no stepping up onto melons and pumpkins (needs a map rebuild)
 *   /villager walkfeel stickysteps on|off    a step that fails three times stays failed for the session
 *   /villager walkfeel bumplearn on|off      a block in the way the map missed: step marked failed / column kept off the line
 *   /villager walkfeel stuckloop on|off      three stucks on one tile in 30 s: step marked failed, rescues at most every 30 s
 *   /villager walkfeel cornerround on|off    the route follower curves through level corners with room
 *   /villager walkfeel vanillaclimb on|off   a walking villager climbs a full block at Minecraft's pace (off: quick hop)
 *   /villager walkfeel arrowmap on|off       archers walk to their arrows on the village map (off: Minecraft's path)
 *   /villager walkfeel campmap on|off        travellers' evening walks with no map route end where they stand (off: Minecraft's path)
 *   /villager walkfeel nomadmap on|off       a nomad with no map route to its Town Hall tile waits (off: Minecraft's path)
 *   /villager walkfeel wakespot on|off       a villager that stands up boxed in beside its bed goes back to the tile it lay down from
 *   /villager walkfeel wakecell on|off       the wake spot judges the villager's own tile; no tile there = boxed in (off: nearest tile)
 *   /villager walkfeel wakebedside on|off    no lie-down tile saved: a boxed-in waker goes beside its bed (off: Minecraft's spot)
 *   /villager walkfeel rescuenowall on|off   the trapped rescue only moves to a tile the map joins by its own steps, never through a wall (off: nearest)
 *   /villager walkfeel trapnotile on|off     a villager on a spot with no map tile can be rescued as trapped (off: log line only)
 *   /villager walkfeel tiebreak on|off       the route planner takes equal tiles nearest the goal first (off: the old order)
 *   /villager walkfeel climbcost on|off      routes priced by walking time: stairs beat blocks up and down (off: old 2/1 prices)
 *   /villager walkfeel airstart on|off       a walk asked for in mid-jump starts from the tile under the villager (off: the mid-air cell)
 *   /villager walkfeel jumpcost <n>          extra for a full-block climb, tenths of a tile (15)
 *   /villager walkfeel dropcost <n>          extra per block dropped off a ledge, tenths of a tile (15)
 *   /villager walkfeel fleemap on|off        running away with no building to run to goes over the village map (off: Minecraft's path)
 *   /villager walkfeel travelgiveup on|off   a traveller's map walk gives up after 3 stucks (off: re-plans after every stuck)
 *   /villager walkfeel stepbackmap on|off    a guard's step back from an axe or a creeper goes over the village map (off: Minecraft's path)
 *   /villager walkfeel retreatmap on|off     no straight run to a shelter the map can't reach: onto the map first (off: straight Minecraft run)
 *   /villager walkfeel chasemap on|off       OFF at start: a guard's close chase walks a short map route (off: Minecraft's path)
 *   /villager walkfeel raidmap on|off        ON at start (10-23): raiders on foot march, loot, snatch and run home on the village map (off: Minecraft's path)
 *   /villager walkfeel raidchase on|off      ON at start (10-23), needs raidmap: a raider walks the map toward a far or hidden foe (off: Minecraft's chase)
 *   /villager walkfeel raidbig on|off        ON at start (10-23), needs raidmap: the Warlord and ravagers walk by map route in Minecraft-path stretches (off: Minecraft's path)
 *   /villager walkfeel captivepace on|off    a kidnapped villager is led at its captor's pace, new path when the captor moved 1.5 blocks (off: speed 1.3, path every 1/4 s)
 *   /villager walkfeel raidreplan on|off     with raidmap: a raider re-planning toward a moving foe walks on meanwhile (off: stops for the planning)
 *   /villager walkfeel raidnear on|off       every door within 8 blocks: a raider marches to the furthest door, the middle or round it (off: stands still)
 *   /villager walkfeel raidcampin on|off     raid camps go on ground the village map joins to a door when one is found (off: flat ground anywhere)
 *   /villager walkfeel raidnovanilla on|off  with raidmap: no map way to a door: another door; to a chest: looting given up (off: Minecraft's path into the wall)
 *   /villager walkfeel raidwater on|off      with raidmap: a raider in deep water swims to the dry tile that leads on to its goal, foe chases too (off: Minecraft's path in any water)
 *   /villager walkfeel raidjoinrules on|off  camp spots and ways out of water must join the village by raider rules, no ladders or ranch gates (off: villager rules)
 *   /villager walkfeel raidchaseend on|off   with raidmap: chase route walked, foe moved: plan again at once; foe still: Minecraft does the last bit (off: wait, same route again)
 *   /villager walkfeel raidhalfpath on|off   raiders' Minecraft paths searched 40 blocks; a half path into a wall is not walked (off: walked)
 *   /villager walkfeel raidlegs on|off       raiders' Minecraft legs kept until walked, nearest the goal in 18 degrees (off: new random dark leg each second)
 *   /villager walkfeel raidfoenear on|off    with raidmap: a foe on a roof or just outside the border is walked to the nearest map tile (off: Minecraft's chase)
 *   /villager walkfeel raidbigfresh on|off   with raidbig: big-body tile map made again 5 s after the map changed nearby (off: every 30 s)
 *   /villager walkfeel irondoors on|off      villagers never work iron doors and route through one only while open (off: opened like wood)
 *   /villager walkfeel warlordturn on|off    the Warlord's walking turns gently and slows while coming round (off: Minecraft's 90 a tick)
 *   /villager walkfeel warlordturndeg <n>    its most turn a tick while walking, degrees (20; 5 to 90)
 *   /villager walkfeel frozenground on|off   a walk on ground outside simulation distance waits instead of counting as stuck
 *   /villager walkfeel newtree on|off        a grown tree reaches the map in ~1 s and its druid waits for that (off: 10 s wait, no pause)
 *   /villager walkfeel doorfloor on|off      lines up only for a door on its own floor, 3 tiles ahead at most (off: any by flat distance)
 *   /villager walkfeel doorlane on|off       a door counts as passed only with the body in its lane (off: past its middle alone)
 *   /villager walkfeel playerflee on|off     a villager hit by a player in a building leaves it for another (off: stays, hit ignored)
 *   /villager walkfeel treeplain on|off      stripped logs and bark blocks are never part of a tree (off: counted as its wood)
 *   /villager walkfeel treepiles on|off      a log pile beside a tree is never taken into it (off: only the bottom row lies)
 *   /villager walkfeel treemarks on|off      logs with planks, glass, torches, stripped logs or placed leaves on or under them are builds (off: not looked at)
 *   /villager walkfeel treecrown on|off      a tree needs a trunk on natural ground and its own leaves round a top (off: any leaves within 2)
 *   /villager walkfeel treesplit on|off      OFF at start: touching trees and builds are shared out, one tree per trunk; a build resting on it keeps it (off: one group, one tree)
 *   /villager walkfeel tree2x2 on|off        a big 2x2 trunk is found at the base's own layer (off: only at the group's lowest layer)
 *   /villager walkfeel treekeep on|off       a tree stays on the village list until it falls (off: off the list at the first swing)
 *   /villager walkfeel treeresume on|off     a chop stopped by an emergency is resumed with its swings kept (off: tree and swings let go)
 *   /villager walkfeel growfoot on|off       a grown sapling is found from its own column, leaves are not ground (off: first log from the west/north corner)
 *   /villager walkfeel treeoneclaim on|off   a tree sharing logs with one another lumberjack holds is left alone (off: each base reserved on its own)
 *   /villager walkfeel growretry on|off      a grown tree partly in an unloaded chunk is tried again for 5 minutes (off: waits for the next village scan)
 *   /villager walkfeel replantall on|off     felling touching trees replants every trunk of them (off: only the felled base's spot)
 *   /villager walkfeel treefinish on|off     a chop stopped after the fall takes the rest of the logs down at once (off: left hanging)
 *   /villager walkfeel treesoil on|off       a trunk must stand on soil (tag tree_soil); stone, sand... under it leave it standing
 *   /villager walkfeel chopcheck on|off      the tree is judged again on arrival and when the ground under its trunk changes mid-chop
 *   /villager walkfeel treeleaves on|off     a felled tree takes only the leaves its logs are nearest (off: every leaf of its kind within 2)
 *   /villager walkfeel leafcap on|off        over 1024 leaves: the 1024 nearest fall, the tree is still cut (off: never cut)
 * </pre>
 */
public final class WalkFeelCommands {

    private WalkFeelCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("walkfeel")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> report(ctx.getSource()))
                .then(toggle("spylog", on -> WalkFeel.spyLog = on))
                .then(toggle("legstart", on -> WalkFeel.legStart = on))
                .then(toggle("ease", on -> WalkFeel.ease = on))
                .then(toggle("handover", on -> WalkFeel.chaseHandover = on))
                .then(toggle("guardspot", on -> WalkFeel.steadySpot = on))
                .then(toggle("guardmargin", on -> WalkFeel.chaseMargin = on))
                .then(toggle("ladderlog", on -> WalkFeel.ladderLog = on))
                .then(toggle("laddercross", on -> WalkFeel.ladderCross = on))
                .then(toggle("ladderside", on -> WalkFeel.ladderSide = on))
                .then(toggle("ladderlanding", on -> WalkFeel.ladderLanding = on))
                .then(toggle("ladderperch", on -> WalkFeel.ladderPerch = on))
                .then(toggle("ladderchain", on -> WalkFeel.ladderChain = on))
                .then(toggle("ladderledge", on -> WalkFeel.ladderLedge = on))
                .then(toggle("ladderslab", on -> WalkFeel.ladderSlab = on))
                .then(toggle("ladderblame", on -> WalkFeel.ladderBlame = on))
                .then(toggle("ladderface", on -> WalkFeel.ladderFace = on))
                .then(toggle("stuckdetailed", on -> WalkFeel.stuckDetailed = on))
                .then(toggle("produce", on -> WalkFeel.noProduceStep = on))
                .then(toggle("stickysteps", on -> WalkFeel.stickySteps = on))
                .then(toggle("bumplearn", on -> WalkFeel.bumpLearn = on))
                .then(toggle("stuckloop", on -> WalkFeel.stuckLoop = on))
                .then(toggle("cornerround", on -> WalkFeel.cornerRound = on))
                .then(toggle("vanillaclimb", on -> WalkFeel.vanillaClimb = on))
                .then(toggle("arrowmap", on -> WalkFeel.arrowMap = on))
                .then(toggle("campmap", on -> WalkFeel.campMap = on))
                .then(toggle("nomadmap", on -> WalkFeel.nomadMap = on))
                .then(toggle("fleemap", on -> WalkFeel.fleeMap = on))
                .then(toggle("travelgiveup", on -> WalkFeel.travelGiveUp = on))
                .then(toggle("stepbackmap", on -> WalkFeel.stepBackMap = on))
                .then(toggle("retreatmap", on -> WalkFeel.retreatMap = on))
                .then(toggle("chasemap", on -> WalkFeel.chaseMap = on))
                .then(toggle("raidmap", on -> WalkFeel.raidMap = on))
                .then(toggle("raidchase", on -> WalkFeel.raidChase = on))
                .then(toggle("raidbig", on -> WalkFeel.raidBig = on))
                .then(toggle("captivepace", on -> WalkFeel.captivePace = on))
                .then(toggle("raidreplan", on -> WalkFeel.raidReplan = on))
                .then(toggle("raidnear", on -> WalkFeel.raidNear = on))
                .then(toggle("raidcampin", on -> WalkFeel.raidCampIn = on))
                .then(toggle("raidnovanilla", on -> WalkFeel.raidNoVanilla = on))
                .then(toggle("raidwater", on -> WalkFeel.raidWater = on))
                .then(toggle("raidjoinrules", on -> WalkFeel.raidJoinRules = on))
                .then(toggle("raidchaseend", on -> WalkFeel.raidChaseEnd = on))
                .then(toggle("raidhalfpath", on -> WalkFeel.raidHalfPath = on))
                .then(toggle("raidlegs", on -> WalkFeel.raidLegs = on))
                .then(toggle("raidfoenear", on -> WalkFeel.raidFoeNear = on))
                .then(toggle("raidbigfresh", on -> WalkFeel.raidBigFresh = on))
                .then(toggle("irondoors", on -> WalkFeel.ironDoors = on))
                .then(toggle("warlordturn", on -> WalkFeel.warlordTurn = on))
                .then(Commands.literal("warlordturndeg")
                        .then(Commands.argument("degrees", IntegerArgumentType.integer(5, 90)).executes(ctx -> {
                            WalkFeel.warlordTurnDegrees = IntegerArgumentType.getInteger(ctx, "degrees");
                            return report(ctx.getSource());
                        })))
                .then(toggle("frozenground", on -> WalkFeel.frozenGround = on))
                .then(toggle("newtree", on -> WalkFeel.newTree = on))
                .then(toggle("doorfloor", on -> WalkFeel.doorFloor = on))
                .then(toggle("doorlane", on -> WalkFeel.doorLane = on))
                .then(toggle("playerflee", on -> WalkFeel.playerFlee = on))
                .then(toggle("treeplain", on -> WalkFeel.treePlain = on))
                .then(toggle("treepiles", on -> WalkFeel.treePiles = on))
                .then(toggle("treemarks", on -> WalkFeel.treeMarks = on))
                .then(toggle("treecrown", on -> WalkFeel.treeCrown = on))
                .then(toggle("treesplit", on -> WalkFeel.treeSplit = on))
                .then(toggle("tree2x2", on -> WalkFeel.tree2x2 = on))
                .then(toggle("treekeep", on -> WalkFeel.treeKeep = on))
                .then(toggle("treeresume", on -> WalkFeel.treeResume = on))
                .then(toggle("growfoot", on -> WalkFeel.growFoot = on))
                .then(toggle("treeoneclaim", on -> WalkFeel.treeOneClaim = on))
                .then(toggle("growretry", on -> WalkFeel.growRetry = on))
                .then(toggle("replantall", on -> WalkFeel.replantAll = on))
                .then(toggle("treefinish", on -> WalkFeel.treeFinish = on))
                .then(toggle("treesoil", on -> WalkFeel.treeSoil = on))
                .then(toggle("chopcheck", on -> WalkFeel.chopCheck = on))
                .then(toggle("treeleaves", on -> WalkFeel.treeLeaves = on))
                .then(toggle("leafcap", on -> WalkFeel.leafCap = on))
                .then(toggle("copyland", on -> WalkFeel.copyLand = on))
                .then(toggle("copysoil", on -> WalkFeel.copySoil = on))
                .then(toggle("copytrust", on -> WalkFeel.copyTrust = on))
                .then(toggle("copylogs", on -> WalkFeel.copyLogs = on))
                .then(toggle("copymemory", on -> WalkFeel.copyMemory = on))
                .then(toggle("copylookalikes", on -> WalkFeel.copyLookalikes = on))
                .then(toggle("copyfooting", on -> WalkFeel.copyFooting = on))
                .then(toggle("buildlive", on -> WalkFeel.buildLive = on))
                .then(toggle("buildpairs", on -> WalkFeel.buildPairs = on))
                .then(toggle("buildpair", on -> WalkFeel.buildPair = on))
                .then(toggle("buildapart", on -> WalkFeel.buildApart = on))
                .then(toggle("builderrandstop", on -> WalkFeel.buildErrandStop = on))
                .then(toggle("buildoffstop", on -> WalkFeel.buildOffStop = on))
                .then(toggle("buildsafedata", on -> WalkFeel.buildSafeData = on))
                .then(toggle("builddirtgrass", on -> WalkFeel.buildDirtGrass = on))
                .then(toggle("buildstripmore", on -> WalkFeel.buildStripMore = on))
                .then(toggle("buildunloaded", on -> WalkFeel.buildUnloaded = on))
                .then(toggle("wakespot", on -> WalkFeel.wakeSpot = on))
                .then(toggle("wakecell", on -> WalkFeel.wakeCell = on))
                .then(toggle("wakebedside", on -> WalkFeel.wakeBedside = on))
                .then(toggle("rescuenowall", on -> WalkFeel.rescueNoWall = on))
                .then(toggle("trapnotile", on -> WalkFeel.trapNoTile = on))
                .then(toggle("tiebreak", on -> WalkFeel.tieBreak = on))
                .then(toggle("climbcost", on -> WalkFeel.climbCost = on))
                .then(toggle("airstart", on -> WalkFeel.airStart = on))
                .then(price("jumpcost", n -> StepPrice.climbExtra = n))
                .then(price("dropcost", n -> StepPrice.dropExtraPerBlock = n));
    }

    /** A live route price in tenths of a level tile (0 to 200); planned routes use it from the next search. */
    private static LiteralArgumentBuilder<CommandSourceStack> price(String name, IntConsumer set) {
        return Commands.literal(name)
                .then(Commands.argument("tenths", IntegerArgumentType.integer(0, 200)).executes(ctx -> {
                    set.accept(IntegerArgumentType.getInteger(ctx, "tenths"));
                    return report(ctx.getSource());
                }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> toggle(String name, Consumer<Boolean> set) {
        return Commands.literal(name)
                .then(Commands.literal("on").executes(ctx -> {
                    set.accept(true);
                    return report(ctx.getSource());
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    set.accept(false);
                    return report(ctx.getSource());
                }));
    }

    private static int report(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Walking: "
                + "spylog " + onOff(WalkFeel.spyLog)
                + ", legstart " + onOff(WalkFeel.legStart)
                + ", ease " + onOff(WalkFeel.ease)
                + ", handover " + onOff(WalkFeel.chaseHandover)
                + ", guardspot " + onOff(WalkFeel.steadySpot)
                + ", guardmargin " + onOff(WalkFeel.chaseMargin)
                + ", ladderlog " + onOff(WalkFeel.ladderLog)
                + ", laddercross " + onOff(WalkFeel.ladderCross)
                + ", ladderside " + onOff(WalkFeel.ladderSide)
                + ", ladderlanding " + onOff(WalkFeel.ladderLanding)
                + ", ladderperch " + onOff(WalkFeel.ladderPerch)
                + ", ladderchain " + onOff(WalkFeel.ladderChain)
                + ", ladderledge " + onOff(WalkFeel.ladderLedge)
                + ", ladderslab " + onOff(WalkFeel.ladderSlab)
                + ", ladderblame " + onOff(WalkFeel.ladderBlame)
                + ", ladderface " + onOff(WalkFeel.ladderFace)
                + ", stuckdetailed " + onOff(WalkFeel.stuckDetailed)
                + ", produce " + onOff(WalkFeel.noProduceStep)
                + ", stickysteps " + onOff(WalkFeel.stickySteps)
                + ", bumplearn " + onOff(WalkFeel.bumpLearn)
                + ", stuckloop " + onOff(WalkFeel.stuckLoop)
                + ", cornerround " + onOff(WalkFeel.cornerRound)
                + ", vanillaclimb " + onOff(WalkFeel.vanillaClimb)
                + ", arrowmap " + onOff(WalkFeel.arrowMap)
                + ", campmap " + onOff(WalkFeel.campMap)
                + ", nomadmap " + onOff(WalkFeel.nomadMap)
                + ", wakespot " + onOff(WalkFeel.wakeSpot)
                + ", wakecell " + onOff(WalkFeel.wakeCell)
                + ", wakebedside " + onOff(WalkFeel.wakeBedside)
                + ", rescuenowall " + onOff(WalkFeel.rescueNoWall)
                + ", trapnotile " + onOff(WalkFeel.trapNoTile)
                + ", tiebreak " + onOff(WalkFeel.tieBreak)
                + ", airstart " + onOff(WalkFeel.airStart)
                + ", climbcost " + onOff(WalkFeel.climbCost)
                + " (jumpcost " + StepPrice.climbExtra + ", dropcost " + StepPrice.dropExtraPerBlock
                + " tenths of a tile)"
                + ", fleemap " + onOff(WalkFeel.fleeMap)
                + ", travelgiveup " + onOff(WalkFeel.travelGiveUp)
                + ", stepbackmap " + onOff(WalkFeel.stepBackMap)
                + ", retreatmap " + onOff(WalkFeel.retreatMap)
                + ", chasemap " + onOff(WalkFeel.chaseMap)
                + ", raidmap " + onOff(WalkFeel.raidMap)
                + ", raidchase " + onOff(WalkFeel.raidChase)
                + ", raidbig " + onOff(WalkFeel.raidBig)
                + ", captivepace " + onOff(WalkFeel.captivePace)
                + ", raidreplan " + onOff(WalkFeel.raidReplan)
                + ", raidnear " + onOff(WalkFeel.raidNear)
                + ", raidcampin " + onOff(WalkFeel.raidCampIn)
                + ", raidnovanilla " + onOff(WalkFeel.raidNoVanilla)
                + ", raidwater " + onOff(WalkFeel.raidWater)
                + ", raidjoinrules " + onOff(WalkFeel.raidJoinRules)
                + ", raidchaseend " + onOff(WalkFeel.raidChaseEnd)
                + ", raidhalfpath " + onOff(WalkFeel.raidHalfPath)
                + ", raidlegs " + onOff(WalkFeel.raidLegs)
                + ", raidfoenear " + onOff(WalkFeel.raidFoeNear)
                + ", raidbigfresh " + onOff(WalkFeel.raidBigFresh)
                + ", irondoors " + onOff(WalkFeel.ironDoors)
                + ", warlordturn " + onOff(WalkFeel.warlordTurn) + " (" + WalkFeel.warlordTurnDegrees + " a tick)"
                + ", frozenground " + onOff(WalkFeel.frozenGround)
                + ", newtree " + onOff(WalkFeel.newTree)
                + ", doorfloor " + onOff(WalkFeel.doorFloor)
                + ", doorlane " + onOff(WalkFeel.doorLane)
                + ", playerflee " + onOff(WalkFeel.playerFlee)
                + ", treeplain " + onOff(WalkFeel.treePlain)
                + ", treepiles " + onOff(WalkFeel.treePiles)
                + ", treemarks " + onOff(WalkFeel.treeMarks)
                + ", treecrown " + onOff(WalkFeel.treeCrown)
                + ", treesplit " + onOff(WalkFeel.treeSplit)
                + ", tree2x2 " + onOff(WalkFeel.tree2x2)
                + ", treekeep " + onOff(WalkFeel.treeKeep)
                + ", treeresume " + onOff(WalkFeel.treeResume)
                + ", growfoot " + onOff(WalkFeel.growFoot)
                + ", treeoneclaim " + onOff(WalkFeel.treeOneClaim)
                + ", growretry " + onOff(WalkFeel.growRetry)
                + ", replantall " + onOff(WalkFeel.replantAll)
                + ", treefinish " + onOff(WalkFeel.treeFinish)
                + ", treesoil " + onOff(WalkFeel.treeSoil)
                + ", chopcheck " + onOff(WalkFeel.chopCheck)
                + ", treeleaves " + onOff(WalkFeel.treeLeaves)
                + ", leafcap " + onOff(WalkFeel.leafCap)
                + ". Drawn body turn: /walkfeel"), false);
        return 1;
    }

    private static String onOff(boolean on) {
        return on ? "ON" : "off";
    }
}
