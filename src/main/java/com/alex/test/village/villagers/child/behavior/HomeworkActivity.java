package com.alex.test.village.villagers.child.behavior;

import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.village.VillageChairs;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.Building;
import com.alex.test.village.villagers.teacher.PupilRecord;
import com.alex.test.village.villagers.teacher.TeacherBalance;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A schoolchild's homework: once an evening, before bed, it reads at home for
 * a minute, on a chair if the house has a free one. Worth a few lesson units
 * of learning, inside the day's limit. Only a child who has been to school.
 */
public final class HomeworkActivity extends Activity {

    /** Brain data: the village day of the last homework (cleared when the child grows up). */
    public static final String TAG_DAY = "HomeworkDay";
    private static final int READ_TICKS = 1200;
    private static final double SIT_REACH_SQ = 4.0;

    private final Building home;
    @Nullable private VillageChairs.Chair chair;
    @Nullable private BlockPos target;
    private boolean arrived;
    private int ticks;

    public HomeworkActivity(Building home) {
        this.home = home;
    }

    /** Not done tonight, and this child goes to school. */
    public static boolean due(Context ctx) {
        CompoundTag data = ctx.npc().getBrainData();
        long day = EveningSchedule.dayIndex(ctx.level().getDayTime());
        return PupilRecord.wentToSchool(data) && !(data.contains(TAG_DAY) && data.getLong(TAG_DAY) == day);
    }

    @Override
    protected void start(Context ctx) {
        ctx.npc().getBrainData().putLong(TAG_DAY, EveningSchedule.dayIndex(ctx.level().getDayTime()));
        net.minecraft.world.phys.AABB box = home.roomBox();
        for (VillageChairs.Chair c : VillageChairs.findInBox(ctx.level(), ctx.mgr().getGraph(),
                BlockPos.containing(box.minX - 1, box.minY + 1, box.minZ - 1),
                BlockPos.containing(box.maxX, box.maxY - 2, box.maxZ))) {
            if (!home.getNavMeshBlocks().contains(c.approach())) continue;
            if (!VillageChairs.isFreeFor(ctx.level(), c.pos(), ctx.npc().getUUID())) continue;
            chair = c;
            break;
        }
        if (chair != null) {
            target = chair.approach();
        } else {
            List<BlockPos> tiles = new ArrayList<>(home.getNavMeshBlocks());
            if (!tiles.isEmpty()) target = tiles.get(ctx.npc().getRandom().nextInt(tiles.size()));
        }
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " HOMEWORK at home");
    }

    @Override
    protected Status tick(Context ctx) {
        if (target == null || !home.isValid()) return Status.DONE;
        if (chair != null) VillageChairs.claim(ctx.level(), chair.pos(), ctx.npc().getUUID());
        if (!arrived) {
            if (!moveTo(ctx, target, VillagerModel.WALK_SPEED)) return Status.RUNNING;
            if (moveFailed(ctx, target)) return Status.DONE;
            if (chair != null && ctx.npc().distanceToSqr(chair.pos().getCenter()) <= SIT_REACH_SQ
                    && VillageChairs.isFreeFor(ctx.level(), chair.pos(), ctx.npc().getUUID())) {
                ctx.npc().sitOnChair(chair.pos());
            } else {
                chair = null;
            }
            arrived = true;
            ctx.npc().setAnimation(VillagerAnimationState.READ);
        }
        if (ctx.npc().getAnimation() != VillagerAnimationState.READ) {
            ctx.npc().setAnimation(VillagerAnimationState.READ);
        }
        if (++ticks < READ_TICKS) return Status.RUNNING;
        CompoundTag data = ctx.npc().getBrainData();
        int teacher = Math.max(1, PupilRecord.bestTeacher(data));
        float paid = PupilRecord.learn(data, ctx.brain().skills(),
                EveningSchedule.dayIndex(ctx.level().getDayTime()),
                TeacherBalance.unitGain(teacher) * TeacherBalance.HOMEWORK_UNITS, TeacherBalance.dailyCap(teacher));
        ctx.brain().diary().log(String.format(Locale.ROOT, "%s HOMEWORK done (+%.2f intelligence)",
                VillagerDiary.ts(ctx.level()), paid));
        return Status.DONE;
    }

    @Override
    public void stop(Context ctx) {
        if (ctx.npc().getAnimation() == VillagerAnimationState.READ) {
            ctx.npc().setAnimation(ctx.npc().isChairSeated()
                    ? VillagerAnimationState.CHAIR_SIT : VillagerAnimationState.IDLE);
        }
        if (chair != null) VillageChairs.release(ctx.level(), chair.pos(), ctx.npc().getUUID());
        ctx.model().stopMoving();
    }

    @Override
    public boolean keepsChairSeat() {
        return chair != null;
    }

    @Override
    public String name() {
        return "evening_homework";
    }
}
