package com.alex.test.village.visitors;

import com.alex.test.DynamicVillages;
import com.alex.test.Tr;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.visitor.NomadEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerVillageLink;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.HouseBuilding;
import com.alex.test.village.building.MageTowerBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.chat.VillageDamageAlerts;
import com.alex.test.village.log.VillageLog;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.registry.VillagerJobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.List;

/** The single transactional nomad-to-resident conversion path. */
public final class NomadRecruitment {
    private NomadRecruitment() {}

    public static InteractionResult handleProfessionTokenUse(
            NomadEntity nomad, Player player, InteractionHand hand) {
        Profession profession = VillagerJobManager.professionForToken(player.getItemInHand(hand));
        if (profession == null) return InteractionResult.PASS;
        if (!(nomad.level() instanceof ServerLevel level)) return InteractionResult.SUCCESS;

        VillageManager village = resolveVillage(nomad, level);
        if (village == null) {
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.nomad.no_village",
                    "This nomad is not inside an active village."));
            return InteractionResult.SUCCESS;
        }
        if (village.nomads().isOffended(nomad.getUUID())) {
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.nomad.offended",
                    "This nomad is upset with the village and won't join."));
            return InteractionResult.SUCCESS;
        }
        Component refusal = admissionFailure(village, level);
        if (refusal != null) {
            player.sendSystemMessage(refusal);
            return InteractionResult.SUCCESS;
        }

        NpcEntity recruited = convert(nomad, level, village, profession);
        if (recruited == null) {
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.nomad.failed",
                    "Recruitment failed; the nomad and profession token were preserved."));
            return InteractionResult.SUCCESS;
        }
        player.getItemInHand(hand).shrink(1);
        VillagerJobManager.requestImmediateWorkStart(recruited);
        player.sendSystemMessage(Tr.c("chat.dynamicvillages.nomad.joined",
                "The nomad joined the village as its new %s.",
                Component.translatableWithFallback(profession.translationKey(), profession.displayName())));
        return InteractionResult.SUCCESS;
    }

    /** Converts only after admission was checked; removes the nomad last. */
    @Nullable
    private static NpcEntity convert(NomadEntity nomad, ServerLevel level,
                                     VillageManager village, Profession profession) {
        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        if (npc == null) return null;
        npc.moveTo(nomad.getX(), nomad.getY(), nomad.getZ(), nomad.getYRot(), nomad.getXRot());
        npc.setYHeadRot(nomad.getYHeadRot());
        // A nomad a player named with a name tag keeps that name if no villager has it.
        npc.suggestName(nomad.getCustomName());
        npc.setVillageLink(new VillagerVillageLink(
                village.getVillageId(), level.dimension(), village.getBellPos()));
        npc.inheritNomadProfessionSkills(nomad.getProfessionSkills());
        npc.inheritNomadIntelligence(nomad.storedIntelligence());
        npc.inheritNomadPersonality(nomad);

        if (!VillagerJobManager.applyFirstTime(npc, profession)) return null;
        if (!level.addFreshEntity(npc)) return null;

        try {
            village.registerVillager(npc);
            if (village.getModel(npc.getNpcId()) == null) throw new IllegalStateException("no model");
        } catch (RuntimeException failure) {
            village.removeVillager(npc.getNpcId());
            npc.discard();
            DynamicVillages.LOGGER.error("Failed to finish nomad recruitment for village {}.",
                    village.getVillageId(), failure);
            return null;
        }

        // Only this fully-committed conversion path counts as integration.
        // The persistent group remains active through every earlier failure.
        nomad.confirmExternalVisitEnd(level, "integrated into village");
        nomad.discard();
        DynamicVillages.LOGGER.info("[VillageManager {}] Recruited nomad {} as {} NPC {}.",
                village.getVillageId().toString().substring(0, 8), nomad.getUUID(),
                profession, npc.getNpcId());
        // Names it now (the setup its first tick would do), so the log line has its name.
        npc.ensureProfessionSkillsInitialized();
        VillageLog.record(village, level, VillageLog.Kind.JOINED,
                Tr.c("log.dynamicvillages.joined", "%s joined the village", VillageDamageAlerts.jobAndName(npc)),
                Tr.c("log.dynamicvillages.joined.nomad", "A nomad who was given a job token."),
                npc.blockPosition());
        return npc;
    }

    @Nullable
    private static VillageManager resolveVillage(NomadEntity nomad, ServerLevel level) {
        VillageManager village = VillageManagerRegistry.get(level.getServer())
                .findVillageAt(level, nomad.blockPosition());
        if (village == null && VillageManager.INSTANCE.getLevel() == level
                && VillageManager.INSTANCE.contains(nomad.blockPosition())) {
            village = VillageManager.INSTANCE;
        }
        return village;
    }

    /** Villages under this population use the small homeless allowance. */
    private static final int SMALL_VILLAGE_POPULATION_CEILING = 10;
    /** Max residents without an assigned bed in a village below the ceiling. */
    private static final int HOMELESS_CAP_SMALL_VILLAGE = 2;
    /** Max residents without an assigned bed in a village at/above the ceiling. */
    private static final int HOMELESS_CAP_LARGE_VILLAGE = 4;

    /** Specific player-facing reason an explicit recruitment cannot succeed. */
    @Nullable
    private static Component admissionFailure(VillageManager village, ServerLevel level) {
        boolean hasFood = false;
        int totalBeds = 0;
        for (Building building : village.getBuildings()) {
            if (!building.isValid()) continue;
            if (building instanceof StorageBuilding storage
                    && storage.hasResource(level, StorageBuilding.ResourceType.FOOD)) {
                hasFood = true;
            }
            if (building instanceof HouseBuilding house) totalBeds += house.getBeds().size();
            // Defenders in the barracks, mages in the tower and the Mayor in the hall have a
            // home. Only claimed beds count: a recruit can never take a free one of those.
            List<BlockPos> jobBeds = building instanceof BarracksBuilding barracks ? barracks.getBeds()
                    : building instanceof MageTowerBuilding tower ? tower.getBeds()
                    : building instanceof TownHallBuilding hall ? hall.getBeds() : null;
            if (jobBeds != null) {
                totalBeds += Math.min(jobBeds.size(),
                        village.buildingBedClaims(building.getItemFrameId()).size());
            }
        }
        if (!hasFood) return Tr.c("chat.dynamicvillages.nomad.no_food",
                "Nomads only join once the village has a valid Storage room with food "
                + "in its chests. Food marked for another village does not count.");

        // Beds are no longer required to recruit: a nomad may join without one and
        // simply go without a bed, same as any other homeless resident. Instead we
        // cap how many bedless residents a village can carry at once.
        int population = village.getVillagers().size();
        int homelessAfterRecruit = Math.max(0, population + 1 - totalBeds);
        int homelessCap = population < SMALL_VILLAGE_POPULATION_CEILING
                ? HOMELESS_CAP_SMALL_VILLAGE : HOMELESS_CAP_LARGE_VILLAGE;
        if (homelessAfterRecruit > homelessCap) {
            return Tr.c("chat.dynamicvillages.nomad.too_many_homeless",
                    "This village already has too many homeless villagers (limit %s). "
                    + "Build more beds before recruiting another nomad.", homelessCap);
        }
        return null;
    }
}
