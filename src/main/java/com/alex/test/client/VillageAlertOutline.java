package com.alex.test.client;

import com.alex.test.entity.NpcEntity;
import com.alex.test.network.VillageVillagerAlertPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tracks which villager models should receive the temporary alert outline. */
public final class VillageAlertOutline {

    private static final int MAX_DURATION_TICKS = 20 * 30;
    private static final Map<UUID, Highlight> HIGHLIGHTS = new HashMap<>();
    private static ClientLevel activeLevel;

    private VillageAlertOutline() {}

    /** Called on the client thread when the server reports a damaged villager. */
    public static void track(VillageVillagerAlertPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        if (activeLevel != level) {
            clearHighlights();
            activeLevel = level;
        }

        int duration = Math.max(1, Math.min(payload.durationTicks(), MAX_DURATION_TICKS));
        long expiresAt = level.getGameTime() + duration;
        Highlight highlight = HIGHLIGHTS.computeIfAbsent(payload.entityUuid(), ignored -> new Highlight());
        if (payload.guard()) {
            highlight.guardExpiresAt = Math.max(highlight.guardExpiresAt, expiresAt);
        } else if (payload.yellowWarning()) {
            highlight.warningExpiresAt = Math.max(highlight.warningExpiresAt, expiresAt);
        } else if (payload.flashing()) {
            highlight.alertExpiresAt = Math.max(highlight.alertExpiresAt, expiresAt);
        } else {
            highlight.locateExpiresAt = Math.max(highlight.locateExpiresAt, expiresAt);
        }
    }

    public static boolean isActive(NpcEntity villager) {
        Highlight highlight = HIGHLIGHTS.get(villager.getUUID());
        ClientLevel level = Minecraft.getInstance().level;
        if (highlight == null || level == null) return false;
        long now = level.getGameTime();
        if (now < highlight.alertExpiresAt) return true;
        if (now < highlight.guardExpiresAt) return true;
        if (now < highlight.warningExpiresAt) return true;
        return now < highlight.locateExpiresAt;
    }

    /**
     * Red beats yellow while both are on: a cactus brush or a fall in the
     * middle of a fight used to turn an attacked villager's outline yellow
     * until the next blow landed.
     */
    public static boolean isRedAlertActive(NpcEntity villager) {
        Highlight highlight = HIGHLIGHTS.get(villager.getUUID());
        ClientLevel level = Minecraft.getInstance().level;
        if (highlight == null || level == null) return false;
        return level.getGameTime() < highlight.alertExpiresAt;
    }

    public static boolean isYellowAlertActive(NpcEntity villager) {
        Highlight highlight = HIGHLIGHTS.get(villager.getUUID());
        ClientLevel level = Minecraft.getInstance().level;
        if (highlight == null || level == null) return false;
        long now = level.getGameTime();
        return now < highlight.warningExpiresAt && now >= highlight.alertExpiresAt
                && now >= highlight.guardExpiresAt;
    }

    /** A guard, captain or archer a monster hit: blue, which beats yellow as red does. */
    public static boolean isGuardAlertActive(NpcEntity villager) {
        Highlight highlight = HIGHLIGHTS.get(villager.getUUID());
        ClientLevel level = Minecraft.getInstance().level;
        if (highlight == null || level == null) return false;
        long now = level.getGameTime();
        return now < highlight.guardExpiresAt && now >= highlight.alertExpiresAt;
    }

    /**
     * A guard this low flashes blue: 40%, the "badly hurt" line the tower
     * bell rule uses. Read live from the synced health, so a guard healed
     * above it stops flashing at once.
     */
    public static final float LOW_HEALTH_SHARE = 0.4f;

    public static boolean isLowOnHealth(NpcEntity villager) {
        return villager.getHealth() <= villager.getMaxHealth() * LOW_HEALTH_SHARE;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != activeLevel) {
            clearHighlights();
            activeLevel = level;
        }
        if (level == null || HIGHLIGHTS.isEmpty()) return;

        long now = level.getGameTime();
        HIGHLIGHTS.entrySet().removeIf(entry -> now >= entry.getValue().alertExpiresAt
                && now >= entry.getValue().guardExpiresAt
                && now >= entry.getValue().warningExpiresAt
                && now >= entry.getValue().locateExpiresAt);
    }

    private static void clearHighlights() {
        HIGHLIGHTS.clear();
    }

    private static final class Highlight {
        private long alertExpiresAt;
        private long guardExpiresAt;
        private long warningExpiresAt;
        private long locateExpiresAt;
    }
}
