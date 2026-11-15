package com.alex.test.village.villagers.butcher;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavReachability;
import com.alex.test.village.VillageDays;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ButcheryBuilding;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.butcher.behavior.ButcherActivity;
import com.alex.test.village.villagers.butcher.lead.LedAnimals;
import com.alex.test.village.villagers.butcher.settings.ButcherSettingsSchema;
import com.alex.test.village.villagers.rancher.RanchAnimals;
import com.alex.test.village.villagers.rancher.RanchClaims;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.settings.SettingsSchema;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's half of the Butchery window's check list (round 4 of
 * docs/plans/2026-10-24-butchery-window.md): which chopping blocks have floor beside them, how the
 * Ranch links to this Butchery, whether there is a way from its gate to the butcher's tile, and each
 * nearby butcher's own status. Round 5 adds the Animals tab: each pen that brings its animals here,
 * counted as the butcher's pick counts them. The door and the stairs or slabs are checked by the
 * screen from the client's world. Reads only.
 */
public final class ButcheryChecks {

    /** How the village's Ranches link to this Butchery. */
    public enum RanchLink {
        /** No Ranch in the village. */
        NONE,
        /** The nearest Ranch gate is beyond {@link ButcherActivity#MAX_BUTCHERY_DIST}. */
        TOO_FAR,
        /** A Ranch is near enough, but the pens all take their animals to another, nearer Butchery. */
        OTHER_NEARER,
        /** At least one pen brings its animals here. */
        USED
    }

    /** The way a led animal walks: from the Ranch gate to the butcher's tile here. */
    public enum Path { UNKNOWN, OK, NONE }

    /**
     * One butcher near a player: its name, its status line's first line (empty when nothing stops
     * it), whether its work is switched on, the adults of each kind it keeps and the kinds it may
     * cull (entity type ids), from its own settings. Round 6: {@code kills} and {@code strays} since
     * the village morning, {@code sword} the share of its sword left in percent ({@link #NO_SWORD},
     * {@link #NO_WEAR}), {@code sharpened} village days since its last grindstone visit ({@link #NEVER}).
     */
    public record Butcher(String name, String status, boolean workOn, int keep, List<String> allowed,
                          int kills, int strays, int sword, int sharpened) {
        public static final int NO_SWORD = -1, NO_WEAR = -2, NEVER = -1;
    }

    /**
     * One kind of animal in a pen, as the butcher's pick counts it: {@code adults} leaves out babies
     * and players' animals; {@code busy} of them are on a butcher's lead or being worked by someone (as
     * {@code spare} leaves them out); {@code waiting} more are in love or tied up, so never picked now.
     */
    public record Herd(String type, int adults, int busy, int waiting) {
        /** Adults over {@code keep} once the busy ones are left out. */
        public int toCull(int keep) {
            return Math.max(0, adults - busy - keep);
        }

        /** Whether one of those can be picked now: not busy and not waiting. */
        public boolean pickable() {
            return adults - busy - waiting > 0;
        }
    }

    /**
     * A pen that brings its animals here: blocks from its gate, its herds of kinds with meat, and the
     * babies and players' animals of those kinds (never culled).
     */
    public record Pen(int distance, List<Herd> herds, int neverCulled) {}

    /**
     * {@code usableBlocks}: the chopping blocks with a free tile beside them for the butcher and one
     * more for the animal. {@code outside}: the two tiles outside the door, nearest first.
     * {@code distance}: blocks to the nearest Ranch gate (to the one using it, when {@code USED});
     * {@code ranchesUsing}: pens bringing animals here, listed in {@code pens} (round 5).
     * {@code defaultKeep}: what a butcher keeps when its setting is untouched (no butcher nearby).
     */
    public record Checks(List<BlockPos> usableBlocks, List<BlockPos> outside, RanchLink ranch, int distance,
                         int ranchesUsing, Path path, List<Butcher> butchers, List<Pen> pens, int defaultKeep) {
    }

    /** Herds in this order, the rest after by id. */
    private static final List<EntityType<?>> KIND_ORDER =
            List.of(EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN);

    private ButcheryChecks() {}

    public static Checks compute(VillageManager village, ButcheryBuilding butchery) {
        List<BlockPos> usable = butchery.getWorkSpots().stream().map(ButcheryBuilding.WorkSpots::chop)
                .distinct().sorted().toList();
        Direction out = butchery.getInteriorDirection().getOpposite();
        List<BlockPos> outside = List.of(butchery.getDoorPos().relative(out), butchery.getDoorPos().relative(out, 2));
        ServerLevel level = village.getLevel();
        long now = level.getGameTime();

        // As ButcherActivity.placeFor picks: the working Butchery nearest each pen's gate, within reach.
        BlockPos from = butchery.killSpot() != null ? butchery.killSpot() : butchery.getDoorPos();
        double nearest = Double.MAX_VALUE, usedNearest = Double.MAX_VALUE;
        int using = 0;
        RanchBuilding usedBy = null, nearestRanch = null;
        List<Pen> pens = new ArrayList<>();
        for (Building building : village.getBuildings()) {
            if (!(building instanceof RanchBuilding ranch) || !ranch.isValid()) continue;
            double dist = Math.sqrt(from.distSqr(ranch.getGatePos()));
            if (dist < nearest) {
                nearest = dist;
                nearestRanch = ranch;
            }
            if (butchery.canButcher() && dist <= ButcherActivity.MAX_BUTCHERY_DIST
                    && ButcherActivity.nearestWorkingButchery(village, ranch.getGatePos()) == butchery) {
                using++;
                pens.add(pen(level, ranch, (int) Math.round(dist), now));
                if (dist < usedNearest) {
                    usedNearest = dist;
                    usedBy = ranch;
                }
            }
        }
        pens.sort(Comparator.comparingInt(Pen::distance));
        RanchLink link = nearestRanch == null ? RanchLink.NONE
                : using > 0 ? RanchLink.USED
                : nearest > ButcherActivity.MAX_BUTCHERY_DIST ? RanchLink.TOO_FAR
                : RanchLink.OTHER_NEARER;
        double shown = link == RanchLink.USED ? usedNearest : nearest;

        Path path = Path.UNKNOWN;
        RanchBuilding walkFrom = usedBy != null ? usedBy : link == RanchLink.OTHER_NEARER ? nearestRanch : null;
        NavGraph graph = village.getGraph();
        if (walkFrom != null && graph != null && butchery.killSpot() != null) {
            path = NavReachability.canReach(graph, walkFrom.getGatePos(), butchery.killSpot()) ? Path.OK : Path.NONE;
        }

        List<Butcher> butchers = new ArrayList<>();
        long today = VillageDays.dayIndex(level.getDayTime());
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.getProfession() != Profession.BUTCHER) continue;
            ProfessionSettings settings = ProfessionSettings.of(npc, ButcherSettingsSchema.SCHEMA);
            boolean workOn = settings.getBool(ButcherSettingsSchema.WORK_ENABLED);
            String status = ButcherStatus.get(npc).split("\n", 2)[0];
            List<String> allowed = new ArrayList<>();
            for (EntityType<?> kind : KIND_ORDER) {
                if (ButcherActivity.isCullable(settings, kind)) allowed.add(EntityType.getKey(kind).toString());
            }
            int slot = ButcherSword.slot(npc);
            ItemStack sword = slot < 0 ? ItemStack.EMPTY : npc.getInventory().getSlot(slot);
            int swordLeft = sword.isEmpty() ? Butcher.NO_SWORD : !sword.isDamageableItem() ? Butcher.NO_WEAR
                    : (sword.getMaxDamage() - sword.getDamageValue()) * 100 / sword.getMaxDamage();
            long sharpenDay = ButcherActivity.sharpenDay(npc.getBrainData());
            int sharpened = sharpenDay == Long.MIN_VALUE ? Butcher.NEVER : (int) Math.clamp(today - sharpenDay, 0, 9999);
            butchers.add(new Butcher(VillageManager.displayName(model), status, workOn,
                    ButcherActivity.keepAdults(settings), List.copyOf(allowed), model.brain().butcherKillsToday(),
                    model.brain().butcherStraysToday(), swordLeft, sharpened));
        }
        butchers.sort(Comparator.comparing(Butcher::name, String.CASE_INSENSITIVE_ORDER));
        int defaultKeep = ButcherSettingsSchema.SCHEMA.findRow(ButcherSettingsSchema.KEEP_ADULTS)
                .map(SettingsSchema.Row::defaultInt).orElse(2);
        return new Checks(usable, outside, link, link == RanchLink.NONE ? 0 : (int) Math.round(shown), using, path,
                List.copyOf(butchers), List.copyOf(pens), Math.max(2, defaultKeep));
    }

    /** The pen's kinds with meat, counted as {@code ButcherActivity.herds} and {@code spare} do. */
    private static Pen pen(ServerLevel level, RanchBuilding ranch, int distance, long now) {
        Map<EntityType<?>, int[]> counts = new LinkedHashMap<>();
        int never = 0;
        for (Animal animal : ranch.animalsInside(level)) {
            if (RanchAnimals.meatFor(animal.getType()) == null) continue;
            if (animal.isBaby() || ButcherActivity.isPlayersAnimal(animal)) {
                never++;
                continue;
            }
            int[] count = counts.computeIfAbsent(animal.getType(), type -> new int[3]);
            count[0]++;
            if (LedAnimals.isLed(animal) || RanchClaims.claimant(animal.getUUID(), now) != null) {
                count[1]++;
            } else if (animal.isInLove() || animal.isLeashed()) {
                count[2]++;
            }
        }
        List<Herd> herds = new ArrayList<>();
        counts.forEach((type, count) -> herds.add(new Herd(EntityType.getKey(type).toString(), count[0], count[1], count[2])));
        herds.sort(Comparator.comparingInt((Herd herd) -> order(herd.type())).thenComparing(Herd::type));
        return new Pen(distance, List.copyOf(herds), never);
    }

    private static int order(String type) {
        for (int i = 0; i < KIND_ORDER.size(); i++) {
            if (EntityType.getKey(KIND_ORDER.get(i)).toString().equals(type)) return i;
        }
        return KIND_ORDER.size();
    }
}
