package com.alex.test.village.economy.away.jobs;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.NavGraph;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ButcheryBuilding;
import com.alex.test.village.economy.AwayClock;
import com.alex.test.village.economy.away.AwayContext;
import com.alex.test.village.economy.away.AwayJob;
import com.alex.test.village.economy.away.AwayPens;
import com.alex.test.village.economy.away.AwayRun;
import com.alex.test.village.economy.away.AwayXp;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.butcher.ButcherBalance;
import com.alex.test.village.villagers.butcher.ButcherStatus;
import com.alex.test.village.villagers.butcher.ButcherSword;
import com.alex.test.village.villagers.butcher.behavior.ButcherActivity;
import com.alex.test.village.villagers.butcher.lead.LeadSupply;
import com.alex.test.village.villagers.butcher.settings.ButcherSettingsSchema;
import com.alex.test.village.villagers.rancher.RanchAnimals;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A butcher's away day (round 6 of the plan), on the village's pens
 * ({@link AwayPens}) after the ranchers' turns: the butchers take theirs in
 * order, each over its own work hours, so what one culled is gone for the
 * next, and no herd goes below the keep number however many butchers there
 * are (old bugs B1, B2).
 *
 * <p>Live rules it copies ({@code ButcherActivity}), and where they live:
 * <ul>
 *   <li>Checks: work on; a lead (pack, else storage: {@code LeadSupply.REQUIREMENT}) and a sword (pack,
 *       else storage or whittled: {@code ButcherSword.REQUIREMENT}), fetched only once there is an
 *       animal to cull; per pen, the Butchery with a chopping block nearest its gate, within
 *       {@code MAX_BUTCHERY_DIST}, gate and kill spot both on the butcher's part of the map
 *       ({@code placeFor}, {@code usablePlace}; old bug B3).</li>
 *   <li>Pick ({@code findCull}, {@code herds}, {@code spare}): pen by pen, kind by kind, the grown
 *       animals of an allowed kind that are nobody's pet or ride (named, saddled), counted without the
 *       ones on a lead; a kind above {@code keepAdults} gives one not in love and on no lead. Babies
 *       grown on an earlier away day count ({@link AwayPens} grows them at each day's end).</li>
 *   <li>Time: one clock over the work hours at {@code AwayClock.AWAY_PACE}. A cull is the walk to the
 *       pen and into it, the walk back on the lead, the blows ({@code blowsToKill} x {@code BLOW_TICKS})
 *       and the drops gathered; walks from the real distance gate to kill spot. As live, the end of the
 *       work hours can stop the walk to the animal, but once the lead is on the cull is finished
 *       ({@code allowsRoutineInterruption}). Up to {@code cullsPerShift} a shift, then a bank trip.</li>
 *   <li>Loot: {@code killYield} (the vanilla roll plus skill), the fleece only while the sheep has its
 *       wool on the pens' clock and salvage is on. The whole roll must fit in the pack, a bank trip
 *       first if needed; with no room at all the animal lives and the day's culling ends (old bug B5;
 *       live stops on a full pack too).</li>
 *   <li>Sword: a point a blow, for kills that happen (old bug B4); one that breaks mid-kill finishes
 *       it, the next comes from storage. The grindstone once a day, after a kill, at that kill's
 *       Butchery, when the sword has any wear ({@code afterKill}; old bug B6).</li>
 *   <li>XP: each kill ({@link AwayXp#butcherKill}); a session's intelligence per shift.</li>
 * </ul>
 * Not done away: strays brought home, an animal left waiting in the Butchery, the sword's Fire Aspect
 * (cooked meat) and Looting (nobody saw it, as the old calculator).
 */
public final class ButcherAway implements AwayJob {

    public static final ButcherAway INSTANCE = new ButcherAway();

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Blocks a tick with an animal on the lead (LEAD_SPEED 0.45, its waits for the animal included: my call). */
    static final double LEAD_BLOCKS_PER_TICK = 0.12;
    /** Finding the animal in the pen and putting the lead on (my call). */
    static final long IN_PEN_TICKS = 40L;
    /** Bringing it through the door onto its spot by the block (my call). */
    static final long ENTER_TICKS = 60L;
    /** The drops settling and gathered (live {@code DROP_SETTLE_TICKS} 10 and the pick-up: my call). */
    static final long COLLECT_TICKS = 40L;
    /** The walk to the grindstone (my call); the wheel itself is live {@code SHARPEN_TICKS}. */
    static final long GRINDSTONE_WALK_TICKS = 40L;
    private static final String CANNOT_REACH = "Cannot reach the Ranch or the Butchery";

    private ButcherAway() {}

    @Override
    @Nullable
    public String cannotWork(AwayContext ctx) {
        ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), ButcherSettingsSchema.SCHEMA);
        if (!settings.getBool(ButcherSettingsSchema.WORK_ENABLED)) return "work off";
        if (ctx.pens().isEmpty()) return ctx.pens().notLoaded() > 0 ? "pen not loaded" : "no pen";
        for (Building building : ctx.mgr.getBuildings()) {
            if (building instanceof ButcheryBuilding butchery && butchery.canButcher()) return null;
        }
        return "no Butchery with a chopping block";
    }

    @Override
    public void workDay(AwayContext ctx) {
        new Shift(ctx).run();
    }

    /** Where a pen's animals go: its Butchery and how far it is, or why none. */
    private record Place(@Nullable ButcheryBuilding butchery, double blocks, @Nullable String problem) {}

    private record Pick(AwayPens.Pen pen, AwayPens.Beast beast, ButcheryBuilding butchery, double blocks) {}

    /** One butcher's day. */
    private static final class Shift {
        private final AwayContext ctx;
        private final NpcEntity npc;
        private final InventoryManager pack;
        private final ProfessionSettings settings;
        private final AwayPens pens;
        private final int keep;
        private final boolean salvageWool;
        /** The butcher's clock, in run ticks. */
        private long t;
        private final long end;
        private final Map<AwayPens.Pen, Place> places = new HashMap<>();
        private boolean storageFull;
        /** Why the day ended before its hours did; null when the hours ran out or nothing was left. */
        @Nullable private String stop;
        /** The first pen with spare animals that could not be worked, for the log. */
        @Nullable private String problem;
        private final Map<Item, Integer> gross = new LinkedHashMap<>();
        private final Map<String, Integer> culledByKind = new TreeMap<>();
        private int culls;
        private int shiftCulls;
        private int shifts;
        private int blows;
        private int trips;
        private int broken;
        private int sharpened;

        Shift(AwayContext ctx) {
            this.ctx = ctx;
            this.npc = ctx.npc();
            this.pack = npc.getInventory();
            this.settings = ProfessionSettings.of(npc, ButcherSettingsSchema.SCHEMA);
            this.pens = ctx.pens();
            this.keep = ButcherActivity.keepAdults(settings);
            this.salvageWool = settings.getBool(ButcherSettingsSchema.SALVAGE_WOOL);
            this.t = pens.dayStart(ctx.day);
            this.end = t + Math.round(ctx.day.workTicks() * AwayClock.AWAY_PACE);
        }

        void run() {
            // As live hasWork: a butcher hired before leads existed gets its one lead.
            LeadSupply.giveOnceToOldHire(npc);
            while (t < end) {
                Pick pick = pick();
                if (pick == null) break;
                // Room first, so a bank trip never puts away a lead or sword just fetched.
                List<ItemStack> loot = loot(pick);
                if (!AwayPack.fits(pack, loot)) {
                    if (!storageFull && AwayPack.depositable(ctx) > 0) bank();
                    if (!AwayPack.fits(pack, loot)) {
                        stop = storageFull ? ButcherStatus.STORAGE_FULL : ButcherStatus.PACK_FULL;
                        break;
                    }
                }
                if (!LeadSupply.hasLead(npc) && !fetch(LeadSupply.REQUIREMENT, ButcherStatus.NEEDS_LEAD)) break;
                if (ButcherSword.slot(npc) < 0 && !fetch(ButcherSword.REQUIREMENT, ButcherStatus.NEEDS_SWORD)) break;
                if (t >= end) break;
                // The fetched sword or lead can take a slot the loot needed.
                if (!AwayPack.fits(pack, loot)) {
                    stop = ButcherStatus.PACK_FULL;
                    break;
                }
                if (!cull(pick, loot)) break;
                if (shiftCulls >= ButcherBalance.cullsPerShift(level())) endShift();
            }
            finish();
        }

        // ---------------------------------------------------------------------
        // Choosing
        // ---------------------------------------------------------------------

        /** The animal live would lead out now ({@code findCull}), or null when no pen has a spare one it can work. */
        @Nullable
        private Pick pick() {
            pens:
            for (AwayPens.Pen pen : pens.pens()) {
                for (List<AwayPens.Beast> herd : herds(pen).values()) {
                    if (spare(herd) <= keep) continue;
                    for (AwayPens.Beast candidate : herd) {
                        if (candidate.led() || pens.inLove(candidate, t)) continue;
                        Place place = places.computeIfAbsent(pen, this::placeFor);
                        if (place.problem() != null) {
                            if (problem == null) problem = place.problem();
                            continue pens;
                        }
                        return new Pick(pen, candidate, place.butchery(), place.blocks());
                    }
                }
            }
            return null;
        }

        /** The pen's grown animals this butcher may cull, by kind ({@code herds}). */
        private Map<EntityType<?>, List<AwayPens.Beast>> herds(AwayPens.Pen pen) {
            Map<EntityType<?>, List<AwayPens.Beast>> herds = new LinkedHashMap<>();
            for (AwayPens.Beast beast : pen.beasts()) {
                if (beast.baby() || beast.named() || beast.saddled()) continue;
                if (RanchAnimals.meatFor(beast.type()) == null) continue;
                if (!ButcherActivity.isCullable(settings, beast.type())) continue;
                herds.computeIfAbsent(beast.type(), type -> new ArrayList<>()).add(beast);
            }
            return herds;
        }

        /** The herd without the ones on a lead: on their way somewhere ({@code spare}). */
        private static int spare(List<AwayPens.Beast> herd) {
            int spare = 0;
            for (AwayPens.Beast beast : herd) if (!beast.led()) spare++;
            return spare;
        }

        /** The Butchery nearest the pen's gate that works, in reach and on this butcher's part of the map. */
        private Place placeFor(AwayPens.Pen pen) {
            BlockPos gate = pen.ranch().getGatePos();
            ButcheryBuilding best = null;
            boolean anyButchery = false;
            boolean anyChop = false;
            double bestDist = Double.MAX_VALUE;
            for (Building building : ctx.mgr.getBuildings()) {
                if (!(building instanceof ButcheryBuilding candidate) || !candidate.isValid()) continue;
                anyButchery = true;
                if (!candidate.canButcher()) continue;
                anyChop = true;
                double dist = Math.sqrt(candidate.killSpot().distSqr(gate));
                if (dist < bestDist) {
                    bestDist = dist;
                    best = candidate;
                }
            }
            if (!anyButchery) return new Place(null, 0, ButcherStatus.NEEDS_BUTCHERY);
            if (!anyChop) return new Place(null, 0, ButcherStatus.NEEDS_CHOPPING_BLOCK);
            if (bestDist > ButcherActivity.MAX_BUTCHERY_DIST) return new Place(null, 0, ButcherStatus.TOO_FAR);
            NavGraph graph = ctx.mgr.getGraph();
            if (graph == null || !ctx.model().canReach(graph, gate) || !ctx.model().canReach(graph, best.killSpot())) {
                return new Place(null, 0, CANNOT_REACH);
            }
            return new Place(best, bestDist, null);
        }

        // ---------------------------------------------------------------------
        // The cull
        // ---------------------------------------------------------------------

        /** The kill's drops, rolled now, as the village's stacks. */
        private List<ItemStack> loot(Pick pick) {
            AwayPens.Beast beast = pick.beast();
            Item wool = salvageWool && beast.type() == EntityType.SHEEP && pens.woolly(pick.pen(), beast, t)
                    ? beast.wool() : null;
            List<ItemStack> stacks = new ArrayList<>();
            for (Map.Entry<Item, Integer> drop : ButcherActivity.killYield(beast.type(), wool, level(),
                    ctx.level.random).entrySet()) {
                if (drop.getValue() <= 0) continue;
                ItemStack stack = VillageItemOwnership.produce(new ItemStack(drop.getKey(), drop.getValue()), ctx.mgr);
                // Live loses a drop that cannot be the village's the same way.
                if (!stack.isEmpty()) stacks.add(stack);
            }
            return stacks;
        }

        /** One cull; false when the work hours ended on the walk to the animal. */
        private boolean cull(Pick pick, List<ItemStack> loot) {
            long walk = AwayWalk.walk(pick.blocks()) + IN_PEN_TICKS;
            if (t + walk > end) {
                t = end;
                return false;
            }
            int slot = ButcherSword.slot(npc);
            if (slot < 0) {
                stop = ButcherStatus.NEEDS_SWORD;
                return false;
            }
            int kill = ButcherActivity.blowsToKill(pens.health(pick.beast()), pack.getSlot(slot), level());
            // The lead is on: the rest is finished even past the work hours, as live.
            t += walk + AwayWalk.ticks(pick.blocks(), LEAD_BLOCKS_PER_TICK) + ENTER_TICKS
                    + (long) kill * ButcherActivity.BLOW_TICKS + COLLECT_TICKS;
            for (int blow = 0; blow < kill; blow++) {
                // Broken mid-kill: the kill is finished, the next sword comes before the next one.
                if (!ToolSelectionHelper.wearTool(ctx.level, pack, slot)) {
                    broken++;
                    break;
                }
            }
            blows += kill;
            for (ItemStack stack : loot) {
                int placed = stack.getCount();
                if (!pack.addItem(stack)) placed = ctx.store(stack.copy());
                if (placed > 0) gross.merge(stack.getItem(), placed, Integer::sum);
            }
            pens.cull(pick.pen(), pick.beast());
            culledByKind.merge(EntityType.getKey(pick.beast().type()).getPath(), 1, Integer::sum);
            culls++;
            shiftCulls++;
            AwayXp.butcherKill(ctx);
            sharpen(pick.butchery());
            return true;
        }

        /** The day's one grindstone visit, after a kill, while the sword has any wear ({@code afterKill}). */
        private void sharpen(ButcheryBuilding butchery) {
            long day = ctx.day.calendarDay();
            if (t >= end || butchery.getGrindstone() == null
                    || ButcherActivity.sharpenedOn(npc.getBrainData(), day)) return;
            int slot = ButcherSword.slot(npc);
            if (slot < 0) return;
            ItemStack sword = pack.getSlot(slot);
            if (!sword.isDamageableItem() || sword.getDamageValue() <= 0) return;
            ButcherActivity.markSharpened(npc.getBrainData(), day);
            t += GRINDSTONE_WALK_TICKS + ButcherActivity.SHARPEN_TICKS;
            if (ButcherActivity.sharpenSword(npc, ctx.level.random) >= 0) sharpened++;
        }

        /** A lead or sword from storage, a trip; false (and why) when there is none. */
        private boolean fetch(ProfessionToolRequirement requirement, String none) {
            if (!ctx.restock(requirement)) {
                stop = none;
                return false;
            }
            t += AwayWalk.TRIP_TICKS;
            trips++;
            return true;
        }

        // ---------------------------------------------------------------------
        // Pack and storage
        // ---------------------------------------------------------------------

        /** The shift's culls are done: the pack is banked ({@code goDeposit}) and a new shift begins. */
        private void endShift() {
            if (!storageFull && AwayPack.depositable(ctx) > 0) bank();
            shifts++;
            shiftCulls = 0;
        }

        /** A storage trip with everything the butcher does not keep ({@code DepositActivity.keptPerSlot}). */
        private void bank() {
            t += AwayWalk.TRIP_TICKS;
            trips++;
            if (AwayPack.bank(ctx) == 0) storageFull = true;
        }

        // ---------------------------------------------------------------------
        // The day's books
        // ---------------------------------------------------------------------

        private int level() {
            return ctx.model().brain().butcherSkillLevel();
        }

        private void finish() {
            if (shiftCulls > 0) endShift();
            if (shifts > 0) AwayXp.workSessions(ctx, shifts);
            gross.forEach(ctx::made);
            ctx.stoppedPartway(stop);
            LOGGER.info("[AwayButcher] {} day {}: culled {} {} in {} shift(s), {} tick(s) left of {}; {} blow(s), "
                            + "{} sword(s) broke, sharpened {}, trips {}; made {}{}{}",
                    npc.getNpcId().toString().substring(0, 8), ctx.day.index() + 1, culls, culledByKind, shifts,
                    Math.max(0L, end - t), Math.round(ctx.day.workTicks() * AwayClock.AWAY_PACE), blows, broken,
                    sharpened, trips, AwayRun.counts(gross), stop == null ? "" : "; stopped: " + stop,
                    problem == null ? "" : "; pen skipped: " + problem);
        }
    }
}
