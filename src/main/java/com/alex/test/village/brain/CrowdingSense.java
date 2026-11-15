package com.alex.test.village.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.stats.CrowdingBalance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;
import java.util.UUID;

/**
 * One villager's sense of being crowded, TekTopia style.
 *
 * <p>Checked every two seconds against the village headcount. A building is
 * felt at most once per visit: the first check that finds it crowded costs
 * wellbeing and floats the overcrowded icon, and nothing more happens until
 * the villager has walked out. The first check after walking in only notes
 * the building, so passing straight through a room never counts.
 *
 * <p>Crowding can take at most {@link CrowdingBalance#DAILY_CAP} wellbeing a
 * day. Past that a crowded building still shows the icon and is still
 * remembered, it just costs nothing more until tomorrow.
 */
final class CrowdingSense {

    private static final int CHECK_INTERVAL = 40;
    private static final int ICON_TICKS = 80;
    private static final String TAG_DAY = "CrowdedDay";
    private static final String TAG_TAKEN = "CrowdedToday";

    private int timer;
    /** The building this villager is in, as of the last check; null outside. */
    private UUID inside;
    /** This visit has already been felt. */
    private boolean felt;
    private long day = Long.MIN_VALUE;
    private float takenToday;

    CrowdingSense(int phase) {
        this.timer = phase % CHECK_INTERVAL;
    }

    void tick(VillagerBrain brain, VillageManager village, ServerLevel level, NpcEntity npc) {
        if (--timer > 0) return;
        timer = CHECK_INTERVAL;

        Building building = village.crowding().buildingOf(npc.getNpcId());
        if (building == null) {
            inside = null;
            felt = false;
            return;
        }
        if (!building.getItemFrameId().equals(inside)) {
            inside = building.getItemFrameId();
            felt = false;
            return;
        }
        if (felt) return;

        int bodies = village.crowding().bodiesIn(building);
        float space = building.getSpace().space();
        float wanted = CrowdingBalance.penalty(space, bodies);
        if (wanted <= 0f) return;
        felt = true;

        long today = EveningSchedule.dayIndex(level.getDayTime());
        if (day != today) {
            day = today;
            takenToday = 0f;
        }
        float applied = CrowdingBalance.allowedToday(wanted, takenToday);
        takenToday += applied;
        if (applied > 0f) brain.needs().wellbeingRules().onCrowded(brain.needs(), applied);
        brain.rememberAndShow(MemoryTopic.CROWDED, ThoughtIcon.STATUS_OVERCROWDED, ICON_TICKS, level);
        village.crowding().recordIncident(building, bodies, level.getGameTime());
        brain.diary().log(String.format(Locale.ROOT,
                "%s CROWDED in %s at %s: %d inside, space %s, wanted -%.2f, applied -%.2f (today -%.2f)",
                VillagerDiary.ts(level), building.getDisplayName(), building.getDoorPos().toShortString(),
                bodies, CrowdingBalance.format(space), wanted, applied, takenToday));
    }

    /** Wellbeing crowding has already cost today, for the debug dump. */
    float takenToday(ServerLevel level) {
        return day == EveningSchedule.dayIndex(level.getDayTime()) ? takenToday : 0f;
    }

    void save(CompoundTag tag) {
        tag.putLong(TAG_DAY, day);
        tag.putFloat(TAG_TAKEN, takenToday);
    }

    void load(CompoundTag tag) {
        if (!tag.contains(TAG_DAY)) return;
        day = tag.getLong(TAG_DAY);
        takenToday = tag.getFloat(TAG_TAKEN);
    }
}
