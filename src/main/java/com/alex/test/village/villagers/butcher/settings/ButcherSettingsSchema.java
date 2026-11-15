package com.alex.test.village.villagers.butcher.settings;

import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.SettingsSchema;
import net.minecraft.world.entity.EntityType;

import java.util.List;

/**
 * Butcher's settings schema, following the generic settings framework (see
 * {@code HOW_TO_ADD_SETTINGS.md} and the reference implementation,
 * {@code ArcherSettingsSchema}).
 *
 * <p>Every row here maps onto a real branch in {@code ButcherActivity}:
 * a work-enabled gate, how much breeding stock to spare per species (the
 * {@code KEEP_ADULTS} threshold), which livestock species are fair game to
 * cull at all, and whether ready-to-shear sheep get their wool salvaged
 * before slaughter.
 */
public final class ButcherSettingsSchema {

    public static final String WORK_ENABLED = "work_enabled";
    public static final String KEEP_ADULTS = "keep_adults";
    public static final String SALVAGE_WOOL = "salvage_wool";
    public static final String ALLOWED_LIVESTOCK = "allowed_livestock";

    public static final SettingsSchema SCHEMA = new SettingsSchema(
            Profession.BUTCHER,
            "ButcherSettings",
            List.of(
                    new SettingsSchema.Section("Behaviour", List.of(
                            SettingsSchema.Row.masterToggle(
                                    WORK_ENABLED, "Working", true)
                                    .describedAs("ON: leads spare animals to the Butchery.\n"
                                            + "OFF: culls nothing."),
                            SettingsSchema.Row.behaviorToggle(
                                    SALVAGE_WOOL, "Collecting Wool", true)
                                    .describedAs("ON: a woolly sheep also gives its\nwool when culled.\n"
                                            + "OFF: only meat."))),
                    new SettingsSchema.Section("Livestock", List.of(
                            SettingsSchema.Row.numericStepper(
                                    KEEP_ADULTS, "Adults to keep of each animal", 2, 2, 6, 1)
                                    .describedAs("Never culls below this many adults\nof each animal, so the rancher can\n"
                                            + "breed more. Babies are never culled."),
                            SettingsSchema.Row.allowList(ALLOWED_LIVESTOCK, "Livestock to cull", livestock())
                                    .describedAs("ON: may cull this animal.\nOFF: leaves it alone.")))));

    private ButcherSettingsSchema() {}

    private static List<SettingsSchema.AllowListEntry> livestock() {
        return List.of(
                new SettingsSchema.AllowListEntry(
                        EntityType.getKey(EntityType.COW).toString(), "Cow", true),
                new SettingsSchema.AllowListEntry(
                        EntityType.getKey(EntityType.SHEEP).toString(), "Sheep", true),
                new SettingsSchema.AllowListEntry(
                        EntityType.getKey(EntityType.PIG).toString(), "Pig", true),
                new SettingsSchema.AllowListEntry(
                        EntityType.getKey(EntityType.CHICKEN).toString(), "Chicken", true));
    }
}
