package com.alex.test.client;

import com.alex.test.ClientConfig;
import com.alex.test.DynamicVillages;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which set of villager skins this computer draws, chosen by the player like a resource pack
 * ({@link ClientConfig#villagerTheme}, saved in the client config file, never in a world).
 *
 * <p>Every set keeps today's file names in its own folder: CLASSIC is {@code textures/entity/} (shown as
 * "Victorian English" since 10-23: the farmer, guard, archer, lumberjack, Mayor and nomad files are Victorian),
 * LONDON is {@code textures/entity/london/} ("Victorian London", 10-24: every job in sooty foggy-London dress, each
 * skin with its own 3D pieces in {@code geo/london/<skin>.json}), MODERN is {@code textures/entity/modern/} (the
 * hand-painted farmer, guard, archer, lumberjack, Mayor and nomad skins, 10-23), RUSTIC is
 * {@code textures/entity/rustic/} (every job but the bard, 10-24, pieces in {@code geo/rustic/}). A skin a set has
 * not painted yet falls back to
 * today's file, so a half-painted set never shows the purple-black missing texture. The order here is the
 * Theme tab's card order; settings name a set, never its number.
 *
 * <p>Covers the skins {@link VillagerGeoModel} picks (villagers, the Mayor, merchants, nomads) and
 * the farmers' dirt stages ({@link FarmerDirtTextures}). Not capes, guard armour, instruments,
 * held items or raiders: those keep their files in every set.
 *
 * <p>The player can change single skins of a set ({@link ClientConfig#skinChanges}): while that set is in use, a
 * skin can be drawn from another set instead (left out: from Classic; brought in: from the set it was liked in).
 * Each skin keeps those changes as an array by set in use, so drawing stays an array read.
 */
public enum VillagerTheme {
    CLASSIC("textures/entity/"),
    LONDON("textures/entity/london/"),
    MODERN("textures/entity/modern/"),
    RUSTIC("textures/entity/rustic/");

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Today's folder, the one every other set mirrors. */
    private static final String CLASSIC_FOLDER = "textures/entity/";
    /** Every skin file of ours starts with this; a skin's name in the player's changes leaves it out. */
    private static final String FILE_PREFIX = "dynamic_villagers_";
    private static final VillagerTheme[] VALUES = values();
    /** A skin with no changes: shared by all of them, never written to. */
    private static final VillagerTheme[] NO_CHANGES = new VillagerTheme[VALUES.length];
    /**
     * The player's changes, read from the config: per skin name, per set in use, the set its picture comes from
     * (null: the set itself). Replaced whole at every {@link #refresh}.
     */
    private static volatile Map<String, VillagerTheme[]> changes = Map.of();
    /** Bumped at every {@link #refresh}, so screens know the set in use or the changes may have changed. */
    private static volatile int changesVersion;

    /** The set in use; Modern (the default, 10-25) until the config is read. A plain field: villagers read it every frame. */
    private static volatile VillagerTheme current = MODERN;
    /** Per set: how many of today's skin files it has its own copy of; null until counted after a reload. Render thread only. */
    private static int[] paintedBySet;
    private static int skinFiles;
    /** Bumped at every resource reload, so screens know the painted files may have changed. */
    private static int filesVersion;
    /** Set only while one preview villager is drawn (the Mayor's Theme tab): that drawing wears this set. Render thread only. */
    private static VillagerTheme forced;

    private final String folder;
    /** {@code geo/<set>/}: the set's own rigs, one per skin that wears 3D pieces of its own ({@link #geoOf}). */
    private final String geoFolder;
    /** Per skin name, its rig in this set or {@link #NO_GEO}; emptied at every reload. Render thread only. */
    private final Map<String, ResourceLocation> geos = new HashMap<>();
    /** Where this set's generated farmer dirt stages are registered ({@link FarmerDirtTextures}); Classic's are directly in {@code dirt_stage/}. */
    private final String stageFolder;

    VillagerTheme(String folder) {
        this.folder = folder;
        this.stageFolder = "dirt_stage/" + name().toLowerCase(Locale.ROOT) + "/";
        this.geoFolder = "geo/" + name().toLowerCase(Locale.ROOT) + "/";
    }

    private static final ResourceLocation NO_GEO = ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "none");

    /**
     * This set's own rig for a skin ({@code geo/london/guard_2.json}: base_villager with that skin's 3D pieces), or
     * null when the set has none and the skin is drawn on the shared rig. {@code skin} as in
     * {@link VillagerGeoModel#skinKey}. Looked for once per reload, then a map read.
     */
    @Nullable
    ResourceLocation geoOf(String skin) {
        if (this == CLASSIC) return null;
        ResourceLocation geo = geos.get(skin);
        if (geo == null) {
            ResourceLocation file = ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, geoFolder + skin + ".json");
            geo = exists(file) ? file : NO_GEO;
            geos.put(skin, geo);
        }
        return geo == NO_GEO ? null : geo;
    }

    /** {@code dirt_stage/<set>/}: the folder of this set's generated farmer dirt stages (not used for Classic). */
    String stageFolder() {
        return stageFolder;
    }

    /**
     * The set a skin file handed out by {@link Skin#get} or {@link FarmerDirtTextures#texture} belongs to: the pack
     * whose 3D pieces go with that picture ({@link VillagerGeoModel}). A set with no picture of its own hands out
     * Classic's file, so that villager is Classic here too. Allocation-free: villagers ask it every frame.
     */
    public static VillagerTheme setOf(ResourceLocation file) {
        String path = file.getPath();
        for (VillagerTheme set : VALUES) {
            if (set != CLASSIC && (path.startsWith(set.folder) || path.startsWith(set.stageFolder))) return set;
        }
        return CLASSIC;
    }

    public static VillagerTheme current() {
        return current;
    }

    /**
     * The set a skin with these changes ({@link #changesFor}) is drawn from now: the one a preview drawing forces
     * (that set's own pictures, no changes), else the set in use, or the set the player took this skin from while
     * that set is in use.
     */
    static VillagerTheme drawnFrom(VillagerTheme[] from) {
        VillagerTheme set = forced;
        if (set != null) return set;
        set = current;
        VillagerTheme changed = from[set.ordinal()];
        return changed != null ? changed : set;
    }

    /**
     * Until {@code drawAs(null)}, every villager drawn wears {@code set} instead of the set in use. Only around one
     * preview drawing, on the render thread, always undone in a {@code finally}.
     */
    public static void drawAs(@Nullable VillagerTheme set) {
        forced = set;
    }

    /** Changes at every resource reload (a pack switched, F3+T): which files each set has may have changed. */
    public static int filesVersion() {
        return filesVersion;
    }

    /**
     * Reads the settings again, the set in use and the player's changes: when the client config loads or changes,
     * and after a screen sets them. Every villager follows at its next drawing.
     */
    public static void refresh() {
        current = ClientConfig.villagerTheme();
        changes = readChanges(ClientConfig.skinChanges());
        for (Skin skin : Skin.ALL) skin.from = changesFor(skin.key);
        FarmerDirtTextures.readChanges();
        changesVersion++;
    }

    /** Changes at every {@link #refresh}: the set in use or the player's changes may be new. */
    public static int changesVersion() {
        return changesVersion;
    }

    /** Per set in use, the set this skin's picture comes from (null: the set itself). Never null; never write to it. */
    static VillagerTheme[] changesFor(String skin) {
        VillagerTheme[] from = changes.get(skin);
        return from != null ? from : NO_CHANGES;
    }

    /**
     * The set this skin's picture comes from while {@code inUse} is in use, by the player's changes; null: the set
     * itself. {@code skin} as in {@link VillagerGeoModel#skinKey}.
     */
    @Nullable
    public static VillagerTheme changeOf(VillagerTheme inUse, String skin) {
        return changesFor(skin)[inUse.ordinal()];
    }

    /** How many skins the player changed for {@code inUse} (the Theme tab's "3 changes"). */
    public static int changeCount(VillagerTheme inUse) {
        int count = 0;
        for (VillagerTheme[] from : changes.values()) {
            if (from[inUse.ordinal()] != null) count++;
        }
        return count;
    }

    /**
     * The player's change lines with this one change made: every line for {@code skin} while {@code inUse} is in use
     * goes, and one line is added unless {@code from} is null or the set itself (no change). Lines not understood are
     * kept as written.
     */
    public static List<String> withChange(List<? extends String> lines, VillagerTheme inUse, String skin,
                                          @Nullable VillagerTheme from) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            Change change = Change.parse(line);
            if (change == null || change.inUse() != inUse || !change.skin().equals(skin)) out.add(line);
        }
        if (from != null && from != inUse) out.add(new Change(inUse, skin, from).line());
        return out;
    }

    /** The player's change lines without any for {@code inUse}: every skin of that set wears its own picture again. */
    public static List<String> withoutChanges(List<? extends String> lines, VillagerTheme inUse) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            Change change = Change.parse(line);
            if (change == null || change.inUse() != inUse) out.add(line);
        }
        return out;
    }

    /** A skin file's name in the player's changes: {@code dynamic_villagers_guard_2} is {@code guard_2}. */
    static String changeName(String fileName) {
        return fileName.startsWith(FILE_PREFIX) ? fileName.substring(FILE_PREFIX.length()) : fileName;
    }

    private static Map<String, VillagerTheme[]> readChanges(List<? extends String> lines) {
        Set<String> known = knownSkins();
        Map<String, VillagerTheme[]> out = new HashMap<>();
        for (String line : lines) {
            Change change = Change.parse(line);
            if (change == null || (!known.isEmpty() && !known.contains(change.skin()))) {
                LOGGER.warn("[Skin sets] Skipped the skinChanges line \"{}\": it should look like \"rustic: guard_2 = classic\"", line);
                continue;
            }
            // A later line for the same skin and set wins; its own set again means no change.
            out.computeIfAbsent(change.skin(), skin -> new VillagerTheme[VALUES.length])[change.inUse().ordinal()] =
                    change.from() == change.inUse() ? null : change.from();
        }
        return Map.copyOf(out);
    }

    /** Every skin name a change can use; empty while the villager model has not made its skins yet (nothing checked then). */
    private static Set<String> knownSkins() {
        Set<String> known = new HashSet<>();
        for (Skin skin : Skin.ALL) known.add(skin.key);
        if (!known.isEmpty()) FarmerDirtTextures.addChangeNames(known);
        return known;
    }

    /** One line of {@link ClientConfig#skinChanges}: while {@code inUse} is in use, {@code skin} is drawn from {@code from}. */
    record Change(VillagerTheme inUse, String skin, VillagerTheme from) {
        /** "rustic: guard_2 = classic", in any case and spacing; null for a line not like that. */
        @Nullable
        static Change parse(String line) {
            int colon = line.indexOf(':');
            int equals = colon < 0 ? -1 : line.indexOf('=', colon + 1);
            if (equals < 0) return null;
            VillagerTheme inUse = byName(line.substring(0, colon));
            String skin = line.substring(colon + 1, equals).trim().toLowerCase(Locale.ROOT);
            VillagerTheme from = byName(line.substring(equals + 1));
            return inUse == null || from == null || skin.isEmpty() ? null : new Change(inUse, skin, from);
        }

        /** As the Theme tab writes it: "rustic: guard_2 = classic". */
        String line() {
            return inUse.name().toLowerCase(Locale.ROOT) + ": " + skin + " = " + from.name().toLowerCase(Locale.ROOT);
        }
    }

    @Nullable
    private static VillagerTheme byName(String name) {
        String wanted = name.trim();
        // The default set's name on the Theme tab since 10-23; the saved name stays "classic".
        if (wanted.equalsIgnoreCase("victorian")) return CLASSIC;
        for (VillagerTheme set : VALUES) {
            if (set.name().equalsIgnoreCase(wanted)) return set;
        }
        return null;
    }

    /** {@code textures/entity/x.png} in this set's folder; a file outside today's folder is the same in every set. */
    ResourceLocation inThisSet(ResourceLocation classic) {
        if (this == CLASSIC || !classic.getPath().startsWith(CLASSIC_FOLDER)) return classic;
        return ResourceLocation.fromNamespaceAndPath(classic.getNamespace(),
                folder + classic.getPath().substring(CLASSIC_FOLDER.length()));
    }

    /** Whether the game's resources hold this file now (the mod's own or a resource pack's). */
    static boolean exists(ResourceLocation file) {
        return Minecraft.getInstance().getResourceManager().getResource(file).isPresent();
    }

    /** At every resource reload (F3+T, a pack switched): every set's files are looked for again. */
    static void forgetFiles() {
        for (Skin skin : Skin.ALL) skin.forget();
        for (VillagerTheme set : VALUES) set.geos.clear();
        paintedBySet = null;
        filesVersion++;
    }

    /** How many skin files a set can have: every {@link Skin} and the farmers' clean and dirty files (the Theme tab). */
    public static int skinCount() {
        count();
        return skinFiles;
    }

    /** How many of those this set has its own file for (today's set has them all). Counted once per resource reload. */
    public int paintedCount() {
        return count()[ordinal()];
    }

    private static int[] count() {
        int[] counts = paintedBySet;
        if (counts != null) return counts;
        // Each of today's files, with the one it is only drawn with (null: none).
        Map<ResourceLocation, ResourceLocation> files = new LinkedHashMap<>();
        for (Skin skin : Skin.ALL) {
            if (!skin.own) files.put(skin.classic, null);
        }
        FarmerDirtTextures.addClassicFiles(files);
        counts = new int[VALUES.length];
        for (VillagerTheme set : VALUES) {
            for (Map.Entry<ResourceLocation, ResourceLocation> file : files.entrySet()) {
                ResourceLocation needs = file.getValue();
                if (exists(set.inThisSet(file.getKey())) && (needs == null || exists(set.inThisSet(needs)))) {
                    counts[set.ordinal()]++;
                }
            }
        }
        skinFiles = files.size();
        // Before the villager model class has made its skins the list is short: count again next time.
        if (!Skin.ALL.isEmpty()) paintedBySet = counts;
        return counts;
    }

    /**
     * One skin of today's set and its file in each other set. {@link #get} is two array reads; a set's
     * file is looked for once, the first time it is drawn after a reload.
     */
    public static final class Skin {
        /** Every skin made, so a reload can forget them all. Filled while the model class loads. */
        private static final List<Skin> ALL = new CopyOnWriteArrayList<>();

        private final ResourceLocation classic;
        /** A skin only some sets paint ({@link #own}): no Classic file, so {@link #get} may hand out null. */
        private final boolean own;
        /** Its name in the player's changes: the file name without "dynamic_villagers_" (guard_2). */
        private final String key;
        /** Per set in use, the set its picture comes from ({@link #changesFor}); replaced whole when the config changes. */
        private volatile VillagerTheme[] from;
        /** Per set: the file to draw, or null until it has been looked for. CLASSIC is always filled. */
        private final ResourceLocation[] bySet = new ResourceLocation[VALUES.length];

        private Skin(ResourceLocation classic, String key, boolean own) {
            this.classic = classic;
            this.key = key;
            this.own = own;
            this.from = changesFor(key);
            if (!own) bySet[CLASSIC.ordinal()] = classic;
            ALL.add(this);
        }

        /** {@code textures/entity/<name>.png} of this mod. */
        public static Skin of(String name) {
            return new Skin(ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID,
                    CLASSIC_FOLDER + name + ".png"), changeName(name), false);
        }

        /**
         * A skin Classic has no file for (the captain's own, 10-24): {@link #get} hands out null in a set without one,
         * and the caller draws its stand-in. Not counted on the Theme tab.
         */
        public static Skin own(String name) {
            return new Skin(ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID,
                    CLASSIC_FOLDER + name + ".png"), changeName(name), true);
        }

        /** Its name in the player's changes (guard_2). */
        String key() {
            return key;
        }

        /**
         * The file to draw in the set in use with the player's changes (or in the one a preview drawing forces); null
         * only for an {@link #own} skin that set has not painted.
         */
        @Nullable
        public ResourceLocation get() {
            VillagerTheme theme = drawnFrom(from);
            ResourceLocation file = bySet[theme.ordinal()];
            if (file == null) {
                ResourceLocation inSet = theme.inThisSet(classic);
                file = exists(inSet) ? inSet : own ? NO_GEO : classic;
                bySet[theme.ordinal()] = file;
            }
            return file == NO_GEO ? null : file;
        }

        private void forget() {
            for (int i = 0; i < bySet.length; i++) {
                if (own || i != CLASSIC.ordinal()) bySet[i] = null;
            }
        }
    }
}
