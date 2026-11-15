package com.alex.test.village.villagers.teacher;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.teacher.behavior.TeacherActivity;

/** Wires the Teacher profession to its behavior implementation. */
public final class TeacherProfession {

    public static final Profession PROFESSION = Profession.TEACHER;

    private TeacherProfession() {}

    public static WorkActivity createWorkActivity() {
        return new TeacherActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        return TeacherActivity.hasWork(ctx);
    }
}
