package com.alex.test.village.economy.away;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.MealLeftovers;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkill;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.alex.test.village.villagers.lumberjack.settings.LumberjackSettings;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.settings.SettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchemaRegistry;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One villager's copy for the catch-up: its pack, brain data, needs, skills,
 * the skill levels it shows and its health.
 *
 * <p>A step swaps the copy into the body ({@link #swapIn}), runs the job, and
 * swaps the real state back ({@link #swapOut}), all inside one tick, so live
 * helpers that read and change the body work unchanged while between ticks and
 * in any save the body holds its real state. {@link #write} then gives the
 * body what changed in the copy, field by field.
 *
 * <p>Trap: anything a job changes on the body or its brain must be in
 * {@link Snapshot}, or a preview (and a run started again after a restart)
 * changes the real villager.
 */
final class AwayVillager {

    private static final Logger LOGGER = LogUtils.getLogger();

    final UUID id;
    private final VillagerModel model;
    private final NpcEntity body;
    private final Profession profession;
    /** As read: what {@link #write} measures the changes against. */
    private final Snapshot start;
    /** The copy the days work on. */
    private Snapshot away;
    /** The real state while the copy is swapped in, else null. */
    @Nullable private Snapshot real;

    // Away health ({@link AwayHealth}), over all the days.
    /** Starved to death while away: skipped from then on, killed at WRITE. */
    private boolean starved;
    /** Ticks at hunger 0 toward the next starving hit. */
    long starveTimer;
    /** Was at hunger 0 at some time while away. */
    boolean wentHungry;

    private AwayVillager(UUID id, VillagerModel model, NpcEntity body, Snapshot start) {
        this.id = id;
        this.model = model;
        this.body = body;
        this.profession = body.getProfession();
        this.start = start;
        // Sharing is safe: a snapshot is never changed in place, swapOut makes a new one.
        this.away = start;
    }

    /** The copy of a member with a live body, or null when it has none. */
    @Nullable
    static AwayVillager read(UUID id, @Nullable VillagerModel model) {
        NpcEntity npc = model == null ? null : model.puppet();
        if (npc == null || !npc.isAlive() || npc.isRemoved()) return null;
        // After a restart a dormant village's brains never ticked: their needs and
        // skills are blank until loaded from the saved data.
        model.brain().prepareForOfflineReconciliation(npc);
        return new AwayVillager(id, model, npc, Snapshot.take(npc, model.brain()));
    }

    Profession profession() { return profession; }

    /**
     * Whether {@code npc}'s job has its work switched on. Most jobs: the row their
     * settings schema marks {@code RowKind.MASTER_TOGGLE}, read live. Lumberjack and
     * farmer keep their own flag. A job with neither always works. (Moved from the
     * old calculator in round 12.)
     */
    static boolean workEnabled(NpcEntity npc) {
        if (npc.getProfession() == Profession.LUMBERJACK) return LumberjackSettings.of(npc).works();
        if (npc.getProfession() == Profession.FARMER) return FarmerSettings.of(npc).works();
        return SettingsSchemaRegistry.forProfession(npc.getProfession())
                .map(schema -> masterToggleEnabled(npc, schema))
                .orElse(true);
    }

    private static boolean masterToggleEnabled(NpcEntity npc, SettingsSchema schema) {
        for (SettingsSchema.Section section : schema.sections()) {
            for (SettingsSchema.Row row : section.rows()) {
                if (row.kind() == SettingsSchema.RowKind.MASTER_TOGGLE) {
                    return ProfessionSettings.of(npc, schema).getBool(row.settingId());
                }
            }
        }
        return true;
    }

    NpcEntity body() { return body; }

    VillagerModel model() { return model; }

    /** The body read is still this member's, alive and in the world. */
    boolean bodyHere() {
        return model.puppet() == body && body.isAlive() && !body.isRemoved();
    }

    /** Puts the copy into the body; false (nothing done) when the body is gone. */
    boolean swapIn() {
        if (real != null) throw new IllegalStateException("Villager copy already swapped in");
        if (!bodyHere()) return false;
        real = Snapshot.take(body, brain());
        away.put(body, brain());
        return true;
    }

    /** Takes the copy back out of the body and puts the real state back. */
    void swapOut() {
        if (real == null) return;
        away = Snapshot.take(body, brain());
        real.put(body, brain());
        real = null;
    }

    boolean changed() {
        return starved || !away.sameAs(start);
    }

    boolean starved() { return starved; }

    /** Starved to death on a day of the copy; the body is killed at WRITE, never before. */
    void markStarved() { starved = true; }

    /**
     * WRITE: gives the body what changed in the copy since it was read. Only
     * what changed is written, so anything that touched the real villager
     * meanwhile stays. False when the body is gone (the copy is dropped).
     */
    boolean write() {
        if (!changed()) return true;
        if (!bodyHere()) {
            LOGGER.warn("[AwayWrite] villager {} has a new body or none; its away changes are dropped",
                    id.toString().substring(0, 8));
            return false;
        }
        VillagerBrain brain = brain();
        CompoundTag live = body.getBrainData();
        Set<String> keys = new HashSet<>(start.data.getAllKeys());
        keys.addAll(away.data.getAllKeys());
        for (String key : keys) {
            Tag was = start.data.get(key);
            Tag now = away.data.get(key);
            if (Objects.equals(was, now)) continue;
            if (now == null) live.remove(key);
            else live.put(key, now.copy());
        }
        brain.needs().load(live);
        brain.skills().load(live);
        writePack();
        writeBowls();
        if (!away.levels.equals(start.levels)) body.restoreProfessionSkills(away.levels);
        if (starved) {
            // Through the normal damage path, as a live starving death: the death message,
            // the village's bookkeeping, a cleric's Guardian Angel if one may still save it.
            LOGGER.info("[AwayWrite] villager {} ({}) starved to death while away",
                    id.toString().substring(0, 8), profession.displayName());
            body.hurt(body.damageSources().starve(), body.getHealth() + body.getMaxHealth());
            return true;
        }
        float healthChange = away.health - start.health;
        if (healthChange != 0f) {
            body.setHealth(Math.max(1f, Math.min(body.getMaxHealth(), body.getHealth() + healthChange)));
        }
        return true;
    }

    /** The emptied-bowl counts ({@code MealLeftovers}), kind by kind. */
    private void writeBowls() {
        if (away.bowls.equals(start.bowls)) return;
        CompoundTag live = MealLeftovers.counts(body);
        Set<String> kinds = new HashSet<>(start.bowls.getAllKeys());
        kinds.addAll(away.bowls.getAllKeys());
        for (String kind : kinds) {
            Tag was = start.bowls.get(kind);
            Tag now = away.bowls.get(kind);
            if (Objects.equals(was, now)) continue;
            if (now == null) live.remove(kind);
            else live.put(kind, now.copy());
        }
        MealLeftovers.setCounts(body, live);
    }

    /** The pack's difference, item by item; what no longer fits is logged. */
    private void writePack() {
        InventoryManager pack = body.getInventory();
        List<ItemStack> before = tally(start.pack);
        List<ItemStack> after = tally(away.pack);
        for (ItemStack was : before) {
            int gone = was.getCount() - count(after, was);
            for (int i = 0; i < InventoryManager.SIZE && gone > 0; i++) {
                ItemStack slot = pack.getSlot(i);
                if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, was)) continue;
                int take = Math.min(gone, slot.getCount());
                pack.reduceSlot(i, take);
                gone -= take;
            }
        }
        for (ItemStack now : after) {
            int added = now.getCount() - count(before, now);
            if (added <= 0) continue;
            if (!pack.addItem(now.copyWithCount(added))) {
                LOGGER.warn("[AwayWrite] villager {}: {} {} made while away did not fit its pack",
                        id.toString().substring(0, 8), added, BuiltInRegistries.ITEM.getKey(now.getItem()));
            }
        }
    }

    private VillagerBrain brain() {
        return model.brain();
    }

    private static List<ItemStack> tally(ItemStack[] stacks) {
        List<ItemStack> tally = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            ItemStack same = null;
            for (ItemStack seen : tally) {
                if (ItemStack.isSameItemSameComponents(seen, stack)) { same = seen; break; }
            }
            if (same != null) same.grow(stack.getCount());
            else tally.add(stack.copy());
        }
        return tally;
    }

    private static int count(List<ItemStack> tally, ItemStack like) {
        for (ItemStack stack : tally) {
            if (ItemStack.isSameItemSameComponents(stack, like)) return stack.getCount();
        }
        return 0;
    }

    /**
     * Everything of a villager a job may change: the swap list. Never shared
     * with the body: taking copies, putting copies.
     */
    private static final class Snapshot {
        /** Brain data with the brain's in-memory needs and skills written in. */
        final CompoundTag data;
        final ItemStack[] pack;
        final List<ProfessionSkill> levels;
        final float health;
        /** Emptied-container counts ({@code MealLeftovers}): on the body, not in the brain data. */
        final CompoundTag bowls;

        private Snapshot(CompoundTag data, ItemStack[] pack, List<ProfessionSkill> levels, float health,
                         CompoundTag bowls) {
            this.data = data;
            this.pack = pack;
            this.levels = levels;
            this.health = health;
            this.bowls = bowls;
        }

        static Snapshot take(NpcEntity npc, VillagerBrain brain) {
            CompoundTag data = npc.getBrainData().copy();
            // The brain keeps needs and skills in memory and writes them only now and then.
            brain.needs().save(data);
            brain.skills().save(data);
            ItemStack[] slots = npc.getInventory().getSlots();
            ItemStack[] pack = new ItemStack[slots.length];
            for (int i = 0; i < slots.length; i++) pack[i] = slots[i].copy();
            // Already a copy that is never changed in place (List.copyOf).
            return new Snapshot(data, pack, npc.getProfessionSkills(), npc.getHealth(), MealLeftovers.counts(npc));
        }

        void put(NpcEntity npc, VillagerBrain brain) {
            CompoundTag live = npc.getBrainData();
            if (!live.equals(data)) {
                for (String key : List.copyOf(live.getAllKeys())) live.remove(key);
                live.merge(data);
            }
            brain.needs().load(live);
            brain.skills().load(live);
            InventoryManager inventory = npc.getInventory();
            for (int i = 0; i < pack.length && i < InventoryManager.SIZE; i++) {
                if (!ItemStack.matches(inventory.getSlot(i), pack[i])) inventory.setSlot(i, pack[i].copy());
            }
            if (npc.getProfessionSkills() != levels) npc.restoreProfessionSkills(levels);
            if (npc.getHealth() != health) npc.setHealth(health);
            if (!MealLeftovers.counts(npc).equals(bowls)) MealLeftovers.setCounts(npc, bowls);
        }

        boolean sameAs(Snapshot other) {
            if (health != other.health || !levels.equals(other.levels) || !data.equals(other.data)
                    || !bowls.equals(other.bowls)) return false;
            for (int i = 0; i < pack.length; i++) {
                if (!ItemStack.matches(pack[i], other.pack[i])) return false;
            }
            return true;
        }
    }
}
