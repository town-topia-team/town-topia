package com.alex.test.village.villagers.rancher.settings;

import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.rancher.RanchAnimals;
import com.alex.test.village.villagers.settings.SettingsSchema;
import net.minecraft.world.entity.EntityType;

import java.util.List;

/**
 * Rancher's settings schema, following the generic settings framework (see
 * {@code HOW_TO_ADD_SETTINGS.md} and the reference implementation,
 * {@code ArcherSettingsSchema}).
 *
 * <p>The behavior toggles mirror the forks in
 * {@code RancherActivity.chooseNext()}: shear/milk/egg/shed are all
 * no-consumable "product collection" jobs, feeding a breeding pair is a
 * separate, food-consuming decision, and rounding up leads loose animals
 * from around the village into the pen ({@code RanchRoundUp}). The allow-list reuses
 * {@link RanchAnimals}'s own managed-species list (cow/sheep/pig/chicken --
 * anything {@code breedFoodFor} recognizes) rather than inventing a second
 * one, the same way {@code ArcherSettingsSchema} reuses
 * {@code GuardMobPolicy}'s list instead of a fresh one.
 */
public final class RancherSettingsSchema {

    public static final String WORK_ENABLED = "work_enabled";
    public static final String BREEDING_ENABLED = "breeding_enabled";
    public static final String PRODUCT_COLLECTION_ENABLED = "product_collection_enabled";
    public static final String ALLOWED_ANIMAL_TYPES = "allowed_animal_types";
    public static final String ROUNDUP_ENABLED = "roundup_enabled";
    public static final String ROUNDUP_RANGE = "roundup_range";

    public static final SettingsSchema SCHEMA = new SettingsSchema(
            Profession.RANCHER,
            "RancherSettings",
            List.of(
                    new SettingsSchema.Section("Behaviour", List.of(
                            SettingsSchema.Row.masterToggle(
                                    WORK_ENABLED, "Working", true)
                                    .describedAs("ON: looks after the ranch animals.\n"
                                            + "OFF: stops all ranch work."),
                            SettingsSchema.Row.behaviorToggle(
                                    PRODUCT_COLLECTION_ENABLED, "Collecting Goods", true)
                                    .describedAs("ON: shears sheep, milks cows,\ngathers eggs and combs leather\n"
                                            + "off cows.\nOFF: skips all of that."),
                            SettingsSchema.Row.behaviorToggle(
                                    BREEDING_ENABLED, "Breeding", true)
                                    .describedAs("ON: feeds pairs so they have\nbabies.\n"
                                            + "OFF: the herd stops growing."),
                            SettingsSchema.Row.behaviorToggle(
                                    ROUNDUP_ENABLED, "Rounding Up", true)
                                    .describedAs("ON: leads loose animals near\nthe pen into it while there\nis room.\n"
                                            + "OFF: only brings back animals\nthat got out of the pen."))),
                    new SettingsSchema.Section("Animals", List.of(
                            SettingsSchema.Row.numericStepper(
                                    ROUNDUP_RANGE, "Round-up range", 32, 16, 64, 8)
                                    .describedAs("How far from the pen gate,\nin blocks, it looks for loose\nanimals to bring in."),
                            SettingsSchema.Row.allowList(ALLOWED_ANIMAL_TYPES, "Managed animals", animalTypes())
                                    .describedAs("ON: looks after this animal.\n"
                                            + "OFF: ignores it, including\nbreeding and fetching.")))));

    private RancherSettingsSchema() {}

    /**
     * Every species {@link RanchAnimals#breedFoodFor} recognizes -- the
     * complete set of animals a Rancher is ever coded to tend. Sourced from
     * the same authoritative list rather than duplicated here so a future
     * species added to {@code RanchAnimals} doesn't silently go unlisted.
     */
    private static List<SettingsSchema.AllowListEntry> animalTypes() {
        return List.of(
                entry(EntityType.COW, "Cows"),
                entry(EntityType.SHEEP, "Sheep"),
                entry(EntityType.PIG, "Pigs"),
                entry(EntityType.CHICKEN, "Chickens"));
    }

    private static SettingsSchema.AllowListEntry entry(EntityType<?> type, String label) {
        return new SettingsSchema.AllowListEntry(EntityType.getKey(type).toString(), label, true);
    }
}
