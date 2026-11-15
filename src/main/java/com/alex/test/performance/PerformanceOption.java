package com.alex.test.performance;

import com.alex.test.Config;
import com.alex.test.Tr;
import com.alex.test.village.farawake.FarAwake;
import com.alex.test.village.farawake.FarCost;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The world-wide performance choices on the Mayor's Performance page (plan:
 * docs/plans/2026-10-19-performance-panel.md). A new option is one more entry here: the page,
 * the messages and the save file show whatever this list holds.
 *
 * <p>Text is read with {@link Tr#s}, so {@link #title}, {@link #description} and {@link #hover}
 * belong on the client; the server only uses the key, the values and {@link #apply}.
 */
public enum PerformanceOption {

    FAR_AWAKE("far_awake"),
    SMOOTH_FAR("smooth_far");

    private final String key;

    PerformanceOption(String key) {
        this.key = key;
    }

    /** Saved in the world and sent over the network: never rename one. */
    public String key() {
        return key;
    }

    /** The value of a world that never chose. */
    public boolean defaultValue() {
        return switch (this) {
            case FAR_AWAKE -> Config.farVillagersAwake();
            case SMOOTH_FAR -> true;
        };
    }

    /** What the server runs right now (commands and the safety cut-out change it too). */
    public boolean live() {
        return switch (this) {
            case FAR_AWAKE -> FarAwake.awake;
            case SMOOTH_FAR -> FarAwake.sync;
        };
    }

    /** Takes effect at once, no restart. Server thread. */
    public void apply(boolean on) {
        switch (this) {
            case FAR_AWAKE -> FarAwake.setAwake(on);
            case SMOOTH_FAR -> FarAwake.setSync(on);
        }
    }

    /** Where it runs, and the heading it shows under. Every option today is {@link Group#SERVER}. */
    public Group group() {
        return Group.SERVER;
    }

    /**
     * Server: this option's live cost (the last 5 s), or {@code null} when it has no meter. The page shows it only
     * while the option is on.
     */
    public @Nullable PerformanceCost cost(MinecraftServer server) {
        long tick = server.getTickCount();
        return switch (this) {
            case FAR_AWAKE -> FarCost.work(tick);
            case SMOOTH_FAR -> FarCost.sends(tick);
        };
    }

    /** Greyed out on the page while this one is off. */
    public @Nullable PerformanceOption needs() {
        return this == SMOOTH_FAR ? FAR_AWAKE : null;
    }

    /** Switched off by a safety cut-out (not by a player); the page says so. */
    public boolean stopped() {
        return switch (this) {
            case FAR_AWAKE -> FarAwake.stoppedAfterErrors;
            case SMOOTH_FAR -> FarAwake.syncStoppedAfterErrors;
        };
    }

    public Item icon() {
        return switch (this) {
            case FAR_AWAKE -> Items.SPYGLASS;
            case SMOOTH_FAR -> Items.FEATHER;
        };
    }

    public String title() {
        return switch (this) {
            case FAR_AWAKE -> Tr.s("performance.dynamicvillages.far_awake.title", "Far villagers stay awake");
            case SMOOTH_FAR -> Tr.s("performance.dynamicvillages.smooth_far.title", "Smooth far movement");
        };
    }

    /** The short line under the title. */
    public String description(boolean on) {
        return switch (this) {
            case FAR_AWAKE -> on
                    ? Tr.s("performance.dynamicvillages.far_awake.on", "Far villagers keep working")
                    : Tr.s("performance.dynamicvillages.far_awake.off", "Far villagers freeze, like plain Minecraft");
            case SMOOTH_FAR -> on
                    ? Tr.s("performance.dynamicvillages.smooth_far.on", "Far villagers walk smoothly")
                    : Tr.s("performance.dynamicvillages.smooth_far.off", "Far villagers may jump around");
        };
    }

    public List<Component> hover() {
        return switch (this) {
            case FAR_AWAKE -> Tr.lines("performance.dynamicvillages.far_awake.tip",
                    "Villagers far from every player keep\nworking, eating and sleeping.\n"
                            + "Off: they wait until a player comes\nback. This saves server time.");
            case SMOOTH_FAR -> Tr.lines("performance.dynamicvillages.smooth_far.tip",
                    "Far villagers you can still see\nwalk smoothly instead of jumping.\nCosts a little server time.");
        };
    }

    /** This group's options, in list order. */
    public static List<PerformanceOption> of(Group group) {
        List<PerformanceOption> found = new java.util.ArrayList<>();
        for (PerformanceOption option : values()) {
            if (option.group() == group) found.add(option);
        }
        return found;
    }

    public static @Nullable PerformanceOption byKey(String key) {
        for (PerformanceOption option : values()) {
            if (option.key.equals(key)) return option;
        }
        return null;
    }

    /**
     * The headings on the page. {@link #SERVER}: runs on the server, one choice for the whole world, saved with it
     * ({@link PerformanceSettings}), server owner only. {@link #CLIENT}: no option here uses it (an option in it would
     * need storage on the player's computer; the server messages and the save file must skip it). The tab's This
     * computer side draws its own rows from {@code ClientConfig} instead ("Villager limbs", 10-22).
     */
    public enum Group {
        SERVER,
        CLIENT;

        public String title() {
            return switch (this) {
                case SERVER -> Tr.s("performance.dynamicvillages.group.server", "Server");
                case CLIENT -> Tr.s("performance.dynamicvillages.group.client", "This computer");
            };
        }
    }
}
