package com.alex.test.village.log;

import com.alex.test.village.VillageManager;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What happened in one village, newest last, the last {@link #MAX_ENTRIES} kept. Shown on the Mayor's
 * Village Log tab. Saved with the village inside {@link com.alex.test.village.VillageRules} (like the raid
 * state). Lines are text keys with their words, so each player reads them in their own language.
 */
public final class VillageLog {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int MAX_ENTRIES = 200;

    /**
     * What an entry is about: its icon and its filter on the Village Log tab. Saved by name: never rename one.
     * BIRTH, GREW_UP, JOINED, TAKEN (carried off by kidnappers) and JOB (a job token) are villagers coming and going;
     * RAID (began), RAID_WON, RAID_LOST, RAID_LEFT (attacked, then left with no result); BUILDING (registered,
     * works again), BUILDING_LOST (stopped working, unreachable, removed) and TOWN_HALL (founded, paused, back).
     */
    public enum Kind {
        DEATH, BIRTH, GREW_UP, JOINED, TAKEN, JOB,
        RAID, RAID_WON, RAID_LOST, RAID_LEFT, BUILDING, BUILDING_LOST, TOWN_HALL
    }

    /**
     * One thing that happened. {@code dayTime} is the world clock then (day = dayTime / 24000 + 1);
     * {@code detail} is the hover's extra line (for a death, the game's own death line), or null.
     */
    public record Entry(long dayTime, Kind kind, Component line, @Nullable Component detail, BlockPos pos) {}

    private static final String TAG_ENTRIES = "entries";
    private static final String TAG_DAY_TIME = "t";
    private static final String TAG_KIND = "k";
    private static final String TAG_LINE = "l";
    private static final String TAG_DETAIL = "d";
    private static final String TAG_POS = "p";

    /** One counter for every log on the server, so a version number never means two different lists. */
    private static final AtomicLong CHANGES = new AtomicLong();

    private final List<Entry> entries = new ArrayList<>();
    /**
     * Changes with every new entry (and every load); the Mayor window only gets the list again when it moved.
     * Never 0, even for a log never loaded: 0 is the answer for a Mayor with no village.
     */
    private long version = CHANGES.incrementAndGet();

    /** Adds a line to the village's log and marks the village for saving. */
    public static void record(VillageManager manager, ServerLevel level, Kind kind, Component line,
                              @Nullable Component detail, BlockPos pos) {
        // The old built-in village is never saved and lives on from one world to the next:
        // its log would show another world's deaths.
        if (manager == VillageManager.INSTANCE) return;
        manager.rules().log().add(new Entry(level.getDayTime(), kind, plain(line),
                detail == null ? null : plain(detail), pos.immutable()));
        manager.markPersistentDataDirty();
    }

    public void add(Entry entry) {
        entries.add(entry);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
        version = CHANGES.incrementAndGet();
    }

    public long version() {
        return version;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    // ---------------------------------------------------------------------
    // Saving (also how the list travels to the Mayor window)
    // ---------------------------------------------------------------------

    public CompoundTag save() {
        ListTag list = new ListTag();
        for (Entry entry : entries) {
            CompoundTag tag = saveEntry(entry);
            if (tag != null) list.add(tag);
        }
        CompoundTag out = new CompoundTag();
        out.put(TAG_ENTRIES, list);
        return out;
    }

    /** Reads a saved log; a missing one (an older save) is empty, a damaged entry is skipped. */
    public void load(@Nullable CompoundTag tag) {
        entries.clear();
        entries.addAll(readEntries(tag));
        version = CHANGES.incrementAndGet();
    }

    /** The entries of a saved (or sent) log, oldest first. Never throws. */
    public static List<Entry> readEntries(@Nullable CompoundTag tag) {
        List<Entry> out = new ArrayList<>();
        if (tag == null || !tag.contains(TAG_ENTRIES, Tag.TAG_LIST)) return out;
        ListTag list = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Entry entry = loadEntry(list.getCompound(i));
            if (entry != null) out.add(entry);
        }
        while (out.size() > MAX_ENTRIES) out.remove(0);
        return out;
    }

    @Nullable
    private static CompoundTag saveEntry(Entry entry) {
        Optional<Tag> line = encode(entry.line());
        if (line.isEmpty()) return null;
        CompoundTag tag = new CompoundTag();
        tag.putLong(TAG_DAY_TIME, entry.dayTime());
        tag.putString(TAG_KIND, entry.kind().name());
        tag.put(TAG_LINE, line.get());
        if (entry.detail() != null) encode(entry.detail()).ifPresent(detail -> tag.put(TAG_DETAIL, detail));
        tag.putLong(TAG_POS, entry.pos().asLong());
        return tag;
    }

    @Nullable
    private static Entry loadEntry(CompoundTag tag) {
        try {
            // A kind from a newer version of the mod: left out rather than shown wrong.
            Kind kind = Kind.valueOf(tag.getString(TAG_KIND));
            Component line = decode(tag.get(TAG_LINE));
            if (line == null) return null;
            return new Entry(tag.getLong(TAG_DAY_TIME), kind, line, decode(tag.get(TAG_DETAIL)),
                    BlockPos.of(tag.getLong(TAG_POS)));
        } catch (RuntimeException damaged) {
            return null;
        }
    }

    private static Optional<Tag> encode(Component component) {
        try {
            DataResult<Tag> result = ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, component);
            result.error().ifPresent(error -> LOGGER.warn("[VillageLog] Could not save a line: {}", error.message()));
            return result.result();
        } catch (RuntimeException failed) {
            LOGGER.warn("[VillageLog] Could not save a line: {}", failed.toString());
            return Optional.empty();
        }
    }

    @Nullable
    private static Component decode(@Nullable Tag tag) {
        if (tag == null) return null;
        try {
            return ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(null);
        } catch (RuntimeException failed) {
            return null;
        }
    }

    /**
     * The same words without colours, hover or click: entity names carry a hover with the entity, and an
     * item's name the whole item, which would need the world's registries to save. Translatable parts stay
     * translatable; anything else becomes its plain text.
     */
    static MutableComponent plain(Component component) {
        MutableComponent out;
        if (component.getContents() instanceof TranslatableContents translatable) {
            Object[] args = translatable.getArgs();
            Object[] plainArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                plainArgs[i] = args[i] instanceof Component part ? plain(part) : Component.literal(String.valueOf(args[i]));
            }
            out = Component.translatableWithFallback(translatable.getKey(), translatable.getFallback(), plainArgs);
        } else if (component.getContents() instanceof PlainTextContents text) {
            out = Component.literal(text.text());
        } else {
            StringBuilder text = new StringBuilder();
            component.getContents().visit(part -> {
                text.append(part);
                return Optional.empty();
            });
            out = Component.literal(text.toString());
        }
        for (Component sibling : component.getSiblings()) out.append(plain(sibling));
        return out;
    }
}
