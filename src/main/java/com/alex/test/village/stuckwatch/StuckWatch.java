package com.alex.test.village.stuckwatch;

import com.alex.test.entity.NpcEntity;
import com.alex.test.network.StuckWatchPayloads;
import com.alex.test.village.MoveToCommand;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillagerModel;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The stuck catcher ({@code /villager stuckwatch}, 2026-10-18). Off unless
 * turned on; nothing is saved.
 *
 * <p>While on, every villager near a player keeps a {@link BlackBox}. When one
 * is caught stuck (the walker's stuck rules, a rescue teleport, a destination
 * given up, a body inside a block, a trapped villager, or with {@code early}
 * the first signs of a stuck), a report file is written and the whole world
 * freezes (Minecraft's own tick freeze): the player can fly round, step the
 * world a tick at a time, scrub the last 15 s as a ghost, put the villager
 * back a few seconds and watch it again, then resume. In record-only mode the
 * report is written and nothing freezes.
 *
 * <p>Village code ticks from level-tick events that Minecraft fires even while
 * frozen; those handlers return early on {@link #worldHeld}.
 */
public final class StuckWatch {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Kind {
        STUCK("stuck", "stuck"),
        EARLY("early", "early sign of stuck"),
        RESCUE("rescue", "rescue teleport"),
        GAVE_UP("gaveup", "gave up its destination"),
        BLOCKED("blocked", "inside a block"),
        TRAPPED("trapped", "trapped");

        public final String id;
        public final String label;
        boolean on = true;

        Kind(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public boolean on() { return on; }
        public void set(boolean on) { this.on = on; }
    }

    /**
     * What a catch knew at the moment it was caught, before the walker reacted. {@code x, y, z,
     * feet} are where it stood then (null feet: no body): a trapped rescue moves it in the same
     * tick, and the report's map used to show the tile it was moved to instead.
     */
    public record PendingCatch(Kind kind, String reason, @Nullable NavGraph graph, String spotLine,
                               String walkerState, @Nullable List<BlockPos> route, int wpIndex,
                               @Nullable BlockPos goal, float speed, boolean smoothed, int repaths,
                               int recoveries, String job, List<BlockPos> minecraftPath, int minecraftNext,
                               double x, double y, double z, @Nullable BlockPos feet) {}

    /** One catch: what it knew, the black box copy and where its report went. */
    public static final class StuckCatch {
        public final int id;
        public final PendingCatch at;
        public final VillagerModel model;
        public final UUID npcId;
        public final int entityId;
        public final String name;
        public final String profession;
        public final long tick;
        public final long wallMillis;
        public final double x, y, z;
        public final BlockPos feet;
        public final List<BlackBox.Frame> frames;
        public final List<BlackBox.Route> routes;
        public final List<BlackBox.RestorePoint> restorePoints;
        @Nullable public java.nio.file.Path reportPath;

        StuckCatch(int id, PendingCatch at, VillagerModel model, NpcEntity npc, long tick,
                   List<BlackBox.Frame> frames, List<BlackBox.Route> routes, List<BlackBox.RestorePoint> restorePoints) {
            this.id = id;
            this.at = at;
            this.model = model;
            this.npcId = model.npcId;
            this.entityId = npc.getId();
            String given = model.name();
            this.name = given == null || given.isBlank() ? model.npcId.toString().substring(0, 8) : given;
            this.profession = npc.getProfession().name().toLowerCase(Locale.ROOT);
            this.tick = tick;
            this.wallMillis = System.currentTimeMillis();
            boolean caughtSpot = at.feet() != null;
            this.x = caughtSpot ? at.x() : npc.getX();
            this.y = caughtSpot ? at.y() : npc.getY();
            this.z = caughtSpot ? at.z() : npc.getZ();
            this.feet = caughtSpot ? at.feet() : NavGraph.feetCell(npc);
            this.frames = frames;
            this.routes = routes;
            this.restorePoints = restorePoints;
        }

        public String shortId() { return npcId.toString().substring(0, 8); }
    }

    private record Cooldown(long tick, BlockPos feet) {}

    private record Glow(NpcEntity npc, boolean had) {}

    private static boolean enabled;
    private static boolean recordOnly;
    private static boolean early;
    private static long generation;
    @Nullable private static String onlyName;
    private static int radius = 64;
    private static int cooldownSeconds = 20;

    @Nullable private static StuckCatch current;
    /** The world is frozen on a catch and the ghost is up. */
    private static boolean viewing;
    /** This catcher froze the world (so resume unfreezes it). */
    private static boolean weFroze;
    /** The tick rate before {@code play} slowed it, or -1. */
    private static float savedRate = -1.0F;
    private static int catchCounter;
    private static final Map<UUID, Glow> GLOWED = new HashMap<>();
    private static final Map<VillagerModel, Boolean> BRAIN_HELD = new IdentityHashMap<>();
    private static final Map<String, Cooldown> COOLDOWNS = new HashMap<>();

    private StuckWatch() {}

    // -------------------------------------------------------------------------
    // Asked by the village code
    // -------------------------------------------------------------------------

    /** True while the catcher is on, so villagers near players keep their black box. */
    public static boolean recording() { return enabled; }

    /** Bumped on every on/off, so black boxes from an earlier session start clean. */
    public static long generation() { return generation; }

    public static boolean earlyOn() { return enabled && early; }

    /**
     * True while Minecraft's world freeze holds the game (the tick freeze, between
     * steps). Village code ticked from level-tick events skips its turn then, as
     * entities do, so brains and walkers do not run on with frozen bodies.
     */
    public static boolean worldHeld(Level level) {
        return !level.tickRateManager().runsNormally();
    }

    public static boolean worldHeld(MinecraftServer server) {
        return !server.tickRateManager().runsNormally();
    }

    /** Whether this villager is one to record: near a player, and the one picked if one is. */
    static boolean wantsRecording(VillagerModel model, NpcEntity npc) {
        if (!enabled) return false;
        if (onlyName != null && !matches(model, onlyName)) return false;
        double r2 = (double) radius * radius;
        for (var player : npc.level().players()) {
            if (player.distanceToSqr(npc) <= r2) return true;
        }
        return false;
    }

    static boolean matches(VillagerModel model, String wanted) {
        String id = model.npcId.toString();
        if (id.startsWith(wanted.toLowerCase(Locale.ROOT))) return true;
        String name = model.name();
        return name != null && name.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT));
    }

    /**
     * A villager was caught: {@code reason} says how, in words. Kept until the
     * end of the villager's tick, so the frame has the walker's whole turn;
     * what the walk was is read now, before the walker drops it.
     */
    public static void caught(VillagerModel model, @Nullable NavGraph graph, Kind kind, String reason) {
        if (!enabled || !kind.on) return;
        if (kind == Kind.EARLY && !early) return;
        BlackBox box = model.blackBox();
        if (!box.recordingNow()) return;
        try {
            box.setPendingCatch(pending(model, graph, kind, reason));
        } catch (RuntimeException e) {
            LOGGER.warn("[StuckCatch] could not read the walk of {} at a catch", model.npcId, e);
        }
    }

    static PendingCatch pending(VillagerModel model, @Nullable NavGraph graph, Kind kind, String reason) {
        NpcEntity npc = model.puppet();
        MoveToCommand move = model.activeMove();
        String spot;
        try {
            spot = model.stuckSpotLine(graph);
        } catch (RuntimeException e) {
            spot = "(could not read: " + e + ")";
        }
        String walker = move == null || npc == null ? "no walk" : move.debugState(npc);
        List<BlockPos> mcPath = new ArrayList<>();
        int mcNext = -1;
        if (npc != null) {
            Path path = npc.getNavigation().getPath();
            if (path != null) {
                for (int i = 0; i < path.getNodeCount(); i++) mcPath.add(path.getNodePos(i));
                mcNext = path.getNextNodeIndex();
            }
        }
        var activity = model.brain().currentActivity();
        String job = activity == null ? "none" : activity.name() + " (" + activity.getClass().getSimpleName() + ")";
        return new PendingCatch(kind, reason, graph, spot, walker,
                move == null ? null : move.waypoints(), move == null ? -1 : move.currentWaypointIndex(),
                model.moveGoal(), model.moveSpeed(), model.moveSmoothed(), model.stuckRepaths(),
                model.stuckRecoveryCount(), job, List.copyOf(mcPath), mcNext,
                npc == null ? 0 : npc.getX(), npc == null ? 0 : npc.getY(), npc == null ? 0 : npc.getZ(),
                npc == null ? null : NavGraph.feetCell(npc));
    }

    /** End of the caught villager's tick: report, then freeze (unless record-only). */
    static void finish(VillagerModel model, NpcEntity npc, PendingCatch p) {
        if (!enabled || !(npc.level() instanceof ServerLevel level)) return;
        if (!model.blackBox().fullyRecorded()) {
            // Right after turning on (or coming into range) there is too little to look back on.
            LOGGER.debug("[StuckCatch] {} {} not caught: only {} s recorded", model.npcId, p.kind().id,
                    model.blackBox().recordedSeconds());
            return;
        }
        MinecraftServer server = level.getServer();
        long now = level.getGameTime();
        if (current != null && current.tick == now && (!recordOnly || current.npcId.equals(model.npcId))) return;
        BlockPos feet = p.feet() != null ? p.feet() : NavGraph.feetCell(npc);
        String key = model.npcId + "/" + p.kind().id;
        Cooldown last = COOLDOWNS.get(key);
        if (last != null && now - last.tick() < cooldownSeconds * 20L && last.feet().distManhattan(feet) <= 2) return;
        COOLDOWNS.put(key, new Cooldown(now, feet));

        BlackBox box = model.blackBox();
        StuckCatch c = new StuckCatch(++catchCounter, p, model, npc, now,
                box.snapshotFrames(), box.snapshotRoutes(), box.snapshotRestorePoints());
        try {
            String text = StuckReport.build(c, level, npc);
            c.reportPath = StuckReport.write(server, c, text);
        } catch (RuntimeException e) {
            LOGGER.warn("[StuckCatch] report for {} failed", c.shortId(), e);
        }
        current = c;
        LOGGER.info("[StuckCatch] #{} {} ({}) {} at {}: {}; report {}", c.id, c.name, c.profession,
                p.kind().label, c.feet.toShortString(), p.reason(),
                c.reportPath == null ? "not written" : c.reportPath.getFileName());

        if (recordOnly) {
            tell(server, header(c).append(Component.literal(" Saved, not frozen (record only). ")
                    .withStyle(ChatFormatting.GRAY)).append(button("Go there", "/villager stuckwatch goto",
                    "Fly to it")).append(" ").append(reportButton(c)));
            return;
        }
        ServerTickRateManager ticks = server.tickRateManager();
        if (!ticks.isFrozen()) {
            ticks.setFrozen(true);
            weFroze = true;
        } else if (ticks.isSteppingForward()) {
            ticks.stopStepping();
        }
        viewing = true;
        glow(npc);
        tell(server, catchMessage(c));
        sendShow(server, c, null);
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    public static void turnOn() {
        enabled = true;
        generation++;
        LogTail.attach();
    }

    public static void turnOff(MinecraftServer server) {
        if (viewing || weFroze || !BRAIN_HELD.isEmpty()) resume(server);
        enabled = false;
        generation++;
        LogTail.detach();
    }

    public static boolean enabled() { return enabled; }
    public static boolean recordOnly() { return recordOnly; }
    public static void setRecordOnly(boolean on) { recordOnly = on; }
    public static boolean early() { return early; }
    public static void setEarly(boolean on) { early = on; }
    @Nullable public static String onlyName() { return onlyName; }
    public static void setOnlyName(@Nullable String name) {
        onlyName = name == null || name.isBlank() ? null : name.strip();
        generation++;
    }
    public static int radius() { return radius; }
    public static void setRadius(int blocks) { radius = blocks; }
    public static int cooldownSeconds() { return cooldownSeconds; }
    public static void setCooldownSeconds(int seconds) { cooldownSeconds = seconds; }
    @Nullable public static StuckCatch current() { return current; }
    public static boolean viewing() { return viewing; }

    /** Unfreezes (if this froze it), back to full speed, hands brains back, takes the ghost down. */
    public static void resume(MinecraftServer server) {
        ServerTickRateManager ticks = server.tickRateManager();
        if (weFroze && ticks.isFrozen()) ticks.setFrozen(false);
        weFroze = false;
        if (savedRate > 0.0F) {
            ticks.setTickRate(savedRate);
            savedRate = -1.0F;
        }
        for (Map.Entry<VillagerModel, Boolean> held : BRAIN_HELD.entrySet()) {
            held.getKey().setBrainSuspended(held.getValue());
        }
        BRAIN_HELD.clear();
        for (Glow glow : GLOWED.values()) {
            if (glow.npc().isAlive()) glow.npc().setGlowingTag(glow.had());
        }
        GLOWED.clear();
        viewing = false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, new StuckWatchPayloads.Clear());
        }
    }

    /** Lets the frozen world run {@code ticks} ticks; false when it is not frozen. */
    public static boolean step(MinecraftServer server, int ticks) {
        return server.tickRateManager().stepGameIfPaused(ticks);
    }

    /** Unfreezes at {@code tps} ticks a second (slow motion) until resume. */
    public static void play(MinecraftServer server, float tps) {
        ServerTickRateManager ticks = server.tickRateManager();
        if (savedRate < 0.0F) savedRate = ticks.tickrate();
        ticks.setTickRate(tps);
        if (ticks.isFrozen()) ticks.setFrozen(false);
        weFroze = false;
    }

    /**
     * Puts the last caught villager back as it was {@code seconds} before the
     * catch: place, facing, motion, its route and waypoint, the doors around
     * it. Its brain is held on that walk until resume. Returns what happened.
     */
    public static Component rewind(MinecraftServer server, double seconds) {
        StuckCatch c = current;
        if (c == null) return Component.literal("Nothing caught yet.").withStyle(ChatFormatting.RED);
        VillagerModel model = c.model;
        NpcEntity npc = model.puppet();
        if (npc == null || !npc.isAlive() || !(npc.level() instanceof ServerLevel level)) {
            return Component.literal(c.name + " is not loaded.").withStyle(ChatFormatting.RED);
        }
        long target = c.tick - Math.round(seconds * 20.0);
        BlackBox.RestorePoint point = null;
        for (BlackBox.RestorePoint rp : c.restorePoints) {
            if (rp.tick() <= target) point = rp;
        }
        boolean fellShort = point == null;
        if (point == null && !c.restorePoints.isEmpty()) point = c.restorePoints.get(0);
        if (point == null || point.waypoints().isEmpty()) {
            return Component.literal("It was not walking in the recorded time; nothing to put it back to.")
                    .withStyle(ChatFormatting.RED);
        }
        ServerTickRateManager ticks = server.tickRateManager();
        if (!ticks.isFrozen()) {
            ticks.setFrozen(true);
            weFroze = true;
        } else if (ticks.isSteppingForward()) {
            ticks.stopStepping();
        }
        viewing = true;
        BRAIN_HELD.putIfAbsent(model, model.isBrainSuspended());
        model.setBrainSuspended(true);
        model.stopMoving();
        restoreDoors(level, point.doors());
        npc.teleportTo(point.x(), point.y(), point.z());
        npc.setYRot(point.yRot());
        npc.setYBodyRot(point.bodyRot());
        npc.setYHeadRot(point.headRot());
        npc.setDeltaMovement(point.vx(), point.vy(), point.vz());
        npc.resetFallDistance();
        PacketDistributor.sendToPlayersTrackingEntity(npc, new StuckWatchPayloads.Snap(npc.getId(),
                point.x(), point.y(), point.z(), point.yRot(), point.bodyRot(), point.headRot()));
        boolean followed = model.replayWalk(point.waypoints(), point.wpIndex(), point.goal(), point.speed(),
                point.smoothed(), point.repaths(), point.recoveries(), c.at.graph(), point.excluded());
        String walker = followed ? "route follower"
                : "could not rebuild its walk (the map no longer has its tiles)";
        COOLDOWNS.keySet().removeIf(key -> key.startsWith(model.npcId + "/"));
        glow(npc);
        long back = c.tick - point.tick();
        model.blackBox().event("put back " + back + " ticks before catch #" + c.id + " (rewind, " + walker + ")");
        LOGGER.info("[StuckCatch] #{} rewound {} to t-{} at ({}), wp {}/{}, {}", c.id, c.name, back,
                String.format(Locale.ROOT, "%.2f,%.2f,%.2f", point.x(), point.y(), point.z()),
                point.wpIndex() + 1, point.waypoints().size(), walker);
        // Restore points exist only while it walked: say so when none reached back far enough.
        String shortOf = fellShort
                ? String.format(Locale.ROOT, " (asked %.0f s; that is the earliest moment it was walking)", seconds)
                : "";
        return Component.literal(String.format(Locale.ROOT,
                        "Put %s back %.1f s (t-%d)%s, waypoint %d/%d, %s, brain held on that walk. ",
                        c.name, back / 20.0, back, shortOf, point.wpIndex() + 1, point.waypoints().size(), walker))
                .withStyle(ChatFormatting.AQUA)
                .append(button("Step 1", "/villager stuckwatch step 1", "One tick"))
                .append(" ").append(button("Step 20", "/villager stuckwatch step 20", "One second"))
                .append(" ").append(button("Play slow", "/villager stuckwatch play 4", "Run at 4 ticks a second"))
                .append(" ").append(button("Resume", "/villager stuckwatch resume", "Full speed, brain back"));
    }

    /** Flies {@code player} to a few blocks from the caught villager, looking at it. */
    public static boolean goTo(ServerPlayer player) {
        StuckCatch c = current;
        if (c == null) return false;
        NpcEntity npc = c.model.puppet();
        Vec3 at = npc != null && npc.isAlive() ? npc.position() : new Vec3(c.x, c.y, c.z);
        ServerLevel level = npc != null && npc.level() instanceof ServerLevel sl ? sl : player.serverLevel();
        double dx = player.getX() - at.x, dz = player.getZ() - at.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3) {
            dx = 1.0;
            dz = 1.0;
            len = Math.sqrt(2.0);
        }
        double px = at.x + dx / len * 4.0, py = at.y + 2.0, pz = at.z + dz / len * 4.0;
        double lx = at.x - px, ly = at.y + 1.2 - (py + player.getEyeHeight()), lz = at.z - pz;
        float yaw = (float) (Mth.atan2(lz, lx) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(ly, Math.sqrt(lx * lx + lz * lz)) * Mth.RAD_TO_DEG);
        player.teleportTo(level, px, py, pz, yaw, pitch);
        return true;
    }

    /** Sends the last catch's ghost to {@code only} (or every permitted player), the cursor at {@code offset} if given. */
    public static boolean sendShow(MinecraftServer server, @Nullable StuckCatch c, @Nullable ServerPlayer only) {
        if (c == null) return false;
        StuckWatchPayloads.Show show = StuckWatchPayloads.Show.of(c);
        if (only != null) {
            PacketDistributor.sendToPlayer(only, show);
            return true;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2)) PacketDistributor.sendToPlayer(player, show);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Chat
    // -------------------------------------------------------------------------

    private static MutableComponent header(StuckCatch c) {
        return Component.literal("[Stuck catch #" + c.id + "] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(c.name + " (" + c.profession + ") at "
                        + c.feet.getX() + " " + c.feet.getY() + " " + c.feet.getZ() + ": ")
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(c.at.reason()).withStyle(ChatFormatting.WHITE));
    }

    private static Component catchMessage(StuckCatch c) {
        PendingCatch p = c.at;
        MutableComponent text = header(c);
        String walk = p.route() == null ? " Not walking."
                : String.format(Locale.ROOT, " Walking to %s for %s, waypoint %d/%d.",
                p.goal() == null ? "?" : p.goal().toShortString(), p.job(), p.wpIndex() + 1, p.route().size());
        text.append(Component.literal(walk + " World frozen.\n").withStyle(ChatFormatting.GRAY));
        text.append(button("Resume", "/villager stuckwatch resume", "Unfreeze at full speed"));
        text.append(" ").append(button("Step 1", "/villager stuckwatch step 1", "Let one tick run"));
        text.append(" ").append(button("Step 20", "/villager stuckwatch step 20", "Let one second run"));
        text.append(" ").append(button("Back 3 s", "/villager stuckwatch rewind 3",
                "Put it back 3 s and hold it there, frozen"));
        text.append(" ").append(button("Back 8 s", "/villager stuckwatch rewind 8",
                "Put it back 8 s and hold it there, frozen"));
        text.append(" ").append(button("Back 15 s", "/villager stuckwatch rewind 15",
                "Put it back to the start of the recording and hold it there, frozen"));
        text.append(" ").append(button("Play slow", "/villager stuckwatch play 4", "Run at 4 ticks a second"));
        text.append(" ").append(button("Go there", "/villager stuckwatch goto", "Fly to it"));
        text.append(" ").append(reportButton(c));
        text.append(Component.literal("\nGhost: [ and ] step through the last 15 s (hold Shift for a second at a time), \\ hides the panel.")
                .withStyle(ChatFormatting.DARK_GRAY));
        return text;
    }

    static MutableComponent button(String label, String command, String hover) {
        return Component.literal("[" + label + "]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    static MutableComponent reportButton(StuckCatch c) {
        if (c.reportPath == null) return Component.literal("[no report]").withStyle(ChatFormatting.DARK_GRAY);
        String path = c.reportPath.toAbsolutePath().toString();
        return Component.literal("[Report]").withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, path))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Click to copy the path:\n" + path))));
    }

    private static void tell(MinecraftServer server, Component message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2)) player.sendSystemMessage(message);
        }
    }

    // -------------------------------------------------------------------------
    // World
    // -------------------------------------------------------------------------

    private static void glow(NpcEntity npc) {
        GLOWED.putIfAbsent(npc.getUUID(), new Glow(npc, npc.hasGlowingTag()));
        npc.setGlowingTag(true);
    }

    private static void restoreDoors(ServerLevel level, List<BlackBox.DoorState> doors) {
        for (BlackBox.DoorState door : doors) {
            BlockState state = level.getBlockState(door.pos());
            if (!state.hasProperty(BlockStateProperties.OPEN) || state.getValue(BlockStateProperties.OPEN) == door.open()) {
                continue;
            }
            if (state.getBlock() instanceof DoorBlock doorBlock) {
                doorBlock.setOpen(null, level, state, door.pos(), door.open());
            } else {
                level.setBlock(door.pos(), state.setValue(BlockStateProperties.OPEN, door.open()),
                        Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer leaving) || !(viewing || weFroze || !BRAIN_HELD.isEmpty())) return;
        MinecraftServer server = leaving.server;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player != leaving && player.hasPermissions(2)) return;
        }
        resume(server);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        current = null;
        viewing = false;
        weFroze = false;
        savedRate = -1.0F;
        BRAIN_HELD.clear();
        GLOWED.clear();
        COOLDOWNS.clear();
    }
}
