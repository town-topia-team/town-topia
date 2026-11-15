package com.alex.test.village.villagers.butcher.lead;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

/**
 * The lead a butcher (and later a rancher) walks animals on.
 *
 * <p>The lead never leaves the pack while an animal is led: the hand only
 * shows a copy, and every leash we remove drops no item. So a lead can be
 * neither duplicated nor lost. One is kept on every deposit; with none
 * carried, the villager fetches one from storage like a tool.
 */
public final class LeadSupply {

    public static final TagKey<Item> VILLAGER_LEADS = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "villager_leads"));

    /** Leads kept back from a deposit. */
    public static final int KEEP = 1;

    /** Brain-data flag: this villager has had its one free lead from an older save. */
    private static final String TAG_LEAD_GIVEN = "LeadGiven";

    /** Fetch one from storage; never crafted. Not "required" so a missing lead never banks the pack. */
    public static final ProfessionToolRequirement REQUIREMENT = new ProfessionToolRequirement() {
        @Override public String activityName() { return "get_lead"; }
        @Override public TagKey<Item> toolTag() { return VILLAGER_LEADS; }
        @Override public Set<Item> allowedTools(Activity.Context ctx) { return Set.of(Items.LEAD); }
        @Override public boolean canCraft(Activity.Context ctx) { return false; }
        @Override public ItemStack craftedTool(Activity.Context ctx) { return ItemStack.EMPTY; }
        @Override public int woodCost() { return 0; }
        @Override public int craftTicks() { return 0; }
        @Override public boolean requiredForWork() { return false; }
    };

    private LeadSupply() {}

    /** Professions that walk animals on a lead. */
    public static boolean usesLead(Profession profession) {
        return profession == Profession.BUTCHER || profession == Profession.RANCHER;
    }

    /** Leads this villager carries and may use. */
    public static int carried(NpcEntity npc) {
        var inv = npc.getInventory();
        int count = 0;
        for (ItemStack stack : inv.getSlots()) {
            if (stack.is(Items.LEAD) && inv.mayUse(stack)) count += stack.getCount();
        }
        return count;
    }

    public static boolean hasLead(NpcEntity npc) {
        return carried(npc) > 0;
    }

    /** A new village-owned lead for {@code npc}'s pack. */
    public static ItemStack issue(NpcEntity npc) {
        return VillageItemOwnership.issue(new ItemStack(Items.LEAD), npc);
    }

    /** How long the lead in hand shows without a refresh: a walk refreshes it every tick. */
    private static final long SHOWN_TICKS = 40L;

    /**
     * The lead in hand while leading: a display-only prop, never the pack's
     * own lead. A plain lead there was not the village's, so the villager
     * dropped it as a real item the next tick: a free lead every walk.
     */
    public static void showInHand(NpcEntity npc) {
        npc.holdDisplayItem(new ItemStack(Items.LEAD), npc.level().getGameTime() + SHOWN_TICKS);
    }

    /** Keeps the lead in hand for another moment; the leading activity calls this every tick. */
    public static void keepShown(NpcEntity npc) {
        npc.extendDisplayItem(npc.level().getGameTime() + SHOWN_TICKS);
    }

    /** Puts the lead in hand away; a real item in hand (the sword) is left alone. */
    public static void putAway(NpcEntity npc) {
        npc.clearDisplayItem();
    }

    /** Marks a newly hired villager as already given its lead. */
    public static void markGiven(NpcEntity npc) {
        npc.getBrainData().putBoolean(TAG_LEAD_GIVEN, true);
    }

    /**
     * Older saves: a butcher hired before leads existed gets one, once. Only
     * when it has none and the pack has room; otherwise it fetches one later.
     */
    public static void giveOnceToOldHire(NpcEntity npc) {
        var data = npc.getBrainData();
        if (data.getBoolean(TAG_LEAD_GIVEN)) return;
        data.putBoolean(TAG_LEAD_GIVEN, true);
        if (hasLead(npc)) return;
        npc.getInventory().addItem(issue(npc));
    }
}
