package com.alex.test.village.villagers.settings;

import com.alex.test.entity.NpcEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Optional;

/**
 * Generic, stateless NBT accessor for a profession's settings, mirroring
 * {@code GuardSettings}'s pattern but driven entirely by a {@link SettingsSchema}
 * instead of hand-written getters/setters.
 *
 * <p>Every read falls back to the schema's declared default whenever the NBT
 * key is absent, so a villager a settings screen was never opened for behaves
 * exactly as if using hardcoded defaults.
 */
public final class ProfessionSettings {

    private final NpcEntity villager;
    private final SettingsSchema schema;

    private ProfessionSettings(NpcEntity villager, SettingsSchema schema) {
        this.villager = villager;
        this.schema = schema;
    }

    public static ProfessionSettings of(NpcEntity villager, SettingsSchema schema) {
        return new ProfessionSettings(villager, schema);
    }

    public SettingsSchema schema() {
        return schema;
    }

    // =========================================================================
    // Booleans (MASTER_TOGGLE / BEHAVIOR_TOGGLE)
    // =========================================================================

    public boolean getBool(String settingId) {
        CompoundTag tag = readTag();
        if (tag.contains(settingId)) return tag.getBoolean(settingId);
        return schema.findRow(settingId).map(SettingsSchema.Row::defaultBool).orElse(false);
    }

    public void setBool(String settingId, boolean value) {
        writeTag().putBoolean(settingId, value);
    }

    // =========================================================================
    // Integers (NUMERIC_STEPPER)
    // =========================================================================

    public int getInt(String settingId) {
        CompoundTag tag = readTag();
        int value = tag.contains(settingId)
                ? tag.getInt(settingId)
                : schema.findRow(settingId).map(SettingsSchema.Row::defaultInt).orElse(0);
        return clamp(settingId, value);
    }

    public void setInt(String settingId, int value) {
        writeTag().putInt(settingId, clamp(settingId, value));
    }

    private int clamp(String settingId, int value) {
        Optional<SettingsSchema.Row> row = schema.findRow(settingId);
        if (row.isEmpty() || (row.get().kind() != SettingsSchema.RowKind.NUMERIC_STEPPER
                && row.get().kind() != SettingsSchema.RowKind.CHOICE)) return value;
        return Math.max(row.get().minInt(), Math.min(row.get().maxInt(), value));
    }

    // =========================================================================
    // Allow-lists (TIER_TOGGLES / ALLOW_LIST)
    // =========================================================================

    public boolean isAllowed(String settingId, String entryId) {
        CompoundTag tag = readTag();
        if (tag.contains(settingId, Tag.TAG_COMPOUND)) {
            CompoundTag entries = tag.getCompound(settingId);
            if (entries.contains(entryId)) return entries.getBoolean(entryId);
        }
        return defaultAllowed(settingId, entryId);
    }

    /** Switching off the last entry still on in a {@code keepingOne} row is refused (nothing changes). */
    public void setAllowed(String settingId, String entryId, boolean allowed) {
        if (!allowed && isLastOneOn(settingId, entryId)) return;
        CompoundTag tag = writeTag();
        CompoundTag entries = tag.contains(settingId, Tag.TAG_COMPOUND)
                ? tag.getCompound(settingId)
                : new CompoundTag();
        entries.putBoolean(entryId, allowed);
        tag.put(settingId, entries);
    }

    private boolean isLastOneOn(String settingId, String entryId) {
        Optional<SettingsSchema.Row> row = schema.findRow(settingId);
        if (row.isEmpty() || !row.get().keepsOne() || !isAllowed(settingId, entryId)) return false;
        for (String other : row.get().entryIds()) {
            if (!other.equals(entryId) && isAllowed(settingId, other)) return false;
        }
        return true;
    }

    private boolean defaultAllowed(String settingId, String entryId) {
        Optional<SettingsSchema.Row> maybeRow = schema.findRow(settingId);
        if (maybeRow.isEmpty()) return true;
        SettingsSchema.Row row = maybeRow.get();
        if (row.kind() == SettingsSchema.RowKind.ALLOW_LIST) {
            for (SettingsSchema.AllowListEntry entry : row.allowListEntries()) {
                if (entry.id().equals(entryId)) return entry.defaultAllowed();
            }
            return true;
        }
        // TIER_TOGGLES entries carry no per-entry default -- every tier starts
        // allowed, mirroring GuardSettings.SwordTier's all-true default.
        return true;
    }

    // =========================================================================
    // Storage
    // =========================================================================

    private CompoundTag readTag() {
        return villager.getBrainData().getCompound(schema.tagName());
    }

    private CompoundTag writeTag() {
        CompoundTag brainData = villager.getBrainData();
        if (!brainData.contains(schema.tagName(), Tag.TAG_COMPOUND)) {
            brainData.put(schema.tagName(), new CompoundTag());
        }
        return brainData.getCompound(schema.tagName());
    }
}
