package com.alex.test.village.villagers.settings;

import com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema;
import com.alex.test.village.villagers.builder.settings.BuilderSettingsSchema;
import com.alex.test.village.villagers.bard.settings.BardSettingsSchema;
import com.alex.test.village.villagers.blacksmith.settings.BlacksmithSettingsSchema;
import com.alex.test.village.villagers.butcher.settings.ButcherSettingsSchema;
import com.alex.test.village.villagers.cleric.settings.ClericSettingsSchema;
import com.alex.test.village.villagers.cook.settings.CookSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.druid.settings.DruidSettingsSchema;
import com.alex.test.village.villagers.enchanter.settings.EnchanterSettingsSchema;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import com.alex.test.village.villagers.rancher.settings.RancherSettingsSchema;
import com.alex.test.village.villagers.teacher.settings.TeacherSettingsSchema;
import com.alex.test.village.villagers.tradesman.settings.TradesmanSettingsSchema;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Lookup of which {@link SettingsSchema} (if any) a profession has. Backed by
 * a plain static map so wiring in a new profession is a single call to
 * {@link #register} — see {@code HOW_TO_ADD_SETTINGS.md} in this package.
 *
 * <p>This class is not sided: the same registrations run on client and
 * server, which is what lets {@code GenericSettingsMenu} rebuild an identical
 * schema on both ends from just a {@link Profession} id on the wire.
 */
public final class SettingsSchemaRegistry {

    private static final Map<Profession, SettingsSchema> SCHEMAS = new EnumMap<>(Profession.class);

    static {
        register(ArcherSettingsSchema.SCHEMA);
        register(BuilderSettingsSchema.SCHEMA);
        register(BardSettingsSchema.SCHEMA);
        register(BlacksmithSettingsSchema.SCHEMA);
        register(ButcherSettingsSchema.SCHEMA);
        register(ClericSettingsSchema.SCHEMA);
        register(CookSettingsSchema.SCHEMA);
        register(DruidSettingsSchema.SCHEMA);
        register(EnchanterSettingsSchema.SCHEMA);
        register(com.alex.test.village.villagers.mage.settings.MageSettingsSchema.SCHEMA);
        register(MinerSettingsSchema.SCHEMA);
        register(RancherSettingsSchema.SCHEMA);
        register(TeacherSettingsSchema.SCHEMA);
        register(TradesmanSettingsSchema.SCHEMA);
        // One profession per line: register(FooSettingsSchema.SCHEMA);
    }

    private SettingsSchemaRegistry() {}

    public static void register(SettingsSchema schema) {
        SCHEMAS.put(schema.profession(), schema);
    }

    public static Optional<SettingsSchema> forProfession(Profession profession) {
        return Optional.ofNullable(SCHEMAS.get(profession));
    }

    /** Every registered schema, in profession order (for the lang data run). */
    public static java.util.Collection<SettingsSchema> all() {
        return java.util.List.copyOf(SCHEMAS.values());
    }
}
