package com.alex.test.village.economy.away.jobs;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.LibraryBuilding;
import com.alex.test.village.economy.AwayClock;
import com.alex.test.village.economy.away.AwayContext;
import com.alex.test.village.economy.away.AwayJob;
import com.alex.test.village.economy.away.AwayRun;
import com.alex.test.village.economy.away.AwayXp;
import com.alex.test.village.villagers.enchanter.EnchanterBalance;
import com.alex.test.village.villagers.enchanter.behavior.EnchanterActivity;
import com.alex.test.village.villagers.enchanter.settings.EnchanterSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * An enchanter's away day (round 10 of the plan): the live wish list and its pay
 * ({@code EnchanterActivity.workOffline}) once a day, on the book, after the smith,
 * so a tool forged that day can get its book.
 *
 * <p>Live rules it copies, and where they live:
 * <ul>
 *   <li>Checks: work on ({@code EnchanterSettingsSchema.WORK_ENABLED}); a library with an enchanting
 *       table ({@code findLibrary}).</li>
 *   <li>Plan: {@code EnchantNeeds} as live (the tools each worker works with, less the books waiting in
 *       storage and packs; the player's book orders first, spares, a top book to sell once every tool is
 *       covered), reading every villager's away copy ({@link #seesEveryCopy}).</li>
 *   <li>Books a day: {@code EnchanterBalance.booksPerDay} by skill, less what the enchanter wrote live
 *       that day (its day stamp), at {@code AwayClock.AWAY_PACE} of the day's work hours, as the old
 *       calculator paces it; the share of a book left over waits for the next day (saved on the
 *       villager). Each away day is stamped with its own village day number (old bug N1).</li>
 *   <li>Pay: a blank book from the pack or storage, else bound from 3 paper (or 3 sugar cane pressed) and
 *       a leather with "Paper and Book Crafting" on; lapis by the book's level, the pack first, leaving
 *       "Lapis to leave for me" in storage; chests in reach only, above the Stock tab's holds; counted
 *       before anything is taken, each chest asked until it gives nothing (S3); the lapis-save chance.</li>
 *   <li>Time: study and table time by skill (binding and paper when made), a storage trip per book with
 *       "Storing Books" on, else per 3 books ({@link AwayWalk#TRIP_TICKS}), within the work hours at the
 *       away pace.</li>
 *   <li>The book: into storage, else the pack. One for the player's order is marked as theirs; the order
 *       hears of it at WRITE ({@code AwayOrders}).</li>
 *   <li>XP: each book, paper and binding ({@link AwayXp#enchanterPractice}); a session's intelligence
 *       for a day it wrote.</li>
 * </ul>
 * Not done away: selling to the merchant (none visits a sleeping village), fitting the workers' books,
 * binding tomorrow's blank books in the evening.
 */
public final class EnchanterAway implements AwayJob {

    public static final EnchanterAway INSTANCE = new EnchanterAway();

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The share of a book the day's allowance left over, for the next away day. */
    static final String TAG_BOOK_SHARE = "EnchanterAwayBookShare";
    /** A full day's work hours, dawn to the end of work: a day with fewer allows that share of its books. */
    static final long FULL_WORK_TICKS = Math.floorMod(
            (long) EveningSchedule.MEAN_END_WORK - EveningSchedule.WAKE_TIME, EveningSchedule.DAY_TICKS);

    private EnchanterAway() {}

    @Override
    @Nullable
    public String cannotWork(AwayContext ctx) {
        ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), EnchanterSettingsSchema.SCHEMA);
        if (!settings.getBool(EnchanterSettingsSchema.WORK_ENABLED)) return "work off";
        boolean library = false;
        for (Building building : ctx.mgr.getBuildings()) {
            if (!(building instanceof LibraryBuilding room) || !room.isValid()) continue;
            if (!room.getEnchantingTables().isEmpty()) return null;
            library = true;
        }
        return library ? "no enchanting table in the library" : "no library";
    }

    /** The wish list reads every worker's tools: their away copies, not what they carried when you left. */
    @Override
    public boolean seesEveryCopy() {
        return true;
    }

    @Override
    public void workDay(AwayContext ctx) {
        NpcEntity npc = ctx.npc();
        CompoundTag data = npc.getBrainData();
        int levelBefore = ctx.model().brain().enchanterSkillLevel();
        long day = ctx.day.calendarDay();
        int already = EnchanterActivity.booksWrittenOn(npc, day);
        int perDay = EnchanterBalance.booksPerDay(levelBefore);
        double share = FULL_WORK_TICKS <= 0 ? 1.0 : Math.min(1.0, ctx.day.workTicks() / (double) FULL_WORK_TICKS);
        double allowance = data.getDouble(TAG_BOOK_SHARE) + perDay * share * AwayClock.AWAY_PACE;
        int allowed = (int) Math.min(Math.floor(allowance), Math.max(0, perDay - already));
        double left = allowance - Math.floor(allowance);
        if (left > 0.0) data.putDouble(TAG_BOOK_SHARE, left);
        else data.remove(TAG_BOOK_SHARE);

        long hours = Math.round(ctx.day.workTicks() * AwayClock.AWAY_PACE);
        EnchanterActivity.OfflineWork work = allowed <= 0 ? null : EnchanterActivity.workOffline(ctx.activity(),
                allowed, hours, new EnchanterActivity.AwayHooks(amount -> AwayXp.enchanterPractice(ctx, amount),
                        AwayWalk.TRIP_TICKS, ctx.orders()));
        int books = work == null ? 0 : work.books();
        if (books > 0) {
            EnchanterActivity.stampBooks(npc, day, already + books);
            AwayXp.workSession(ctx);
        }
        Map<Item, Integer> made = work == null ? Map.of() : work.made();
        Map<Item, Integer> used = work == null ? Map.of() : work.used();
        made.forEach(ctx::made);
        used.forEach(ctx::used);
        String stop = work == null ? null : work.stop();
        ctx.stoppedPartway(stop);
        LOGGER.info("[AwayEnchanter] {} day {}: wrote {} of {} allowed ({} a day, {} written live that day, "
                        + "{} of a book carried on), level {} -> {}; {} tick(s) used of {}; made {}; used {}{}",
                npc.getNpcId().toString().substring(0, 8), ctx.day.index() + 1, books, allowed, perDay, already,
                String.format("%.2f", left), levelBefore, ctx.model().brain().enchanterSkillLevel(),
                work == null ? 0 : work.ticks(), hours, AwayRun.counts(made), AwayRun.counts(used),
                stop == null ? "" : "; stopped: " + stop);
    }
}
