package com.alex.test.village.villagers.builder.settings;

import com.alex.test.village.building.ArcheryRangeBuilding;
import com.alex.test.village.building.WatchtowerBuilding;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ForgeBuilding;
import com.alex.test.village.building.HouseBuilding;
import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.building.LibraryBuilding;
import com.alex.test.village.building.MerchantStallBuilding;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.building.SchoolBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.TavernBuilding;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.SettingsSchema;

import java.util.List;

/**
 * Builder's settings schema.
 *
 * <p>The builder has no equipment (it works bare-handed, see
 * {@code BuilderAssignmentHandler}), so there is no equipment-tier row.
 * Its one real "which of several options" decision is which building types
 * are worth its inspection sweep, hence {@link #ALLOWED_BUILDING_TYPES}
 * (entry ids are {@code Building} subclass simple names, matched against
 * {@code candidate.getClass().getSimpleName()} in
 * {@code BuilderActivity.pickLeastRecentlyVisited}).
 */
public final class BuilderSettingsSchema {

    public static final String WORK_ENABLED = "work_enabled";
    public static final String ALLOWED_BUILDING_TYPES = "allowed_building_types";
    public static final String BUILD_ENABLED = "build_enabled";
    public static final String MAKE_MISSING = "make_missing_blocks";
    // "inspect_duration_ticks", "block_time_ticks" and "auto_repair" were rows until 10-25; old saves still hold
    // them, unread. The village re-reads broken buildings on its own, so "auto_repair" only raced it.

    public static final SettingsSchema SCHEMA = new SettingsSchema(
            Profession.BUILDER,
            // Saved key from when this job was the Architect; kept so old villagers keep their settings.
            "ArchitectSettings",
            List.of(
                    new SettingsSchema.Section("Behaviour", List.of(
                            SettingsSchema.Row.masterToggle(
                                    WORK_ENABLED, "Working", true)
                                    .describedAs("ON: walks around the village and checks\nthat each building still counts.\n"
                                            + "OFF: stops checking."),
                            SettingsSchema.Row.behaviorToggle(
                                    BUILD_ENABLED, "Building Orders", true)
                                    .describedAs("ON: builds what you order on the desk,\nusing blocks from the village storage.\n"
                                            + "OFF: orders wait. It only checks buildings."),
                            SettingsSchema.Row.behaviorToggle(
                                    MAKE_MISSING, "Make Missing Blocks", true)
                                    .describedAs("ON: crafts or cuts blocks the order\nis missing, from what is in storage.\n"
                                            + "OFF: waits for the blocks."))),
                    new SettingsSchema.Section("Buildings", List.of(
                            SettingsSchema.Row.allowList(
                                    ALLOWED_BUILDING_TYPES, "Building types to inspect", buildingTypes())
                                    .describedAs("ON: checks this kind of building.\nOFF: skips it.")))));

    private BuilderSettingsSchema() {}

    /**
     * One entry per concrete {@link Building} subclass, all default-allowed.
     * {@code EnclosureBuilding} itself is abstract (see its javadoc) so only
     * its concrete ranch leaf is listed here.
     */
    private static List<SettingsSchema.AllowListEntry> buildingTypes() {
        return List.of(
                new SettingsSchema.AllowListEntry(HouseBuilding.class.getSimpleName(), "House", true),
                new SettingsSchema.AllowListEntry(TownHallBuilding.class.getSimpleName(), "Town Hall", true),
                new SettingsSchema.AllowListEntry(StorageBuilding.class.getSimpleName(), "Storage", true),
                new SettingsSchema.AllowListEntry(RanchBuilding.class.getSimpleName(), "Ranch", true),
                new SettingsSchema.AllowListEntry(KitchenBuilding.class.getSimpleName(), "Kitchen", true),
                new SettingsSchema.AllowListEntry(com.alex.test.village.building.ButcheryBuilding.class.getSimpleName(), "Butchery", true),
                new SettingsSchema.AllowListEntry(ForgeBuilding.class.getSimpleName(), "Forge", true),
                new SettingsSchema.AllowListEntry(MineBuilding.class.getSimpleName(), "Mine", true),
                new SettingsSchema.AllowListEntry(LibraryBuilding.class.getSimpleName(), "Library", true),
                new SettingsSchema.AllowListEntry(SchoolBuilding.class.getSimpleName(), "School", true),
                new SettingsSchema.AllowListEntry(TavernBuilding.class.getSimpleName(), "Tavern", true),
                new SettingsSchema.AllowListEntry(MerchantStallBuilding.class.getSimpleName(), "Merchant Stall", true),
                new SettingsSchema.AllowListEntry(BarracksBuilding.class.getSimpleName(), "Barracks", true),
                new SettingsSchema.AllowListEntry(ArcheryRangeBuilding.class.getSimpleName(), "Archery Range", true),
                new SettingsSchema.AllowListEntry(WatchtowerBuilding.class.getSimpleName(), "Watchtower", true),
                new SettingsSchema.AllowListEntry(
                        com.alex.test.village.building.MageTowerBuilding.class.getSimpleName(), "Mage Tower", true));
    }
}
