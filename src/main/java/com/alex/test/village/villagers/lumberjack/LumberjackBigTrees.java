package com.alex.test.village.villagers.lumberjack;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.lumberjack.data.TreeStructure;
import com.alex.test.village.villagers.lumberjack.settings.LumberjackSettings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;

import java.util.HashSet;
import java.util.Set;

/**
 * Big 2x2 trees (every dark oak, big spruce and big jungle) need an iron axe or
 * better ({@link AxeUtils#canFellBigTrees}). Remembers when this lumberjack last
 * passed one over because of its axe, so its thought bubble and villager screen
 * can say why, and only while that is still true.
 */
public final class LumberjackBigTrees {

    /** Brain data key: game time this lumberjack last passed a big tree over for its axe. */
    private static final String SKIPPED_AT_KEY = "LumberjackBigTreeSkippedAt";
    /**
     * How long a pass over stays news: five minutes. A lumberjack busy with small
     * trees only looks again when it picks its next tree, a minute or two apart.
     */
    private static final long RECENT_TICKS = 6000L;

    private LumberjackBigTrees() {}

    /** Any axe fells a small tree; a big 2x2 one only an iron axe or better. */
    public static boolean mayFell(TreeStructure tree, boolean bigTreeAxe) {
        return !tree.twoByTwo() || bigTreeAxe;
    }

    /** It just passed over a big tree it could reach, because its axe is too weak. */
    public static void noteSkipped(NpcEntity npc, long gameTime) {
        npc.getBrainData().putLong(SKIPPED_AT_KEY, gameTime);
    }

    public static void clearSkipped(NpcEntity npc) {
        CompoundTag data = npc.getBrainData();
        if (data.contains(SKIPPED_AT_KEY)) data.remove(SKIPPED_AT_KEY);
    }

    /** Its axe is too weak for big trees, and it passed one over in the last few minutes. */
    public static boolean waitingForAxe(NpcEntity npc, long gameTime) {
        CompoundTag data = npc.getBrainData();
        if (!data.contains(SKIPPED_AT_KEY)) return false;
        long at = data.getLong(SKIPPED_AT_KEY);
        return gameTime >= at && gameTime - at <= RECENT_TICKS && !AxeUtils.hasBigTreeAxe(npc);
    }

    /** At least one axe that fells big trees is switched on in its Equipment tab. */
    public static boolean bigTreeAxeAllowed(NpcEntity npc) {
        for (Item axe : LumberjackSettings.of(npc).getAllowedAxes()) {
            if (AxeUtils.canFellBigTrees(axe)) return true;
        }
        return false;
    }

    /**
     * The axes that fell big trees which a lumberjack here is waiting for
     * ({@link #waitingForAxe}), each limited to the ones that lumberjack may use.
     * Farmers leave these in storage (GetFarmerAxeActivity). Loaded bodies only.
     */
    public static Set<Item> axesWaitedFor(VillageManager mgr, long gameTime) {
        Set<Item> wanted = new HashSet<>();
        for (VillagerModel model : mgr.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.getProfession() != Profession.LUMBERJACK
                    || !waitingForAxe(npc, gameTime)) continue;
            for (Item axe : LumberjackSettings.of(npc).getAllowedAxes()) {
                if (AxeUtils.canFellBigTrees(axe)) wanted.add(axe);
            }
        }
        return wanted;
    }

    /**
     * A storage room holds a free axe it may use that fells big trees. Worth a
     * trip even when there is no tree it can cut yet: the work loop swaps it in
     * (LumberjackActivity.tryStartAxeUpgrade) before it looks for a tree.
     */
    public static boolean storageHasBigTreeAxe(Activity.Context ctx) {
        if (!bigTreeAxeAllowed(ctx.npc())) return false;
        Set<Item> allowed = LumberjackSettings.of(ctx.npc()).getAllowedAxes();
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()
                    || storage.getFunctionalBlocks().isEmpty()) continue;
            if (storage.hasToolBetterThan(ctx.level(), ItemTags.AXES, allowed, AxeUtils::quality,
                    AxeUtils.BIG_TREE_QUALITY - 1, ctx.model().npcId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The job note on its villager screen: the first line sits beside the job
     * name, the rest shows when the mouse is on it. Empty while there is
     * nothing to say.
     */
    public static String workNote(NpcEntity npc, long gameTime) {
        if (!waitingForAxe(npc, gameTime)) return "";
        if (!bigTreeAxeAllowed(npc)) {
            return "Weak axe\nSkips big 2x2 trees like dark oak.\n"
                    + "Iron and better axes are switched\noff on its Equipment tab.";
        }
        return "Weak axe\nSkips big 2x2 trees like dark oak.\n"
                + "Needs an iron axe or better.\nPut one in a storage chest.";
    }
}
