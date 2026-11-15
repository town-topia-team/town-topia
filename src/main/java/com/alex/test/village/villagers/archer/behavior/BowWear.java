package com.alex.test.village.villagers.archer.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.Activity.Context;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.archer.ArcherBowUtils;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bows wear out in archers' hands as in a player's: every shot, in a fight
 * or at practice, costs the bow one point of durability (Unbreaking spares
 * it the same way), so a plain bow lasts vanilla's 384 shots. The bow is used
 * until it breaks; then the archer takes the best bow from storage, or makes
 * one from string and sticks ({@link FletchArrowsActivity#bowForSelf}).
 *
 * <p>So a bow is waiting when one breaks, an archer whose bow is worn makes a
 * spare for storage ({@link FletchArrowsActivity#spareBow}); nobody carries a
 * spare. A worn bow is not practised with unless that spare is there: its
 * last shots are kept for real fights.
 */
final class BowWear {

    /** Below this share of its durability a bow counts as worn. */
    static final float WORN_SHARE = 0.25f;
    private static final int WARN_EVERY_TICKS = 24000;
    /** One archer at a time makes the village's spare bow. */
    private static final int SPARE_CLAIM_TICKS = 2400;

    /** Village id -> game time of its last "no bow" warning in chat. */
    private static final Map<UUID, Long> WARNED = new HashMap<>();
    /** Archer npc id -> game time it last wrote "no bow" in its diary. */
    private static final Map<UUID, Long> NOTED = new HashMap<>();
    /** Village id -> the archer making its spare bow, and until when. */
    private static final Map<UUID, SpareClaim> SPARE_MAKERS = new HashMap<>();

    private record SpareClaim(UUID archer, long until) {}

    private BowWear() {}

    /**
     * One shot's wear on the bow in the pack (the bow in the hand is a copy of
     * it, redrawn every tick, so the wear must go on the pack's). True when the
     * bow broke.
     */
    static boolean afterShot(Context ctx) {
        NpcEntity npc = ctx.npc();
        int slot = ArcherBowUtils.findBowSlot(npc.getInventory());
        if (slot < 0) return false;
        ItemStack bow = npc.getInventory().getSlot(slot);
        boolean[] broke = {false};
        if (com.alex.test.village.tokens.PlayerGear.isPenalised(bow)) {
            // A player's bow: Unbreaking at its share, half again the wear.
            int damage = com.alex.test.village.tokens.PlayerGear.wear(ctx.level(), bow, 1);
            if (damage > 0) {
                int next = bow.getDamageValue() + damage;
                if (next >= bow.getMaxDamage()) {
                    bow.shrink(1);
                    broke[0] = true;
                } else {
                    bow.setDamageValue(next);
                }
            }
        } else {
            bow.hurtAndBreak(1, ctx.level(), npc, item -> broke[0] = true);
        }
        if (!broke[0] && !bow.isEmpty()) return false;
        npc.getInventory().setSlot(slot, ItemStack.EMPTY);
        // The break sound and splinters, from the copy still in the hand.
        npc.onEquippedItemBroken(Items.BOW, EquipmentSlot.MAINHAND);
        ArcherBowUtils.equipBow(npc);
        boolean another = ArcherBowUtils.hasBow(npc.getInventory());
        if (!another) npc.showBubble(ThoughtIcon.STATUS_NO_TOOLS, false, 100);
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " ARCHER bow broke"
                + (another ? ", using another" : ", fetching or making a new one"));
        return true;
    }

    /** Whether the archer's bow is down to its last quarter. */
    static boolean bowWorn(NpcEntity npc) {
        int slot = ArcherBowUtils.findBowSlot(npc.getInventory());
        if (slot < 0) return false;
        ItemStack bow = npc.getInventory().getSlot(slot);
        return bow.isDamageableItem()
                && ToolSelectionHelper.durabilityLeft(bow) < bow.getMaxDamage() * WORN_SHARE;
    }

    /** A usable village bow waiting in one of the storage rooms (not one kept for the player). */
    static boolean spareInStorage(Context ctx) {
        int bows = 0;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            bows += storage.countMatching(ctx.level(),
                    s -> s.is(Items.BOW) && ToolSelectionHelper.isUsableByDefault(s));
        }
        return bows > com.alex.test.village.economy.StockReserves.get(ctx.level().getServer())
                .lockedAmount(ctx.mgr().getVillageId(), Items.BOW);
    }

    /** Practice only with a bow that is not worn, or with a spare waiting in storage. */
    static boolean mayPractise(Context ctx) {
        return !bowWorn(ctx.npc()) || spareInStorage(ctx);
    }

    /** Takes the job of making the village's spare bow; false while another archer has it. */
    static boolean claimSpare(Context ctx) {
        long now = ctx.level().getGameTime();
        UUID village = ctx.mgr().getVillageId();
        UUID self = ctx.model().npcId;
        SpareClaim claim = SPARE_MAKERS.get(village);
        if (claim != null && !claim.archer().equals(self) && now < claim.until()
                && claim.until() - now <= SPARE_CLAIM_TICKS) {
            return false;
        }
        SPARE_MAKERS.put(village, new SpareClaim(self, now + SPARE_CLAIM_TICKS));
        if (SPARE_MAKERS.size() > 64) SPARE_MAKERS.values().removeIf(c -> c.until() < now);
        return true;
    }

    /**
     * No bow, none in storage and nothing to make one from: tells the players
     * in the village, once a day for the whole village, counting every archer
     * without a bow. It used to be once a day per archer, so four archers
     * without bows meant four long lines at once, every day.
     */
    static void warnNoBow(Context ctx) {
        long now = ctx.level().getGameTime();
        if (!recently(NOTED, ctx.model().npcId, now)) {
            ctx.brain().diary().log(VillagerDiary.ts(ctx.level())
                    + " ARCHER no bow: none in storage and no string and sticks to make one");
        }
        if (recently(WARNED, ctx.mgr().getVillageId(), now)) return;

        int without = 0;
        for (com.alex.test.village.VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity archer = model.puppet();
            if (archer != null && archer.isAlive()
                    && archer.getProfession() == com.alex.test.village.villagers.core.Profession.ARCHER
                    && !ArcherBowUtils.hasBow(archer.getInventory())) without++;
        }
        String name = ctx.npc().givenName();
        String who = without > 1 ? without + " archers have"
                : (name != null ? "Archer " + name : "An archer") + " has";
        Component message = Component.literal("[Village] " + who + " no bow.").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" Put a bow in storage, or string and sticks so the archers can make one.")
                        .withStyle(ChatFormatting.GRAY));
        for (ServerPlayer player : ctx.level().players()) {
            if (ctx.mgr().contains(player.blockPosition())) player.sendSystemMessage(message);
        }
    }

    /** Whether {@code id} had its turn within the last day; if not, this is its turn. */
    private static boolean recently(Map<UUID, Long> last, UUID id, long now) {
        Long at = last.get(id);
        if (at != null && now >= at && now - at < WARN_EVERY_TICKS) return true;
        last.put(id, now);
        if (last.size() > 256) last.values().removeIf(t -> now - t > WARN_EVERY_TICKS || t > now);
        return false;
    }
}
