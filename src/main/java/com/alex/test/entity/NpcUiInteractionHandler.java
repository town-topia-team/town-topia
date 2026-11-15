package com.alex.test.entity;

import com.alex.test.ui.NpcUiMenu;
import com.alex.test.entity.visitor.AbstractTravelerEntity;
import com.alex.test.village.VillagerModel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/** Shared "player has this NPC UI open" movement lock and facing behavior. */
public final class NpcUiInteractionHandler {

    /** Held this long after the click, until the screen is seen open. */
    private static final int UI_INTERACTION_LOCK_TICKS = 40;
    /**
     * Held this long once the screen closes: a moment, in case another of the
     * NPC's screens opens next (its settings from the main screen). Then the
     * NPC carries on at once.
     */
    private static final int AFTER_CLOSE_TICKS = 5;

    @Nullable
    private UUID playerId;
    private long lockedUntil;
    /** The screen has been seen open since the click. */
    private boolean menuSeen;

    public void clear() {
        playerId = null;
        lockedUntil = 0;
        menuSeen = false;
    }

    public void markOpened(NpcEntity npc, ServerPlayer player) {
        playerId = player.getUUID();
        menuSeen = false;
        if (npc.level() instanceof ServerLevel level) {
            lockedUntil = Math.max(
                    lockedUntil,
                    level.getGameTime() + UI_INTERACTION_LOCK_TICKS);
        }
    }

    public boolean tick(
            NpcEntity npc,
            VillagerModel movement,
            ServerLevel level,
            boolean canPauseAndFace) {
        ServerPlayer player = updatePlayer(npc, level);
        boolean locked = level.getGameTime() < lockedUntil;
        if (!locked) return false;

        if (canPauseAndFace) {
            movement.pauseMovingForUi();
            if (player != null && player.isAlive()) {
                faceEntity(npc, movement, player, true);
            }
        }
        return true;
    }

    /**
     * {@code /vtest uipause}: holds the NPC exactly as an open screen would,
     * for {@code ticks}, with no player. Tests drive the real pause this way.
     */
    public void holdForTest(ServerLevel level, int ticks) {
        playerId = null;
        menuSeen = false;
        lockedUntil = level.getGameTime() + ticks;
    }

    /** Travellers have vanilla navigation rather than a village movement model. */
    public boolean tickTraveler(AbstractTravelerEntity traveler, ServerLevel level) {
        ServerPlayer player = updatePlayer(traveler, level);
        if (level.getGameTime() >= lockedUntil) return false;

        traveler.setLadderClimbTarget(null);
        traveler.holdStillForAnimation();
        if (player != null && player.isAlive()) faceEntity(traveler, player, true);
        return true;
    }

    /** A player has this NPC's screen (trade, stats) open right now. */
    public boolean isMenuOpen(NpcEntity npc, ServerLevel level) {
        return resolvePlayerWithMenu(npc, level) != null;
    }

    @Nullable
    private ServerPlayer updatePlayer(NpcEntity npc, ServerLevel level) {
        ServerPlayer player = resolvePlayerWithMenu(npc, level);
        long now = level.getGameTime();
        if (player != null) {
            menuSeen = true;
            lockedUntil = now + UI_INTERACTION_LOCK_TICKS;
        } else if (menuSeen) {
            // Closed: carry on almost at once, not the whole open-screen margin later.
            menuSeen = false;
            lockedUntil = Math.min(lockedUntil, now + AFTER_CLOSE_TICKS);
        } else if (now >= lockedUntil) {
            playerId = null;
        }
        return player;
    }

    @Nullable
    private ServerPlayer resolvePlayerWithMenu(NpcEntity npc, ServerLevel level) {
        if (playerId == null) return null;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) return null;
        if (player.containerMenu instanceof NpcUiMenu menu && menu.isForNpc(npc)) {
            return player;
        }
        return null;
    }

    /** Accurate eye-level facing with a smooth body turn while standing still. */
    public static void faceEntity(
            NpcEntity npc,
            VillagerModel movement,
            Entity target,
            boolean turnBody) {
        faceEntity(npc, target, turnBody && !movement.isMoving());
    }

    public static void faceEntity(NpcEntity npc, Entity target, boolean turnBody) {
        Vec3 eye = target.getEyePosition();
        npc.getLookControl().setLookAt(eye.x, eye.y, eye.z, 30.0F, 30.0F);

        double dx = eye.x - npc.getX();
        double dz = eye.z - npc.getZ();
        if (dx * dx + dz * dz < 0.0001) return;

        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        npc.setYHeadRot(Mth.rotLerp(0.35F, npc.getYHeadRot(), targetYaw));
        if (turnBody && !npc.isChairSeated()) {
            float bodyYaw = Mth.rotLerp(0.25F, npc.getYRot(), targetYaw);
            npc.setYRot(bodyYaw);
            npc.setYBodyRot(bodyYaw);
        }
    }
}
