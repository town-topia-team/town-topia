package com.alex.test.village.villagers.registry;

import com.alex.test.DynamicVillages;
import com.alex.test.Tr;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.VillagerVillageLink;
import com.alex.test.village.log.VillageLog;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.villagers.blacksmith.BlacksmithAssignmentHandler;
import com.alex.test.village.villagers.butcher.ButcherAssignmentHandler;
import com.alex.test.village.villagers.cook.CookAssignmentHandler;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkill;
import com.alex.test.village.villagers.core.ProfessionSkills;
import com.alex.test.village.villagers.farmer.FarmerAssignmentHandler;
import com.alex.test.village.villagers.guard.CaptainAssignmentHandler;
import com.alex.test.village.villagers.guard.GuardAssignmentHandler;
import com.alex.test.village.villagers.lumberjack.LumberjackAssignmentHandler;
import com.alex.test.village.villagers.miner.MinerAssignmentHandler;
import com.alex.test.village.villagers.rancher.RancherAssignmentHandler;
import com.alex.test.village.villagers.builder.BuilderAssignmentHandler;
import com.alex.test.village.villagers.bard.BardAssignmentHandler;
import com.alex.test.village.villagers.cleric.ClericAssignmentHandler;
import com.alex.test.village.villagers.druid.DruidAssignmentHandler;
import com.alex.test.village.villagers.enchanter.EnchanterAssignmentHandler;
import com.alex.test.village.villagers.teacher.TeacherAssignmentHandler;
import com.alex.test.village.villagers.tradesman.TradesmanAssignmentHandler;
import com.alex.test.village.villagers.archer.ArcherAssignmentHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/** Coordinates profession assignment without coupling item logic to NpcEntity. */
public final class VillagerJobManager {

    private VillagerJobManager() {}

    /**
     * Eligible skills come from registered profession tokens. The naming convention is
     * the source of truth, so adding a profession and its token automatically includes it.
     */
    public static List<Profession> eligibleProfessions() {
        return Arrays.stream(Profession.values())
                .filter(p -> p != Profession.NONE && p != Profession.CHILD)
                .filter(p -> tokenItem(p) != Items.AIR)
                .toList();
    }

    public static Item tokenItem(Profession profession) {
        if (profession == null || profession == Profession.NONE || profession == Profession.CHILD) {
            return Items.AIR;
        }
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, profession.name().toLowerCase(java.util.Locale.ROOT) + "_token");
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == null ? Items.AIR : item;
    }

    /** Returns the profession represented by a registered token, or {@code null} for a non-token. */
    @Nullable
    public static Profession professionForToken(ItemStack stack) {
        if (stack.isEmpty()) return null;
        for (Profession profession : eligibleProfessions()) {
            if (stack.is(tokenItem(profession))) return profession;
        }
        return null;
    }

    /** Runs the production first-time assignment path without consuming an item. */
    public static boolean applyFirstTime(NpcEntity villager, Profession profession) {
        return switch (profession) {
            case LUMBERJACK -> LumberjackAssignmentHandler.assignFirstTime(villager);
            case GUARD -> GuardAssignmentHandler.assignFirstTime(villager);
            case FARMER -> FarmerAssignmentHandler.assignFirstTime(villager);
            case RANCHER -> RancherAssignmentHandler.assignFirstTime(villager);
            case COOK -> CookAssignmentHandler.assignFirstTime(villager);
            case BLACKSMITH -> BlacksmithAssignmentHandler.assignFirstTime(villager);
            case BUTCHER -> ButcherAssignmentHandler.assignFirstTime(villager);
            case MINER -> MinerAssignmentHandler.assignFirstTime(villager);
            case BUILDER -> BuilderAssignmentHandler.assignFirstTime(villager);
            case BARD -> BardAssignmentHandler.assignFirstTime(villager);
            case CLERIC -> ClericAssignmentHandler.assignFirstTime(villager);
            case DRUID -> DruidAssignmentHandler.assignFirstTime(villager);
            case ENCHANTER -> EnchanterAssignmentHandler.assignFirstTime(villager);
            case TEACHER -> TeacherAssignmentHandler.assignFirstTime(villager);
            case ARCHER -> ArcherAssignmentHandler.assignFirstTime(villager);
            case TRADESMAN -> TradesmanAssignmentHandler.assignFirstTime(villager);
            case NONE -> true;
            case CHILD -> false;
            case CAPTAIN -> CaptainAssignmentHandler.assignFirstTime(villager);
            case MAGE -> com.alex.test.village.villagers.mage.MageAssignmentHandler.assignFirstTime(villager);
        };
    }

    public static InteractionResult handleProfessionTokenUse(
            NpcEntity villager, Player player, InteractionHand hand) {
        if (LumberjackAssignmentHandler.isLumberjackToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.LUMBERJACK,
                    LumberjackAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.no_space_axe",
                    "This villager has no inventory space for a starter axe."));
        }
        if (CaptainAssignmentHandler.isCaptainToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.CAPTAIN,
                    CaptainAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.no_space_wooden_sword",
                    "This villager has no inventory space for a starter wooden sword."));
        }
        if (GuardAssignmentHandler.isGuardToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.GUARD,
                    GuardAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.no_space_wooden_sword",
                    "This villager has no inventory space for a starter wooden sword."));
        }
        if (FarmerAssignmentHandler.isFarmerToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.FARMER,
                    FarmerAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.no_space_hoe",
                    "This villager has no inventory space for a starter hoe."));
        }
        if (RancherAssignmentHandler.isRancherToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.RANCHER,
                    RancherAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.no_space_shears",
                    "This villager has no inventory space for starter shears."));
        }
        if (CookAssignmentHandler.isCookToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.COOK,
                    CookAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.could_not_become_cook",
                    "This villager could not become a cook."));
        }
        if (BlacksmithAssignmentHandler.isBlacksmithToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.BLACKSMITH,
                    BlacksmithAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.could_not_become_blacksmith",
                    "This villager could not become a blacksmith."));
        }
        if (ButcherAssignmentHandler.isButcherToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.BUTCHER,
                    ButcherAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.no_space_wooden_sword",
                    "This villager has no inventory space for a starter wooden sword."));
        }
        if (MinerAssignmentHandler.isMinerToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.MINER,
                    MinerAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.no_space_wooden_pickaxe",
                    "This villager has no inventory space for a starter wooden pickaxe."));
        }
        if (BuilderAssignmentHandler.isBuilderToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.BUILDER,
                    BuilderAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.could_not_become_builder",
                    "This villager could not become a builder."));
        }
        if (BardAssignmentHandler.isBardToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.BARD,
                    BardAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.could_not_become_bard",
                    "This villager could not become a bard."));
        }
        if (ClericAssignmentHandler.isClericToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.CLERIC,
                    ClericAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.could_not_become_cleric",
                    "This villager could not become a cleric."));
        }
        if (DruidAssignmentHandler.isDruidToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.DRUID,
                    DruidAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.could_not_become_druid",
                    "This villager could not become a druid."));
        }
        if (EnchanterAssignmentHandler.isEnchanterToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.ENCHANTER,
                    EnchanterAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.could_not_become_enchanter",
                    "This villager could not become an enchanter."));
        }
        if (TeacherAssignmentHandler.isTeacherToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.TEACHER,
                    TeacherAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.could_not_become_teacher",
                    "This villager could not become a teacher."));
        }
        if (ArcherAssignmentHandler.isArcherToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.ARCHER,
                    ArcherAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.no_space_bow",
                    "This villager has no inventory space for a starter bow."));
        }
        if (com.alex.test.village.villagers.mage.MageAssignmentHandler.isMageToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.MAGE,
                    com.alex.test.village.villagers.mage.MageAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.could_not_become_mage",
                    "This villager could not become a mage."));
        }
        if (TradesmanAssignmentHandler.isTradesmanToken(player.getItemInHand(hand))) {
            return assignProfession(villager, player, hand, Profession.TRADESMAN,
                    TradesmanAssignmentHandler::assignFirstTime,
                    Tr.c("chat.dynamicvillages.job.could_not_become_tradesman",
                    "This villager could not become a tradesman."));
        }
        return InteractionResult.PASS;
    }

    private static InteractionResult assignProfession(
            NpcEntity villager,
            Player player,
            InteractionHand hand,
            Profession targetProfession,
            java.util.function.Predicate<NpcEntity> assignment,
            Component noInventorySpaceMessage) {
        if (!(villager.level() instanceof ServerLevel level)) return InteractionResult.CONSUME;
        // A child only becomes an adult by growing up; a token used to switch it
        // straight to the job, skipping everything growing up gives.
        if (villager.getProfession() == Profession.CHILD) {
            String name = villager.givenName() != null ? villager.givenName() : "This villager";
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.job.child",
                    "%s is still a child and can't take a job until it grows up.", name));
            return InteractionResult.SUCCESS;
        }
        VillageManager village = resolveManager(villager, level);
        // Village claims round 7c: giving a resident a job takes Friend or better there (Visitors only look).
        if (village != null && player instanceof ServerPlayer claimant
                && !com.alex.test.village.claims.ClaimLock.mayIn(claimant, village,
                        com.alex.test.village.claims.ClaimLock.Act.USE)) {
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.claim.job_locked",
                    "[Village] Only people %s trusts may give jobs here.", village.claim().ownerName())
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.SUCCESS;
        }
        ItemStack token = player.getItemInHand(hand);
        if (village != null && VillageTokenBinding.belongsToAnotherVillage(token, village)) {
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.job.village_this_token_belongs_to_another", "[Village] This token belongs to another village.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.SUCCESS;
        }
        if (village == null || village.getLevel() != level
                || !village.contains(villager.blockPosition())
                || VillageManagerRegistry.get(level.getServer()).isVillageInactive(village.getVillageId())
                || !VillageTokenBinding.matches(token, village)) {
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.job.wrong_village",
                    "Use this profession token on a resident inside its own Town Hall's village."));
            return InteractionResult.SUCCESS;
        }
        if (villager.getProfession() == targetProfession) {
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.job.already",
                    "This villager's job is already %s.", jobName(targetProfession)));
            return InteractionResult.SUCCESS;
        }
        if (targetProfession == Profession.CAPTAIN
                && !CaptainAssignmentHandler.hasOpenBarracks(village, villager)) {
            player.sendSystemMessage(noCaptaincyMessage(village));
            return InteractionResult.SUCCESS;
        }

        if (targetProfession == Profession.MAGE
                && !com.alex.test.village.villagers.mage.MageAssignmentHandler.hasHiringPlace(village)) {
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.job.mage_needs_tower",
                    "A mage is hired at a Mage Tower or a Barracks. Build one in this village first."));
            return InteractionResult.SUCCESS;
        }

        List<ProfessionSkill> skills = villager.getProfessionSkills();
        if (ProfessionSkills.forSwitch(skills, targetProfession, -1) == null) {
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.CONSUME;
            villager.beginProfessionChange(serverPlayer, hand, targetProfession);
            villager.markUiOpened(serverPlayer);
            VillagerModel model = village.getModel(villager.getNpcId());
            if (model != null) model.pauseMovingForUi();
            if (serverPlayer.openMenu(villager, buffer ->
                    com.alex.test.ui.VillagerMenu.writeOpeningData(buffer, villager, serverPlayer))
                    .isEmpty()) {
                villager.cancelProfessionChange(serverPlayer);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.SUCCESS;
        }

        Profession before = villager.getProfession();
        if (!commitSwitch(villager, targetProfession, -1, assignment)) {
            player.sendSystemMessage(noInventorySpaceMessage);
            return InteractionResult.SUCCESS;
        }
        logJobChange(village, level, villager, before, targetProfession);

        VillageTokenBinding.bind(token, village);
        token.shrink(1);
        requestImmediateWorkStart(villager);
        ProfessionSkill saved = skills.stream()
                .filter(skill -> skill.profession() == targetProfession)
                .findFirst().orElse(null);
        Component result = saved != null
                ? Tr.c("chat.dynamicvillages.job.switched_saved",
                        "Switched to %1$s in saved slot %2$s of 4 at level %3$s. No slot was replaced.",
                        jobName(targetProfession), skills.indexOf(saved) + 1, saved.level())
                : Tr.c("chat.dynamicvillages.job.switched_empty",
                        "Switched to %1$s at level 1 in empty slot %2$s of 4.",
                        jobName(targetProfession), skills.size() + 1);
        if (targetProfession == Profession.CAPTAIN && saved == null) {
            int promoted = captainLevel(villager);
            if (promoted > 1) {
                result = Tr.c("chat.dynamicvillages.job.promoted_captain",
                        "Promoted to Captain at level %1$s, keeping its guard experience, in empty slot %2$s of 4.",
                        promoted, skills.size() + 1);
            }
        }
        player.sendSystemMessage(result);
        return InteractionResult.SUCCESS;
    }

    private static int captainLevel(NpcEntity villager) {
        for (ProfessionSkill skill : villager.getProfessionSkills()) {
            if (skill.profession() == Profession.CAPTAIN) return skill.level();
        }
        return 1;
    }

    /** Invoked only from the open replacement menu, with the selected slot checked server-side. */
    public static void selectProfessionReplacement(
            NpcEntity villager, ServerPlayer player, int slot) {
        NpcEntity.PendingProfessionChange pending = villager.pendingProfessionChange(player);
        if (pending == null || slot < 0 || slot >= ProfessionSkills.MAX_SKILLS
                || !(player.containerMenu instanceof com.alex.test.ui.VillagerMenu menu)
                || !menu.isForNpc(villager) || !menu.isProfessionReplacement()
                || !menu.stillValid(player)) return;
        List<ProfessionSkill> skills = villager.getProfessionSkills();
        if (skills.size() != ProfessionSkills.MAX_SKILLS
                || skills.stream().anyMatch(skill -> skill.profession() == pending.target())) return;
        ProfessionSkill replaced = skills.get(slot);
        ServerLevel level = player.serverLevel();
        VillageManager village = resolveManager(villager, level);
        ItemStack token = player.getItemInHand(pending.hand());
        if (village == null || village.getLevel() != level
                || !village.contains(villager.blockPosition())
                || VillageManagerRegistry.get(level.getServer()).isVillageInactive(village.getVillageId())
                || !VillageTokenBinding.matches(token, village)
                || !token.is(tokenItem(pending.target()))
                || !com.alex.test.village.claims.ClaimLock.mayIn(player, village,
                        com.alex.test.village.claims.ClaimLock.Act.USE)) return;
        if (pending.target() == Profession.CAPTAIN
                && !CaptainAssignmentHandler.hasOpenBarracks(village, villager)) {
            player.sendSystemMessage(noCaptaincyMessage(village));
            return;
        }
        Profession before = villager.getProfession();
        if (!commitSwitch(villager, pending.target(), slot,
                npc -> applyFirstTime(npc, pending.target()))) {
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.job.switch_failed",
                    "This villager could not switch professions right now."));
            return;
        }
        logJobChange(village, level, villager, before, pending.target());
        villager.getBrainData().getCompound("Skills").remove(replaced.profession().name());
        VillageManager owner = resolveManager(villager, level);
        VillagerModel ownerModel = owner == null ? null : owner.getModel(villager.getNpcId());
        if (ownerModel != null) {
            ownerModel.brain().forgetProfessionExperience(replaced.profession(), villager);
        }
        VillageTokenBinding.bind(token, village);
        token.shrink(1);
        villager.cancelProfessionChange(player);
        player.closeContainer();
        requestImmediateWorkStart(villager);
        player.sendSystemMessage(Tr.c("chat.dynamicvillages.job.replaced",
                "Replaced %1$s (level %2$s) with %3$s (level %4$s) in slot %5$s of 4.",
                jobName(replaced.profession()), replaced.level(), jobName(pending.target()),
                pending.target() == Profession.CAPTAIN ? captainLevel(villager) : 1, slot + 1));
    }

    private static boolean commitSwitch(NpcEntity villager, Profession target, int replacementSlot,
                                        java.util.function.Predicate<NpcEntity> firstAssignment) {
        if (villager.getProfession() == Profession.CHILD) return false;
        List<ProfessionSkill> before = villager.getProfessionSkills();
        List<ProfessionSkill> after = ProfessionSkills.forSwitch(before, target, replacementSlot);
        if (after == null) return false;
        if (villager.getProfession() == Profession.NONE) {
            ItemStack[] slots = villager.getInventory().getSlots();
            ItemStack[] saved = Arrays.stream(slots).map(ItemStack::copy).toArray(ItemStack[]::new);
            if (!firstAssignment.test(villager)) {
                for (int i = 0; i < slots.length; i++) villager.getInventory().setSlot(i, saved[i]);
                villager.restoreProfessionSkills(before);
                return false;
            }
            villager.restoreProfessionSkills(after);
            stopOldProfessionWork(villager);
        } else {
            stopOldProfessionWork(villager);
            villager.restoreProfessionSkills(after);
            villager.setProfession(target);
        }
        if (target == Profession.CAPTAIN) CaptainAssignmentHandler.carryOverGuardLevel(villager, before);
        // A guard's armour comes off with the job (guard <-> captain keeps it).
        villager.stowArmorUnlessGuard();
        return true;
    }

    /** "Tom took a new job: Miner" in the village log, with the job it had before. */
    private static void logJobChange(VillageManager village, ServerLevel level, NpcEntity villager, Profession before,
                                     Profession now) {
        VillageLog.record(village, level, VillageLog.Kind.JOB,
                Tr.c("log.dynamicvillages.job", "%s took a new job: %s", villager.getDisplayName(), jobName(now)),
                before == Profession.NONE
                        ? Tr.c("log.dynamicvillages.job.none_before", "Had no job before.")
                        : Tr.c("log.dynamicvillages.job.before", "Job before: %s.", jobName(before)),
                villager.blockPosition());
    }

    private static Component noCaptaincyMessage(VillageManager village) {
        int barracks = CaptainAssignmentHandler.barracksCount(village);
        return barracks == 0
                ? Tr.c("chat.dynamicvillages.job.captain_needs_barracks",
                        "A captain needs a Barracks to lead. Build one first.")
                : Tr.c("chat.dynamicvillages.job.barracks_full",
                        "Every Barracks already has its captain (one per Barracks).");
    }

    /** The job's name in each player's own language. */
    private static Component jobName(Profession profession) {
        return Component.translatableWithFallback(profession.translationKey(), profession.displayName());
    }

    private static void stopOldProfessionWork(NpcEntity villager) {
        if (villager.level() instanceof ServerLevel level) {
            VillageManager manager = resolveManager(villager, level);
            VillagerModel model = manager == null ? null : manager.getModel(villager.getNpcId());
            if (model != null) model.brain().onProfessionChanged(manager, level, villager);
        }
    }

    public static boolean requestImmediateWorkStart(NpcEntity villager) {
        if (!(villager.level() instanceof ServerLevel level)) return false;
        VillageManager manager = resolveManager(villager, level);
        if (manager == null) return false;
        VillagerModel model = manager.getModel(villager.getNpcId());
        return model != null
                && model.brain().tryStartWorkImmediately(manager, level, villager);
    }

    @Nullable
    private static VillageManager resolveManager(NpcEntity villager, ServerLevel level) {
        VillagerVillageLink link = villager.getVillageLink();
        if (link != null && link.dimension().equals(level.dimension())) {
            VillageManager manager =
                    VillageManagerRegistry.get(level.getServer()).get(link.villageId());
            if (manager != null) return manager;
            if (VillageManager.INSTANCE.getVillageId().equals(link.villageId())) {
                return VillageManager.INSTANCE;
            }
        }
        return null;
    }
}
