package com.alex.test.village.raid.warlord;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.building.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Pounding on a door: when the Warlord, not fighting, stands at the closed door
 * or gate of a building with a villager or a player inside, it pounds on it
 * twice and roars. The door holds (it is never touched), and the Warlord then
 * looks for another way. The same door at most once every 20 seconds.
 */
public final class WarlordPoundGoal extends Goal {

    /** How far it looks for a door, from its feet, sideways. */
    private static final double REACH = 2.5;
    private static final int LOOK_EVERY_TICKS = 10;
    private static final int SECOND_BLOW_TICKS = 8;
    private static final int ROAR_TICKS = 20;
    private static final int LENGTH_TICKS = 30;

    private final RaidWarlord warlord;
    private final Map<BlockPos, Long> pounded = new HashMap<>();
    @Nullable private BlockPos door;
    private int ticks;
    private long nextLook;

    public WarlordPoundGoal(RaidWarlord warlord) {
        this.warlord = warlord;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (warlord.action() != WarlordAction.NONE || !warlord.onGround()) return false;
        LivingEntity target = warlord.getTarget();
        if (target != null && target.isAlive() && warlord.isWithinMeleeAttackRange(target)) return false;
        long now = warlord.level().getGameTime();
        if (now < nextLook || !(warlord.level() instanceof ServerLevel level)) return false;
        nextLook = now + LOOK_EVERY_TICKS;
        door = findDoor(level, now);
        return door != null;
    }

    @Override
    public boolean canContinueToUse() {
        return door != null && ticks < LENGTH_TICKS && warlord.isAlive();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        ticks = 0;
        BlockPos at = door;
        if (at == null || !(warlord.level() instanceof ServerLevel level)) return;
        pounded.put(at, level.getGameTime());
        warlord.getNavigation().stop();
        face(at);
        warlord.playClip("pound");
        WarlordEffects.poundDoor(level, at);
    }

    @Override
    public void tick() {
        BlockPos at = door;
        if (at == null || !(warlord.level() instanceof ServerLevel level)) return;
        ticks++;
        warlord.getNavigation().stop();
        face(at);
        if (ticks == SECOND_BLOW_TICKS) WarlordEffects.doorDust(level, at);
        if (ticks == ROAR_TICKS) warlord.roar();
    }

    @Override
    public void stop() {
        door = null;
        warlord.unstuck().forceDetour();
    }

    /** A closed door or gate near its feet, of a building someone is in, not pounded lately. */
    @Nullable
    private BlockPos findDoor(ServerLevel level, long now) {
        pounded.values().removeIf(at -> now - at >= WarlordMath.POUND_COOLDOWN_TICKS);
        BlockPos feet = warlord.blockPosition();
        VillageManager village = warlord.raidVillage();
        if (village == null) village = VillageManagerRegistry.get(level.getServer()).findVillageAt(level, feet);
        if (village == null) return null;
        int r = Mth.ceil(REACH);
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, 0, -r), feet.offset(r, 1, r))) {
            if (!closedDoor(level.getBlockState(pos))) continue;
            Vec3 at = Vec3.atBottomCenterOf(pos);
            if (WarlordSlamGoal.horizontalDistanceSqr(at, warlord.position()) > (REACH + 0.7) * (REACH + 0.7)) continue;
            BlockPos found = pos.immutable();
            if (pounded.containsKey(found)) continue;
            if (someoneInside(level, village, found)) return found;
        }
        return null;
    }

    private static boolean closedDoor(BlockState state) {
        if (state.getBlock() instanceof DoorBlock) {
            return !state.getValue(DoorBlock.OPEN) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
        }
        return state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN);
    }

    private static boolean someoneInside(ServerLevel level, VillageManager village, BlockPos door) {
        for (Building building : village.getBuildings()) {
            if (!building.isNearRoom(door)) continue;
            boolean anyone = !level.getEntitiesOfClass(LivingEntity.class, building.roomBox(),
                    e -> e.isAlive() && (e instanceof NpcEntity || e instanceof Player player && !player.isSpectator())).isEmpty();
            if (anyone) return true;
        }
        return false;
    }

    private void face(BlockPos at) {
        double dx = at.getX() + 0.5 - warlord.getX();
        double dz = at.getZ() + 0.5 - warlord.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        warlord.setYRot(yaw);
        warlord.setYBodyRot(yaw);
        warlord.setYHeadRot(yaw);
    }
}
