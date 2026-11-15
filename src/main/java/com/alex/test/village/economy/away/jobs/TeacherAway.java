package com.alex.test.village.economy.away.jobs;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageDays;
import com.alex.test.village.building.SchoolBuilding;
import com.alex.test.village.economy.away.AwayContext;
import com.alex.test.village.economy.away.AwayJob;
import com.alex.test.village.economy.away.AwayXp;
import com.alex.test.village.villagers.child.ChildProfession;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.teacher.SchoolClasses;
import com.alex.test.village.villagers.teacher.SchoolDay;
import com.alex.test.village.villagers.teacher.TeacherBalance;
import com.alex.test.village.villagers.teacher.settings.TeacherSettingsSchema;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * A teacher's away day (round 11 of the plan): the school days the children
 * count when they are back, taught to its own class.
 *
 * <p>The children credit their own school days away when they first tick again
 * ({@code ChildProfession.creditDaysAway}): half a day of lessons for each
 * village day between the one they were last seen and today. The teacher
 * learns from the same days, not from the counted ticks (old bug T1):
 * <ul>
 *   <li>Checks: work on ({@code TeacherSettingsSchema.WORK_ENABLED}); a class of its own
 *       ({@code SchoolClasses.savedTeacherClass}), its lectern its own and still standing in a standing
 *       school (old bug T2); a whole village day away ({@link AwayContext#wholeDayAway}); a pupil.</li>
 *   <li>Class: the children enrolled in its own class ({@code SchoolClasses.savedEnrolment}), alive in
 *       the run and still children on that day (old bug T3: every teacher counted every child).</li>
 *   <li>XP: a school day's lesson units at {@code TeacherBalance.AWAY_DAY_SHARE}, each weighted by the
 *       class's size ({@code unitWeight}), on the day's real number ({@link AwayXp#teacherUnits}); a
 *       session's intelligence for the day.</li>
 * </ul>
 * Not done away: a child with no body is not counted (nothing saved says it is a child, or in which
 * class: old bug T4 stays); the children's own lessons are theirs, credited on their first tick.
 */
public final class TeacherAway implements AwayJob {

    public static final TeacherAway INSTANCE = new TeacherAway();

    private static final Logger LOGGER = LogUtils.getLogger();

    private TeacherAway() {}

    @Override
    @Nullable
    public String cannotWork(AwayContext ctx) {
        NpcEntity npc = ctx.npc();
        if (!ProfessionSettings.of(npc, TeacherSettingsSchema.SCHEMA).getBool(TeacherSettingsSchema.WORK_ENABLED)) {
            return "work off";
        }
        SchoolClasses.ClassKey key = SchoolClasses.savedTeacherClass(npc);
        if (key == null) return "no class of its own";
        if (!(ctx.mgr.getBuilding(key.school()) instanceof SchoolBuilding school) || !school.isValid()) {
            return "no school";
        }
        if (!school.getFunctionalBlocks().contains(key.lectern())) return "its lectern is gone";
        if (!npc.getNpcId().equals(SchoolClasses.lecternOwner(ctx.mgr, key))) return "its lectern is another teacher's";
        if (!ctx.wholeDayAway()) return "school held in person that day";
        if (pupils(ctx, key) == 0) return "no pupils in its class";
        return null;
    }

    @Override
    public void workDay(AwayContext ctx) {
        NpcEntity npc = ctx.npc();
        SchoolClasses.ClassKey key = SchoolClasses.savedTeacherClass(npc);
        if (key == null) return;
        int pupils = pupils(ctx, key);
        int levelBefore = ctx.model().brain().teacherSkillLevel();
        float units = TeacherBalance.FULL_DAY_UNITS * TeacherBalance.AWAY_DAY_SHARE;
        float weighted = units * TeacherBalance.unitWeight(pupils);
        AwayXp.teacherUnits(ctx, weighted);
        AwayXp.workSession(ctx);
        LOGGER.info("[AwayTeacher] {} day {}: a school day away for {} pupil(s), {} lesson unit(s) ({} weighted), "
                        + "level {} -> {}",
                npc.getNpcId().toString().substring(0, 8), ctx.day.index() + 1, pupils,
                String.format("%.1f", units), String.format("%.1f", weighted), levelBefore,
                ctx.model().brain().teacherSkillLevel());
    }

    /** Children enrolled in {@code key}, alive in the run, still children by that day's lessons. */
    private static int pupils(AwayContext ctx, SchoolClasses.ClassKey key) {
        long lessons = ctx.day.calendarDay() * VillageDays.DAY_TICKS + SchoolDay.LESSON_1_START;
        int count = 0;
        for (NpcEntity child : ctx.bodiesAlive(Profession.CHILD)) {
            if (key.equals(SchoolClasses.savedEnrolment(child)) && ChildProfession.childAt(child, lessons)) count++;
        }
        return count;
    }
}
