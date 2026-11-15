package com.alex.test.village.villagers.child;

import com.alex.test.Tr;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageDays;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.SchoolBuilding;
import com.alex.test.village.chat.TokenFeedback;
import com.alex.test.village.log.VillageLog;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.child.behavior.AttendSchoolActivity;
import com.alex.test.village.villagers.child.behavior.HomeworkActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkills;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.teacher.PupilRecord;
import com.alex.test.village.villagers.teacher.SchoolClasses;
import com.alex.test.village.villagers.teacher.settings.TeacherSettingsSchema;
import com.alex.test.village.villagers.teacher.SchoolSubjects;
import com.alex.test.village.villagers.teacher.TeacherBalance;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.Locale;

/** Wires the Child profession to its behavior implementation, and grows children up. */
public final class ChildProfession {

    public static final Profession PROFESSION = Profession.CHILD;

    /** Village days a child spends before growing into a jobless adult. */
    public static final long GROWTH_DAYS = TeacherBalance.CHILDHOOD_DAYS;

    /** Brain data: village time the child was born (first ticked). */
    private static final String TAG_BORN = "ChildBornVillageTime";
    /** The old birth stamp, in game time, which stopped while players slept. */
    private static final String TAG_BORN_OLD = "ChildBornGameTime";
    /** The last village day this child was seen, for school days missed while nobody was near. */
    private static final String TAG_LAST_SEEN = "ChildLastSeenDay";

    private ChildProfession() {}

    public static WorkActivity createWorkActivity() {
        return new AttendSchoolActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        return AttendSchoolActivity.hasWork(ctx);
    }

    /**
     * Ages this child up once {@link #GROWTH_DAYS} village days have passed
     * since it was born. Village days count sleeping through the night, so a
     * childhood is five days whether or not the players sleep. Stamps the
     * birth time on first call rather than requiring an explicit spawn hook,
     * so both bred and test-spawned children age correctly.
     *
     * <p>Called every brain tick from {@code VillagerBrain.tick} while
     * {@code profession() == Profession.CHILD}.
     */
    public static void tickGrowth(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        CompoundTag data = npc.getBrainData();
        long villageTime = VillageDays.villageTime(ctx.level().getDayTime());
        if (!data.contains(TAG_BORN)) {
            long born = villageTime;
            if (data.contains(TAG_BORN_OLD)) {
                // From before village days: keep the age it had reached.
                born = villageTime - Math.max(0L, ctx.level().getGameTime() - data.getLong(TAG_BORN_OLD));
                data.remove(TAG_BORN_OLD);
            }
            data.putLong(TAG_BORN, born);
        }
        creditDaysAway(ctx, data);
        if (villageTime - data.getLong(TAG_BORN) < GROWTH_DAYS * 24000L) return;
        graduate(ctx, data);
    }

    /**
     * Whether {@code npc} is still a child at village time {@code villageTime}
     * by its birth stamp (the away calculator asks about past days); a child
     * with no stamp yet has only just been born.
     */
    public static boolean childAt(NpcEntity npc, long villageTime) {
        if (npc.getProfession() != Profession.CHILD) return false;
        CompoundTag data = npc.getBrainData();
        return !data.contains(TAG_BORN) || villageTime - data.getLong(TAG_BORN) < GROWTH_DAYS * 24000L;
    }

    /** Whole village days until this child grows up, rounded up; -1 for a grown-up. */
    public static int daysLeft(NpcEntity npc) {
        if (npc.getProfession() != Profession.CHILD) return -1;
        CompoundTag data = npc.getBrainData();
        if (!data.contains(TAG_BORN)) return (int) GROWTH_DAYS;
        long left = GROWTH_DAYS * 24000L - (VillageDays.villageTime(npc.level().getDayTime()) - data.getLong(TAG_BORN));
        return (int) Math.max(0, (left + 23999) / 24000);
    }

    /**
     * School days the child missed while nobody was near the village (it does
     * not tick then) count half, when its own class has a teacher at work in
     * a school that stands: half a day of lessons from that teacher, the
     * lessons' jobs toward its favourite (picked the way the teacher picks
     * them), and that teacher toward its head start.
     */
    private static void creditDaysAway(Activity.Context ctx, CompoundTag data) {
        long today = EveningSchedule.dayIndex(ctx.level().getDayTime());
        long last = data.contains(TAG_LAST_SEEN) ? data.getLong(TAG_LAST_SEEN) : today;
        if (last == today && data.contains(TAG_LAST_SEEN)) return;
        data.putLong(TAG_LAST_SEEN, today);
        // The away catch-up already moved the stamp past days it does not
        // count; this cap is for a stamp nothing moved (an older save).
        long missed = Math.min(Math.min(GROWTH_DAYS, com.alex.test.village.economy.AwayClock.MAX_AWAY_DAYS),
                today - last - 1);
        if (missed <= 0) return;
        VillagerModel teacher = classTeacher(ctx.mgr(), ctx.npc());
        if (teacher == null) return;
        int level = teacher.brain().teacherSkillLevel();
        float credit = missed * TeacherBalance.AWAY_DAY_SHARE * TeacherBalance.dayOfLessons(level);
        PupilRecord.credit(data, ctx.brain().skills(), credit);
        PupilRecord.taughtBy(data, level);
        NpcEntity teacherNpc = teacher.puppet();
        boolean villageNeeds = teacherNpc == null || ProfessionSettings.of(teacherNpc, TeacherSettingsSchema.SCHEMA)
                .getBool(TeacherSettingsSchema.VILLAGE_NEEDS);
        float unitsPerLesson = TeacherBalance.FULL_DAY_UNITS * TeacherBalance.AWAY_DAY_SHARE / LESSONS_PER_DAY;
        for (long i = 0; i < missed * LESSONS_PER_DAY; i++) {
            PupilRecord.taught(data, ctx.model().npcId,
                    SchoolSubjects.pick(ctx.mgr(), ctx.npc().getRandom(), villageNeeds), unitsPerLesson);
        }
        ctx.brain().diary().log(String.format(Locale.ROOT,
                "%s CHILD %d school day(s) while nobody was near: +%.1f intelligence (teacher level %d)",
                VillagerDiary.ts(ctx.level()), missed, credit, level));
    }

    /**
     * Away time the village does not live through (past the
     * {@link com.alex.test.village.economy.AwayClock} cap, or with nobody on
     * the server): the child neither ages nor misses school in it, so its
     * birth and last-seen stamps move forward by that much.
     */
    public static void skipUncountedAway(NpcEntity npc, long ticks) {
        if (ticks <= 0L || npc.getProfession() != Profession.CHILD) return;
        CompoundTag data = npc.getBrainData();
        if (data.contains(TAG_BORN)) data.putLong(TAG_BORN, data.getLong(TAG_BORN) + ticks);
        // Rounded, not floored: a floor left up to a day of skipped time counted as a missed school day.
        if (data.contains(TAG_LAST_SEEN)) {
            data.putLong(TAG_LAST_SEEN, data.getLong(TAG_LAST_SEEN) + Math.round(ticks / 24000.0));
        }
    }

    /** Lessons in a school day, for the jobs a child hears about while nobody is near. */
    private static final int LESSONS_PER_DAY = 3;

    /**
     * The teacher of the class this child is enrolled in, when it teaches:
     * switched on, the class its own, its lectern its own and still in a school
     * that stands (the away teacher's checks, {@code TeacherAway}). Null for a
     * child in no class, or a class with no such teacher (leftovers round 4:
     * before, the village's best working teacher, any class).
     */
    @Nullable
    private static VillagerModel classTeacher(VillageManager village, NpcEntity child) {
        SchoolClasses.ClassKey enrolled = SchoolClasses.savedEnrolment(child);
        if (enrolled == null) return null;
        if (!(village.getBuilding(enrolled.school()) instanceof SchoolBuilding school) || !school.isValid()
                || !school.getFunctionalBlocks().contains(enrolled.lectern())) return null;
        for (VillagerModel model : village.getVillagers()) {
            if (model.brain().profession() != Profession.TEACHER) continue;
            NpcEntity npc = model.puppet();
            if (npc == null || !enrolled.equals(SchoolClasses.savedTeacherClass(npc))) continue;
            if (!model.npcId.equals(SchoolClasses.lecternOwner(village, enrolled))) continue;
            if (!ProfessionSettings.of(npc, TeacherSettingsSchema.SCHEMA).getBool(TeacherSettingsSchema.WORK_ENABLED)) {
                continue;
            }
            // Right after a restart a child can tick before the teacher: read its saved level, not level 1.
            model.brain().prepareForOfflineReconciliation(npc);
            return model;
        }
        return null;
    }

    /**
     * Grown up: a jobless adult now, with a note of the job it was taught most
     * and how far ahead it starts that job, and a word to anyone nearby.
     */
    private static void graduate(Activity.Context ctx, CompoundTag data) {
        NpcEntity npc = ctx.npc();
        boolean schooled = PupilRecord.wentToSchool(data);
        Profession favourite = PupilRecord.favourite(data);
        int headStart = TeacherBalance.headStartLevels(PupilRecord.bestTeacher(data));
        // Nobody grows up knowing less than a newcomer to the village: the same
        // roll a newcomer gets (about 9). School is what makes them cleverer.
        float learned = ctx.brain().skills().intelligence();
        int floor = ProfessionSkills.rollIntelligence(new ProfessionSkills.RandomValues() {
            @Override public double nextDouble() { return npc.getRandom().nextDouble(); }
            @Override public int nextInt(int bound) { return npc.getRandom().nextInt(bound); }
        });
        if (learned < floor) ctx.brain().skills().addIntelligence(floor - learned);
        int intelligence = Math.round(ctx.brain().skills().intelligence());
        boolean hasFavourite = schooled && favourite != null;
        if (hasFavourite) {
            data.putString(PupilRecord.TAG_FAVOURITE_JOB, favourite.name());
            data.putInt(PupilRecord.TAG_HEAD_START, headStart);
            data.remove(PupilRecord.TAG_HEAD_START_APPLIED);
        }
        PupilRecord.clear(data);
        data.remove(TAG_BORN);
        data.remove(TAG_LAST_SEEN);
        data.remove(HomeworkActivity.TAG_DAY);
        ctx.brain().skills().save(data);
        // Leaves the school day it may be in the middle of (or a game, or
        // homework): a grown-up does not stay sitting in class.
        ctx.brain().onProfessionChanged(ctx.mgr(), ctx.level(), npc);
        // Also puts the head start on its favourite job's skill (NpcEntity).
        npc.setProfession(Profession.NONE);

        // In each player's own language (the server only sends keys and the English).
        Component name = npc.givenName() != null ? Component.literal(npc.givenName())
                : Tr.c("chat.dynamicvillages.child.someone", "A village child");
        Component line;
        if (!schooled) {
            line = Tr.c("chat.dynamicvillages.child.grew_up.no_school", "%1$s has grown up. With no school it only knows "
                    + "the basics (intelligence %2$s). A School with a Teacher makes children much cleverer.", name, intelligence);
        } else if (hasFavourite) {
            int level = favouriteLevel(npc, favourite);
            Component job = Component.translatableWithFallback(favourite.translationKey(), favourite.displayName());
            line = level > 0
                    ? Tr.c("chat.dynamicvillages.child.grew_up.favourite_level", "%1$s has grown up and finished school "
                            + "(intelligence %2$s). Favourite job: %3$s. It loved %4$s lessons and is already level %5$s at it.",
                            name, intelligence, job, SchoolSubjects.lessonText(favourite), level)
                    : Tr.c("chat.dynamicvillages.child.grew_up.favourite", "%1$s has grown up and finished school "
                            + "(intelligence %2$s). Favourite job: %3$s. It loved %4$s lessons, and as a %3$s it starts %5$s levels ahead.",
                            name, intelligence, job, SchoolSubjects.lessonText(favourite), headStart);
        } else {
            line = Tr.c("chat.dynamicvillages.child.grew_up.school", "%1$s has grown up and finished school: intelligence %2$s.",
                    name, intelligence);
        }
        TokenFeedback.announce(ctx.level(), npc.blockPosition(),
                Tr.c("chat.dynamicvillages.village.line", "[Village] %s", line).withStyle(ChatFormatting.GREEN));
        // The server's own language: English on a dedicated server, the player's in single player.
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " CHILD grew up: " + line.getString());
        VillageLog.record(ctx.mgr(), ctx.level(), VillageLog.Kind.GREW_UP,
                Tr.c("log.dynamicvillages.grew_up", "%s grew up", npc.getDisplayName()),
                !schooled ? Tr.c("log.dynamicvillages.grew_up.no_school", "There was no school, so it only knows the basics.")
                        : hasFavourite ? Tr.c("log.dynamicvillages.grew_up.favourite", "Finished school. Favourite job: %s.",
                                Component.translatableWithFallback(favourite.translationKey(), favourite.displayName()))
                        : Tr.c("log.dynamicvillages.grew_up.school", "Finished school."),
                npc.blockPosition());

        // Village news; the teachers are proud.
        for (VillagerModel member : ctx.mgr().getVillagers()) {
            member.brain().remember(MemoryTopic.GRADUATION, ctx.level());
            if (schooled && member.brain().profession() == Profession.TEACHER) {
                member.brain().rememberAndShow(MemoryTopic.GRADUATION, ThoughtIcon.EMOTE_PRIDE, 80, ctx.level());
            }
        }
        ctx.brain().showIcon(ThoughtIcon.EMOTE_CELEBRATION, 80, ctx.level());
    }

    /**
     * The Mayor's workplace line, with a child's days left and favourite
     * lessons, or a grown child's favourite job.
     */
    public static String workplaceNote(@Nullable NpcEntity npc, String workplace) {
        if (npc == null) return workplace;
        CompoundTag data = npc.getBrainData();
        if (npc.getProfession() == Profession.CHILD) {
            int days = daysLeft(npc);
            Profession favourite = PupilRecord.favourite(data);
            return workplace + " · grows up in " + days + (days == 1 ? " day" : " days")
                    + (favourite != null ? " · loves " + SchoolSubjects.lessonName(favourite) : "");
        }
        Profession job = PupilRecord.favouriteJob(data);
        if (npc.getProfession() == Profession.NONE && job != null) {
            int level = favouriteLevel(npc, job);
            return workplace + " · favourite job: " + job.displayName()
                    + (level > 0 ? " (level " + level + ")" : "");
        }
        return workplace;
    }

    /** The level shown on {@code job}'s skill, or -1 when it has none yet. */
    private static int favouriteLevel(NpcEntity npc, Profession job) {
        for (var skill : npc.getProfessionSkills()) {
            if (skill.profession() == job) return skill.level();
        }
        return -1;
    }
}
