package com.alex.test.village.stuckwatch;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * Stuck catcher: what the last move asked of a villager's body, what it got,
 * and the block that stopped it. {@code NpcEntity.move} fills it in while
 * {@link StuckWatch} records; {@link BlackBox} reads it once a tick. Reads the
 * world only; nothing here steers.
 */
public final class MoveProbe {

    /** How far ahead of the body the blocker is looked for along the wanted move. */
    private static final double PROBE = 0.06;

    long moveTick = Long.MIN_VALUE;
    double wantX, wantY, wantZ;
    double gotX, gotY, gotZ;
    @Nullable BlockPos blockedAt;
    String blockedBy = "";
    long jumpTick = Long.MIN_VALUE;
    long jumpRefusedTick = Long.MIN_VALUE;

    /** One move of the body: asked {@code want}, ended up moving by the {@code got} parts. */
    public void record(Entity entity, Vec3 want, double gx, double gy, double gz) {
        moveTick = entity.level().getGameTime();
        wantX = want.x;
        wantY = want.y;
        wantZ = want.z;
        gotX = gx;
        gotY = gy;
        gotZ = gz;
        blockedAt = null;
        blockedBy = "";
        if (entity.horizontalCollision) findBlocker(entity, want);
    }

    /** The body left the ground on a jump this tick. */
    public void jumped(long gameTime) { jumpTick = gameTime; }

    /** A jump was asked for and dropped (the stair assist refuses jumps near stairs). */
    public void jumpRefused(long gameTime) { jumpRefusedTick = gameTime; }

    /**
     * The block whose collision box sits just ahead of the body along the
     * wanted move, tried along x and along z on their own so a diagonal move
     * into one wall still names that wall.
     */
    private void findBlocker(Entity entity, Vec3 want) {
        AABB box = entity.getBoundingBox();
        double sx = Math.abs(want.x) > 1.0E-4 ? Math.signum(want.x) * PROBE : 0.0;
        double sz = Math.abs(want.z) > 1.0E-4 ? Math.signum(want.z) * PROBE : 0.0;
        if (sx != 0.0 && blockerIn(entity, box, box.move(sx, 0.0, 0.0))) return;
        if (sz != 0.0) blockerIn(entity, box, box.move(0.0, 0.0, sz));
    }

    private boolean blockerIn(Entity entity, AABB body, AABB probe) {
        Level level = entity.level();
        AABB inner = body.deflate(1.0E-3);
        CollisionContext context = CollisionContext.of(entity);
        int x0 = (int) Math.floor(probe.minX), x1 = (int) Math.floor(probe.maxX - 1.0E-7);
        int y0 = (int) Math.floor(probe.minY), y1 = (int) Math.floor(probe.maxY - 1.0E-7);
        int z0 = (int) Math.floor(probe.minZ), z1 = (int) Math.floor(probe.maxZ - 1.0E-7);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    VoxelShape shape = state.getCollisionShape(level, pos, context);
                    if (shape.isEmpty()) continue;
                    for (AABB part : shape.toAabbs()) {
                        AABB placed = part.move(x, y, z);
                        if (placed.intersects(probe) && !placed.intersects(inner)) {
                            blockedAt = pos.immutable();
                            blockedBy = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()
                                    + String.format(java.util.Locale.ROOT, " (%d,%d,%d) top %.2f",
                                    x, y, z, y + part.maxY);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
