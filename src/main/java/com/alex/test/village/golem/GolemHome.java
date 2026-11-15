package com.alex.test.village.golem;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.IronGolem;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The village an iron golem calls home. A golem adopts a village only by
 * standing inside its border; it keeps that home while it strays outside
 * (chasing a mob, pushed out), and forgets it when its village is gone or it
 * has been taken far away. Saved on the golem, so it survives reloads.
 */
final class GolemHome {

    private static final String TAG = "dynamicvillages_golem_home";
    /** Ticks between looks at which village the golem stands in (5 seconds). */
    private static final int CHECK_TICKS = 100;
    /** A golem this far from the last spot it stood inside its village forgets it (led or carried off). */
    private static final int FORGET_DISTANCE = 48;

    private final IronGolem golem;
    @Nullable private UUID villageId;
    /** The last spot the golem stood inside its home's border; where it walks back to. */
    @Nullable private BlockPos lastInside;
    @Nullable private VillageManager home;
    private int nextCheck;

    GolemHome(IronGolem golem) {
        this.golem = golem;
        // Spread the checks so golems loaded together do not all look on the same tick.
        this.nextCheck = golem.tickCount + golem.getId() % CHECK_TICKS;
        CompoundTag saved = golem.getPersistentData().getCompound(TAG);
        if (saved.hasUUID("Village")) {
            villageId = saved.getUUID("Village");
            lastInside = NbtUtils.readBlockPos(saved, "LastInside").orElse(null);
            if (lastInside == null) villageId = null;
        }
    }

    /** The golem's home village (looked up again every 5 seconds), or null for a vanilla golem. */
    @Nullable
    VillageManager current() {
        if (golem.tickCount >= nextCheck) {
            nextCheck = golem.tickCount + CHECK_TICKS;
            refresh();
        }
        return home;
    }

    /** Where to walk back to; only meaningful while {@link #current()} is not null. */
    @Nullable
    BlockPos lastInside() { return lastInside; }

    private void refresh() {
        if (!(golem.level() instanceof ServerLevel level) || level.getServer() == null) return;
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        BlockPos pos = golem.blockPosition();
        VillageManager inside = registry.findVillageAt(level, pos);
        if (inside != null) {
            home = inside;
            if (!inside.getVillageId().equals(villageId) || !pos.equals(lastInside)) {
                villageId = inside.getVillageId();
                lastInside = pos;
                save();
            }
            return;
        }
        if (villageId == null) {
            home = null;
            return;
        }
        VillageManager saved = registry.get(villageId);
        boolean gone = saved == null || saved.getLevel() != level || registry.isVillageInactive(villageId);
        // A golem on a lead keeps its home until it is let go; far away, it then forgets it.
        boolean tooFar = !golem.isLeashed()
                && lastInside.distSqr(pos) > (double) FORGET_DISTANCE * FORGET_DISTANCE;
        if (gone || tooFar) {
            forget();
            return;
        }
        home = saved;
    }

    private void forget() {
        home = null;
        villageId = null;
        lastInside = null;
        golem.getPersistentData().remove(TAG);
    }

    private void save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Village", villageId);
        tag.put("LastInside", NbtUtils.writeBlockPos(lastInside));
        golem.getPersistentData().put(TAG, tag);
    }
}
