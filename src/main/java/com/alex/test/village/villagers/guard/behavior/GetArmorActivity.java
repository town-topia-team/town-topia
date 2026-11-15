package com.alex.test.village.villagers.guard.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.guard.GuardArmorUtils;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Guard-specific configuration for fetching one piece of armor from storage,
 * one instance per humanoid armor slot — mirrors {@link GetSwordActivity}.
 *
 * <p>No crafting fallback: armor is only ever picked up if already stocked
 * (by a player or the smith), never conjured from raw wood like a starter
 * sword.
 *
 * <p>{@link #forUpgrade} swaps the piece worn in a slot for a better one
 * ({@link GuardArmorUtils#quality}); the old piece goes back to storage,
 * stamped for melting down like a swapped-out tool.
 */
public final class GetArmorActivity extends GetToolActivity {

    private static final ToLongFunction<ItemStack> QUALITY = GuardArmorUtils::quality;

    public static final ProfessionToolRequirement HEAD_REQUIREMENT = requirementFor(EquipmentSlot.HEAD);
    public static final ProfessionToolRequirement CHEST_REQUIREMENT = requirementFor(EquipmentSlot.CHEST);
    public static final ProfessionToolRequirement LEGS_REQUIREMENT = requirementFor(EquipmentSlot.LEGS);
    public static final ProfessionToolRequirement FEET_REQUIREMENT = requirementFor(EquipmentSlot.FEET);

    /** A slot whose worn piece {@code storage} can better. */
    public record Upgrade(EquipmentSlot slot, StorageBuilding storage) {}

    private final EquipmentSlot slot;

    private GetArmorActivity(EquipmentSlot slot) {
        super(requirementOf(slot));
        this.slot = slot;
    }

    private GetArmorActivity(EquipmentSlot slot, StorageBuilding storage) {
        super(requirementOf(slot), storage);
        this.slot = slot;
    }

    public static GetArmorActivity forSlot(EquipmentSlot slot) {
        return new GetArmorActivity(slot);
    }

    /** Trade the piece worn in {@code slot} for a better one in {@code storage}. */
    public static GetArmorActivity forUpgrade(EquipmentSlot slot, StorageBuilding storage) {
        return new GetArmorActivity(slot, storage);
    }

    /** The first empty slot (head, chest, legs, feet) storage holds a piece for; null when none. */
    @Nullable
    public static EquipmentSlot firstFillableSlot(Context ctx) {
        for (EquipmentSlot slot : GuardArmorUtils.ARMOR_SLOTS) {
            if (GuardArmorUtils.isArmorSlotEmpty(ctx.npc(), slot) && canAcquire(ctx, requirementOf(slot))) return slot;
        }
        return null;
    }

    /**
     * The first slot (head, chest, legs, feet) with a better piece in storage than the
     * one worn, and the nearest storage holding it; null when every worn piece is the best.
     */
    @Nullable
    public static Upgrade findUpgrade(Context ctx) {
        BlockPos npcPos = ctx.npc().blockPosition();
        for (EquipmentSlot slot : GuardArmorUtils.ARMOR_SLOTS) {
            if (!isSwappable(ctx, ctx.npc().getItemBySlot(slot))) continue;
            StorageBuilding best = null;
            double bestDist = Double.MAX_VALUE;
            for (Building b : ctx.mgr().getBuildings()) {
                if (!(b instanceof StorageBuilding s) || !hasBetterPiece(ctx, s, slot)) continue;
                double d = s.getDoorPos().distSqr(npcPos);
                if (d < bestDist) {
                    bestDist = d;
                    best = s;
                }
            }
            if (best != null) return new Upgrade(slot, best);
        }
        return null;
    }

    @Override
    protected boolean hasBetterToolAt(Context ctx, StorageBuilding storage) {
        return hasBetterPiece(ctx, storage, slot);
    }

    @Override
    protected boolean reserveBetterToolAt(Context ctx, StorageBuilding storage) {
        ItemStack worn = ctx.npc().getItemBySlot(slot);
        if (!isSwappable(ctx, worn) || !storage.isValid()) return false;
        return storage.reserveBetterTool(ctx.level(), GuardArmorUtils.tagForSlot(slot),
                GuardArmorUtils.allowedArmorFor(slot), QUALITY, QUALITY.applyAsLong(worn), ctx.model().npcId);
    }

    @Override
    protected boolean swapToBetterTool(Context ctx, StorageBuilding storage) {
        ItemStack worn = ctx.npc().getItemBySlot(slot);
        if (!isSwappable(ctx, worn)) return false;
        ItemStack old = worn.copy();
        ItemStack better = storage.swapForBetterTool(ctx.level(), GuardArmorUtils.tagForSlot(slot),
                GuardArmorUtils.allowedArmorFor(slot), QUALITY, old, ctx.model().npcId);
        if (better.isEmpty()) return false;
        ctx.npc().setItemSlot(slot, better.copyWithCount(1));
        // Storage took the old piece unless the room was full and the new one
        // could not go back either: then it rides in the pack.
        if (!old.isEmpty() && !ctx.npc().getInventory().addItem(old)) {
            ItemLossLog.drop(ctx, old, "swapped-out armor could not be stored or carried");
        }
        return true;
    }

    /** Real armor the guard may hand in: not a display piece, not someone else's. */
    private static boolean isSwappable(Context ctx, ItemStack worn) {
        return !worn.isEmpty() && !NpcEntity.isDisplayOnly(worn) && ctx.npc().getInventory().mayUse(worn);
    }

    private static boolean hasBetterPiece(Context ctx, StorageBuilding storage, EquipmentSlot slot) {
        ItemStack worn = ctx.npc().getItemBySlot(slot);
        if (!isSwappable(ctx, worn) || !storage.isValid() || storage.getFunctionalBlocks().isEmpty()) return false;
        return storage.hasToolBetterThan(ctx.level(), GuardArmorUtils.tagForSlot(slot),
                GuardArmorUtils.allowedArmorFor(slot), QUALITY, QUALITY.applyAsLong(worn), ctx.model().npcId);
    }

    private static ProfessionToolRequirement requirementOf(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> HEAD_REQUIREMENT;
            case CHEST -> CHEST_REQUIREMENT;
            case LEGS -> LEGS_REQUIREMENT;
            case FEET -> FEET_REQUIREMENT;
            default -> throw new IllegalArgumentException("Not an armor slot: " + slot);
        };
    }

    private static ProfessionToolRequirement requirementFor(EquipmentSlot slot) {
        return new ProfessionToolRequirement() {
            @Override
            public String activityName() { return "get_armor_" + slot.getName(); }

            @Override
            public TagKey<Item> toolTag() { return GuardArmorUtils.tagForSlot(slot); }

            @Override
            public Set<Item> allowedTools(Context ctx) { return GuardArmorUtils.allowedArmorFor(slot); }

            // The best piece for an empty slot too, so a fetched piece is never
            // swapped again on the next check.
            @Override
            public ToLongFunction<ItemStack> quality(Context ctx) { return QUALITY; }

            @Override
            public boolean canCraft(Context ctx) { return false; }

            @Override
            public ItemStack craftedTool(Context ctx) { return ItemStack.EMPTY; }

            @Override
            public int woodCost() { return 0; }

            @Override
            public int craftTicks() { return 0; }

            @Override
            public boolean requiredForWork() { return false; }

            @Override
            public boolean canPlaceAcquiredTool(Context ctx, ItemStack stack) {
                return ctx.npc().getItemBySlot(slot).isEmpty()
                        && ctx.npc().getInventory().mayUse(stack)
                        && stack.is(toolTag())
                        && allowedTools(ctx).contains(stack.getItem());
            }

            @Override
            public boolean placeAcquiredTool(Context ctx, ItemStack stack) {
                if (!canPlaceAcquiredTool(ctx, stack)) return false;
                ctx.npc().setItemSlot(slot, stack.copyWithCount(1));
                return true;
            }
        };
    }
}
