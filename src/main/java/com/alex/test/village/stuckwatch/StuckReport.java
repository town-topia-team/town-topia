package com.alex.test.village.stuckwatch;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Stuck catcher: writes one plain-text report per catch into
 * {@code <game folder>/stuck-reports/}. Everything the villager knew at the
 * catch (job, goal, route, Minecraft's path, the walker's state), the blocks
 * and village-map steps around it, the black box tick by tick, its log lines
 * and the rewind's restore points. Built on the server thread (it reads the
 * world), written on the IO pool. The newest {@value #KEEP_FILES} are kept.
 */
final class StuckReport {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FOLDER = "stuck-reports";
    private static final int KEEP_FILES = 200;
    private static final int GRID_XZ = 5;
    private static final int GRID_DOWN = 2;
    private static final int GRID_UP = 3;
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TEXT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private StuckReport() {}

    static String build(StuckWatch.StuckCatch c, ServerLevel level, NpcEntity npc) {
        StuckWatch.PendingCatch p = c.at;
        StringBuilder out = new StringBuilder(64 * 1024);
        line(out, "STUCK CATCH #" + c.id + "   " + TEXT_TIME.format(Instant.ofEpochMilli(c.wallMillis)));
        line(out, "Who:     " + c.name + " (" + c.profession + ") " + c.npcId);
        line(out, String.format(Locale.ROOT, "Where:   %.2f %.2f %.2f   feet cell %s", c.x, c.y, c.z, c.feet.toShortString()));
        BlockPos movedTo = NavGraph.feetCell(npc);
        if (!movedTo.equals(c.feet)) {
            line(out, String.format(Locale.ROOT, "Moved:   to %.2f %.2f %.2f (feet cell %s) in the same tick, after the catch;"
                    + " the maps below show where it was caught", npc.getX(), npc.getY(), npc.getZ(), movedTo.toShortString()));
        }
        line(out, "Caught:  " + p.kind().label + ": " + p.reason());
        long day = level.getDayTime();
        line(out, String.format(Locale.ROOT, "When:    game tick %d, day %d, time of day %d", c.tick, day / 24000L, day % 24000L));
        line(out, "Mode:    " + (StuckWatch.recordOnly() ? "record only (not frozen)" : "frozen")
                + (StuckWatch.early() ? ", early trigger on" : ""));
        out.append('\n');

        line(out, "WHAT IT WAS DOING");
        line(out, "Job:          " + p.job());
        line(out, "Destination:  " + (p.goal() == null ? "none" : p.goal().toShortString())
                + String.format(Locale.ROOT, "   speed %.2f   route %s   stuck re-plans so far %d   safe-spot moves %d",
                p.speed(), p.smoothed() ? "smoothed (long straight legs)" : "detailed (tile by tile)",
                p.repaths(), p.recoveries()));
        line(out, "Walker:       " + p.walkerState());
        line(out, "Stuck spot:   " + p.spotLine());
        out.append('\n');

        List<BlockPos> route = p.route();
        if (route != null) {
            line(out, "THE ROUTE (" + route.size() + " waypoints, at " + (p.wpIndex() + 1) + ")   floor / feet / head");
            for (int i = 0; i < route.size(); i++) {
                BlockPos wp = route.get(i);
                line(out, String.format(Locale.ROOT, " %s%3d  %-14s stand %7.2f  %s / %s / %s",
                        i == p.wpIndex() ? ">" : " ", i + 1, wp.toShortString(), NavGraph.standY(level, wp),
                        blockText(level.getBlockState(wp.below())), blockText(level.getBlockState(wp)),
                        blockText(level.getBlockState(wp.above()))));
            }
        } else {
            line(out, "THE ROUTE: none (not walking at the catch)");
        }
        out.append('\n');

        if (!p.minecraftPath().isEmpty()) {
            line(out, "MINECRAFT'S OWN PATH for the stretch (" + p.minecraftPath().size() + " points, next "
                    + (p.minecraftNext() + 1) + ")");
            for (int i = 0; i < p.minecraftPath().size(); i++) {
                BlockPos node = p.minecraftPath().get(i);
                line(out, String.format(Locale.ROOT, " %s%3d  %-14s stand %7.2f  floor %s",
                        i == p.minecraftNext() ? ">" : " ", i + 1, node.toShortString(), NavGraph.standY(level, node),
                        blockText(level.getBlockState(node.below()))));
            }
        } else {
            line(out, "MINECRAFT'S OWN PATH: none (steered straight, or Minecraft gave none)");
        }
        out.append('\n');

        mapAround(out, p.graph(), c.feet, route, p.wpIndex());
        blockGrid(out, level, c.feet, route, p.wpIndex(), p.minecraftPath());

        List<BlackBox.Frame> frames = c.frames;
        line(out, "THE LAST " + frames.size() + " TICKS (" + String.format(Locale.ROOT, "%.1f", frames.size() / 20.0)
                + " s), oldest first. t0 = the catch.");
        line(out, "  flags: G on ground, P pressed against a block, J jumped (j: jump refused), W water, A feet off ground at a step");
        line(out, "  steer: follow = route follower along its line, ladder = ladder leg, join = walking straight onto");
        line(out, "         its route's first tile, hop/push = step helpers (nav/straight only in reports from before 10-18)");
        line(out, "  ask/got = horizontal move asked of the body and what it got; stop = block that stopped it");
        line(out, "tick       x       y        z     yaw  motion x y z      flags  waypoint            steer");
        String lastJob = null;
        int lastRoute = Integer.MIN_VALUE;
        for (BlackBox.Frame f : frames) {
            if (!f.job.equals(lastJob)) {
                line(out, "        -- job: " + (f.job.isEmpty() ? "none" : f.job) + ", pose " + f.anim);
                lastJob = f.job;
            }
            if (f.routeSeq != lastRoute && f.routeSeq >= 0) {
                line(out, "        -- route #" + f.routeSeq + (f.route == null ? "" : " (" + f.route.size() + " waypoints)"));
                lastRoute = f.routeSeq;
            }
            line(out, f.tableRow(c.tick));
        }
        out.append('\n');

        if (c.routes.size() > 1) {
            line(out, "ROUTES IN THE RECORDING");
            for (BlackBox.Route r : c.routes) {
                List<BlockPos> w = r.waypoints();
                line(out, "  #" + r.seq() + ": " + w.size() + " waypoints, "
                        + (w.isEmpty() ? "" : w.get(0).toShortString() + " -> " + w.get(w.size() - 1).toShortString()));
            }
            out.append('\n');
        }

        String uuid = npc.getUUID().toString().substring(0, 8);
        List<LogTail.Line> logs = LogTail.matching(c.wallMillis - 20_000L, c.shortId(), uuid, c.name);
        line(out, "LOG LINES ABOUT IT (last 20 s, debug and up)");
        if (logs.isEmpty()) line(out, "  none");
        for (LogTail.Line l : logs) {
            line(out, String.format(Locale.ROOT, "  %+6.1f s %-5s [%s] %s",
                    (l.millis() - c.wallMillis) / 1000.0, l.level(), l.logger(), l.message()));
        }
        out.append('\n');

        line(out, "RESTORE POINTS (for /villager stuckwatch rewind <seconds>)");
        if (c.restorePoints.isEmpty()) line(out, "  none (it was not walking)");
        for (BlackBox.RestorePoint rp : c.restorePoints) {
            line(out, String.format(Locale.ROOT, "  t%-5d %.1f s back  at %.2f %.2f %.2f  wp %d/%d  goal %s  %d doors  job %s%s",
                    rp.tick() - c.tick, (c.tick - rp.tick()) / 20.0, rp.x(), rp.y(), rp.z(), rp.wpIndex() + 1,
                    rp.waypoints().size(), rp.goal() == null ? "-" : rp.goal().toShortString(), rp.doors().size(), rp.job(),
                    rp.follow() ? "  (route follower)" : ""));
        }
        return out.toString();
    }

    /** The village map's node under the feet and every step it links to. */
    private static void mapAround(StringBuilder out, @Nullable NavGraph graph, BlockPos feet,
                                  @Nullable List<BlockPos> route, int wpIndex) {
        line(out, "THE VILLAGE MAP AROUND ITS FEET");
        if (graph == null) {
            line(out, "  no map");
            out.append('\n');
            return;
        }
        NavNode here = null;
        for (int dy : new int[] {0, 1, -1}) {
            here = graph.nodeAt(feet.getX(), feet.getY() + dy, feet.getZ());
            if (here != null) break;
        }
        if (here == null) {
            line(out, "  no node at or next to the feet cell " + feet.toShortString());
        } else {
            line(out, String.format(Locale.ROOT, "  node %s stand %.2f on %s%s%s%s", here.feetPos().toShortString(), here.standY,
                    BuiltInRegistries.BLOCK.getKey(here.surfaceBlock).getPath(), here.climb ? ", ladder" : "",
                    here.portal ? ", door or gate" : "", here.isRoutable() ? "" : ", not routable"));
            BlockPos target = route != null && wpIndex >= 0 && wpIndex < route.size() ? route.get(wpIndex) : null;
            for (NavNode next : here.neighbours()) {
                String verdict = here.canStepTo(next) ? (here.hasFailedStepTo(next) ? "failed before (avoided)" : "step ok")
                        : "no step this way";
                line(out, String.format(Locale.ROOT, "    -> %-14s %s%+d,%+d  rise %+.2f on %-16s %s%s",
                        next.feetPos().toShortString(), dirName(next.x - here.x, next.z - here.z), next.x - here.x, next.z - here.z,
                        next.standY - here.standY, BuiltInRegistries.BLOCK.getKey(next.surfaceBlock).getPath(), verdict,
                        next.feetPos().equals(target) ? "   <- current waypoint" : ""));
            }
        }
        out.append('\n');
    }

    /** Letter layers of the blocks around the feet, with the villager, route and Minecraft path marked. */
    private static void blockGrid(StringBuilder out, ServerLevel level, BlockPos feet, @Nullable List<BlockPos> route,
                                  int wpIndex, List<BlockPos> mcPath) {
        int x0 = feet.getX() - GRID_XZ, x1 = feet.getX() + GRID_XZ;
        int z0 = feet.getZ() - GRID_XZ, z1 = feet.getZ() + GRID_XZ;
        Map<String, Character> letters = new LinkedHashMap<>();
        line(out, "BLOCKS AROUND IT, one layer per height. Rows run north (top) to south, columns west (left) to east.");
        line(out, "  marks (they hide the block): @ villager's feet  * current waypoint  + other waypoints  o Minecraft's path");
        StringBuilder header = new StringBuilder("          ");
        for (int x = x0; x <= x1; x++) header.append(Math.floorMod(x, 10));
        for (int y = feet.getY() + GRID_UP; y >= feet.getY() - GRID_DOWN; y--) {
            int rel = y - feet.getY();
            line(out, "  y=" + y + (rel == 0 ? " (feet)" : String.format(Locale.ROOT, " (feet%+d)", rel)));
            line(out, header + "   x " + x0 + ".." + x1);
            for (int z = z0; z <= z1; z++) {
                StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "  z%6d  ", z));
                for (int x = x0; x <= x1; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    char mark = markAt(pos, feet, route, wpIndex, mcPath);
                    if (mark != 0) {
                        row.append(mark);
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        row.append('.');
                        continue;
                    }
                    String key = blockText(state);
                    Character letter = letters.get(key);
                    if (letter == null) {
                        letter = letters.size() < LETTERS.length() ? LETTERS.charAt(letters.size()) : '?';
                        letters.put(key, letter);
                    }
                    row.append(letter);
                }
                line(out, row.toString());
            }
        }
        line(out, "  legend: . air");
        for (Map.Entry<String, Character> e : letters.entrySet()) line(out, "          " + e.getValue() + " " + e.getKey());
        out.append('\n');
    }

    private static char markAt(BlockPos pos, BlockPos feet, @Nullable List<BlockPos> route, int wpIndex, List<BlockPos> mcPath) {
        if (pos.equals(feet)) return '@';
        if (route != null) {
            for (int i = 0; i < route.size(); i++) {
                if (route.get(i).equals(pos)) return i == wpIndex ? '*' : '+';
            }
        }
        for (BlockPos node : mcPath) if (node.equals(pos)) return 'o';
        return 0;
    }

    /** A block and the properties that matter for walking (no waterlogged, power and the like). */
    static String blockText(BlockState state) {
        if (state.isAir()) return "air";
        StringBuilder s = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
        List<String> props = new ArrayList<>();
        for (Property<?> prop : state.getProperties()) {
            String name = prop.getName();
            if (name.equals("waterlogged") || name.equals("powered") || name.equals("distance")
                    || name.equals("persistent") || name.equals("age") || name.equals("moisture")) continue;
            props.add(name + "=" + state.getValue(prop));
        }
        if (!props.isEmpty()) s.append('[').append(String.join(",", props)).append(']');
        return s.toString();
    }

    private static String dirName(int dx, int dz) {
        String ns = dz < 0 ? "N" : dz > 0 ? "S" : "";
        String ew = dx < 0 ? "W" : dx > 0 ? "E" : "";
        String d = ns + ew;
        return String.format(Locale.ROOT, "%-3s", d.isEmpty() ? "-" : d);
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }

    /** Writes the report on the IO pool; returns where it will be. */
    static Path write(MinecraftServer server, StuckWatch.StuckCatch c, String text) {
        Path dir = server.getServerDirectory().resolve(FOLDER);
        String safeName = c.name.replaceAll("[^A-Za-z0-9_-]+", "_");
        Path file = dir.resolve(FILE_TIME.format(Instant.ofEpochMilli(c.wallMillis)) + "_" + c.id + "_" + safeName
                + "_" + c.at.kind().id + ".txt");
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(dir);
                Files.writeString(file, text, StandardCharsets.UTF_8);
                prune(dir);
            } catch (IOException e) {
                LOGGER.warn("[StuckCatch] could not write {}", file, e);
            }
        }, Util.ioPool());
        return file;
    }

    /** Keeps the newest reports; only this catcher's own files (names ending in a catch kind). */
    private static void prune(Path dir) throws IOException {
        List<Path> reports;
        try (Stream<Path> files = Files.list(dir)) {
            reports = files.filter(f -> f.getFileName().toString().endsWith(".txt"))
                    .filter(f -> {
                        String name = f.getFileName().toString();
                        for (StuckWatch.Kind kind : StuckWatch.Kind.values()) {
                            if (name.endsWith("_" + kind.id + ".txt")) return true;
                        }
                        return false;
                    })
                    .sorted(Comparator.comparing(f -> f.getFileName().toString()))
                    .toList();
        }
        for (int i = 0; i < reports.size() - KEEP_FILES; i++) Files.deleteIfExists(reports.get(i));
    }
}
