package com.alex.test.village.raid.warlord;

import com.alex.test.village.WalkFeel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.MoveControl;

/**
 * Raider plan bug 24, fix 1 (10-19, switch {@code warlordturn}): the Warlord's walking turns at
 * most {@link WalkFeel#warlordTurnDegrees} a tick instead of Minecraft's 90, so a new heading is
 * a curve, not a snap of its 3-block body. With the way far off where it faces, it slows while it
 * comes round (never under a quarter), so it does not stride off the wrong way meanwhile.
 *
 * <p>Only walking goes through here (Minecraft's path, the map route's stretches). The moves that
 * face it outright (swing, spin, charge, pound, leap: {@link RaidWarlord#face}) turn as before.
 */
final class WarlordMoveControl extends MoveControl {

    /** Up to this far off its facing it walks at full pace. */
    private static final float FULL_PACE_WITHIN = 30.0F;
    /** From there to this far off, the pace eases down to {@link #SLOWEST}. */
    private static final float SLOWEST_FROM = 120.0F;
    private static final float SLOWEST = 0.25F;

    WarlordMoveControl(RaidWarlord warlord) {
        super(warlord);
    }

    @Override
    public void tick() {
        boolean walking = operation == Operation.MOVE_TO;
        super.tick();
        if (!walking || !WalkFeel.warlordTurn) return;
        double dx = wantedX - mob.getX();
        double dz = wantedZ - mob.getZ();
        if (dx * dx + dz * dz < MIN_SPEED_SQR) return;
        float wanted = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float off = Math.abs(Mth.wrapDegrees(wanted - mob.getYRot()));
        if (off <= FULL_PACE_WITHIN) return;
        float t = Mth.clamp((off - FULL_PACE_WITHIN) / (SLOWEST_FROM - FULL_PACE_WITHIN), 0.0F, 1.0F);
        mob.setSpeed(mob.getSpeed() * (1.0F - t * (1.0F - SLOWEST)));
    }

    /** Minecraft turns a walking mob up to 90 degrees a tick here; the Warlord much less. */
    @Override
    protected float rotlerp(float from, float to, float most) {
        return super.rotlerp(from, to, WalkFeel.warlordTurn ? Math.min(most, WalkFeel.warlordTurnDegrees) : most);
    }
}
