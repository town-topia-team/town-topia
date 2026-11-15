package com.alex.test.village.villagers.registry;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.blacksmith.BlacksmithProfession;
import com.alex.test.village.villagers.butcher.ButcherProfession;
import com.alex.test.village.villagers.cook.CookProfession;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.farmer.FarmerProfession;
import com.alex.test.village.villagers.guard.GuardProfession;
import com.alex.test.village.villagers.farmer.FarmerProfession;
import com.alex.test.village.villagers.lumberjack.LumberjackProfession;
import com.alex.test.village.villagers.miner.MinerProfession;
import com.alex.test.village.villagers.builder.BuilderProfession;
import com.alex.test.village.villagers.bard.BardProfession;
import com.alex.test.village.villagers.cleric.ClericProfession;
import com.alex.test.village.villagers.druid.DruidProfession;
import com.alex.test.village.villagers.enchanter.EnchanterProfession;
import com.alex.test.village.villagers.enchanter.BookFitting;
import com.alex.test.village.villagers.teacher.TeacherProfession;
import com.alex.test.village.villagers.tradesman.TradesmanProfession;
import com.alex.test.village.villagers.archer.ArcherProfession;
import com.alex.test.village.villagers.child.ChildProfession;
import com.alex.test.village.villagers.rancher.RancherProfession;
import com.alex.test.village.villagers.tools.StrandedToolDeposit;

/** Central dispatch point for profession-specific villager behavior. */
public final class VillagerRegistry {

    private VillagerRegistry() {}

    public static WorkActivity createWorkActivity(Profession profession) {
        return switch (profession) {
            case LUMBERJACK -> LumberjackProfession.createWorkActivity();
            case GUARD, CAPTAIN -> GuardProfession.createWorkActivity();
            case FARMER -> FarmerProfession.createWorkActivity();
            case RANCHER -> RancherProfession.createWorkActivity();
            case BUTCHER -> ButcherProfession.createWorkActivity();
            case COOK -> CookProfession.createWorkActivity();
            case BLACKSMITH -> BlacksmithProfession.createWorkActivity();
            case MINER -> MinerProfession.createWorkActivity();
            case BUILDER -> BuilderProfession.createWorkActivity();
            case BARD -> BardProfession.createWorkActivity();
            case CLERIC -> ClericProfession.createWorkActivity();
            case DRUID -> DruidProfession.createWorkActivity();
            case ENCHANTER -> EnchanterProfession.createWorkActivity();
            case TEACHER -> TeacherProfession.createWorkActivity();
            case TRADESMAN -> TradesmanProfession.createWorkActivity();
            case ARCHER -> ArcherProfession.createWorkActivity();
            case MAGE -> com.alex.test.village.villagers.mage.MageProfession.createWorkActivity();
            case CHILD -> ChildProfession.createWorkActivity();
            case NONE -> null;
        };
    }

    /**
     * The routine work session for this villager right now: its profession's
     * own work, or -- when a tool it needs is gone for good -- a trip to bank
     * its pack (see {@link StrandedToolDeposit}).
     */
    public static WorkActivity createWorkActivity(Profession profession, Activity.Context ctx) {
        if (StrandedToolDeposit.shouldBank(ctx, profession)) {
            return new StrandedToolDeposit.BankActivity();
        }
        // A useful enchanted book in storage: take it to the smith first.
        if (BookFitting.shouldVisit(ctx, profession)) return new BookFitting.Visit();
        if (profession == Profession.FARMER) return FarmerProfession.createWorkActivity(ctx);
        return createWorkActivity(profession);
    }

    public static boolean hasImmediateWork(Profession profession, Activity.Context ctx) {
        if (StrandedToolDeposit.shouldBank(ctx, profession)) return true;
        // No book errand here: one replaces a work session only when the job itself
        // has work (createWorkActivity), so a job switched off never goes.
        return switch (profession) {
            case LUMBERJACK -> LumberjackProfession.hasImmediateWork(ctx);
            case GUARD, CAPTAIN -> GuardProfession.hasImmediateWork(ctx);
            case FARMER -> FarmerProfession.hasImmediateWork(ctx);
            case RANCHER -> RancherProfession.hasImmediateWork(ctx);
            case BUTCHER -> ButcherProfession.hasImmediateWork(ctx);
            case COOK -> CookProfession.hasImmediateWork(ctx);
            case BLACKSMITH -> BlacksmithProfession.hasImmediateWork(ctx);
            case MINER -> MinerProfession.hasImmediateWork(ctx);
            case BUILDER -> BuilderProfession.hasImmediateWork(ctx);
            case BARD -> BardProfession.hasImmediateWork(ctx);
            case CLERIC -> ClericProfession.hasImmediateWork(ctx);
            case DRUID -> DruidProfession.hasImmediateWork(ctx);
            case ENCHANTER -> EnchanterProfession.hasImmediateWork(ctx);
            case TEACHER -> TeacherProfession.hasImmediateWork(ctx);
            case TRADESMAN -> TradesmanProfession.hasImmediateWork(ctx);
            case ARCHER -> ArcherProfession.hasImmediateWork(ctx);
            case MAGE -> com.alex.test.village.villagers.mage.MageProfession.hasImmediateWork(ctx);
            case CHILD -> ChildProfession.hasImmediateWork(ctx);
            case NONE -> false;
        };
    }

    /** Whether this profession should be considered for work at the current time. */
    /**
     * Work that justifies interrupting leisure, checked by the brain on a slow
     * cadence while a villager is idling. Almost nothing qualifies: a tree can
     * wait for the stroll to finish, a bleeding villager cannot. Implementations
     * must stay as cheap as {@code hasImmediateWork}.
     */
    public static boolean hasUrgentWork(Profession profession, Activity.Context ctx) {
        return switch (profession) {
            case CLERIC -> ClericProfession.hasUrgentWork(ctx);
            // A worker stands beside it with a book: the smith (or enchanter) leaves its stroll.
            case BLACKSMITH, ENCHANTER -> BookFitting.hasCustomer(ctx);
            // The school bell: the teacher goes to ring it, the children come when it rings.
            case TEACHER -> com.alex.test.village.villagers.teacher.behavior.TeacherActivity.hasUrgentWork(ctx);
            case CHILD -> com.alex.test.village.villagers.child.behavior.AttendSchoolActivity.hasUrgentWork(ctx);
            default -> false;
        };
    }

    public static boolean shouldWorkNow(Profession profession, Activity.Context ctx) {
        return switch (profession) {
            case LUMBERJACK, FARMER, RANCHER, COOK, BLACKSMITH, BUTCHER, MINER,
                 BUILDER, BARD, DRUID, ENCHANTER, TEACHER, TRADESMAN,
                 CHILD ->
                    // Civilian work hours end at each villager's own end-of-work
                    // time (see EveningSchedule), before the world's night.
                    VillagerBrain.isWorkHours(ctx);
            case GUARD, CAPTAIN, ARCHER, MAGE -> GuardProfession.shouldWorkNow(ctx);
            // Night Shift on: the guards' night shift instead of the day.
            case CLERIC -> ClericProfession.shouldWorkNow(ctx);
            case NONE -> false;
        };
    }
}
