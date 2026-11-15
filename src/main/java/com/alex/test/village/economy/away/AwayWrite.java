package com.alex.test.village.economy.away;

import com.alex.test.village.VillageManager;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * WRITE, one tick at the end of an {@link AwayRun}: the real chests get the
 * book's differences, each villager what changed in its copy, the world the
 * changes that still hold; then the days past the counted ones are skipped
 * ({@link AwaySkip#skipUncounted}).
 *
 * <p>Each part goes in on its own (leftovers round 2): one that throws, a bug
 * of ours, is logged and noted, and the others still go in. The chests go
 * first, so a villager written after a broken chests part keeps what it took
 * (the chests still hold it too): a gain, never a loss.
 */
final class AwayWrite {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AwayWrite() {}

    /** A villager's write: nothing changed, written, or dropped (its body went). */
    private enum Wrote { SAME, WRITTEN, DROPPED }

    static void apply(ServerLevel level, VillageManager mgr, AwayRun run) {
        AwayBook.Written storage = part(run, "chests", () -> run.book().write());
        int changed = 0;
        int dropped = 0;
        int starved = 0;
        boolean night = run.livedANight();
        for (AwayVillager villager : run.villagers()) {
            Wrote wrote = part(run, "villager " + AwayRun.shortId(villager.id), () -> write(mgr, villager, night));
            if (wrote == Wrote.WRITTEN) {
                changed++;
                if (villager.starved()) starved++;
            } else if (wrote == Wrote.DROPPED) {
                dropped++;
            }
        }
        // The Stock tab orders cooked while away (round 4): their dishes are in the real chests now.
        part(run, "kitchen", () -> {
            run.kitchen().writeTo(level, mgr);
            LOGGER.info("[AwayRun] village {} kitchen: {}", AwayRun.shortId(run.villageId), run.kitchen().describe());
        });
        // The Stock tab orders forged (round 9) and the book orders written (round 10) while away:
        // in the smith's pack or the real chests now.
        part(run, "Stock tab orders", () -> {
            run.orders().writeTo(level, mgr);
            LOGGER.info("[AwayRun] village {} Stock tab orders: {}", AwayRun.shortId(run.villageId),
                    run.orders().describe());
        });
        // The fields' end state, tile by tile (round 3), after the villagers' own changes.
        part(run, "fields", () -> run.farm().writeTo(run.world()));
        // The pens' animals, babies born and grass eaten (round 5).
        part(run, "pens", () -> run.pens().writeTo(run.world()));
        // The felled trees: logs and leaves gone, saplings planted (round 7).
        part(run, "trees", () -> run.trees().writeTo(run.world()));
        // The mines dug on: walls and plugs, then the dug blocks, then the torches (round 8).
        part(run, "mines", () -> run.mines().writeTo(run.world()));
        // Each world change on its own inside (AwayWorld.apply).
        Integer applied = part(run, "world changes", () -> run.world().apply(level));
        if (run.world().broke() > 0) {
            run.broke = true;
            run.notes.add("Write-back: " + run.world().broke() + " world change(s) broke.");
        }
        part(run, "log", () -> {
            LOGGER.info("[AwayRun] village {} pens: {}", AwayRun.shortId(run.villageId), run.pens().describe());
            LOGGER.info("[AwayRun] village {} trees: {}", AwayRun.shortId(run.villageId), run.trees().describe());
            LOGGER.info("[AwayRun] village {} mines: {}", AwayRun.shortId(run.villageId), run.mines().describe());
        });
        part(run, "uncounted days skip", () -> AwaySkip.skipUncounted(level, mgr, run));
        LOGGER.info("[AwayRun] village {} WRITE: storage {}; "
                        + "{} villager(s) changed ({} starved to death), {} dropped; world {} of {} change(s); "
                        + "{} of {} tick(s) counted; {} serving(s) eaten, {} starving hit(s){}",
                AwayRun.shortId(run.villageId), storage == null ? "BROKE"
                        : storage.takenOut() + " item(s) out, " + storage.putIn() + " in, " + storage.missing()
                        + " missing, " + storage.lost() + " lost",
                changed, starved, dropped, applied == null ? "BROKE" : applied, run.world().size(),
                run.countedTicks(), run.awayTicks(), run.mealsEaten, run.starvationHits,
                run.broke ? "; something broke, see the errors above" : "");
    }

    /** One villager: its nights lived here, then what changed in its copy. */
    private static Wrote write(VillageManager mgr, AwayVillager villager, boolean night) {
        // Its nights and dawn heals were lived here: the first day change back gives
        // no second heal and, in its own bed, no sleepless memory (round 12).
        if (night && !villager.starved() && villager.bodyHere()) {
            villager.model().brain().livedNightsAway(mgr.getAssignedBed(villager.id) != null);
        }
        if (!villager.changed()) return Wrote.SAME;
        return villager.write() ? Wrote.WRITTEN : Wrote.DROPPED;
    }

    private static void part(AwayRun run, String name, Runnable body) {
        part(run, name, () -> {
            body.run();
            return Boolean.TRUE;
        });
    }

    /** One part on its own; null when it threw (logged, noted, {@link AwayRun#broke} set). */
    @Nullable
    private static <T> T part(AwayRun run, String name, Supplier<T> body) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            run.broke = true;
            LOGGER.error("[AwayWrite] village {}: the write-back's {} broke; the other parts still go in",
                    AwayRun.shortId(run.villageId), name, e);
            run.notes.add("Write-back: " + name + " broke (" + e + ").");
            return null;
        }
    }
}
