package com.alex.test.village.villagers.teacher.settings;

import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.SettingsSchema;

import java.util.List;

/**
 * Teacher's settings schema. See {@code HOW_TO_ADD_SETTINGS.md} in the
 * settings package for the worked walkthrough this class follows.
 *
 * <p>Teaching has no material inputs and no equipment/target allow-lists the
 * way combat or gathering professions do, so this schema stays small: a
 * master work toggle, the parts of the school day that can be switched off,
 * how lessons choose their subject, and how patient a teacher is with an
 * empty class. Only children learn at school. See {@code TeacherActivity}
 * for where each is read.
 */
public final class TeacherSettingsSchema {

    public static final String WORK_ENABLED = "work_enabled";
    public static final String RECESS = "recess";
    public static final String FIELD_TRIPS = "field_trips";
    public static final String VILLAGE_NEEDS = "village_needs";
    public static final String IDLE_TOLERANCE_UNITS = "idle_tolerance_units";

    public static final SettingsSchema SCHEMA = new SettingsSchema(
            Profession.TEACHER,
            "TeacherSettings",
            List.of(
                    new SettingsSchema.Section("Behaviour", List.of(
                            SettingsSchema.Row.masterToggle(
                                    WORK_ENABLED, "Working", true)
                                    .describedAs("ON: teaches the village children\nat the school.\n"
                                            + "OFF: teaches no one, and the\nchildren play instead."),
                            SettingsSchema.Row.behaviorToggle(
                                    RECESS, "Recess", true)
                                    .describedAs("ON: the children play in the yard\nfrom 10:00 to 10:45.\n"
                                            + "OFF: lessons run straight on\nuntil lunch."),
                            SettingsSchema.Row.behaviorToggle(
                                    FIELD_TRIPS, "Field Trips", true)
                                    .describedAs("ON: once a week the class walks\nout to watch a job: a workplace,\n"
                                            + "or a worker in the fields.\nOFF: always taught at school."),
                            SettingsSchema.Row.behaviorToggle(
                                    VILLAGE_NEEDS, "Teaching What's Needed", true)
                                    .describedAs("ON: jobs nobody does yet come up\nin lessons and field trips\nmore often.\n"
                                            + "OFF: every job comes up as often."),
                            SettingsSchema.Row.numericStepper(
                                    IDLE_TOLERANCE_UNITS, "Wait for pupils",
                                    3, 1, 10, 1)
                                    .describedAs("How long it keeps an empty\nclass open once the children\n"
                                            + "have gone. Each step is 5 seconds.")))));

    private TeacherSettingsSchema() {}
}
