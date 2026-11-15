package com.alex.test.village.villagers.tools;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;

/**
 * Shared answer to "my tool is gone and there is no way to get another".
 *
 * <p>A villager in that state cannot work, and without this it also never
 * reaches the deposit step at the end of a work session -- so whatever it was
 * carrying (a lumberjack's logs, a miner's ore, a farmer's crops) sits in its
 * pack indefinitely, often the very goods the village needs to make the
 * replacement tool. Instead, routine work for such a villager becomes one trip
 * to the storage room to bank its pack.
 *
 * <p>Applies to every profession through
 * {@link ProfessionSupplyRules#toolRequirements}; only requirements that are
 * {@linkplain ProfessionToolRequirement#requiredForWork() required for work}
 * count, so missing armor or optional shears never trigger it.
 */
public final class StrandedToolDeposit {

    private StrandedToolDeposit() {}

    /** True when this villager should bank its pack instead of working. */
    public static boolean shouldBank(Activity.Context ctx, Profession profession) {
        // Cheapest first: almost every call finds the tool in the pack.
        return isStranded(ctx, profession)
                && !isDefenderOnCall(ctx, profession)
                && DepositActivity.countDepositable(ctx) > 0;
    }

    /** True when a required tool is missing and cannot currently be acquired. */
    public static boolean isStranded(Activity.Context ctx, Profession profession) {
        for (ProfessionToolRequirement requirement : ProfessionSupplyRules.toolRequirements(profession)) {
            if (!requirement.requiredForWork(ctx)) continue;
            if (GetToolActivity.hasUsableTool(ctx, requirement)) continue;
            if (!GetToolActivity.canAcquire(ctx, requirement)) return true;
        }
        return false;
    }

    /** A guard or archer answering an alarm must not walk off to a chest. */
    private static boolean isDefenderOnCall(Activity.Context ctx, Profession profession) {
        if (!profession.isDefender()) return false;
        return GuardAlertSystem.hasEmergencyForGuard(ctx);
    }

    /** The work session of a stranded villager: bank the pack, then finish. */
    public static final class BankActivity extends WorkActivity {

        private DepositActivity deposit;

        @Override
        protected void start(Context ctx) {
            deposit = new DepositActivity();
            ctx.brain().diary().log(VillagerDiary.ts(ctx.level())
                    + " work  no usable tool can fit or be acquired"
                    + " — banking carried items");
        }

        @Override
        protected Status doTick(Context ctx) {
            if (deposit == null) return Status.FAILED;
            return deposit.tickOuter(ctx);
        }

        @Override
        protected void doStop(Context ctx) {
            if (deposit != null) {
                deposit.stop(ctx);
                deposit = null;
            }
        }
    }
}
