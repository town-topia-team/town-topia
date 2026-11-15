package com.alex.test.village.economy;

import com.alex.test.village.VillageManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A new tool while nobody is near, the way a live villager gets one
 * ({@code GetToolActivity}): the best allowed spare from storage, else a
 * wooden one crafted from any logs or planks in a storage room with a
 * crafting table, when the job's settings allow crafting. Each job wears its
 * tool by its own live rule; this only replaces a broken one.
 */
public final class AwayTools {

    private AwayTools() {}

    /**
     * Puts one tool for {@code requirement} into the villager's pack, for the
     * away calculator ({@code economy/away}): wood used for a crafted tool is
     * counted in {@code consumed}. With its book open, the storage reads and
     * takes go to the copies.
     *
     * @return false when there is no spare and none can be crafted
     */
    public static boolean restock(ServerLevel level, Activity.Context ctx, ProfessionToolRequirement requirement,
                                  Map<Item, Integer> consumed) {
        Set<Item> allowed = requirement.allowedTools(ctx);
        if (allowed.isEmpty()) return false;
        VillageManager mgr = ctx.mgr();
        UUID npcId = ctx.npc().getNpcId();
        Predicate<ItemStack> usable = requirement.usablePredicate(ctx);
        for (Building building : mgr.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            ItemStack tool = storage.withdrawToolOfTypes(level, requirement.toolTag(), allowed,
                    stack -> usable.test(stack) && requirement.canPlaceAcquiredTool(ctx, stack),
                    requirement.quality(ctx), npcId);
            if (tool.isEmpty()) continue;
            if (requirement.placeAcquiredTool(ctx, tool)) return true;
            storage.depositItems(level, tool);
            return false;
        }
        if (!requirement.canCraft(ctx)) return false;
        ItemStack crafted = VillageItemOwnership.produce(requirement.craftedTool(ctx), mgr);
        if (crafted.isEmpty() || !requirement.canPlaceAcquiredTool(ctx, crafted)) return false;
        int cost = requirement.woodCost();
        for (Building building : mgr.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()
                    || !storage.hasCraftingTable() || storage.freeWood(level, npcId) < cost) continue;
            List<StorageBuilding.WithdrawnStack> wood = storage.withdrawWood(level, cost, npcId);
            int got = 0;
            for (StorageBuilding.WithdrawnStack held : wood) got += held.stack().getCount();
            if (got >= cost && requirement.placeAcquiredTool(ctx, crafted)) {
                for (StorageBuilding.WithdrawnStack held : wood) {
                    consumed.merge(held.stack().getItem(), held.stack().getCount(), Integer::sum);
                }
                return true;
            }
            // Not enough after all, or no room: the wood goes back.
            for (StorageBuilding.WithdrawnStack held : wood) {
                if (!storage.returnToChest(level, held.chestPos(), held.stack())) {
                    storage.depositItems(level, held.stack());
                }
            }
            return false;
        }
        return false;
    }
}
