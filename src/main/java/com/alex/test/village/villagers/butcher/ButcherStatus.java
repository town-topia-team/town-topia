package com.alex.test.village.villagers.butcher;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.VillagerDiary;
import net.minecraft.nbt.CompoundTag;

/**
 * The butcher's one-line status on its villager screen: why it is not
 * working ("Needs a lead", "Needs a sword", "Needs a Butchery"), or empty while it can.
 * Each reason goes in the diary at most once a day.
 */
public final class ButcherStatus {

    private static final String TAG_STATUS = "ButcherStatus";
    private static final String TAG_LOGGED = "ButcherStatusLogged";

    public static final String NEEDS_LEAD = "Needs a lead";
    public static final String NEEDS_SWORD = "Needs a sword";
    public static final String NEEDS_RANCH = "Needs a Ranch";
    public static final String NEEDS_BUTCHERY = "Needs a Butchery";
    public static final String NEEDS_CHOPPING_BLOCK = "Needs a chopping block";
    public static final String TOO_FAR = "Butchery too far from the Ranch";
    /** Too many animals given up at the Butchery lately: no culls there until the next morning. */
    public static final String PAUSED = "Paused: animals kept getting stuck at the Butchery\n"
            + "No more animals go there until tomorrow morning.\n"
            + "Fix the room, then right-click the Butchery token and press Try again now.";
    /** The pack has no room for meat and storage will not take what it could bank. */
    public static final String STORAGE_FULL = "Storage is full";
    /** The pack has no room for meat and everything in it is something the butcher keeps. */
    public static final String PACK_FULL = "Pack is full";

    private ButcherStatus() {}

    public static String get(NpcEntity npc) {
        return npc.getBrainData().getString(TAG_STATUS);
    }

    public static void set(Activity.Context ctx, String text) {
        CompoundTag data = ctx.npc().getBrainData();
        data.putString(TAG_STATUS, text);
        if (text.isEmpty()) return;
        String logged = EveningSchedule.dayIndex(ctx.level().getDayTime()) + ":" + text;
        if (logged.equals(data.getString(TAG_LOGGED))) return;
        data.putString(TAG_LOGGED, logged);
        // A status with line breaks: its first line is the reason, the rest is help for the hover box.
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " BUTCHER idle: " + text.split("\n", 2)[0]);
    }

    public static void clear(Activity.Context ctx) {
        ctx.npc().getBrainData().putString(TAG_STATUS, "");
    }
}
