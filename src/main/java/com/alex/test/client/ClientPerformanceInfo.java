package com.alex.test.client;

import com.alex.test.network.PerformancePayloads;
import com.alex.test.performance.PerformanceCost;
import com.alex.test.performance.PerformanceOption;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The world-wide performance choices as the server last sent them for the Mayor whose screen is open
 * ({@code PerformancePayloads.Info}). Unknown until the answer arrives: the page then shows
 * "Asking the Mayor..." and greys every button. Keys this build does not know are skipped.
 * The costs (server tick, each running option's time) come only to the server owner.
 */
public final class ClientPerformanceInfo {

    private static int mayorId = -1;
    private static boolean mayChange;
    private static long tickNanos = PerformancePayloads.NONE;
    private static long tickTargetNanos = PerformancePayloads.NONE;
    private static final Map<PerformanceOption, Boolean> VALUES = new EnumMap<>(PerformanceOption.class);
    private static final Set<PerformanceOption> STOPPED = EnumSet.noneOf(PerformanceOption.class);
    private static final Map<PerformanceOption, PerformanceCost> COSTS = new EnumMap<>(PerformanceOption.class);

    private ClientPerformanceInfo() {}

    public static void set(int forMayor, boolean mayChangeNow, long tickNow, long tickTargetNow,
                           List<PerformancePayloads.Value> values) {
        forget();
        mayorId = forMayor;
        mayChange = mayChangeNow;
        tickNanos = tickNow;
        tickTargetNanos = tickTargetNow;
        if (values == null) return;
        for (PerformancePayloads.Value value : values) {
            PerformanceOption option = PerformanceOption.byKey(value.key());
            if (option == null) continue;
            VALUES.put(option, value.on());
            if (value.stopped()) STOPPED.add(option);
            if (value.costAverageNanos() >= 0 && value.costWorstNanos() >= 0) {
                COSTS.put(option, new PerformanceCost(value.costAverageNanos(), value.costWorstNanos()));
            }
        }
    }

    /**
     * The page opens: drop the last answer, so another world's or another Mayor's values (entity ids
     * start again in each world) never show while the new answer is on its way.
     */
    public static void forget() {
        mayorId = -1;
        mayChange = false;
        tickNanos = tickTargetNanos = PerformancePayloads.NONE;
        VALUES.clear();
        STOPPED.clear();
        COSTS.clear();
    }

    /** Shows a click at once, before the server's answer (which may undo it). Its old cost goes until then. */
    public static void setLocally(int forMayor, PerformanceOption option, boolean on) {
        if (forMayor != mayorId) return;
        VALUES.put(option, on);
        STOPPED.remove(option);
        COSTS.remove(option);
    }

    public static boolean isFor(int forMayor) {
        return mayorId == forMayor;
    }

    /** False until the server's answer says so. */
    public static boolean mayChange(int forMayor) {
        return forMayor == mayorId && mayChange;
    }

    /** {@code null}: not known yet. */
    @Nullable
    public static Boolean value(int forMayor, PerformanceOption option) {
        return forMayor == mayorId ? VALUES.get(option) : null;
    }

    public static boolean stopped(int forMayor, PerformanceOption option) {
        return forMayor == mayorId && STOPPED.contains(option);
    }

    /** {@code null}: not sent (not the owner, not running, or no meter). */
    @Nullable
    public static PerformanceCost cost(int forMayor, PerformanceOption option) {
        return forMayor == mayorId ? COSTS.get(option) : null;
    }

    /** The server's average tick in nanoseconds, or {@link PerformancePayloads#NONE}. */
    public static long tickNanos(int forMayor) {
        return forMayor == mayorId ? tickNanos : PerformancePayloads.NONE;
    }

    /** One tick's share of a second (50 ms at the normal speed), or {@link PerformancePayloads#NONE}. */
    public static long tickTargetNanos(int forMayor) {
        return forMayor == mayorId ? tickTargetNanos : PerformancePayloads.NONE;
    }
}
