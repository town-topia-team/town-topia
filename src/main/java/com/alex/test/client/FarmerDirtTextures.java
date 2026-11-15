package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.DirtLevel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The farmer skins at every dirt stage.
 *
 * <p>Stage 0 is the clean skin and stage 4 its hand-painted {@code _dirty}
 * twin. Stages 1 to 3 are built here at every resource reload by revealing
 * the twin's mud splotch by splotch from the boots up (see
 * {@link DirtStagePlanner}), and registered as dynamic textures. Each farmer
 * only ever moves between its own clean and dirty pair.
 *
 * <p>If the rig cannot be read, or a skin and its twin differ in size (a
 * resource pack replaced only one of them), that skin gets no in-between
 * stages: the clean skin shows up to stage 2 and the dirty one from stage 3.
 *
 * <p>Each skin set ({@link VillagerTheme}) gets its own stages. A set's farmer is used only when the set has
 * its clean skin; with no dirty twin painted yet it stays clean at every stage. A set without the clean skin
 * draws today's farmer, dirt and all. The player's change for a farmer ({@code farmer_female_1} in
 * {@code skinChanges}) moves its clean picture and its mud together, to the other set's stages.
 *
 * <p>In the development environment every stage is also written to
 * {@code debug/dirt_stages/} in the game directory, to look at by eye.
 */
public final class FarmerDirtTextures implements ResourceManagerReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Farmer skins, indexed by the stored {@code NpcEntity#getFarmerSkin}
     * (handed out by {@code FarmerSkinSequence}). The first two are the old
     * UUID-picked pair, so farmers from before skins were stored keep their faces.
     */
    private static final String[] SKIN_NAMES = {"female_1", "female_2", "gay_1", "male_2"};
    public static final int SKIN_COUNT = SKIN_NAMES.length;

    private static final ResourceLocation RIG =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "geo/base_villager.json");

    private static final VillagerTheme[] SETS = VillagerTheme.values();
    private static final int CLASSIC = VillagerTheme.CLASSIC.ordinal();
    /** [set][skin]: each set's clean and dirty files, in that set's folder. */
    private static final ResourceLocation[][] CLEAN = new ResourceLocation[SETS.length][SKIN_COUNT];
    private static final ResourceLocation[][] DIRTY = new ResourceLocation[SETS.length][SKIN_COUNT];
    /** [set][skin][stage]: what renders at each stage; rebuilt at every reload. */
    private static final ResourceLocation[][][] BY_STAGE =
            new ResourceLocation[SETS.length][SKIN_COUNT][DirtLevel.STAGES];
    /**
     * [skin][set in use]: the set this farmer's clean and muddy pictures come from, the player's change
     * ({@link VillagerTheme#changesFor}; null: the set itself). Replaced whole when the config changes.
     */
    private static volatile VillagerTheme[][] from = changes();

    static {
        for (int i = 0; i < SKIN_COUNT; i++) {
            ResourceLocation clean = skin("dynamic_villagers_farmer_" + SKIN_NAMES[i]);
            ResourceLocation dirty = skin("dynamic_villagers_farmer_" + SKIN_NAMES[i] + "_dirty");
            for (VillagerTheme set : SETS) {
                CLEAN[set.ordinal()][i] = set.inThisSet(clean);
                DIRTY[set.ordinal()][i] = set.inThisSet(dirty);
            }
            // Until the first reload has looked for them, every set draws today's farmers.
            for (VillagerTheme set : SETS) useFallback(CLASSIC, i, set.ordinal());
        }
    }

    private static ResourceLocation skin(String name) {
        return ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "textures/entity/" + name + ".png");
    }

    /** Today's ids for Classic (unchanged), {@code dirt_stage/<set>/...} for the others. */
    private static ResourceLocation generated(int set, int skin, int stage) {
        // VillagerTheme.setOf reads the set back from this folder (which pack's 3D pieces the farmer wears).
        String folder = set == CLASSIC ? "dirt_stage/" : SETS[set].stageFolder();
        return ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, folder + "farmer_" + SKIN_NAMES[skin] + "_" + stage);
    }

    /**
     * Today's farmer files, which every other set mirrors ({@link VillagerTheme#paintedCount}), each with the file it
     * is only drawn with: a set's dirty twin is never drawn without its clean skin.
     */
    static void addClassicFiles(Map<ResourceLocation, ResourceLocation> into) {
        for (int i = 0; i < SKIN_COUNT; i++) {
            into.put(CLEAN[CLASSIC][i], null);
            into.put(DIRTY[CLASSIC][i], CLEAN[CLASSIC][i]);
        }
    }

    /**
     * The texture for farmer skin {@code skin} at dirt stage {@code stage} in the set drawn now, with the player's
     * change for this farmer; both are clamped.
     */
    public static ResourceLocation texture(int skin, int stage) {
        int s = Math.max(0, Math.min(SKIN_COUNT - 1, skin));
        VillagerTheme set = VillagerTheme.drawnFrom(from[s]);
        return BY_STAGE[set.ordinal()][s][Math.max(0, Math.min(DirtLevel.STAGES - 1, stage))];
    }

    /** A farmer's name in the player's changes, after its clean file: {@code farmer_female_1}. */
    static String changeName(int skin) {
        return "farmer_" + SKIN_NAMES[skin];
    }

    static void addChangeNames(Set<String> into) {
        for (int i = 0; i < SKIN_COUNT; i++) into.add(changeName(i));
    }

    /** The player's changes were read again ({@link VillagerTheme#refresh}). */
    static void readChanges() {
        from = changes();
    }

    private static VillagerTheme[][] changes() {
        VillagerTheme[][] out = new VillagerTheme[SKIN_COUNT][];
        for (int i = 0; i < SKIN_COUNT; i++) out[i] = VillagerTheme.changesFor(changeName(i));
        return out;
    }

    /**
     * Set {@code from}'s clean skin up to stage 2 and its dirty one from stage 3, shown in set {@code into}:
     * used whenever stages cannot be generated.
     */
    private static void useFallback(int from, int skin, int into) {
        for (int stage = 0; stage < DirtLevel.STAGES; stage++) {
            BY_STAGE[into][skin][stage] = stage < 3 ? CLEAN[from][skin] : DIRTY[from][skin];
        }
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        // Skin pictures and baked bones may change: what each skin shows of each face is read again (SkinFaces).
        SkinFaces.forget();
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        DirtStagePlanner.TexelHeights heights = readRig(resourceManager);
        if (heights == null) LOGGER.warn("Dirt stages: could not read {}; farmers skip the in-between stages", RIG);
        Path dumpDir = FMLEnvironment.production ? null
                : Minecraft.getInstance().gameDirectory.toPath().resolve("debug").resolve("dirt_stages");

        for (int skin = 0; skin < SKIN_COUNT; skin++) {
            // Classic first: a set that has not painted this farmer copies its stages.
            buildStages(resourceManager, textures, CLASSIC, skin, heights, dumpDir);
            for (int set = 0; set < SETS.length; set++) {
                if (set == CLASSIC) continue;
                if (resourceManager.getResource(CLEAN[set][skin]).isEmpty()) {
                    BY_STAGE[set][skin] = BY_STAGE[CLASSIC][skin].clone();
                } else if (resourceManager.getResource(DIRTY[set][skin]).isEmpty()) {
                    // Painted clean, its dirty twin not yet: clean at every stage rather than today's mud.
                    Arrays.fill(BY_STAGE[set][skin], CLEAN[set][skin]);
                    LOGGER.info("Dirt stages: {} has no dirty twin yet; it stays clean", CLEAN[set][skin]);
                } else {
                    // A skin with its own rig (London, Rustic: 3D aprons and skirts) climbs its mud on that rig.
                    DirtStagePlanner.TexelHeights own = readRig(resourceManager, ResourceLocation.fromNamespaceAndPath(
                            DynamicVillages.MODID, "geo/" + SETS[set].name().toLowerCase(Locale.ROOT) + "/"
                                    + changeName(skin) + ".json"));
                    buildStages(resourceManager, textures, set, skin, own != null ? own : heights, dumpDir);
                }
            }
        }
        // Each set's skin files are looked for again too (VillagerGeoModel's skins).
        VillagerTheme.forgetFiles();
    }

    /** Set {@code set}'s stages for {@code skin} from its clean and dirty files, or its fallback. */
    private static void buildStages(ResourceManager resourceManager, TextureManager textures, int set, int skin,
                                    @Nullable DirtStagePlanner.TexelHeights heights, @Nullable Path dumpDir) {
        useFallback(set, skin, set);
        if (heights == null) return;
        try (NativeImage clean = readImage(resourceManager, CLEAN[set][skin]);
             NativeImage dirty = readImage(resourceManager, DIRTY[set][skin])) {
            if (clean == null || dirty == null
                    || clean.getWidth() != dirty.getWidth() || clean.getHeight() != dirty.getHeight()) {
                LOGGER.warn("Dirt stages: {} and its dirty twin are missing or differ in size", CLEAN[set][skin]);
                return;
            }
            build(textures, set, skin, clean, dirty, heights,
                    dumpDir == null || set == CLASSIC ? dumpDir : dumpDir.resolve(SETS[set].name().toLowerCase(Locale.ROOT)));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Dirt stages: could not build {}", CLEAN[set][skin], e);
            useFallback(set, skin, set);
        }
    }

    private static void build(TextureManager textures, int set, int skin, NativeImage clean, NativeImage dirty,
                              DirtStagePlanner.TexelHeights heights, @Nullable Path dumpDir) throws IOException {
        int w = clean.getWidth(), h = clean.getHeight();
        int[] cleanPixels = new int[w * h];
        int[] dirtyPixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                cleanPixels[y * w + x] = clean.getPixelRGBA(x, y);
                dirtyPixels[y * w + x] = dirty.getPixelRGBA(x, y);
            }
        }
        int[] stageOf = DirtStagePlanner.stagePerPixel(cleanPixels, dirtyPixels, w, h, heights);

        if (dumpDir != null) {
            Files.createDirectories(dumpDir);
            clean.writeToFile(dumpDir.resolve(SKIN_NAMES[skin] + "_0.png"));
            dirty.writeToFile(dumpDir.resolve(SKIN_NAMES[skin] + "_4.png"));
        }
        BY_STAGE[set][skin][0] = CLEAN[set][skin];
        BY_STAGE[set][skin][4] = DIRTY[set][skin];
        for (int stage = 1; stage <= 3; stage++) {
            NativeImage image = new NativeImage(w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    boolean muddy = stageOf[i] != 0 && stageOf[i] <= stage;
                    image.setPixelRGBA(x, y, muddy ? dirtyPixels[i] : cleanPixels[i]);
                }
            }
            if (dumpDir != null) image.writeToFile(dumpDir.resolve(SKIN_NAMES[skin] + "_" + stage + ".png"));
            ResourceLocation id = generated(set, skin, stage);
            // Registering over the previous reload's texture closes that one.
            textures.register(id, new DynamicTexture(image));
            BY_STAGE[set][skin][stage] = id;
        }
    }

    @Nullable
    private static DirtStagePlanner.TexelHeights readRig(ResourceManager resourceManager) {
        return readRig(resourceManager, RIG);
    }

    @Nullable
    private static DirtStagePlanner.TexelHeights readRig(ResourceManager resourceManager, ResourceLocation rig) {
        Optional<Resource> resource = resourceManager.getResource(rig);
        if (resource.isEmpty()) return null;
        try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            JsonObject geo = JsonParser.parseReader(reader).getAsJsonObject();
            return DirtStagePlanner.fromGeo(geo);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static NativeImage readImage(ResourceManager resourceManager, ResourceLocation id) throws IOException {
        Optional<Resource> resource = resourceManager.getResource(id);
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open()) {
            return NativeImage.read(in);
        }
    }
}
