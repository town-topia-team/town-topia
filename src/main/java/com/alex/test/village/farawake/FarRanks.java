package com.alex.test.village.farawake;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.AdmireTownActivity;
import com.alex.test.village.brain.BakeBreadActivity;
import com.alex.test.village.brain.ConverseActivity;
import com.alex.test.village.brain.EatActivity;
import com.alex.test.village.brain.EveningActivity;
import com.alex.test.village.brain.FetchFoodActivity;
import com.alex.test.village.brain.FleeActivity;
import com.alex.test.village.brain.GoHomeActivity;
import com.alex.test.village.brain.LeisureActivity;
import com.alex.test.village.brain.LibraryActivity;
import com.alex.test.village.brain.PairDanceActivity;
import com.alex.test.village.brain.PreTaskHungerActivity;
import com.alex.test.village.brain.ShelterActivity;
import com.alex.test.village.brain.SocializeActivity;
import com.alex.test.village.brain.WanderActivity;

import javax.annotation.Nullable;

/** What a far villager is doing, as a {@link FarRank}: only decides which chunk loads first. */
final class FarRanks {

    private FarRanks() {}

    static FarRank of(@Nullable Activity activity, NpcEntity npc) {
        if (npc.isSleeping()) return FarRank.ASLEEP;
        if (activity == null) return FarRank.WORK;
        if (activity instanceof GoHomeActivity || activity instanceof FleeActivity
                || activity instanceof ShelterActivity) {
            return FarRank.HOME;
        }
        if (activity instanceof EatActivity || activity instanceof FetchFoodActivity
                || activity instanceof BakeBreadActivity || activity instanceof PreTaskHungerActivity) {
            return FarRank.FOOD;
        }
        if (activity instanceof EveningActivity || activity instanceof LeisureActivity
                || activity instanceof LibraryActivity || activity instanceof SocializeActivity
                || activity instanceof ConverseActivity || activity instanceof WanderActivity
                || activity instanceof AdmireTownActivity || activity instanceof PairDanceActivity) {
            return FarRank.FREE;
        }
        // Jobs, chest trips, moves and anything new: the village's goods.
        return FarRank.WORK;
    }
}
