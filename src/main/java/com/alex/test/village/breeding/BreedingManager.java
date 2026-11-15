package com.alex.test.village.breeding;

import com.alex.test.DynamicVillages;
import com.alex.test.Tr;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.log.VillageLog;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.HouseBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.stats.FoodCatalog;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.villagers.child.ChildAssignmentHandler;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Grows the village's population when it can afford to.
 *
 * <p>At night, two eligible adults sleeping in the same house may have a child
 * when the village has enough food and a spare bed.
 */
public final class BreedingManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ticks between breeding checks. */
    public static final int CHECK_INTERVAL = 400;
    /** Food items a valid StorageBuilding must hold before a birth is even considered. */
    public static final int FOOD_RESERVE = 16;
    /** Food withdrawn from storage per birth. May land short of this if the matched
     *  stack itself is smaller — {@link StorageBuilding#withdrawItems} only ever
     *  withdraws one concrete item type per call, so a birth still costs something
     *  even when the reserve is split across several food types. */
    public static final int FOOD_COST = 8;
    /** Minimum current happiness for a villager to be willing to breed. */
    public static final float WELLBEING_THRESHOLD = 42f;
    /** Minimum hunger (i.e. how well-fed) a parent needs to be willing to breed. */
    public static final float FED_THRESHOLD = 50f;
    /** Minimum ticks between eligible attempts in the same village. */
    public static final long BIRTH_COOLDOWN_TICKS = 24000L;
    /** Chance of a birth on each eligible attempt. */
    // 0.25 until 0.1.3: with one attempt a night that was a child every four nights at best, and
    // late-game towns with free beds and happy people saw three children in weeks of play.
    public static final float BIRTH_CHANCE = 0.4f;

    /**
     * Members a village may never exceed, birth or no birth.
     *
     * <p>A backstop against runaway growth, not the intended ceiling — the real
     * limiter is the bed gate below, which needs strictly more beds than
     * members and so makes population something the player builds. This was 20,
     * which is well under the fifty-villager town the mod is aiming at: an
     * economy bench with forty beds and a full larder pinned at exactly twenty
     * and stayed there, and no amount of farming would have moved it.
     */
    public static final int MAX_POPULATION = 64;
    /** Sleeping adults content and fed enough to breed, required for an attempt. */
    private static final int MIN_WILLING_PARENTS = 2;

    private final VillageManager village;
    private int timer;

    /** Game time of the last eligible birth attempt, successful or not. */
    private long lastAttemptGameTime = Long.MIN_VALUE;

    public BreedingManager(VillageManager village) {
        this.village = village;
    }

    public void tick(ServerLevel level) {
        if (DynamicVillages.isServerStopping()) return;
        if (++timer < CHECK_INTERVAL) return;
        timer = 0;
        tryBreedAt(level, level.getGameTime(), false);
    }

    /**
     * The night is ending without further ticks; give sleepers their nightly
     * attempt. A clock turned back to morning ({@code /time set day}) lands
     * here after the clock already reads day, so the night check is skipped;
     * the parents still have to be lying in their beds.
     */
    public void onPlayerSleepFinished(ServerLevel level, long skippedTicks) {
        tryBreedAt(level, level.getGameTime(), true);
        if (lastAttemptGameTime != Long.MIN_VALUE) lastAttemptGameTime -= skippedTicks;
    }

    /**
     * Runs one breeding gate-check-and-spawn cycle at {@code now}.
     */
    private boolean tryBreedAt(ServerLevel level, long now, boolean nightEnding) {
        if (!birthsEnabled()) return false;
        if (!nightEnding && !VillagerBrain.isNight(level)) return false;

        if (lastAttemptGameTime != Long.MIN_VALUE && now - lastAttemptGameTime < BIRTH_COOLDOWN_TICKS) {
            return false;
        }

        Collection<VillagerModel> members = village.getVillagers();
        // The village's own list, not a copy: it counts the child too once it is bound.
        int before = members.size();
        if (members.size() >= MAX_POPULATION) return false;

        // Spare housing before spare food: cheaper to compute, and a village with
        // no free bed cannot house the child regardless of what storage holds.
        int totalBeds = countHouseBeds();
        if (totalBeds <= members.size()) return false;

        StorageBuilding storage = findStorageWithFood(level);
        if (storage == null) return false;

        List<VillagerModel> willing = findWillingParents(members);
        if (willing.size() < MIN_WILLING_PARENTS) return false;
        lastAttemptGameTime = now;
        if (level.random.nextFloat() >= BIRTH_CHANCE) return false;

        NpcEntity child = DynamicVillages.NPC.get().create(level);
        if (child == null) return false;

        BlockPos spawnAt = willing.get(0).puppet().blockPosition();
        child.moveTo(spawnAt.getX() + 0.5, spawnAt.getY(), spawnAt.getZ() + 0.5, 0, 0);
        // Refused (another mod cancelling the spawn): no baby, so no food spent, no news and no log line.
        if (!level.addFreshEntity(child)) return false;
        village.bindVillager(child);
        ChildAssignmentHandler.assignFirstTime(child);

        // Births do not eat into the stock limit set in the storage screen.
        StockReserves reserves = StockReserves.get(level.getServer());
        ItemStack consumed = storage.withdrawItems(level, StorageBuilding.ResourceType.FOOD, FOOD_COST,
                item -> reserves.free(village, level, item) <= 0);

        // The parents remember it as family, everyone else as village news --
        // which is how a birth ends up being what the square is talking about
        // the next morning.
        for (VillagerModel parent : willing) {
            parent.brain().remember(MemoryTopic.FAMILY, level);
        }
        for (VillagerModel member : village.getVillagers()) {
            member.brain().remember(MemoryTopic.CELEBRATION, level);
        }

        VillageLog.record(village, level, VillageLog.Kind.BIRTH,
                Tr.c("log.dynamicvillages.birth", "%s and %s had a baby",
                        parentName(willing.get(0)), parentName(willing.get(1))),
                Tr.c("log.dynamicvillages.birth.count", "The village now has %s villagers.", before + 1),
                spawnAt);

        LOGGER.info("[BreedingManager {}] Birth: village grew {} -> {} member(s); consumed {}x food.",
                shortId(),
                before, before + 1, consumed.getCount());
        return true;
    }

    private static Component parentName(VillagerModel parent) {
        NpcEntity npc = parent.puppet();
        return npc != null ? npc.getDisplayName() : Component.literal("Villager");
    }

    /**
     * Whether the player has left births on at the Mayor's screen. Kept in
     * the village rules so the choice is saved with the world.
     */
    public boolean birthsEnabled() { return village.rules().birthsEnabled(); }

    public void setBirthsEnabled(boolean enabled) {
        if (birthsEnabled() == enabled) return;
        village.rules().setBirthsEnabled(enabled);
        village.markPersistentDataDirty();
        LOGGER.info("[BreedingManager {}] Births {}.", shortId(),
                enabled ? "enabled" : "disabled by the Mayor");
    }

    /** Sum of beds across every currently-valid HouseBuilding. */
    private int countHouseBeds() {
        int total = 0;
        for (Building building : village.getBuildings()) {
            if (building instanceof HouseBuilding house && house.isValid()) {
                total += house.getBeds().size();
            }
        }
        return total;
    }

    /** First valid StorageBuilding holding at least {@link #FOOD_RESERVE} food items, or null. */
    private StorageBuilding findStorageWithFood(ServerLevel level) {
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            if (countFood(level, storage) >= FOOD_RESERVE) return storage;
        }
        return null;
    }

    /** Direct chest tally rather than a cached count — StorageBuilding only indexes
     *  presence (which chests hold food), not quantity. A player's food counts a
     *  quarter, so a chest of it does not start a baby boom. */
    private int countFood(ServerLevel level, StorageBuilding storage) {
        float total = 0f;
        for (var chest : StorageBuilding.inventories(level,
                storage.findChestsWithResource(level, StorageBuilding.ResourceType.FOOD))) {
            for (int i = 0; i < chest.size(); i++) {
                ItemStack stack = chest.peek(i);
                if (storage.mayUse(level, stack) && VillageItemOwnership.isVillagerFood(stack)) {
                    total += stack.getCount() * (FoodCatalog.isHomeMade(stack)
                            ? 1f : PLAYER_FOOD_BIRTH_SHARE);
                }
            }
        }
        return (int) total;
    }

    /** How much a player's food counts towards the food a birth needs in storage. */
    private static final float PLAYER_FOOD_BIRTH_SHARE = 0.25f;

    /**
     * Adult (non-CHILD) members happy and fed enough to breed.
     *
     * Requires two eligible adults asleep in beds belonging to the same valid
     * house. Sleeping in the Town Hall does not count.
     */
    private List<VillagerModel> findWillingParents(Collection<VillagerModel> members) {
        Map<HouseBuilding, List<VillagerModel>> byHouse = new LinkedHashMap<>();
        for (VillagerModel model : members) {
            if (model.brain().profession() == Profession.CHILD) continue;
            if (!village.isSleepingInAssignedBed(model)) continue;
            if (!(village.getAssignedBedBuilding(model.npcId) instanceof HouseBuilding house)
                    || !house.isValid()) continue;
            VillagerStats needs = model.brain().needs();
            if (needs.wellbeing >= WELLBEING_THRESHOLD
                    && needs.hunger >= FED_THRESHOLD) {
                List<VillagerModel> housemates = byHouse.computeIfAbsent(house, ignored -> new ArrayList<>());
                housemates.add(model);
                if (housemates.size() >= MIN_WILLING_PARENTS) return housemates;
            }
        }
        return List.of();
    }

    private String shortId() {
        return village.getVillageId().toString().substring(0, 8);
    }
}
