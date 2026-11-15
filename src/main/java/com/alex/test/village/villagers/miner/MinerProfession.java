package com.alex.test.village.villagers.miner;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.miner.behavior.MinerActivity;

/** Wires the Miner profession to its behavior implementation. */
public final class MinerProfession {

    public static final Profession PROFESSION = Profession.MINER;

    private MinerProfession() {}

    public static WorkActivity createWorkActivity() {
        return new MinerActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        return MinerActivity.hasWork(ctx);
    }
}
