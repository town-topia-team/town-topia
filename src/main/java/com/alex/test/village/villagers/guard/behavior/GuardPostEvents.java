package com.alex.test.village.villagers.guard.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Event bridge that keeps guard-post item frames registered with saved state. */
public final class GuardPostEvents {

    private GuardPostEvents() {}

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() == null) return;
        GuardPostRegistry.get(level).tick(level);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() == null) return;
        if (com.alex.test.village.stuckwatch.StuckWatch.worldHeld(level)) return;
        GuardPostRegistry.get(level).tick(level);
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() == null) return;
        if (event.getEntity() instanceof ItemFrame frame
                && ItemFrameTokenRegistry.isGuardPostToken(frame.getItem())) {
            // EntityJoin runs before the frame is queryable and may precede village restoration.
            GuardPostRegistry.get(level).queueFrameCheck(level, frame.blockPosition());
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() == null) return;
        if (event.getEntity() instanceof NpcEntity npc
                && npc.getProfession().isGuardLike()) {
            GuardPostRegistry registry = GuardPostRegistry.get(level);
            registry.releaseReservation(npc.getNpcId());
            registry.releaseActive(npc.getNpcId());
            return;
        }
        if (!(event.getEntity() instanceof ItemFrame frame)) return;
        Entity.RemovalReason reason = frame.getRemovalReason();
        if (reason == null
                || reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            return;
        }
        GuardPostRegistry.get(level).removeFrame(level, frame);
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        queueFrame(event.getLevel(), event.getTarget());
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        queueFrame(event.getLevel(), event.getTarget());
    }

    private static void queueFrame(
            net.minecraft.world.level.LevelAccessor level,
            Entity target) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) return;
        if (!(target instanceof ItemFrame frame)) return;
        GuardPostRegistry.get(serverLevel).queueFrameCheck(serverLevel, frame.blockPosition());
    }
}
