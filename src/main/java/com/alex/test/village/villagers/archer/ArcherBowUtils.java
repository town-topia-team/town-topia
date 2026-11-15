package com.alex.test.village.villagers.archer;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shared bow/arrow inventory and equip helpers for Archers, mirroring GuardSwordUtils. */
public final class ArcherBowUtils {

    private ArcherBowUtils() {}

    /** The archer skill level shown on this villager (its synced skill slot), or 1. */
    public static int skillLevel(NpcEntity npc) {
        for (com.alex.test.village.villagers.core.ProfessionSkill skill : npc.getProfessionSkills()) {
            if (skill.profession() == com.alex.test.village.villagers.core.Profession.ARCHER) return skill.level();
        }
        return 1;
    }

    public static boolean isBow(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.BOW);
    }

    public static boolean isArrow(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.ARROWS);
    }

    /**
     * The usable bow with the most enchantment, the same rule as the bow upgrade
     * and banking (GetBowActivity's default quality); -1 when there is none.
     */
    public static int findBowSlot(InventoryManager inventory) {
        InventoryManager.SlotMemo memo = inventory.bowMemo;
        long now = System.nanoTime();
        if (memoFresh(memo, inventory, now)) {
            if (memo.slot < 0) return -1;
            // The remembered bow is only handed out while it is still there and still usable.
            if (isUsableBow(inventory, memo.slot)) return memo.slot;
        }
        int slot = ToolSelectionHelper.findBestToolSlot(inventory,
                stack -> isBow(stack) && ToolSelectionHelper.isUsableByDefault(stack),
                ToolSelectionHelper::quality);
        remember(memo, inventory, slot, now);
        return slot;
    }

    /**
     * How long a bow or arrow scan is reused: 10 ticks. A remembered slot is
     * re-checked on every use, so this only delays noticing a better bow or
     * a newly arrived bow / arrow, by at most half a second.
     */
    private static final long SCAN_REUSE_NANOS = 500_000_000L;

    private static boolean memoFresh(InventoryManager.SlotMemo memo, InventoryManager inventory, long now) {
        return memo.known && memo.changes == inventory.changes() && now - memo.scannedAtNanos < SCAN_REUSE_NANOS;
    }

    private static void remember(InventoryManager.SlotMemo memo, InventoryManager inventory, int slot, long now) {
        memo.known = true;
        memo.slot = slot;
        memo.changes = inventory.changes();
        memo.scannedAtNanos = now;
    }

    private static boolean isUsableBow(InventoryManager inventory, int slot) {
        ItemStack stack = inventory.getSlot(slot);
        return !stack.isEmpty() && inventory.mayUse(stack)
                && isBow(stack) && ToolSelectionHelper.isUsableByDefault(stack);
    }

    public static boolean hasBow(InventoryManager inventory) {
        return findBowSlot(inventory) >= 0;
    }

    /** First inventory slot holding at least one arrow, or -1. Does not consume it. */
    public static int findArrowSlot(InventoryManager inventory) {
        InventoryManager.SlotMemo memo = inventory.arrowMemo;
        long now = System.nanoTime();
        if (memoFresh(memo, inventory, now)) {
            if (memo.slot < 0) return -1;
            ItemStack kept = inventory.getSlot(memo.slot);
            if (inventory.mayUse(kept) && isArrow(kept)) return memo.slot;
        }
        int found = -1;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            if (inventory.mayUse(inventory.getSlot(i)) && isArrow(inventory.getSlot(i))) { found = i; break; }
        }
        remember(memo, inventory, found, now);
        return found;
    }

    /** First slot of plain arrows, or -1: practice never spends spectral or tipped ones. */
    public static int findPlainArrowSlot(InventoryManager inventory) {
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inventory.getSlot(i);
            if (inventory.mayUse(stack) && stack.is(Items.ARROW)) return i;
        }
        return -1;
    }

    /**
     * The arrow to loose at {@code target}: a spectral arrow on a mob that is
     * not glowing yet (it lights it up for the guards and the player), a
     * tipped arrow on a big mob, else a plain arrow, else whatever is carried.
     */
    public static int pickArrowSlot(InventoryManager inventory, LivingEntity target) {
        int plain = -1, spectral = -1, tipped = -1, any = -1;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inventory.getSlot(i);
            if (!inventory.mayUse(stack) || !isArrow(stack)) continue;
            if (any < 0) any = i;
            if (stack.is(Items.SPECTRAL_ARROW)) { if (spectral < 0) spectral = i; }
            else if (stack.is(Items.TIPPED_ARROW)) { if (tipped < 0) tipped = i; }
            else if (plain < 0) plain = i;
        }
        if (spectral >= 0 && !target.hasEffect(MobEffects.GLOWING)) return spectral;
        if (tipped >= 0 && target.getMaxHealth() >= BIG_MOB_HEALTH) return tipped;
        return plain >= 0 ? plain : any;
    }

    /** Max health from which a mob is worth a tipped arrow (a zombie has 20). */
    private static final float BIG_MOB_HEALTH = 40f;

    /**
     * The speed an arrow's damage is reckoned at: a skeleton's 1.6, the speed
     * archers used to shoot at (a slow lob a walking mob had time to step out of).
     */
    private static final float DAMAGE_SPEED = 1.6F;

    /**
     * An arrow for an archer to loose at {@code speed} (faster with skill, see
     * {@code ArcherBalance.arrowSpeed}), hitting as hard as it used to. Vanilla
     * sets a mob's arrow damage from its speed, and a hit multiplies that by the
     * speed again, so a full-draw arrow would hit about three times as hard (a
     * zombie in a shot or two); its damage is scaled back to the old speed's.
     * Power adds on top as on a player's bow.
     */
    public static net.minecraft.world.entity.projectile.AbstractArrow fullDrawArrow(
            NpcEntity archer, ItemStack arrowStack, ItemStack bow, float speed) {
        net.minecraft.world.entity.projectile.AbstractArrow arrow =
                net.minecraft.world.entity.projectile.ProjectileUtil.getMobArrow(
                        archer, arrowStack, DAMAGE_SPEED, bow.isEmpty() ? null
                                : com.alex.test.village.tokens.PlayerGear.effective(bow, archer.getRandom()));
        arrow.setBaseDamage(arrow.getBaseDamage() * DAMAGE_SPEED / speed);
        return arrow;
    }

    /**
     * Whether shooting {@code arrow} from {@code bow} uses nothing up: a plain
     * arrow from an Infinity bow, exactly as vanilla decides it for a player.
     */
    public static boolean isInfiniteShot(net.minecraft.server.level.ServerLevel level, ItemStack bow, ItemStack arrow) {
        return !bow.isEmpty() && arrow.is(Items.ARROW)
                && net.minecraft.world.item.enchantment.EnchantmentHelper.processAmmoUse(level,
                        com.alex.test.village.tokens.PlayerGear.effective(bow, level.random), arrow, 1) == 0;
    }

    /** Arrows this archer fills its quiver to: its "Arrows to carry" setting. */
    public static int quiverSize(NpcEntity archer) {
        return com.alex.test.village.villagers.settings.ProfessionSettings.of(archer,
                        com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema.SCHEMA)
                .getInt(com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema.ARROWS_TO_CARRY);
    }

    /** Below this many arrows it goes for more: half its quiver. */
    public static int refillLine(NpcEntity archer) {
        return quiverSize(archer) / 2;
    }

    /** One of this archer's own switches on the archer screen. */
    public static boolean setting(NpcEntity archer, String settingId) {
        return com.alex.test.village.villagers.settings.ProfessionSettings.of(archer,
                com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema.SCHEMA).getBool(settingId);
    }

    public static boolean hasArrow(InventoryManager inventory) {
        return findArrowSlot(inventory) >= 0;
    }

    public static int countArrows(InventoryManager inventory) {
        int total = 0;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inventory.getSlot(i);
            if (inventory.mayUse(stack) && isArrow(stack)) total += stack.getCount();
        }
        return total;
    }

    /**
     * Mirrors the carried bow into MAINHAND every tick, same pattern as
     * {@code GuardSwordUtils.equipBestAllowedSword}. Safe to re-copy each
     * tick (unlike armor): the shots' wear goes on the bow in the pack
     * ({@code BowWear}), never on this copy, so the hand always shows the
     * pack bow's real durability.
     */
    public static int equipBow(NpcEntity npc) {
        int slot = findBowSlot(npc.getInventory());
        if (slot >= 0) {
            npc.setItemSlot(EquipmentSlot.MAINHAND, npc.getInventory().getSlot(slot).copy());
        } else if (isBow(npc.getMainHandItem())) {
            npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        return slot;
    }
}
