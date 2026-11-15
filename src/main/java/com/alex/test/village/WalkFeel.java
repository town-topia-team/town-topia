package com.alex.test.village;

/**
 * Switches for the walking-feel rounds (2026-10-15) and the chase fixes that
 * followed, so any one of them can be turned off in game, without a rebuild,
 * if it makes walking worse: {@code /villager walkfeel} for the ones that
 * change how villagers move or what gets logged, {@code /walkfeel} (client) for
 * the one that only changes how the body is drawn. Not saved: a restart turns
 * every one back on.
 *
 * <p>Round 5 (the head looking into turns) was tried and taken out: players
 * found it uncanny.
 */
public final class WalkFeel {

    /** Round 1: the {@code [WalkBack]} lines, see {@link WalkBackLog}. Reads only. */
    public static volatile boolean spyLog = true;
    /**
     * Round 2: a new stretch of a walk no longer starts by turning back to the
     * middle of the block the villager is standing on. See {@link LegShape#startSkippable}.
     */
    public static volatile boolean legStart = true;
    /** Round 6: a gentle start, slowing for sharp turns, and slowing toward the end of a walk. */
    public static volatile boolean ease = true;
    /**
     * Guards, captains and archers keep running on their old route while the
     * new one toward a mob that moved is planned, instead of stopping first.
     */
    public static volatile boolean chaseHandover = true;
    /**
     * Guards and captains pick the spot to strike a mob from on their own side
     * of it, keep the spot they are running to unless another is clearly
     * better, and judge a spot's height by the floor the mob stands on.
     */
    public static volatile boolean steadySpot = true;
    /**
     * Guards and captains keep running straight at a mob a little past where
     * they would start to, so they do not flip between that and the village
     * route at the edge.
     */
    public static volatile boolean chaseMargin = true;

    /**
     * Ladder fixes (2026-10-17): the {@code [LadderLeg]} lines in the log, for
     * villagers crossing, stalling on or falling off a ladder. Reads only.
     */
    public static volatile boolean ladderLog = true;
    /**
     * A route that crosses a ladder sideways, with nothing under the ladder
     * space, holds the villager at that height instead of letting it slide to
     * the bottom of the shaft. See {@link LadderLeg}.
     */
    public static volatile boolean ladderCross = true;
    /** Stepping sideways from one ladder to the one beside it keeps the villager's height. */
    public static volatile boolean ladderSide = true;
    /**
     * Stepping off a ladder onto a half-height floor, a bottom slab, counts as
     * arrived once the villager stands on it, not only on a full block.
     */
    public static volatile boolean ladderLanding = true;
    /**
     * A villager stood on the top of a ladder's plate at a floor hole, unable
     * to go down, is nudged off it toward the ladder's open side.
     */
    public static volatile boolean ladderPerch = true;
    /**
     * At the top of a ladder whose next tile is another ladder one block up
     * beside it, standing on a floor, the villager climbs until its feet are
     * above that floor and then steps across, as onto any landing. Off, it
     * was aimed at the next ladder a block too low and slid down the shaft.
     */
    public static volatile boolean ladderChain = true;
    /**
     * A ladder whose bottom rung stands on a block one higher than the floor
     * beside it is stepped (jumped) onto and walked off, like any one-block
     * step. Off, the villager pressed against that block without jumping, or
     * tried to sink through it on the way down.
     */
    public static volatile boolean ladderLedge = true;
    /**
     * A rung standing on a low floor (a bottom slab) counts as reached, and as
     * climbable, from that floor, half a block under the rung. Off, the
     * villager stood under the ladder and never went up.
     */
    public static volatile boolean ladderSlab = true;
    /**
     * The second ladder stuck in a row on the same ladder step, by one villager
     * within two minutes, marks that step failed in the map for ten minutes,
     * so the re-plan goes another way. Off, a ladder step the walker cannot do
     * is chosen again and again.
     */
    public static volatile boolean ladderBlame = true;
    /**
     * Walk spins round 1 (2026-10-17): a villager on a rung faces the ladder
     * and keeps that facing for the whole climb. The move control used to turn
     * the body toward the rung's middle every tick, which flipped it round
     * each time the villager was a hair past the middle. The centring is now
     * a small sideways nudge with a dead band. See {@code NpcEntity.LadderAwareMoveControl}.
     */
    public static volatile boolean ladderFace = true;

    /**
     * Stuck spots B2: stuck a second time on a smoothed route, the villager
     * re-plans step by step instead of smoothing the same leg again (92 repeats
     * at one barrel). See {@code VillagerModel}.
     */
    public static volatile boolean stuckDetailed = true;
    /**
     * Stuck spots C: melon and pumpkin tops are not surfaces to step up onto;
     * level walking across them stays. See {@code NavGraph}.
     */
    public static volatile boolean noProduceStep = true;
    /**
     * Stuck spots E: a step that fails three times stays failed for the
     * session (until the blocks there change) instead of ten minutes, so the
     * same spot does not fail all day. See {@code NavGraph#markStepFailed}.
     */
    public static volatile boolean stickySteps = true;
    /**
     * Route follower stucks, step 3 (10-18): pressed against a solid block the map calls open,
     * the follower tells the map. In a route tile's column the step into that tile is marked
     * failed; beside the route the column is kept off the line for 5 minutes and the village
     * re-reads the block. Off: the block is still named, nothing is learnt.
     */
    public static volatile boolean bumpLearn = true;
    /**
     * Step 4 (10-18): a villager stuck three times in 30 s on the same tile for the same reason
     * gets the step ahead marked failed ({@code [StuckLoop]} line), and stuck rescues (moves to
     * a nearby safe tile) come at most once per 30 s per villager. Off: as before.
     */
    public static volatile boolean stuckLoop = true;
    /**
     * Step 5 (10-18): the route follower starts turning before a level corner where a body-wide
     * box says there is room, instead of walking into the corner and pivoting. Off: tight turns.
     */
    public static volatile boolean cornerRound = true;
    /**
     * Climb-cost step 1 (10-18): a walking villager climbs a full block like a Minecraft mob on
     * its own path: jump half a block before it, lose pace against the face, creep over, land
     * slowly and pick up again, about 0.45 s more than level ground. Runs keep their leap. Off:
     * the old quick hop, carried over at walking pace (a full block cost no time). See
     * {@code FollowWalk#walkingClimb}.
     */
    public static volatile boolean vanillaClimb = true;

    /**
     * Minecraft pathfinder leftovers step 3 (10-18): an archer walks to its arrows on the village
     * map (streets, gates, doors) instead of a Minecraft path; one the map can't reach is left at
     * once. Off: the Minecraft path. See {@code ArrowRecovery}.
     */
    public static volatile boolean arrowMap = true;
    /**
     * Leftovers step 4 (10-18): a merchant or nomad inside the village whose evening walk (camp
     * tile, stroll, tavern, guest bed) has no map route, or whose route ended away from the tile,
     * ends that walk where it stands instead of trying a Minecraft path. Outside the village the
     * Minecraft path stays. See {@code TravelerNight}.
     */
    public static volatile boolean campMap = true;
    /**
     * Leftovers step 5 (10-18): the same for a nomad walking to its Town Hall tile; it waits where
     * it is (hopping to an open tile after a minute without headway). Founding nomads walking in
     * from outside keep the Minecraft path. See {@code NomadLogic}.
     */
    public static volatile boolean nomadMap = true;
    /**
     * Wake spot (10-18): a villager whose stand-up tile beside its bed has no way out on the
     * village map (bed, chair and walls round it) is put back on the tile it stepped into bed
     * from. Off: Minecraft's stand-up tile only. See {@code VillagerModel#standClearOfBed}.
     */
    public static volatile boolean wakeSpot = true;
    /**
     * Wake spot K1 (10-18): the stand-up tile is the map's tile in the villager's own column, and
     * none there counts as boxed in. Off: the nearest tile within 3 blocks stands in for it (a
     * corner with no tile was judged by the bed tile beside it). See {@code VillagerModel#standClearOfBed}.
     */
    public static volatile boolean wakeCell = true;
    /**
     * Wake spot K1b (10-18): out of bed with no lie-down tile saved, a villager not on a tile that
     * connects to the Town Hall door goes on the nearest free floor tile beside its bed that does.
     * Off: Minecraft's stand-up tile, as before. See {@code VillagerModel#standBesideBed}.
     */
    public static volatile boolean wakeBedside = true;
    /**
     * F2 (10-18): the trapped rescue moves a villager only to a tile the map joins to its spot by
     * its own steps (over beds and chests too), fewest steps first, so never through a wall. Off:
     * nearest in a straight line. See {@code VillagerModel#tryRecoverToNearbySafePosition}.
     */
    public static volatile boolean rescueNoWall = true;
    /**
     * K2 (10-18): a villager standing where the map has no tile counts as trapped after 5 goals
     * with no route, like one beside furniture. Needs {@link #rescueNoWall}. Off: it only gets a
     * log line. See {@code VillagerModel#trappedWithoutTile}.
     */
    public static volatile boolean trapNoTile = true;
    /**
     * Climb cost 2E (10-18): the route planner takes equally cheap tiles nearest the goal first,
     * with each tile's price kept from when it was queued. Same prices, far fewer tiles looked at
     * (the {@code [RouteSearch]} line). Off: the old order. See {@code AStarPathfinder}.
     */
    public static volatile boolean tieBreak = true;
    /**
     * Climb cost 2C (10-18): the route planner prices steps by how long a walk takes on them,
     * in tenths of a tile ({@code StepPrice}): a stair front or slab like level ground, a full
     * block up or a drop a tile and a half more per block, so staircases win up and down unless
     * they are far out of the way. Off: the old 2 for any rise, 1 for the rest. Live tuning:
     * {@code /villager walkfeel jumpcost|dropcost <n>}. See {@code AStarPathfinder#stepCost}.
     */
    public static volatile boolean climbCost = true;
    /**
     * J1 (10-18): a village walk asked for while the villager is in the air (mid-jump, falling)
     * starts from the map tile under it, not from the mid-air cell, which could put the route's
     * start on a ledge a block up beside it. {@code [AirStart]} debug lines. Off: the mid-air cell.
     * See {@code NavGraph#routeStart}.
     */
    public static volatile boolean airStart = true;
    /**
     * Leftovers step 6 (10-18): a villager running away from a mob with no building to run to
     * (hurt guard, archer, mage, fleeing civilian or traveller) runs to village-map tiles about
     * 12 blocks from it, through gates and doors, never past the border. Off: Minecraft's path to
     * a point away from the mob. See {@code DefenderRetreat}.
     */
    public static volatile boolean fleeMap = true;
    /**
     * 10-18: a traveller's on-the-spot map walk (camp, stroll, tavern, guest bed, nomad's Town Hall
     * tile) gives up after 3 stucks on the way instead of re-planning for ever; it then counts as
     * "no map route" until the goal changes. Off: re-plans after every stuck. See
     * {@code TravelerApproach}.
     */
    public static volatile boolean travelGiveUp = true;
    /**
     * Leftovers step 7 (10-18): a guard's step back from an axe and its run clear of a lit creeper
     * go to a village-map tile on a route planned on the spot (no detours, never off a ledge the
     * map does not link). Off: Minecraft's path to a spot behind it. See {@code GuardFootwork}.
     */
    public static volatile boolean stepBackMap = true;
    /**
     * Leftovers step 8 (10-18): no straight Minecraft run to a shelter the map can't reach. A
     * villager too far from every map tile steps straight onto the nearest one (no drop over 3)
     * and plans again; a building on another map island is passed over (next one, then running
     * away). Off: the old straight run. See {@code DefenderRetreat}.
     */
    public static volatile boolean retreatMap = true;
    /**
     * Leftovers step 9 (10-18), OFF until compared in a raid: a guard's close chase (mob within 10
     * blocks, 18 for a shooter, level and in sight) walks a short village-map route planned on the
     * spot instead of Minecraft's path; same spot, same give-ups. {@code [ChaseMap]} line once a
     * minute. Off: Minecraft's path, the one deliberate leftover inside the village. See
     * {@code GuardFootwork}.
     */
    public static volatile boolean chaseMap = false;
    /**
     * Raider plan round 3 (10-19), ON since 10-23 (raider pathfinding round 1, not yet seen in a
     * raid): a villager-sized raider on foot
     * inside the village marches, loots, goes for a villager and runs home along the village map
     * (route follower), by its own route rules ({@code RouteRules.raider}). Off the map, in water,
     * mounted, or after two map stucks in a row (10 s): Minecraft's path as before. See
     * {@code village/raid/RaiderMapWalk}; counts in the {@code [RaidNav]} line.
     */
    public static volatile boolean raidMap = true;
    /**
     * Raider plan round 4 (10-19), ON since 10-23, works only with {@link #raidMap} on:
     * a raider whose foe is over 12 blocks away (16 with a bow) or out of sight for a second walks
     * a village map route toward it; in sight within 8 blocks (12 with a bow) Minecraft's own
     * attack goal does the last of it, as before. See {@code village/raid/RaidApproachGoal}.
     */
    public static volatile boolean raidChase = true;
    /**
     * Raider plan round 5 (10-19), ON since 10-23, works only with {@link #raidMap} on:
     * the Warlord and ravagers walk by village map route over the tiles a big body fits on (2 by 2,
     * 3 high, no doors; {@code WideTiles}), each 10-block stretch on Minecraft's own path. The
     * Warlord's chase, detour and "is there a way" test use it too; a ravager's fight approach
     * also needs {@link #raidChase}. See {@code village/raid/WideMapWalk}.
     */
    public static volatile boolean raidBig = true;
    /**
     * Raider bug list 8 (10-19): the kidnapped villager is led at its captor's pace (the captor's
     * speed against its own, a little quicker when over 4 blocks behind, never over a run) and gets
     * a new Minecraft path only when the captor moved 1.5 blocks (about twice a second behind a
     * walking pillager) or after a second. Off: speed 1.3
     * (about twice a pillager) and a new path every quarter second, as before. See
     * {@code RaidManager.tickCaptive}.
     */
    public static volatile boolean captivePace = true;
    /**
     * Raider bug list 10 (10-19), only with {@link #raidMap} on: a raider walking the map toward
     * someone who moves keeps walking its old route while the new one is planned, and swaps when it
     * arrives. Off: it stops for the tick or two the planning takes, as before. See
     * {@code village/raid/RaiderMapWalk}.
     */
    public static volatile boolean raidReplan = true;
    /**
     * Raider pathfinding round 2 (10-23): a marching raider with every door within 8 blocks (a tiny
     * village) goes to the furthest door over 7 blocks away, else the middle of the doors, else a spot
     * 8 to 12 blocks out round it. Off: no march target, it stands still until it sees someone, as
     * before. See {@code RaidManager.pickMarchTarget}.
     */
    public static volatile boolean raidNear = true;
    /**
     * Raider pathfinding round 3 (10-23): a raid camp (and the horde's sides, the scout's post) goes
     * on ground the village map joins to a building door whenever such a spot is found, flat before
     * uneven; nothing joined far out: tried closer in. Off: flat ground with room first, anywhere,
     * so a camp could sit across a river or cliff from the village, as before. See {@code RaidCamp.findSpot}.
     */
    public static volatile boolean raidCampIn = true;
    /**
     * Raider pathfinding round 4 (10-23), only with {@link #raidMap} on: when the map finds no way to a
     * marcher's door (no route, or stuck twice in a row), it leaves that door alone 30 s and goes for
     * another; a looter with no way to its chest gives it up and fights. No other door left, the camp,
     * running home, a snatch: Minecraft's path as before. Off: Minecraft's path for 2 to 10 s, which
     * presses into the wall nearest the door. See {@code RaidMarchGoal.giveUpOn}.
     */
    public static volatile boolean raidNoVanilla = true;
    /**
     * Raider pathfinding round 5a (10-23), only with {@link #raidMap} on: a raider on the village map in
     * water too deep to wade swims to the nearest dry map tile the map walks on from to its goal (else
     * the least swim plus walk), then walks the map; a toe in the water at a bank is walked on. Off: any
     * water means Minecraft's path toward the goal itself, as before. See {@code RaiderMapWalk.swimAshore}.
     * Since bug B6 (10-23) also the walk toward a foe ({@code RaidApproachGoal}): a toe in the water no
     * longer hands the fight to Minecraft.
     */
    public static volatile boolean raidWater = true;
    /**
     * Raider pathfinding bug B5 (10-23): "does this camp spot (or way out of the water) join the
     * village" asks the raider's own rules too (no ladders, no ranch gates), with one route search
     * after the map's quick answer, a few per pick. Off: the quick answer only, by villager rules,
     * so a camp could be joined only by a ladder or a ranch gate. See {@code RaiderMapWalk.raiderReaches}.
     */
    public static volatile boolean raidJoinRules = true;
    /**
     * Raider pathfinding bug B8, old item 21 (10-23), only with {@link #raidMap} on: a route to someone
     * moving walked to its end is planned again at once when they moved on (before: a pause of up to
     * 1 s); when they did not, the route cannot get nearer, so Minecraft's path does the last of it (the
     * attack goal for its 10 s, the grab for 2 s). Off: wait for the next plan and walk the same route again,
     * which stood a raider for good beside a foe behind a wall. See {@code RaiderMapWalk} DONE.
     */
    public static volatile boolean raidChaseEnd = true;
    /**
     * Raider pathfinding bug B3 (10-23): a raider's walk on Minecraft's path (a march leg, running home,
     * a snatch chase) is searched 40 walked blocks (Minecraft: the follow range, a vindicator's 12), and
     * a half path (no full way: Minecraft then walks to the spot nearest the goal, often a wall) is not
     * walked: counted as no path, tried again in a second. Off: half paths walked, as before. See
     * {@code RaidLegs.far}.
     */
    public static volatile boolean raidHalfPath = true;
    /**
     * Raider pathfinding bug B4 (10-23): a raider's legs on Minecraft's path are kept until walked (or
     * 10 s), not swapped every second; each is the spot nearest the goal within 18 degrees of it, the
     * 90-degree fan after a blocked leg or when none is near. Off: a new random leg every second, the
     * darkest of 10 in a 90-degree fan, so raiders zigzagged. See {@code RaidLegs.pickLeg}.
     */
    public static volatile boolean raidLegs = true;
    /**
     * Raider pathfinding bug B8, old item 14 (10-23), only with {@link #raidMap} on: a foe off the map's
     * tiles (on a roof, down a hole) is walked to the map tile nearest it (6 across, 8 up or down), and
     * a foe just outside the border to the map's edge toward it; Minecraft's attack does the rest. Off:
     * the fight was Minecraft's from wherever the raider stood. See {@code RaiderMapWalk.routeGoal}.
     */
    public static volatile boolean raidFoeNear = true;
    /**
     * Raider pathfinding bug B8, old item 17 (10-23), only with {@link #raidBig} on: the big-body tile
     * map is made again 5 s after the village map changed near a big body (a wall built or broken),
     * not only every 30 s. Off: up to 30 s old, as before. See {@code WideMapWalk.tileRules}.
     */
    public static volatile boolean raidBigFresh = true;
    /**
     * Raider plan bug 5 (10-19): an iron door is redstone's, as in Minecraft. Villagers never open
     * or shut one (walk, lead, evening greeting); their routes go through it only while it stands
     * open, looked at once a second ({@code NavGraph.refreshShutIronDoors}). Off: they open and shut
     * iron doors like wooden ones, so a locked iron door keeps nobody in or out, as before.
     */
    public static volatile boolean ironDoors = true;
    /**
     * Raider plan bug 24, fix 1 (10-19): the Warlord's walking turns at most
     * {@link #warlordTurnDegrees} a tick (Minecraft: 90), and eases its pace while the way is far
     * off where it faces. Off: Minecraft's snap turns, as before. See {@code WarlordMoveControl}.
     */
    public static volatile boolean warlordTurn = true;
    /** Live tune for {@link #warlordTurn}: degrees a tick ({@code /villager walkfeel warlordturndeg}). */
    public static volatile int warlordTurnDegrees = 20;
    /**
     * 10-18: a walking villager whose body stands outside every player's simulation distance
     * (Minecraft does not move it there) waits instead of being taken for stuck, and walks on
     * once the ground runs again ({@code [FrozenGround]} lines). Off: no progress, stuck,
     * re-plans, safe-spot hops, good steps marked failed. See {@code VillagerModel}.
     */
    public static volatile boolean frozenGround = true;
    /**
     * 10-18: a tree that grows is re-read into the village map about a second later, not after
     * the ten-second quiet wait, and a druid that grew it waits for that (at most 3 s) before
     * walking on. Off: the druid's next walk could be planned straight through the new trunk.
     * See {@code NavDirtyTracker.markSoon} and {@code DruidActivity}.
     */
    public static volatile boolean newTree = true;
    /**
     * 10-18: the walker lines up for a door, gate or trapdoor only on the villager's own floor and
     * at most 3 route tiles ahead. Off: the next one on the route by flat distance alone, so a door
     * straight above or below (a two-storey house) turned villagers back through the door they had
     * just used, or pressed them into a closed door that was not on their route. See {@code FollowWalk}.
     */
    public static volatile boolean doorFloor = true;
    /**
     * D1 (10-18): a door, gate or open trapdoor on the route counts as passed only once the body is
     * in its lane (within 1.5 blocks to the side of the gap's line) as well as past its middle. A
     * route round a house to its door had it passed while the villager was still beside the house,
     * so it never lined up and ran into the open panel. Off: past the middle alone.
     * See {@code FollowWalk#threadNarrow}.
     */
    public static volatile boolean doorLane = true;
    /**
     * Player hit, step 1 (10-18): a villager hiding in a building that a player hits there (on its
     * floor, or within 4 blocks from the doorway) leaves for another building, or runs away when
     * there is none. A player standing in the way out gets the far corner first; the next hit runs.
     * Step 2: a villager hit by a player while it stands in any building (working, eating, woken in
     * bed) does not stay put there or run back into it while the player is on its floor or within 6
     * blocks of its door. Step 3: the player who hit it counts as danger while within 16 blocks,
     * up to a minute after the last hit, so the hide does not end with the player beside it.
     * Step 4: chased out of a second building by the same player, the rest of that flee runs over
     * open ground away from the player (inside the village border), no more buildings.
     * {@code [PlayerFlee]} diary lines. Off: a hit from inside the building changed nothing (the
     * monster watch never counts a player as inside), a villager already indoors stayed where it
     * was, and the hide ended 10 s in whoever stood there. See {@code DefenderRetreat#retarget},
     * {@code #planShelter} and {@code FleeActivity#hitByNear}.
     */
    public static volatile boolean playerFlee = true;
    /**
     * Tree judge R1 (10-19): stripped logs and bark blocks ({@code _wood}, {@code _hyphae}) are never
     * part of a tree: the tree search never enters them and the chop never breaks them. Off: they
     * counted as the tree's own wood, so a stripped pillar beside a tree was cut with it.
     * See {@code TreeJudge}.
     */
    public static volatile boolean treePlain = true;
    /**
     * Tree judge R8 (10-19): a sideways log resting on a sideways log that lies on the ground (a log
     * pile, up to 4 high) also lies, so a tree beside a pile never swallows it. Off: only the bottom
     * row of a pile counted as lying. See {@code TreeJudge}.
     */
    public static volatile boolean treePiles = true;
    /**
     * Tree judge R2 (10-19): a log with a built block (planks, glass, cobblestone, a torch: the
     * {@code tree_build_marks} tag), a stripped log or placed leaves right above or below it belongs
     * to a build, and its whole log group is left standing. Off: builds were judged by logs and
     * leaves alone. See {@code TreeJudge}.
     */
    public static volatile boolean treeMarks = true;
    /**
     * Tree judge R3 (10-19): a tree's base must be a trunk foot on natural ground with logs going up,
     * nothing built or of another wood sitting on top, and a top with its own leaves round it on 3
     * of 5 sides. A branch registered as a base hands over its trunk's foot. Off: any log group with
     * leaves of its kind within 2 blocks was a tree, so a house pillar under a neighbour tree's
     * canopy was felled. See {@code TreeJudge}.
     */
    public static volatile boolean treeCrown = true;
    /**
     * Tree judge R4 + R5 (10-19), OFF at start until an in-game check of every tree kind: a log
     * group is shared out between its trunks, its builds and the unknown past its edge, each
     * taking the logs nearest it (ties to builds), so a tree touching a house post, another tree or
     * a big forest is felled alone. A build joined to the tree with no ground of its own (a
     * treehouse, a lantern on a branch) keeps the whole tree standing. Off: the whole group is one
     * tree, and a build mark anywhere in it leaves all of it standing. See {@code TreeJudge}.
     */
    public static volatile boolean treeSplit = false;
    /**
     * Tree judge R7 (10-19): a big 2x2 trunk is found at the base's own layer (its low corner is
     * the replant spot), so a smaller tree joined to it lower on a slope no longer hides it. Off:
     * only a square at the group's lowest layer counted. See {@code TreeJudge}.
     */
    public static volatile boolean tree2x2 = true;
    /**
     * Tree list round 1 (10-22): a tree stays on the village's tree list until it falls (cleanup
     * begins), so a chop stopped mid-swing (an alarm, an attack, the axe changed) leaves it listed
     * for the next lumberjack. Others still skip it while its job is held. Off: it left the list at
     * the first swing and only the next village scan found it again. See {@code ChopTreeActivity}.
     */
    public static volatile boolean treeKeep = true;
    /**
     * Tree list round 2 (10-22), meant with {@link #treeKeep}: a chop stopped mid-swing by an
     * emergency is paused, not ended. The lumberjack keeps the tree reserved and its swing count,
     * and walks back to finish it, before looking for another tree. A job change, or no work it
     * can do while not working, lets go of a reserved tree. Off: every stopped chop let go of the
     * tree and its swings, and a job change kept a tree reserved until the villager unloaded.
     * See {@code ChopTreeActivity#stop}, {@code LumberjackActivity}, {@code LumberjackProfession}.
     */
    public static volatile boolean treeResume = true;
    /**
     * Tree list round 3 (10-22): a sapling that grew is looked up in its own column first, then the
     * rings round it, and leaves never count as ground, so a bent acacia, azalea or mangrove, or a
     * sapling two blocks from a grown tree, lists its own new tree. A refused lookup hands over its
     * trunk's foot, and a new tree joined to one already listed is not listed twice. Off: the first
     * standing log from the west/north corner of a 5x5 won, often a branch on leaves or the
     * neighbour's foot, and the new tree waited for the next village scan. See {@code GrowFoot}.
     */
    public static volatile boolean growFoot = true;
    /**
     * Tree follow-up N2 (10-22): a tree sharing logs with one another lumberjack holds is passed
     * over by the search and the work check, and cannot be reserved, so two lumberjacks never walk
     * to one joined group (with {@link #treeSplit} off it can be listed under two bases). Off: each
     * base was reserved on its own and the slower lumberjack found the logs gone.
     * See {@code LumberjackTreeRegistry#overlapsHeldTree}.
     */
    public static volatile boolean treeOneClaim = true;
    /**
     * Tree follow-up N1 (10-22), with {@link #growFoot}: a tree grown from a sapling that the tree
     * judge could not see whole (part in an unloaded chunk) is looked at again every 10 s for up to
     * 5 minutes, and listed once it can be. Off: it waited for the next village scan (wake or
     * reload). See {@code VillageManager#retryGrownTrees}.
     */
    public static volatile boolean growRetry = true;

    /**
     * Tree follow-up N3 (10-22): felling a group of touching trees (with {@link #treeSplit} off they
     * come down as one) replants every trunk of it, each spot checked on its own. Off: only the
     * felled base's spot. See {@code TreeJudge#otherTrunks}.
     */
    public static volatile boolean replantAll = true;

    /**
     * Tree bug B9 (10-22): a felled tree is never left half cleared. A chop stopped during cleanup
     * (an emergency, a job change, death) takes the rest of the logs down at once and replants; a
     * block in an unloaded chunk is passed by instead of ending the cleanup; a timed-out cleanup
     * finishes the same way. Leaves stay and decay. Off: the rest of the tree was left hanging.
     * Not when the lumberjack unloads mid-cleanup (its pack is saved already).
     */
    public static volatile boolean treeFinish = true;

    /**
     * Tree soil (10-22), with {@link #treeCrown}: a trunk's feet must stand on soil (tag
     * {@code towntopia:tree_soil}: dirt kinds, mangrove roots). Stone, sand, ores, metal blocks...
     * under a trunk leave it standing, as a built block does. Off: any solid natural block was ground.
     */
    public static volatile boolean treeSoil = true;

    /**
     * Chop check (10-22): the lumberjack judges its tree again on arrival, before the first swing,
     * and while swinging judges it again whenever a block under a trunk foot changes. Not a tree any
     * more: it stops, the tree leaves the list, the job is let go. Off: only "2 logs still stand"
     * until the fall.
     */
    public static volatile boolean chopCheck = true;

    /**
     * Tree leaves (10-22): a felled tree takes only its own leaves, the ones its logs are strictly
     * nearer than any other log (any wood, stripped too; a town building's wall does not count). A
     * tie stays. Off: every leaf of its kind within 2 blocks went, also from the tree beside it.
     */
    public static volatile boolean treeLeaves = true;

    /**
     * Leaf cap (10-22): a tree with more than 1024 falling leaves (2-3 tall mangroves grown
     * together) takes the 1024 nearest its logs instead of being refused; the rest decay. Off: such
     * a group was never cut.
     */
    public static volatile boolean leafCap = true;

    /**
     * Builder copy, round 2 (10-23): stone, dirt, sand, snow and ice above a building's lowest floor
     * come along when the world generator's bare land is air or water there (a player placed them),
     * so stone walls and chimneys copy whole. Off: those always count as land. See {@code PlacedJudge}.
     */
    public static volatile boolean copyLand = true;
    /**
     * Builder copy, round 2: soil the bare land calls placed comes only when it looks like part of
     * the building (not on the land, inside the walls, or under building); a mound stays out.
     */
    public static volatile boolean copySoil = true;
    /**
     * Builder copy, round 2: a copy whose surroundings don't match the world generator (older
     * chunks, terrain mods) keeps today's rule. Off: always trust the bare land (testing only).
     */
    public static volatile boolean copyTrust = true;
    /**
     * Builder copy, round 4 (10-23): a log today's rule calls a tree's (tree leaves touch its column)
     * counts as building when it is stripped or bark, or its column stands on a building block.
     */
    public static volatile boolean copyLogs = true;
    /**
     * Builder copy, round 5 (10-23): land blocks players and Builders placed are remembered
     * ({@code PlacedLand}) and always count as placed above the lowest floor, even where the land was.
     */
    public static volatile boolean copyMemory = true;
    /**
     * Builder copy, round 7 (10-23): terracotta, infested stone, packed and blue ice stay out where
     * the world made them (bare land solid, or water for the ice): mesas and icebergs. Off: always building.
     */
    public static volatile boolean copyLookalikes = true;
    /**
     * Builder copy (10-25): a wall outside the room's footprint brings the floor-row block it stands on.
     * The block beside a campfire under the wall was left out (no villager stands on a campfire).
     */
    public static volatile boolean copyFooting = true;
    /**
     * Builder orders (10-25): the right block changed only by use (door open, furnace out, fence joined
     * another way) counts as built. Off: such a block is broken and placed again, every pass.
     */
    public static volatile boolean buildLive = true;
    /**
     * Builder orders (10-25): clearing a door's top or a bed's foot breaks the half that drops the item, so it
     * goes in the pack. Off: the other half breaks by itself and throws the item on the ground.
     */
    public static volatile boolean buildPairs = true;
    /**
     * Builder orders (10-25, two Builders round 2): a second Builder works an order from the far end of each
     * layer; they meet in the middle. Off: one Builder per order (a returning one still checks its hold).
     */
    public static volatile boolean buildPair = true;
    /**
     * Two Builders round 5 (10-25): stand spots keep a block away from the other Builder; a cell it stands in
     * waits 3 s once before being put off. Off: as alone.
     */
    public static volatile boolean buildApart = true;
    /**
     * Builder bug review round 4 (10-25): a fetch or making trip stops as soon as its order is cancelled or
     * paused. Off: the trip finishes first (up to ~2 minutes of making blocks for an order that is gone).
     */
    public static volatile boolean buildErrandStop = true;
    /**
     * Builder bug review round 5 (10-25): switching the Builder's Build setting off stops its half-done build
     * work once (a held table is let go). Off: the trip stays frozen, the Builder counts as walking while inspecting.
     */
    public static volatile boolean buildOffStop = true;
    /**
     * Builder full review round 1 (10-25): block data from a blueprint file is applied as vanilla allows a non-operator:
     * a sign keeps its words but loses click commands; spawners, command blocks, lecterns get none. Off: all of it applied.
     */
    public static volatile boolean buildSafeData = true;
    /**
     * Builder full review round 3 (10-25): grass, podzol or mycelium grown over dirt the blueprint wants counts as built.
     * Off: it is dug out and dirt put back every pass (an order with a block put off then never ends).
     */
    public static volatile boolean buildDirtGrass = true;
    /**
     * Builder full review round 2 (10-25): a blueprint's beehive comes without its bees, a furnace without its stored
     * experience. Off: each build of a copy hands them out again.
     */
    public static volatile boolean buildStripMore = true;
    /**
     * Builder full review round 4 (10-25): a cell in an unloaded part of the site makes the Builder wait 30 s. Off: the
     * cell is put off, and three passes later the order ends with those blocks missing.
     */
    public static volatile boolean buildUnloaded = true;

    /**
     * Round 4, drawing only: the most the drawn body turns per tick while
     * walking, in degrees. 0 (or 180 and up) snaps to the walking direction as
     * before.
     */
    public static volatile float drawnTurnDegrees = 20f;

    private WalkFeel() {}

    /** The movement switches that are on, for the log. */
    public static String roundsOn() {
        StringBuilder on = new StringBuilder();
        if (legStart) on.append(" legstart");
        if (ease) on.append(" ease");
        if (chaseHandover) on.append(" handover");
        if (steadySpot) on.append(" guardspot");
        if (chaseMargin) on.append(" guardmargin");
        if (ladderLog) on.append(" ladderlog");
        if (ladderCross) on.append(" laddercross");
        if (ladderSide) on.append(" ladderside");
        if (ladderLanding) on.append(" ladderlanding");
        if (ladderPerch) on.append(" ladderperch");
        if (ladderChain) on.append(" ladderchain");
        if (ladderLedge) on.append(" ladderledge");
        if (ladderSlab) on.append(" ladderslab");
        if (ladderBlame) on.append(" ladderblame");
        if (ladderFace) on.append(" ladderface");
        if (stuckDetailed) on.append(" stuckdetailed");
        if (noProduceStep) on.append(" produce");
        if (stickySteps) on.append(" stickysteps");
        if (bumpLearn) on.append(" bumplearn");
        if (stuckLoop) on.append(" stuckloop");
        if (cornerRound) on.append(" cornerround");
        if (vanillaClimb) on.append(" vanillaclimb");
        if (arrowMap) on.append(" arrowmap");
        if (campMap) on.append(" campmap");
        if (nomadMap) on.append(" nomadmap");
        if (wakeSpot) on.append(" wakespot");
        if (wakeCell) on.append(" wakecell");
        if (wakeBedside) on.append(" wakebedside");
        if (rescueNoWall) on.append(" rescuenowall");
        if (trapNoTile) on.append(" trapnotile");
        if (tieBreak) on.append(" tiebreak");
        if (climbCost) on.append(" climbcost");
        if (airStart) on.append(" airstart");
        if (fleeMap) on.append(" fleemap");
        if (travelGiveUp) on.append(" travelgiveup");
        if (frozenGround) on.append(" frozenground");
        if (newTree) on.append(" newtree");
        if (doorFloor) on.append(" doorfloor");
        if (doorLane) on.append(" doorlane");
        if (playerFlee) on.append(" playerflee");
        if (treePlain) on.append(" treeplain");
        if (treePiles) on.append(" treepiles");
        if (treeMarks) on.append(" treemarks");
        if (treeCrown) on.append(" treecrown");
        if (treeSplit) on.append(" treesplit");
        if (tree2x2) on.append(" tree2x2");
        if (stepBackMap) on.append(" stepbackmap");
        if (retreatMap) on.append(" retreatmap");
        if (chaseMap) on.append(" chasemap");
        if (raidMap) on.append(" raidmap");
        if (raidChase) on.append(" raidchase");
        if (raidBig) on.append(" raidbig");
        if (captivePace) on.append(" captivepace");
        if (raidReplan) on.append(" raidreplan");
        if (raidNear) on.append(" raidnear");
        if (raidCampIn) on.append(" raidcampin");
        if (raidNoVanilla) on.append(" raidnovanilla");
        if (raidWater) on.append(" raidwater");
        if (raidJoinRules) on.append(" raidjoinrules");
        if (raidChaseEnd) on.append(" raidchaseend");
        if (raidHalfPath) on.append(" raidhalfpath");
        if (raidLegs) on.append(" raidlegs");
        if (raidFoeNear) on.append(" raidfoenear");
        if (raidBigFresh) on.append(" raidbigfresh");
        if (ironDoors) on.append(" irondoors");
        if (warlordTurn) on.append(" warlordturn");
        return on.length() == 0 ? "none" : on.substring(1);
    }
}
