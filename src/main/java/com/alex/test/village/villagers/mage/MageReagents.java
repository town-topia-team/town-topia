package com.alex.test.village.villagers.mage;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkill;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The mage's carried reagents, its mana and its skill level, read from and
 * written to the villager. Big spells burn a reagent (an amethyst shard or a
 * lapis lazuli); the bolt and Ward run on mana alone.
 */
public final class MageReagents {

    /** How many reagents a mage carries; a trip to storage tops it up to this. */
    public static final int CARRY = 16;
    /** Below this, and with nothing to fight, it goes to storage for more. */
    public static final int REFILL_LINE = 6;

    private static final String TAG_MANA = "MageMana";
    private static final String TAG_MANA_AT = "MageManaAt";

    private MageReagents() {}

    public static boolean isReagent(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(Items.AMETHYST_SHARD) || stack.is(Items.LAPIS_LAZULI));
    }

    public static int count(InventoryManager inventory) {
        int total = 0;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inventory.getSlot(i);
            if (isReagent(stack) && inventory.mayUse(stack)) total += stack.getCount();
        }
        return total;
    }

    /**
     * Burns {@code amount} reagents, amethyst first: lapis is what the
     * enchanter binds books with, so the mage leaves it for last. Takes
     * nothing and returns false if the pack holds fewer.
     */
    public static boolean spend(InventoryManager inventory, int amount) {
        if (amount <= 0) return true;
        if (count(inventory) < amount) return false;
        int left = take(inventory, Items.AMETHYST_SHARD, amount);
        take(inventory, Items.LAPIS_LAZULI, left);
        return true;
    }

    private static int take(InventoryManager inventory, Item item, int amount) {
        for (int i = 0; i < InventoryManager.SIZE && amount > 0; i++) {
            ItemStack stack = inventory.getSlot(i);
            if (stack.isEmpty() || !stack.is(item) || !inventory.mayUse(stack)) continue;
            int n = Math.min(amount, stack.getCount());
            if (inventory.reduceSlot(i, n)) amount -= n;
        }
        return amount;
    }

    /** The mage's skill level, 1..100, as the info screen shows it. */
    public static int skillLevel(NpcEntity npc) {
        for (ProfessionSkill skill : npc.getProfessionSkills()) {
            if (skill.profession() == Profession.MAGE) return skill.level();
        }
        return 1;
    }

    /** The mana saved on the villager; a mage that never cast starts full. */
    public static MageMana loadMana(NpcEntity npc, int level, long now) {
        CompoundTag data = npc.getBrainData();
        if (!data.contains(TAG_MANA)) return MageMana.full(level, now);
        return new MageMana(data.getFloat(TAG_MANA), data.getLong(TAG_MANA_AT));
    }

    /** The info screen's line: mana now and reagents carried. */
    public static String status(NpcEntity npc) {
        int level = skillLevel(npc);
        float now = loadMana(npc, level, npc.level().getGameTime()).current(npc.level().getGameTime(), level);
        String line = String.format(java.util.Locale.ROOT, "Mana %.0f/%.0f, %d reagents",
                now, MageBalance.maxMana(level), count(npc.getInventory()));
        // Until a spell burns them it fetches none: say so, or the lapis in storage looks forgotten.
        if (!MageBalance.hasFrostNova(level)) {
            line += " (fetched from Frost Nova, level " + MageBalance.FROST_NOVA_LEVEL + ")";
        }
        return line;
    }

    public static void saveMana(NpcEntity npc, MageMana mana) {
        CompoundTag data = npc.getBrainData();
        data.putFloat(TAG_MANA, mana.stored());
        data.putLong(TAG_MANA_AT, mana.storedAt());
    }
}
