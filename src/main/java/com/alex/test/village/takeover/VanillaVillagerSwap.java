package com.alex.test.village.takeover;

import com.alex.test.DynamicVillages;
import com.alex.test.Tr;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.VillagerVillageLink;
import com.alex.test.village.chat.VillageDamageAlerts;
import com.alex.test.village.log.VillageLog;
import com.alex.test.village.stats.HungerSystem;
import com.alex.test.village.stats.WellbeingSystem;
import com.alex.test.village.villagers.child.ChildAssignmentHandler;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkill;
import com.alex.test.village.villagers.core.ProfessionSkills;
import com.alex.test.village.villagers.registry.VillagerJobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives a brand-new generated village our villagers instead of vanilla ones.
 *
 * <p>Each village gets a size, rolled once: 4 or 5 villagers, 5 only with luck,
 * the Mayor on top. As a brand-new village's chunks load for the first time,
 * each vanilla villager the generator put there is swapped on the spot for a
 * waiting settler of ours, up to that size, so a player never meets one; the
 * rest are gone before anyone sees them. The settler remembers its old trade
 * and does nothing until its village wakes up. Then the village takes its
 * settlers in, nearest the Town Hall first: a farmer always, then the jobs the
 * village rolled (a lumberjack always, a guard sometimes), each to
 * the settler whose old trade fits best. The other adults start with no job,
 * and babies are children. A village that came out with fewer than its size
 * gets newcomers in its houses to make it up.
 *
 * <p>A village that will not wake after all (it turned out too close to
 * another, its setup kept failing, or the takeover was switched off) gets its
 * vanilla villagers back: each waiting settler turns back into the vanilla
 * villager it was, old trade and all.
 *
 * <p>Vanilla villagers are otherwise never touched: not the ones of a village
 * whose chunks were loaded before, not cured zombie villagers, not bred ones,
 * wherever they walk.
 */
final class VanillaVillagerSwap {

    /** Fewest villagers a generated village starts with. */
    static final int MIN_VILLAGERS = 4;
    /** Most villagers a generated village can start with, and only a lucky one. The Mayor comes on top. */
    static final int MAX_VILLAGERS = 5;
    /** Chance weights for a village of 4 and 5 villagers. */
    private static final int[] SIZE_WEIGHTS = {70, 30};

    /**
     * How a village's villagers start out: the jobs handed out in order (the
     * farmer first), and the village's land and wealth for their levels,
     * pockets and moods.
     */
    record Setup(List<Profession> jobs, VillageCharacter.Kind kind, VillageCharacter.Wealth wealth) {}

    /** Where a waiting settler keeps its old vanilla trade, e.g. {@code minecraft:farmer}. */
    private static final String VANILLA_JOB_KEY = "dynamicvillages:vanilla_job";
    /** Where a waiting settler keeps its village's start chunk. */
    private static final String SETTLER_VILLAGE_KEY = "dynamicvillages:settler_village";
    /**
     * On a vanilla villager that was a waiting settler and was given back:
     * the mod never touches it again.
     */
    static final String RESTORED_TAG = "dynamicvillages.vanilla_restored";

    private VanillaVillagerSwap() {}

    record Result(int joined, int left) {}

    /** How many villagers a generated village gets: mostly 4, 5 with luck. */
    static int rollVillageSize(RandomSource random) {
        int total = 0;
        for (int weight : SIZE_WEIGHTS) total += weight;
        int roll = random.nextInt(total);
        for (int i = 0; i < SIZE_WEIGHTS.length; i++) {
            roll -= SIZE_WEIGHTS[i];
            if (roll < 0) return MIN_VILLAGERS + i;
        }
        return MIN_VILLAGERS;
    }

    // =========================================================================
    // Before the village wakes
    // =========================================================================

    /**
     * Puts a waiting settler of ours where a vanilla villager of a brand-new
     * village has just been generated. No particles: this happens as its chunk
     * loads for the first time, at the edge of view.
     */
    static boolean makeWaitingSettler(ServerLevel level, Villager villager, ChunkPos village) {
        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        if (npc == null) return false;
        npc.moveTo(villager.getX(), villager.getY(), villager.getZ(), villager.getYRot(), villager.getXRot());
        npc.setYHeadRot(villager.getYHeadRot());
        // A name-tagged vanilla villager keeps its name if no villager has it.
        if (villager.hasCustomName()) npc.suggestName(villager.getCustomName());
        npc.addTag(NpcEntity.WAITING_SETTLER_TAG);
        npc.getPersistentData().putString(VANILLA_JOB_KEY,
                BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession()).toString());
        npc.getPersistentData().putLong(SETTLER_VILLAGE_KEY, village.toLong());
        if (villager.isBaby()) ChildAssignmentHandler.assignFirstTime(npc);
        if (!level.addFreshEntity(npc)) return false;
        dropQuietly(villager);
        return true;
    }

    /** The start chunk of the village a waiting settler belongs to; null for one made before this was kept. */
    @Nullable
    static ChunkPos settlerVillage(NpcEntity npc) {
        if (!npc.getPersistentData().contains(SETTLER_VILLAGE_KEY)) return null;
        return new ChunkPos(npc.getPersistentData().getLong(SETTLER_VILLAGE_KEY));
    }

    /**
     * Gives a waiting settler back as the vanilla villager it was: its old
     * trade, a child still a child, dressed for the biome it stands in, made
     * the way the generator makes its villagers. For a village that will not
     * wake (too close to another, failed, or the takeover switched off).
     */
    static boolean restoreVanilla(ServerLevel level, NpcEntity npc) {
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) return false;
        villager.moveTo(npc.getX(), npc.getY(), npc.getZ(), npc.getYRot(), npc.getXRot());
        villager.setYHeadRot(npc.getYHeadRot());
        EventHooks.finalizeMobSpawn(villager, level, level.getCurrentDifficultyAt(villager.blockPosition()),
                MobSpawnType.STRUCTURE, null);
        ResourceLocation jobId = ResourceLocation.tryParse(npc.getPersistentData().getString(VANILLA_JOB_KEY));
        VillagerProfession job = jobId != null && BuiltInRegistries.VILLAGER_PROFESSION.containsKey(jobId)
                ? BuiltInRegistries.VILLAGER_PROFESSION.get(jobId) : VillagerProfession.NONE;
        villager.setVillagerData(villager.getVillagerData()
                .setType(VillagerType.byBiome(level.getBiome(villager.blockPosition())))
                .setProfession(job));
        if (npc.getProfession() == Profession.CHILD) villager.setAge(AgeableMob.BABY_START_AGE);
        if (npc.hasCustomName()) {
            villager.setCustomName(npc.getCustomName());
            villager.setCustomNameVisible(npc.isCustomNameVisible());
        }
        villager.addTag(RESTORED_TAG);
        if (!level.addFreshEntity(villager)) return false;
        npc.discard();
        return true;
    }

    /**
     * Gives back every loaded waiting settler of {@code village} in {@code area}
     * as a vanilla villager. Settlers not loaded now are given back as they load.
     *
     * @return how many were given back
     */
    static int restoreAll(ServerLevel level, AABB area, ChunkPos village) {
        int restored = 0;
        for (NpcEntity npc : level.getEntitiesOfClass(NpcEntity.class, area, NpcEntity::isWaitingSettler)) {
            ChunkPos belongs = settlerVillage(npc);
            // Another village's settler standing on this ground keeps waiting for its own.
            if (belongs != null && !belongs.equals(village)) continue;
            if (restoreVanilla(level, npc)) restored++;
        }
        return restored;
    }

    /** A generated vanilla villager past its village's size: gone before anyone sees it. */
    static void dropQuietly(Villager villager) {
        // Off every vanilla claim, so no bed or work block stays marked as taken.
        villager.releasePoi(MemoryModuleType.HOME);
        villager.releasePoi(MemoryModuleType.JOB_SITE);
        villager.releasePoi(MemoryModuleType.POTENTIAL_JOB_SITE);
        villager.releasePoi(MemoryModuleType.MEETING_POINT);
        villager.discard();
    }

    // =========================================================================
    // When the village wakes
    // =========================================================================

    static Result swap(ServerLevel level, VillageManager village, AABB area, BlockPos townHall, int size,
                       Setup setup) {
        List<NpcEntity> everyone = settlers(level, area, Vec3.atCenterOf(townHall));
        Map<NpcEntity, Profession> staying = plan(everyone, village, size, setup.jobs());

        // A well-off village may have one villager far better at their job than the rest.
        NpcEntity veteran = null;
        if (setup.wealth() == VillageCharacter.Wealth.WELL_OFF
                && level.random.nextFloat() < VillageCharacter.VETERAN_CHANCE) {
            List<NpcEntity> workers = new ArrayList<>();
            for (Map.Entry<NpcEntity, Profession> entry : staying.entrySet()) {
                if (entry.getValue() != Profession.NONE && entry.getValue() != Profession.CHILD) {
                    workers.add(entry.getKey());
                }
            }
            if (!workers.isEmpty()) veteran = workers.get(level.random.nextInt(workers.size()));
        }

        int joined = 0;
        int left = 0;
        for (NpcEntity settler : everyone) {
            Profession job = staying.get(settler);
            if (job != null && welcome(level, village, settler, job, setup, settler == veteran)) {
                joined++;
            } else {
                moveAway(level, settler);
                left++;
            }
        }
        DynamicVillages.LOGGER.info("[Takeover] Village {}: {} settler(s) joined, {} moved away{}.",
                village.getVillageId().toString().substring(0, 8), joined, left,
                veteran != null ? ", one of them a veteran" : "");
        return new Result(joined, left);
    }

    /**
     * A waiting settler whose chunk loaded after its village woke. Settlers were
     * already capped at the village's size when they were made, so it simply
     * joins, taking the first of the village's jobs nobody has yet.
     */
    static void adopt(ServerLevel level, VillageManager village, NpcEntity settler, List<Profession> jobs) {
        if (!village.contains(settler.blockPosition())) {
            moveAway(level, settler);
            return;
        }
        Profession job = settler.getProfession() == Profession.CHILD
                ? Profession.CHILD : missingJob(village, jobs);
        if (!welcome(level, village, settler, job, null, false)) moveAway(level, settler);
    }

    /** Waiting settlers in {@code area}, nearest {@code center} first. */
    private static List<NpcEntity> settlers(ServerLevel level, AABB area, Vec3 center) {
        List<NpcEntity> found = new ArrayList<>(level.getEntitiesOfClass(NpcEntity.class, area,
                npc -> npc.isAlive() && npc.isWaitingSettler()));
        found.sort(Comparator.comparingDouble(npc -> npc.distanceToSqr(center)));
        return found;
    }

    /**
     * A villager of ours for a village that came out of generation with fewer
     * villagers than its size, standing at {@code at}, with the first of the
     * village's jobs nobody has yet. It belongs to the village from the start.
     */
    static boolean addNewcomer(ServerLevel level, VillageManager village, BlockPos at, Setup setup) {
        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        if (npc == null) return false;
        npc.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360f, 0f);
        Profession job = missingJob(village, setup.jobs());
        try {
            // The same order a recruited nomad uses.
            npc.setVillageLink(new VillagerVillageLink(
                    village.getVillageId(), level.dimension(), village.getBellPos()));
            if (!VillagerJobManager.applyFirstTime(npc, job)) return false;
            startOut(level, npc, job, setup, false);
            if (!level.addFreshEntity(npc)) return false;
            village.registerVillager(npc);
            if (village.getModel(npc.getNpcId()) == null) throw new IllegalStateException("no model");
        } catch (RuntimeException failure) {
            village.removeVillager(npc.getNpcId());
            npc.discard();
            DynamicVillages.LOGGER.error("[Takeover] Failed to add a newcomer to village {}.",
                    village.getVillageId(), failure);
            return false;
        }
        if (job != Profession.NONE) VillagerJobManager.requestImmediateWorkStart(npc);
        logJoined(level, village, npc, Tr.c("log.dynamicvillages.joined.newcomer",
                "Moved in when the village was founded."));
        return true;
    }

    /** "Farmer Kel joined the village", and how, in the village log. */
    private static void logJoined(ServerLevel level, VillageManager village, NpcEntity npc, Component how) {
        // Names it now (the setup its first tick would do), so the line has its name.
        npc.ensureProfessionSkillsInitialized();
        VillageLog.record(village, level, VillageLog.Kind.JOINED,
                Tr.c("log.dynamicvillages.joined", "%s joined the village", VillageDamageAlerts.jobAndName(npc)),
                how, npc.blockPosition());
    }

    /**
     * Who stays, nearest first, and as what, up to {@code size}: the village's
     * jobs in order (the farmer first), each to the adult whose old trade fits
     * best, then the other adults with no job, then children. Children never
     * take the places the jobs need, so the farmer always comes, as a newcomer
     * if no adult settler is left for it.
     */
    private static Map<NpcEntity, Profession> plan(List<NpcEntity> everyone, VillageManager village, int size,
                                                   List<Profession> jobs) {
        List<NpcEntity> adults = new ArrayList<>();
        List<NpcEntity> children = new ArrayList<>();
        for (NpcEntity settler : everyone) {
            // Ours must live inside the village; anyone outside it simply leaves.
            if (!village.contains(settler.blockPosition())) continue;
            (settler.getProfession() == Profession.CHILD ? children : adults).add(settler);
        }

        Map<NpcEntity, Profession> staying = new LinkedHashMap<>();
        for (Profession job : jobs) {
            if (staying.size() >= size) break;
            NpcEntity pick = null;
            for (NpcEntity adult : adults) {
                if (!staying.containsKey(adult) && suits(adult, job)) {
                    pick = adult;
                    break;
                }
            }
            if (pick == null) {
                for (NpcEntity adult : adults) {
                    if (!staying.containsKey(adult)) {
                        pick = adult;
                        break;
                    }
                }
            }
            if (pick == null) break;
            staying.put(pick, job);
        }
        for (NpcEntity adult : adults) {
            if (staying.size() >= size) break;
            staying.putIfAbsent(adult, Profession.NONE);
        }
        int jobsLeftOpen = 0;
        for (Profession job : jobs) {
            if (!staying.containsValue(job)) jobsLeftOpen++;
        }
        for (NpcEntity child : children) {
            if (staying.size() + jobsLeftOpen >= size) break;
            staying.put(child, Profession.CHILD);
        }
        return staying;
    }

    /** The old vanilla trade nearest to one of the village's jobs. */
    private static boolean suits(NpcEntity settler, Profession job) {
        String trade = settler.getPersistentData().getString(VANILLA_JOB_KEY);
        return switch (job) {
            case FARMER -> trade.equals("minecraft:farmer");
            case LUMBERJACK -> trade.equals("minecraft:fletcher");
            case GUARD -> trade.equals("minecraft:armorer") || trade.equals("minecraft:weaponsmith");
            case RANCHER -> trade.equals("minecraft:shepherd") || trade.equals("minecraft:butcher")
                    || trade.equals("minecraft:leatherworker");
            default -> false;
        };
    }

    /**
     * A waiting settler stops waiting and becomes a resident with {@code job}.
     *
     * @param setup the village's character, for a start that fits it; null for
     *              a settler arriving late, which just joins
     */
    private static boolean welcome(ServerLevel level, VillageManager village, NpcEntity npc, Profession job,
                                   @Nullable Setup setup, boolean veteran) {
        npc.removeTag(NpcEntity.WAITING_SETTLER_TAG);
        npc.getPersistentData().remove(VANILLA_JOB_KEY);
        npc.getPersistentData().remove(SETTLER_VILLAGE_KEY);
        try {
            npc.setVillageLink(new VillagerVillageLink(
                    village.getVillageId(), level.dimension(), village.getBellPos()));
            // A baby settler was made a child when it was spawned.
            if (job != Profession.CHILD && !VillagerJobManager.applyFirstTime(npc, job)) {
                throw new IllegalStateException("could not start as " + job);
            }
            // Before the villager is registered: its brain reads its levels and needs once, then.
            if (setup != null) startOut(level, npc, job, setup, veteran);
            village.registerVillager(npc);
            if (village.getModel(npc.getNpcId()) == null) throw new IllegalStateException("no model");
        } catch (RuntimeException failure) {
            village.removeVillager(npc.getNpcId());
            DynamicVillages.LOGGER.error("[Takeover] Failed to take in a settler for village {}.",
                    village.getVillageId(), failure);
            return false;
        }
        if (job != Profession.NONE && job != Profession.CHILD) {
            VillagerJobManager.requestImmediateWorkStart(npc);
        }
        logJoined(level, village, npc, Tr.c("log.dynamicvillages.joined.settler",
                "Was already living here when the village was founded."));
        return true;
    }

    /**
     * Each villager starts a little differently. Their job level is raised by
     * the village's wealth (much more for a veteran); a grown-up may carry a
     * little of the village's food; and hunger and mood start anywhere in a
     * comfortable range, so the whole village does not go to eat at once.
     * Done before the villager's brain first reads them.
     */
    private static void startOut(ServerLevel level, NpcEntity npc, Profession job, Setup setup, boolean veteran) {
        if (job != Profession.NONE && job != Profession.CHILD) {
            int bonus = VillageCharacter.levelBonus(setup.wealth(), level.random)
                    + (veteran ? VillageCharacter.veteranBonus(level.random) : 0);
            if (bonus > 0) {
                int current = ProfessionSkills.MIN_LEVEL;
                for (ProfessionSkill slot : npc.getProfessionSkills()) {
                    if (slot.profession() == job) current = slot.level();
                }
                npc.setProfessionSkillLevel(job,
                        Math.min(ProfessionSkills.MAX_INITIAL_LEVEL, current + bonus));
            }
        }
        if (job != Profession.CHILD
                && level.random.nextFloat() < VillageCharacter.personalFoodChance(setup.wealth())) {
            // The village's own food: bound, dishes Poor Man's (VillageFood).
            net.minecraft.world.item.ItemStack food = VillageCharacter.pocketFood(setup.kind(), level.random);
            com.alex.test.village.tokens.VillageFood.markVillagerMade(food);
            food = com.alex.test.village.tokens.VillageItemOwnership.issue(food, npc);
            if (!food.isEmpty()) npc.getInventory().addItem(food);
        }
        CompoundTag brain = npc.getBrainData();
        if (!brain.contains(HungerSystem.TAG_HUNGER)) {
            brain.putFloat(HungerSystem.TAG_HUNGER, 70f + level.random.nextFloat() * 30f);
        }
        if (!brain.contains(WellbeingSystem.TAG_WELLBEING)) {
            brain.putFloat(WellbeingSystem.TAG_WELLBEING, 65f + level.random.nextFloat() * 20f);
        }
    }

    /** The first of the village's jobs nobody living there has yet; no job when all are filled. */
    private static Profession missingJob(VillageManager village, List<Profession> jobs) {
        EnumSet<Profession> present = EnumSet.noneOf(Profession.class);
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc != null) present.add(npc.getProfession());
        }
        for (Profession job : jobs) {
            if (!present.contains(job)) return job;
        }
        return Profession.NONE;
    }

    private static void moveAway(ServerLevel level, NpcEntity settler) {
        level.sendParticles(ParticleTypes.POOF,
                settler.getX(), settler.getY() + 0.8, settler.getZ(), 10, 0.3, 0.5, 0.3, 0.02);
        settler.discard();
    }
}
