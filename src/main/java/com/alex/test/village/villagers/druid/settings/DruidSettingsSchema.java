package com.alex.test.village.villagers.druid.settings;

import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.SettingsSchema;

import java.util.List;

/**
 * Druid's settings schema, following {@code ArcherSettingsSchema}'s shape
 * (see {@code HOW_TO_ADD_SETTINGS.md} for the framework contract).
 *
 * <p>A master toggle and one toggle per duty -- crops, saplings, mines -- so
 * the player can have a druid that, say, only tends the fields. The druid
 * no longer plants trees of its own, so the old sapling-type list is gone;
 * a saved value for it, or for the old planting toggle, is simply never read.
 */
public final class DruidSettingsSchema {

    public static final String WORK_ENABLED = "work_enabled";
    public static final String TEND_CROPS_ENABLED = "tend_crops_enabled";
    public static final String GROW_SAPLINGS_ENABLED = "grow_saplings_enabled";
    public static final String REGROW_MINES_ENABLED = "regrow_mines_enabled";

    public static final SettingsSchema SCHEMA = new SettingsSchema(
            Profession.DRUID,
            "DruidSettings",
            List.of(
                    new SettingsSchema.Section("Behaviour", List.of(
                            SettingsSchema.Row.masterToggle(
                                    WORK_ENABLED, "Working", true)
                                    .describedAs("ON: uses nature magic around\nthe village.\n"
                                            + "OFF: does none of it."),
                            SettingsSchema.Row.behaviorToggle(
                                    TEND_CROPS_ENABLED, "Crop Growing", true)
                                    .describedAs("ON: makes farm crops grow faster.\n"
                                            + "OFF: leaves crops alone."),
                            SettingsSchema.Row.behaviorToggle(
                                    GROW_SAPLINGS_ENABLED, "Tree Growing", true)
                                    .describedAs("ON: turns saplings into full trees\nfor the lumberjack.\n"
                                            + "OFF: saplings grow on their own."),
                            SettingsSchema.Row.behaviorToggle(
                                    REGROW_MINES_ENABLED, "Mine Refilling", true)
                                    .describedAs("ON: turns dug out mine tunnels back\ninto rock, so the miner can dig\n"
                                            + "them again.")))));

    private DruidSettingsSchema() {}
}
