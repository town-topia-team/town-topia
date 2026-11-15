package com.alex.test.village.villagers.builder;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.builder.behavior.BuilderActivity;
import com.alex.test.village.villagers.core.Profession;

/** Wires the Builder profession to its behavior implementation. */
public final class BuilderProfession {

    public static final Profession PROFESSION = Profession.BUILDER;

    private BuilderProfession() {}

    public static WorkActivity createWorkActivity() {
        return new BuilderActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        return BuilderActivity.hasWork(ctx);
    }
}
