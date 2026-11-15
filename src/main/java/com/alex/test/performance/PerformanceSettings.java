package com.alex.test.performance;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The world's performance choices ({@link PerformanceOption}), saved with the world on the
 * overworld. Only options someone chose are stored; a missing one is its default, so a world
 * from before this file still follows the config. Unknown keys are kept, so an older build
 * never drops a newer build's choices.
 */
public final class PerformanceSettings extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "dynamicvillages_performance";
    private static final SavedData.Factory<PerformanceSettings> FACTORY =
            new SavedData.Factory<>(PerformanceSettings::new, PerformanceSettings::load, null);

    /** Option key -> on. */
    private CompoundTag options = new CompoundTag();

    public static PerformanceSettings get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** The saved choice, else the default. Not the live value: see {@link PerformanceOption#live}. */
    public boolean value(PerformanceOption option) {
        return saved(option) ? options.getBoolean(option.key()) : option.defaultValue();
    }

    public boolean saved(PerformanceOption option) {
        return options.contains(option.key(), Tag.TAG_BYTE);
    }

    /** Saves the choice; a choice equal to the default is kept too, so a later config change leaves it. */
    public void set(PerformanceOption option, boolean on) {
        if (saved(option) && options.getBoolean(option.key()) == on) return;
        options.putBoolean(option.key(), on);
        setDirty();
    }

    /**
     * Server start, after {@code FarAwake} has reset its switches: each saved choice wins over the
     * config; an option never chosen keeps what the reset gave it. Never throws.
     */
    public static void applyAtStart(MinecraftServer server) {
        try {
            PerformanceSettings settings = get(server);
            List<String> applied = new ArrayList<>();
            for (PerformanceOption option : PerformanceOption.of(PerformanceOption.Group.SERVER)) {
                if (!settings.saved(option)) continue;
                boolean on = settings.options.getBoolean(option.key());
                option.apply(on);
                applied.add(option.key() + " " + (on ? "on" : "off"));
            }
            if (!applied.isEmpty()) LOGGER.info("[Performance] this world's saved choices: {}", String.join(", ", applied));
        } catch (Exception e) {
            LOGGER.error("[Performance] could not read this world's saved choices; the config values stay.", e);
        }
    }

    /** Operators (level 2) and the single-player host, cheats on or off. */
    public static boolean mayChange(ServerPlayer player) {
        return player.hasPermissions(2) || player.server.isSingleplayerOwner(player.getGameProfile());
    }

    /** For {@code /villager farawake}: saved choices told apart from live switches. */
    public static String describe(MinecraftServer server) {
        PerformanceSettings settings = get(server);
        StringBuilder line = new StringBuilder("Saved for this world: ");
        List<PerformanceOption> all = PerformanceOption.of(PerformanceOption.Group.SERVER);
        for (int i = 0; i < all.size(); i++) {
            PerformanceOption option = all.get(i);
            if (i > 0) line.append("; ");
            line.append(option.key()).append(' ');
            if (settings.saved(option)) line.append(settings.value(option) ? "on" : "off");
            else line.append("not chosen (default ").append(option.defaultValue() ? "on" : "off").append(')');
        }
        return line.toString();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("options", options.copy());
        return tag;
    }

    private static PerformanceSettings load(CompoundTag tag, HolderLookup.Provider registries) {
        PerformanceSettings settings = new PerformanceSettings();
        settings.options = tag.getCompound("options").copy();
        return settings;
    }
}
