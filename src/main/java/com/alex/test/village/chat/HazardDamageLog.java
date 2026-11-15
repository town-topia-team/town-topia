package com.alex.test.village.chat;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Logs damage that means a route went wrong: a villager touching a cactus,
 * standing in fire or lava, on magma, in powder snow, drowning, stuck in a
 * wall or crushed in a crowd. Written whether or not a player is in town (the
 * chat alert needs one), so mistakes made while nobody watches still show up
 * in latest.log and {@code /villager debug console}.
 *
 * <p>A cactus hurts twice a second while touched, so each villager and cause
 * gets one line per {@value #REPORT_INTERVAL_TICKS} ticks at most, counting
 * the hits since its last line.
 */
public final class HazardDamageLog {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long REPORT_INTERVAL_TICKS = 200L;
    private static final Map<Key, Window> WINDOWS = new HashMap<>();

    private HazardDamageLog() {}

    public static void record(ServerLevel level, NpcEntity npc, @Nullable VillageManager manager, DamageCause cause) {
        if (!cause.walkingMistake()) return;
        long now = level.getGameTime();
        // A time behind the last hit means the world was reloaded from an earlier save.
        WINDOWS.values().removeIf(window -> now < window.lastHitTick
                || now - window.lastHitTick > REPORT_INTERVAL_TICKS * 2);

        Window window = WINDOWS.computeIfAbsent(new Key(npc.getNpcId(), cause.key()), ignored -> new Window());
        window.lastHitTick = now;
        window.hitsSinceReport++;
        if (window.lastReportTick != Long.MIN_VALUE && now - window.lastReportTick < REPORT_INTERVAL_TICKS) return;

        LOGGER.warn("{}{}; standing at {}{}; {}",
                cause.sentence().about(Component.literal(npc.getDisplayName().getString() + " ("
                        + npc.getProfession().displayName() + ", " + activityOf(npc) + ")")).getString(),
                cause.hazard() == null ? "" : " at " + fmt(cause.hazard()),
                String.format(Locale.ROOT, "[%.2f, %.2f, %.2f]", npc.getX(), npc.getY(), npc.getZ()),
                route(manager, npc),
                window.hitsSinceReport == 1 ? "first hit" : window.hitsSinceReport + " hits since its last report");
        window.lastReportTick = now;
        window.hitsSinceReport = 0;
    }

    /** What the villager was doing ("harvest"), as /villagerstatus would show it. */
    private static String activityOf(NpcEntity npc) {
        String status = npc.workStatus();
        return status.isEmpty() ? "no activity" : status;
    }

    /** Where it was walking to, and the corner of the route it was on, if it was walking. */
    private static String route(@Nullable VillageManager manager, NpcEntity npc) {
        VillagerModel model = manager == null ? null : manager.getModel(npc.getNpcId());
        if (model == null || model.moveGoal() == null) return "; not walking anywhere";
        StringBuilder text = new StringBuilder("; heading to ").append(fmt(model.moveGoal()));
        BlockPos waypoint = model.moveWaypoint();
        if (waypoint != null) {
            text.append(" via ").append(fmt(waypoint)).append(" (waypoint ")
                    .append(model.moveWaypointIndex() + 1).append('/').append(model.moveWaypointCount()).append(')');
        } else if (model.isAwaitingPath()) {
            text.append(" (route not found yet)");
        }
        return text.toString();
    }

    private static String fmt(BlockPos pos) {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }

    private record Key(UUID npcId, String cause) {}

    private static final class Window {
        private long lastReportTick = Long.MIN_VALUE;
        private long lastHitTick;
        private int hitsSinceReport;
    }
}
