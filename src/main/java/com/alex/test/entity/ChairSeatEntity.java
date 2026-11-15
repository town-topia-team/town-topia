package com.alex.test.entity;

import com.alex.test.block.WoodenChairBlock;
import com.alex.test.block.ZalithPianoBenchBlock;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** A nonpersistent mount that holds a sitter at the height of the chair seat. */
public class ChairSeatEntity extends Entity {
    private static final double VILLAGER_FORWARD = 1.0D / 16.0D;
    private static final double VILLAGER_DOWN = 4.5D / 16.0D;
    /** Children are drawn at 0.6 size, so their drop onto the seat shrinks with them. */
    private static final double CHILD_SCALE = 0.6D;
    private static double childForward = VILLAGER_FORWARD * CHILD_SCALE;
    private static double childDown = VILLAGER_DOWN * CHILD_SCALE;

    /** Live tuning from {@code /kidseat}, in pixels (1/16 block); not saved. */
    public static void setChildSeat(double forwardPixels, double downPixels) {
        childForward = forwardPixels / 16.0D;
        childDown = downPixels / 16.0D;
    }

    public static double childForwardPixels() { return childForward * 16.0D; }
    public static double childDownPixels()    { return childDown * 16.0D; }

    /** Back to the defaults: the adult offsets at child size. */
    public static void resetChildSeat() {
        childForward = VILLAGER_FORWARD * CHILD_SCALE;
        childDown = VILLAGER_DOWN * CHILD_SCALE;
    }

    public ChairSeatEntity(EntityType<? extends ChairSeatEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setInvulnerable(true);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && (!isVehicle() || !isSeatBlock(level().getBlockState(chairPos())))) {
            discard();
        }
    }

    /**
     * Blocks a seat may stand on: the chairs, and ZalithYT's piano bench (players only; its seat sits on
     * the edge between its two parts, so chairPos is either part).
     */
    private static boolean isSeatBlock(BlockState state) {
        return state.getBlock() instanceof WoodenChairBlock || state.getBlock() instanceof ZalithPianoBenchBlock;
    }

    /** The chair block this seat belongs to. */
    public BlockPos chairPos() {
        return BlockPos.containing(getX(), getY(), getZ());
    }

    @Override
    public Vec3 getPassengerRidingPosition(Entity passenger) {
        Vec3 normalPosition = super.getPassengerRidingPosition(passenger);
        if (!(passenger instanceof NpcEntity npc)) {
            return normalPosition;
        }

        BlockPos pos = chairPos();
        BlockState state = level().getBlockState(pos);
        if (!(state.getBlock() instanceof WoodenChairBlock chair)) return normalPosition;

        Direction front = state.getValue(WoodenChairBlock.FACING);
        Vec3 attachment = normalPosition.subtract(position());
        boolean child = npc.getProfession() == Profession.CHILD;
        double forward = child ? childForward : VILLAGER_FORWARD;
        double down = child ? childDown : VILLAGER_DOWN;
        return new Vec3(
                pos.getX() + 0.5 + front.getStepX() * forward,
                pos.getY() + chair.seatMountHeight() - down,
                pos.getZ() + 0.5 + front.getStepZ() * forward
        ).add(attachment);
    }

    /** How far a seated villager may turn its head from the chair's front. */
    private static final float VILLAGER_HEAD_TURN = 75.0F;

    /**
     * Keeps a seated villager's body squared to the chair. Its look control
     * still turns the head toward whoever it is talking to, but only as far as
     * a person sitting down would; left alone, the body followed the head and
     * the villager spun round on the seat.
     */
    @Override
    protected void positionRider(Entity passenger, MoveFunction callback) {
        super.positionRider(passenger, callback);
        if (!(passenger instanceof NpcEntity npc)) return;
        BlockState state = level().getBlockState(chairPos());
        if (!(state.getBlock() instanceof WoodenChairBlock)) return;

        float yaw = state.getValue(WoodenChairBlock.FACING).toYRot();
        npc.setYBodyRot(yaw);
        npc.yBodyRotO = yaw;
        float head = Mth.clamp(Mth.wrapDegrees(npc.getYHeadRot() - yaw),
                -VILLAGER_HEAD_TURN, VILLAGER_HEAD_TURN);
        npc.setYHeadRot(yaw + head);
        npc.setYRot(yaw + head);
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        BlockPos chair = chairPos();
        BlockState state = level().getBlockState(chair);
        // The bench's FACING points away from the piano, so getting up tries that open side first.
        Direction front = isSeatBlock(state)
                ? state.getValue(HorizontalDirectionalBlock.FACING)
                : Direction.NORTH;
        Direction[] order = {front, front.getClockWise(), front.getCounterClockWise(), front.getOpposite()};
        // A villager gets up onto the floor the chair stands on. Villagers sit
        // down from behind a chair tucked in at a table, and the first choice,
        // in front, is then the table: a slab or trapdoor table counts as a
        // safe place to stand, and the villager climbed out on top of it.
        if (passenger instanceof NpcEntity) {
            for (Direction direction : order) {
                Vec3 safe = DismountHelper.findSafeDismountLocation(
                        passenger.getType(), level(), chair.relative(direction), true);
                if (safe != null && safe.y - chair.getY() < 0.25) return safe;
            }
        }
        for (Direction direction : order) {
            Vec3 safe = DismountHelper.findSafeDismountLocation(
                    passenger.getType(), level(), chair.relative(direction), true);
            if (safe != null) return safe;
        }
        return Vec3.atBottomCenterOf(chair.above());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
