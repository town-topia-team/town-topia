package com.alex.test.village.brain;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import javax.annotation.Nullable;

/**
 * Append-only text log for one villager's daily decisions and events.
 *
 * <p>The file lives at {@code villager-diaries/<short-id>.log} relative to the
 * game directory. Each server session appends a header line so multiple sessions
 * in the same log are distinguishable.
 *
 * <p>Every line begins with a compact timestamp produced by {@link #ts}:
 * <pre>
 *   [d3 t06420]  day 3, in-day tick 6420
 * </pre>
 *
 * <p>The file is opened on the first line written; nothing is written while
 * {@link #enabled} is off or for a quiet villager (the stress test crowd).
 * All public methods are null-safe: if the file could not be opened, calls
 * are silently ignored.
 */
public final class VillagerDiary {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter SESSION_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int FLUSH_EVERY = 30; // lines between auto-flushes

    /**
     * Diaries on for everyone ({@code /villager diary on|off}, not saved; on after a restart).
     * Off: nothing is written and no new file is opened.
     */
    public static volatile boolean enabled = true;

    private final UUID npcId;
    /** True while this villager writes nothing (the stress test crowd); asked on each line. */
    private final BooleanSupplier quiet;
    @Nullable private PrintWriter out;
    /** The file was tried (opened or failed): it is never tried twice. */
    private boolean opened;
    private int linesSinceFlush = 0;

    public VillagerDiary(UUID npcId) {
        this(npcId, () -> false);
    }

    /**
     * The file is opened on the first line written, not here: a crowd of
     * hundreds that never writes, or writes nothing, keeps no file open.
     */
    public VillagerDiary(UUID npcId, BooleanSupplier quiet) {
        this.npcId = npcId;
        this.quiet = quiet;
    }

    private void open() {
        opened = true;
        Path dir  = Path.of("villager-diaries");
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(npcId.toString().substring(0, 8) + ".log");
            PrintWriter w = new PrintWriter(new BufferedWriter(Files.newBufferedWriter(
                    file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)));
            w.println();
            w.println("══════════════════════════════════════════════════════════════");
            w.printf ("  SESSION  %s%n", LocalDateTime.now().format(SESSION_FMT));
            w.println("══════════════════════════════════════════════════════════════");
            out = w;
        } catch (IOException e) {
            LOGGER.warn("[VillagerDiary] Could not open diary for {}: {}", npcId, e.getMessage());
        }
    }

    /** Whether a line written now would land in the file: callers building a costly line may ask first. */
    public boolean writes() {
        return enabled && !quiet.getAsBoolean() && (out != null || !opened);
    }

    // =========================================================================
    // Public API — all calls are silently no-ops if the file couldn't be opened
    // =========================================================================

    /** Write one pre-formatted line. */
    public void log(String line) {
        if (!enabled || quiet.getAsBoolean()) return;
        if (!opened) open();
        if (out == null) return;
        out.println(line);
        if (++linesSinceFlush >= FLUSH_EVERY) { linesSinceFlush = 0; out.flush(); }
    }

    /** {@code /villager diary [on|off]}: diaries for every villager, until the game restarts. */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> commandTree() {
        return net.minecraft.commands.Commands.literal("diary")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> diaryReport(ctx.getSource()))
                .then(net.minecraft.commands.Commands.literal("on").executes(ctx -> {
                    enabled = true;
                    return diaryReport(ctx.getSource());
                }))
                .then(net.minecraft.commands.Commands.literal("off").executes(ctx -> {
                    enabled = false;
                    return diaryReport(ctx.getSource());
                }));
    }

    private static int diaryReport(net.minecraft.commands.CommandSourceStack source) {
        String text = enabled
                ? "Villager diaries on: villager-diaries/<id>.log in the game folder (stress test crowd excepted)."
                : "Villager diaries off until /villager diary on or a restart.";
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(text), false);
        return 1;
    }

    /** Force-flush the write buffer. */
    public void flush() {
        if (out != null) out.flush();
    }

    public void close() {
        if (out != null) { out.flush(); out.close(); }
    }

    // =========================================================================
    // Timestamp helper — static so activities can use it without holding a ref
    // =========================================================================

    /**
     * Compact timestamp string: {@code [d3 t06420]} — village day 3 (see
     * VillageDays), in-day tick 6420. Pass to {@link #log} at the start of
     * every entry.
     */
    public static String ts(ServerLevel level) {
        long dt  = level.getDayTime();
        // Every line of one tick shares its stamp: made once a tick, not once a line.
        Stamp last = lastStamp;
        if (last != null && last.dayTime() == dt) return last.text();
        long day = EveningSchedule.dayIndex(dt) + 1;
        long t   = Math.floorMod(dt, 24_000L);
        String text = String.format("[d%d t%05d]", day, t);
        lastStamp = new Stamp(dt, text);
        return text;
    }

    private record Stamp(long dayTime, String text) {}

    /** The last stamp made; replaced whole, so a reader never sees half of one. */
    private static volatile Stamp lastStamp;
}
