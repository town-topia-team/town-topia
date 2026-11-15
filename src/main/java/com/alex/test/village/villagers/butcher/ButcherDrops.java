package com.alex.test.village.villagers.butcher;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.butcher.settings.ButcherSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkill;
import com.alex.test.village.villagers.rancher.RanchAnimals;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.UUID;

/**
 * What a butcher's kill leaves on the floor: the animal's own vanilla loot
 * (Looting on the sword counts, a burning animal drops cooked meat), plus
 * what the butcher's skill adds to it ({@link ButcherBalance}). Every drop is
 * marked for the butcher that made the kill, who gathers it up
 * ({@code ButcherActivity}'s COLLECT).
 */
public final class ButcherDrops {

    private static final String TAG_BUTCHER = "DvButcherDrop";

    private ButcherDrops() {}

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Animal animal) || animal.level().isClientSide()) return;
        // Burned to death between blows: the kill is still the butcher's.
        Entity killer = event.getSource().getEntity() != null
                ? event.getSource().getEntity() : animal.getKillCredit();
        if (!(killer instanceof NpcEntity npc) || npc.getProfession() != Profession.BUTCHER) return;
        Item meat = RanchAnimals.meatFor(animal.getType());
        if (meat == null) return;

        Collection<ItemEntity> drops = event.getDrops();
        if (!ProfessionSettings.of(npc, ButcherSettingsSchema.SCHEMA).getBool(ButcherSettingsSchema.SALVAGE_WOOL)) {
            drops.removeIf(drop -> drop.getItem().is(ItemTags.WOOL));
        }

        int level = levelOf(npc);
        RandomSource random = animal.getRandom();
        // Cooked as the vanilla table cooks it: the animal died burning.
        add(drops, animal, animal.isOnFire() ? cooked(meat) : meat, extraMeat(level, random));
        Item hide = hideFor(animal.getType());
        if (hide != null) add(drops, animal, hide, extraHide(level, count(drops, hide), random));

        for (ItemEntity drop : drops) drop.getPersistentData().putUUID(TAG_BUTCHER, npc.getUUID());
    }

    /** Meat the skill adds to one kill: the extra-meat roll and Master Butcher's Looting level. */
    public static int extraMeat(int level, RandomSource random) {
        int extra = looted(random, ButcherBalance.bonusLooting(level));
        if (random.nextDouble() < ButcherBalance.extraMeatChance(level)) {
            extra += 1 + random.nextInt(ButcherBalance.extraMeatMax(level));
        }
        return extra;
    }

    /** Leather or feathers the skill adds when the animal dropped {@code dropped}: Skinner's floor, the extra roll, Looting. */
    public static int extraHide(int level, int dropped, RandomSource random) {
        int extra = Math.max(0, ButcherBalance.hideFloor(level) - dropped);
        if (random.nextDouble() < ButcherBalance.extraHideChance(level)) extra++;
        return extra + looted(random, ButcherBalance.bonusLooting(level));
    }

    /** Whether {@code item} fell from a kill by the butcher {@code butcher}. */
    public static boolean isFor(ItemEntity item, UUID butcher) {
        var data = item.getPersistentData();
        return data.hasUUID(TAG_BUTCHER) && butcher.equals(data.getUUID(TAG_BUTCHER));
    }

    /** The butcher's level on its 1..100 track, as its profession slot shows it. */
    public static int levelOf(NpcEntity npc) {
        return npc.getProfessionSkills().stream()
                .filter(slot -> slot.profession() == Profession.BUTCHER)
                .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
    }

    /** The second thing a cow or chicken drops (leather, feathers); null for the rest. */
    @Nullable
    public static Item hideFor(EntityType<?> type) {
        if (type == EntityType.COW) return Items.LEATHER;
        if (type == EntityType.CHICKEN) return Items.FEATHER;
        return null;
    }

    private static Item cooked(Item meat) {
        if (meat == Items.BEEF) return Items.COOKED_BEEF;
        if (meat == Items.PORKCHOP) return Items.COOKED_PORKCHOP;
        if (meat == Items.MUTTON) return Items.COOKED_MUTTON;
        if (meat == Items.CHICKEN) return Items.COOKED_CHICKEN;
        return meat;
    }

    /** Vanilla Looting: 0 to 1 more per level. */
    private static int looted(RandomSource random, int levels) {
        int extra = 0;
        for (int i = 0; i < levels; i++) extra += random.nextInt(2);
        return extra;
    }

    private static int count(Collection<ItemEntity> drops, Item item) {
        int count = 0;
        for (ItemEntity drop : drops) {
            if (drop.getItem().is(item)) count += drop.getItem().getCount();
        }
        return count;
    }

    private static void add(Collection<ItemEntity> drops, Animal animal, Item item, int count) {
        if (count <= 0) return;
        ItemEntity drop = new ItemEntity(animal.level(), animal.getX(), animal.getY(), animal.getZ(),
                new ItemStack(item, count));
        drop.setDefaultPickUpDelay();
        drops.add(drop);
    }
}
