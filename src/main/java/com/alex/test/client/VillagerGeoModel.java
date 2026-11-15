package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.mixin.GeoModelAccessor;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.ArmorItem;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.loading.math.MathParser;
import software.bernie.geckolib.loading.math.value.Variable;
import software.bernie.geckolib.model.GeoModel;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * GeckoLib model used by the village NPC entity.
 *
 * <p>The generic bound
 * {@code T extends PathfinderMob & GeoEntity} keeps the renderer type-safe without
 * requiring a separate model class per entity type.
 *
 * <h3>Rigs</h3>
 * <p>Every villager uses {@code base_villager}, and Nomads use copies of that rig
 * with and without a head-mounted hood. The original box-UV {@code dynamic_villagers}
 * rig was deleted 10-22; the mocap tools now solve on a flat copy of base_villager
 * ({@code tools/mocap/flat_to_tree.py}) and write onto its bone tree.
 *
 * <p>Skins are painted for base_villager's per-face UVs. The box-UV originals of
 * the older ones are kept in {@code tools/legacy_skins/} as reference only.
 *
 * <h3>Resource paths (relative to assets/towntopia/)</h3>
 * <table border="1">
 *   <caption>Villager resources</caption>
 *   <tr><td>Mesh</td><td>{@code geo/base_villager.json}</td></tr>
 *   <tr><td>Animations</td><td>{@code animations/base_villager.animation.json}</td></tr>
 *   <tr><td>Textures</td><td>{@code textures/entity/dynamic_villagers_*.png}; the other sets the same names in
 *   {@code textures/entity/<set>/} ({@link VillagerTheme})</td></tr>
 *   <tr><td>Per-skin rigs</td><td>{@code geo/<set>/<skin>.json} (London, Rustic): base_villager without the other
 *   packs' pieces, plus that skin's own ({@link #getModelResource})</td></tr>
 * </table>
 */
public class VillagerGeoModel<T extends PathfinderMob & GeoEntity> extends GeoModel<T> {

    private static final ResourceLocation MODEL_BASE_VILLAGER = ResourceLocation.fromNamespaceAndPath(
            DynamicVillages.MODID, "geo/base_villager.json");
    // Both Nomad looks use the current animated rig. The hood is attached to
    // the head only in its own geometry, leaving the farmer's hat untouched.
    private static final ResourceLocation MODEL_NOMAD = ResourceLocation.fromNamespaceAndPath(
            DynamicVillages.MODID, "geo/nomad.json");
    private static final ResourceLocation MODEL_NOMAD_HOOD_OFF = ResourceLocation.fromNamespaceAndPath(
            DynamicVillages.MODID, "geo/nomad_hood_off.json");
    private static final ResourceLocation ANIMATIONS_BASE_VILLAGER = ResourceLocation.fromNamespaceAndPath(
            DynamicVillages.MODID, "animations/base_villager.animation.json");
    /** The outside animator's clips (flyguys_*), made by tools/flyguys/FlyguysPack.java. Looked in only when base_villager lacks the name. */
    private static final ResourceLocation[] ANIMATIONS_FLYGUYS = {ResourceLocation.fromNamespaceAndPath(
            DynamicVillages.MODID, "animations/flyguys_animations.animation.json")};
    private static final String[] FARMER_SLEEVE_BONES = {"farmer_left_sleeve", "farmer_right_sleeve"};
    private static final String[] BARD_VIOLIN_BONES = {"bard_violin", "bard_bow"};
    /** Every bard extra in base_villager; each bard shows only its variant's row of BARD_OUTFITS. */
    private static final String[] BARD_OUTFIT_BONES = {
            "bard_hat", "bard_cavalier", "bard_cap", "bard_wreath",
            "bard_left_puff", "bard_right_puff", "bard_ruff", "bard_lute"};
    /**
     * The extras each bard skin wears, in TEXTURES_BARD order. Each skin paints only
     * its own extras (the hats even share atlas space), so this must match the
     * "extras" lists in tools/art/generate_bard_skin.py.
     */
    private static final java.util.List<java.util.Set<String>> BARD_OUTFITS = java.util.List.of(
            java.util.Set.of("bard_hat", "bard_left_puff", "bard_right_puff"),
            java.util.Set.of("bard_cavalier", "bard_ruff"),
            java.util.Set.of("bard_cap", "bard_left_puff", "bard_right_puff", "bard_lute"),
            java.util.Set.of("bard_wreath", "bard_left_puff", "bard_right_puff", "bard_ruff"));
    /** Every miner extra in base_villager; each miner shows only its variant's row of MINER_OUTFITS. */
    private static final String[] MINER_OUTFIT_BONES = {
            "miner_cap", "miner_rope",
            "miner_goggles", "miner_lantern", "miner_slouch", "miner_beard", "miner_sack"};
    /**
     * The gear each miner skin wears, in TEXTURES_MINER order. The bones are written by
     * tools/art/generate_miner_skin.py into the space its skin leaves free, so this
     * must match the "gear" lists there.
     */
    private static final java.util.List<java.util.Set<String>> MINER_OUTFITS = java.util.List.of(
            java.util.Set.of("miner_cap", "miner_rope"),
            java.util.Set.of("miner_goggles", "miner_lantern"),
            java.util.Set.of("miner_slouch", "miner_beard", "miner_sack"));

    // Skins are VillagerTheme.Skin: each draws its file in the player's chosen set (Classic, Modern or Rustic).
    private static final VillagerTheme.Skin TEXTURE_NITWIT = VillagerTheme.Skin.of("dynamic_villagers_nitwit");
    private static final VillagerTheme.Skin TEXTURE_MAYOR = VillagerTheme.Skin.of("dynamic_villagers_mayor");
    /**
     * The captain's own skin (10-24), painted only by the sets that draw a captain apart from its guards (London,
     * Rustic); elsewhere a captain wears the guard skins and its cape ({@link #ownCaptainSkin}).
     */
    private static final VillagerTheme.Skin TEXTURE_CAPTAIN = VillagerTheme.Skin.own("dynamic_villagers_captain");

    /**
     * The lumberjack skins do not wear a generated tabard: the plaid shirt and jeans already say "lumberjack"
     * at gameplay distance, so stamping a flat panel over the chest would cover the
     * very thing that identifies them. Everyone else gets a painted skin ({@link #painted}).
     */
    private static final VillagerTheme.Skin[] TEXTURES_LUMBERJACK = {
            VillagerTheme.Skin.of("dynamic_villagers_lumberjack_male_1"),
            VillagerTheme.Skin.of("dynamic_villagers_lumberjack_female_1"),
            VillagerTheme.Skin.of("dynamic_villagers_lumberjack_female_2")
    };

    /**
     * Hand-painted skins that come in several interchangeable variants, so a crowd of
     * one profession is not a row of clones. Each is a whole skin, not a recolour --
     * the guard variants share their armour and differ from the neck up.
     *
     * <p>Index order matches the files as delivered:
     * <table border="1">
     *   <caption>Variant provenance</caption>
     *   <tr><th>file</th><th>as delivered</th></tr>
     *   <tr><td>{@code guard_1}</td><td>guard_male_1</td></tr>
     *   <tr><td>{@code guard_2}</td><td>guard_male_2</td></tr>
     *   <tr><td>{@code guard_3}</td><td>guard_female_1</td></tr>
     *   <tr><td>{@code guard_4}</td><td>guard3_finalfinal</td></tr>
     *   <tr><td>{@code archer_1}</td><td>archer</td></tr>
     *   <tr><td>{@code archer_2}</td><td>archer2</td></tr>
     * </table>
     */
    private static VillagerTheme.Skin[] variants(String profession, int count) {
        VillagerTheme.Skin[] out = new VillagerTheme.Skin[count];
        for (int i = 0; i < count; i++) {
            out[i] = VillagerTheme.Skin.of("dynamic_villagers_" + profession + "_" + (i + 1));
        }
        return out;
    }

    private static final VillagerTheme.Skin[] TEXTURES_GUARD  = variants("guard", 4);
    private static final VillagerTheme.Skin[] TEXTURES_ARCHER = variants("archer", 2);
    /**
     * Four minstrels (tools/art/generate_bard_skin.py). Like the lumberjack they
     * stand on their own, so they wear no tabard. Each also wears its own 3D
     * extras, listed in BARD_OUTFITS. Picked by {@link #bardSkin}, not the UUID.
     */
    private static final VillagerTheme.Skin[] TEXTURES_BARD   = variants("bard", 4);
    /**
     * Three miners (tools/art/generate_miner_skin.py), each with its own gear,
     * listed in MINER_OUTFITS. Picked by {@link #minerSkin}, not the UUID.
     * The modern hard-hat miner was removed 10-17 (old saves remapped in NpcEntity).
     */
    private static final VillagerTheme.Skin[] TEXTURES_MINER  = variants("miner", 3);

    /**
     * How many skins a job's villagers are picked from (1 for a job with one painted skin), for the Mayor's
     * Theme tab, which steps through each. Guards and archers pick by UUID, the others by a stored skin.
     */
    public static int skinVariants(Profession job) {
        return switch (job) {
            case LUMBERJACK -> TEXTURES_LUMBERJACK.length;
            case GUARD, CAPTAIN -> TEXTURES_GUARD.length;
            case ARCHER -> TEXTURES_ARCHER.length;
            case FARMER -> FarmerDirtTextures.SKIN_COUNT;
            case BARD -> TEXTURES_BARD.length;
            case MINER -> TEXTURES_MINER.length;
            default -> 1;
        };
    }

    /**
     * The name a job's skin number {@code variant} ({@link #skinVariants} order) goes by in the player's skin changes
     * ({@code guard_2}, {@code farmer_female_1}): the Theme tab's Leave out and Wear buttons write it.
     */
    public static String skinKey(Profession job, int variant) {
        return switch (job) {
            case LUMBERJACK -> nth(TEXTURES_LUMBERJACK, variant);
            case GUARD, CAPTAIN -> nth(TEXTURES_GUARD, variant);
            case ARCHER -> nth(TEXTURES_ARCHER, variant);
            case FARMER -> FarmerDirtTextures.changeName(Math.floorMod(variant, FarmerDirtTextures.SKIN_COUNT));
            case BARD -> nth(TEXTURES_BARD, variant);
            case MINER -> nth(TEXTURES_MINER, variant);
            case RANCHER -> TEXTURE_RANCHER.key();
            case COOK -> TEXTURE_COOK.key();
            case BLACKSMITH -> TEXTURE_BLACKSMITH.key();
            case BUTCHER -> TEXTURE_BUTCHER.key();
            case BUILDER -> TEXTURE_BUILDER.key();
            case CLERIC -> TEXTURE_CLERIC.key();
            case DRUID -> TEXTURE_DRUID.key();
            case ENCHANTER -> TEXTURE_ENCHANTER.key();
            case TEACHER -> TEXTURE_TEACHER.key();
            case TRADESMAN -> TEXTURE_TRADESMAN.key();
            case MAGE -> TEXTURE_MAGE.key();
            case CHILD, NONE -> TEXTURE_NITWIT.key();
        };
    }

    private static String nth(VillagerTheme.Skin[] skins, int variant) {
        return skins[Math.floorMod(variant, skins.length)].key();
    }

    /**
     * Picks a villager's variant from its entity UUID.
     *
     * <p>Deliberately not a {@code SynchedEntityData} field: the UUID is already on the
     * client, already stable across saves and already unique, so keying off it costs no
     * network traffic, no NBT and no spawn hook, and a villager keeps its face forever.
     * {@code UUID.hashCode} is a fold of all 128 bits and spreads evenly -- measured over
     * 200k UUIDs it lands within 0.04% of even across four buckets.
     *
     * <p>{@code floorMod} rather than {@code %} because the hash is signed and a negative
     * remainder would blow up on the array index.
     */
    private static ResourceLocation pickVariant(VillagerTheme.Skin[] skins, PathfinderMob entity) {
        return skins[uuidVariant(entity, skins.length)].get();
    }

    private static int uuidVariant(PathfinderMob entity, int count) {
        return Math.floorMod(entity.getUUID().hashCode(), count);
    }

    private static int lumberjackSkin(NpcEntity npc) {
        return Math.max(0, Math.min(TEXTURES_LUMBERJACK.length - 1, npc.getLumberjackSkin()));
    }

    /**
     * A farmer's stored skin. Until it has synced (or for a farmer that somehow has none), the old UUID pick stands
     * in instead of a wrong index.
     */
    private static int farmerSkin(NpcEntity npc) {
        int skin = npc.getFarmerSkin();
        return skin >= 0 && skin < FarmerDirtTextures.SKIN_COUNT ? skin : uuidVariant(npc, 2);
    }

    private static int nomadOutfit(PathfinderMob entity) {
        return appearanceRandom(entity.getUUID(), 5) < 50 ? 0 : 1;
    }

    /**
     * A bard's stored skin, handed out in turn by {@code BardSkinSequence} so a
     * village's bards differ. Until it has synced, the old UUID pick stands in.
     * Picks both the texture and the extras in BARD_OUTFITS.
     */
    private static int bardSkin(NpcEntity npc) {
        return storedSkin(npc, npc.getBardSkin(), TEXTURES_BARD.length);
    }

    /** A miner's stored skin from {@code MinerSkinSequence}; see {@link #bardSkin}. */
    private static int minerSkin(NpcEntity npc) {
        return storedSkin(npc, npc.getMinerSkin(), TEXTURES_MINER.length);
    }

    private static int storedSkin(NpcEntity npc, int skin, int count) {
        return skin >= 0 && skin < count ? skin : Math.floorMod(npc.getUUID().hashCode(), count);
    }

    /**
     * A profession's own painted skin, {@code textures/entity/dynamic_villagers_<profession>.png}.
     * These replaced the generated tabard skins (deleted 10-22); they are written by
     * {@code tools/art/skins/build.py} and paint the UVs of that profession's
     * accessory bones too (see {@link #ACCESSORY_BONES}). The miner and the bard
     * keep their own rotating skins instead (TEXTURES_MINER, TEXTURES_BARD).
     */
    private static VillagerTheme.Skin painted(String profession) {
        return VillagerTheme.Skin.of("dynamic_villagers_" + profession);
    }

    private static final VillagerTheme.Skin TEXTURE_RANCHER    = painted("rancher");
    private static final VillagerTheme.Skin TEXTURE_COOK       = painted("cook");
    private static final VillagerTheme.Skin TEXTURE_BLACKSMITH = painted("blacksmith");
    private static final VillagerTheme.Skin TEXTURE_BUTCHER    = painted("butcher");
    // Painted while the job was still called Architect; the file kept that name.
    private static final VillagerTheme.Skin TEXTURE_BUILDER    = painted("architect");
    private static final VillagerTheme.Skin TEXTURE_CLERIC     = painted("cleric");
    private static final VillagerTheme.Skin TEXTURE_DRUID      = painted("druid");
    private static final VillagerTheme.Skin TEXTURE_ENCHANTER  = painted("enchanter");
    /** A second enchanter (10-24), only in the sets that paint one: there half the enchanters wear it, by UUID. */
    private static final VillagerTheme.Skin TEXTURE_ENCHANTER_2 = VillagerTheme.Skin.own("dynamic_villagers_enchanter_2");
    private static final VillagerTheme.Skin TEXTURE_TEACHER    = painted("teacher");
    private static final VillagerTheme.Skin TEXTURE_TRADESMAN  = painted("tradesman");
    private static final VillagerTheme.Skin TEXTURE_MERCHANT   = painted("merchant");
    private static final VillagerTheme.Skin TEXTURE_MAGE       = painted("mage");
    // Two nomad outfits, each painted hood up and hood down (the lowered hood is
    // only the bottom rows of the hood strip, a collar). Hood-up skins fit
    // nomad.json's hood UVs, hood-down skins fit nomad_hood_off.json's, so the
    // texture always follows nomadHasHood like the model does.
    private static final VillagerTheme.Skin[] TEXTURES_NOMAD_HOOD = {
            painted("nomad_1_hood"), painted("nomad_2_hood")};
    private static final VillagerTheme.Skin[] TEXTURES_NOMAD_HOOD_DOWN = {
            painted("nomad_1_hood_down"), painted("nomad_2_hood_down")};

    /**
     * Each profession's 3D accessories (hats, a pack, a beard...), written into
     * {@code geo/base_villager.json} by {@code tools/art/skins/accessories.py}.
     * Keys are lower-case profession names, plus {@code "merchant"} for the visiting
     * trader. A villager shows its own and hides everyone else's; the accessories of
     * different professions share texels, which works because each profession has its
     * own skin. There is no miner or bard entry on purpose: those two wear their
     * rotating skins and their own extras (MINER_OUTFITS, BARD_OUTFITS), so
     * the painted-skin helmet and cap bones ({@code acc_miner_*}, {@code acc_bard_*})
     * were left out of base_villager.json when the branches were merged on 10-17.
     * Re-running accessories.py would add them back: drop them again or add entries.
     */
    private static final Map<String, String[]> ACCESSORY_BONES = Map.ofEntries(
            Map.entry("cook", new String[]{"acc_cook_hat"}),
            Map.entry("rancher", new String[]{"acc_rancher_hat"}),
            Map.entry("cleric", new String[]{"acc_cleric_belly", "acc_cleric_cowl"}),
            Map.entry("druid", new String[]{"acc_druid_beard", "acc_druid_twig"}),
            Map.entry("enchanter", new String[]{"acc_enchanter_hat", "acc_enchanter_hat_tip", "acc_enchanter_hat_tip2", "acc_enchanter_hat_tip3"}),
            Map.entry("teacher", new String[]{"acc_teacher_cap", "acc_teacher_bun"}),
            Map.entry("builder", new String[]{"acc_architect_cap", "acc_architect_tube"}),
            Map.entry("tradesman", new String[]{"acc_tradesman_pack"}),
            Map.entry("merchant", new String[]{"acc_merchant_hat"}),
            Map.entry("mage", new String[]{"acc_mage_hood", "acc_mage_hood_peak"}),
            Map.entry("blacksmith", new String[]{"acc_blacksmith_beard"}),
            Map.entry("butcher", new String[]{"acc_butcher_cap", "acc_butcher_cap_tails"})
    );

    /** A pack's row for a wearer with no 3D pieces. */
    private static final String[][] NO_PIECES = {{}};

    /**
     * London and Rustic (10-24): every skin wears its own pieces, kept in its own rig ({@link #getModelResource}),
     * where they always show; none come from this table, and the shared rig's pieces are not in those rigs.
     */
    private static Map<String, String[][]> piecesInOwnRigs() {
        Map<String, String[][]> out = new java.util.HashMap<>();
        for (Profession job : Profession.values()) out.put(job.name().toLowerCase(Locale.ROOT), NO_PIECES);
        for (String wearer : new String[]{"merchant", "mayor", "nomad"}) out.put(wearer, NO_PIECES);
        return Map.copyOf(out);
    }

    /**
     * Each pack's 3D pieces (hats, sleeves, props), because a hat's texels are painted on that pack's own skins: a
     * villager wears the pieces of the pack its picture comes from ({@link VillagerTheme#setOf} of its skin file,
     * so after the player's Leave out / Wear in, and in the Theme tab's previews too). Per wearer (a job's lower-case
     * name, {@code "merchant"}, {@code "mayor"}, {@code "nomad"}), the bones its skins wear: one row for every skin,
     * or one row per skin number in the order the skin is picked ({@link #skinNumber}).
     *
     * <p>A wearer a pack does not list wears Classic's pieces (a set with no picture of its own draws Classic's
     * file anyway). So a pack that paints a job lists it, even with {@link #NO_PIECES}, or another pack's hat could
     * show over its picture. London and Rustic keep their pieces in each skin's own rig instead.
     */
    private static final Map<VillagerTheme, Map<String, String[][]>> PACK_PIECES = Map.of(
            VillagerTheme.CLASSIC, classicPieces(),
            VillagerTheme.LONDON, piecesInOwnRigs(),
            VillagerTheme.RUSTIC, piecesInOwnRigs(),
            VillagerTheme.MODERN, Map.of(
                    // The hand-painted farmers' straw hat and puffed sleeves (their texels are on these skins).
                    "farmer", new String[][]{{"farmer_hat", FARMER_SLEEVE_BONES[0], FARMER_SLEEVE_BONES[1]}},
                    "guard", NO_PIECES, "captain", NO_PIECES, "archer", NO_PIECES,
                    "lumberjack", NO_PIECES, "mayor", NO_PIECES, "nomad", NO_PIECES));

    /**
     * The base pack ("Victorian English" on the Theme tab; Modern is the default since 10-25): one Victorian hat each for the six jobs drawn in that
     * style (the Mayor's tricorn brim is a child of the listed bone, so it hides with it), each other job's
     * accessories, the bard and miner outfits. Nomads wear their hood on the skin.
     */
    private static Map<String, String[][]> classicPieces() {
        Map<String, String[][]> out = new java.util.HashMap<>();
        out.put("farmer", new String[][]{{"victorian_farmer_hat"}});
        out.put("guard", new String[][]{{"victorian_guard_hat"}});
        out.put("captain", new String[][]{{"victorian_guard_hat"}});
        out.put("archer", new String[][]{{"victorian_archer_hat"}});
        out.put("lumberjack", new String[][]{{"victorian_lumberjack_hat"}});
        out.put("mayor", new String[][]{{"victorian_mayor_hat"}});
        ACCESSORY_BONES.forEach((owner, bones) -> out.put(owner, new String[][]{bones}));
        out.put("bard", rows(BARD_OUTFITS));
        out.put("miner", rows(MINER_OUTFITS));
        return Map.copyOf(out);
    }

    private static String[][] rows(java.util.List<java.util.Set<String>> outfits) {
        String[][] out = new String[outfits.size()][];
        for (int i = 0; i < out.length; i++) out[i] = outfits.get(i).toArray(new String[0]);
        return out;
    }

    /**
     * Pieces that sit on the head: hidden while the villager wears an armour helmet ({@code GuardArmorGeoLayer}
     * draws it), so no hat pokes through. A new pack's hat goes here too.
     */
    private static final java.util.Set<String> HAT_BONES = java.util.Set.of(
            "farmer_hat", "acc_cook_hat", "acc_rancher_hat", "acc_cleric_cowl",
            "acc_enchanter_hat", "acc_enchanter_hat_tip", "acc_enchanter_hat_tip2", "acc_enchanter_hat_tip3",
            "acc_teacher_cap", "acc_architect_cap", "acc_merchant_hat", "acc_mage_hood", "acc_mage_hood_peak",
            "acc_butcher_cap", "acc_butcher_cap_tails",
            "bard_hat", "bard_cavalier", "bard_cap", "bard_wreath", "miner_cap", "miner_slouch",
            "victorian_farmer_hat", "victorian_guard_hat", "victorian_archer_hat", "victorian_lumberjack_hat",
            "victorian_mayor_hat");

    private static final Profession[] JOBS = Profession.values();
    /** Wearers past the jobs (whose number is the job's ordinal). */
    private static final int WEARER_MERCHANT = JOBS.length, WEARER_MAYOR = JOBS.length + 1,
            WEARER_NOMAD = JOBS.length + 2, WEARERS = JOBS.length + 3;

    /** Every bone any pack lists, in one fixed order: the masks below and {@link VisibilityBones#pieces} follow it. */
    private static final String[] PIECE_BONES = pieceBones();
    /** [pack][wearer][skin row][piece]: whether that piece shows. Built once from {@link #PACK_PIECES}. */
    private static final boolean[][][][] WORN = wornMasks();
    /** [piece]: whether it hides under an armour helmet ({@link #HAT_BONES}). */
    private static final boolean[] IS_HAT = hatMask();

    private static String wearerKey(int wearer) {
        if (wearer == WEARER_MERCHANT) return "merchant";
        if (wearer == WEARER_MAYOR) return "mayor";
        if (wearer == WEARER_NOMAD) return "nomad";
        return JOBS[wearer].name().toLowerCase(Locale.ROOT);
    }

    private static String[] pieceBones() {
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        // Every bard and miner extra, even one no row wears yet: it stays hidden instead of showing on everyone.
        all.addAll(java.util.List.of(BARD_OUTFIT_BONES));
        all.addAll(java.util.List.of(MINER_OUTFIT_BONES));
        for (Map<String, String[][]> pack : PACK_PIECES.values())
            for (String[][] rows : pack.values())
                for (String[] row : rows) all.addAll(java.util.List.of(row));
        return all.toArray(new String[0]);
    }

    private static boolean[][][][] wornMasks() {
        java.util.List<String> pieces = java.util.List.of(PIECE_BONES);
        Map<String, String[][]> classic = PACK_PIECES.get(VillagerTheme.CLASSIC);
        VillagerTheme[] packs = VillagerTheme.values();
        boolean[][][][] out = new boolean[packs.length][WEARERS][][];
        for (VillagerTheme pack : packs) {
            Map<String, String[][]> own = PACK_PIECES.getOrDefault(pack, Map.of());
            for (int wearer = 0; wearer < WEARERS; wearer++) {
                String key = wearerKey(wearer);
                String[][] rows = own.getOrDefault(key, classic.getOrDefault(key, NO_PIECES));
                boolean[][] masks = new boolean[rows.length][PIECE_BONES.length];
                for (int row = 0; row < rows.length; row++)
                    for (String bone : rows[row]) masks[row][pieces.indexOf(bone)] = true;
                out[pack.ordinal()][wearer] = masks;
            }
        }
        return out;
    }

    private static boolean[] hatMask() {
        boolean[] out = new boolean[PIECE_BONES.length];
        for (int i = 0; i < out.length; i++) out[i] = HAT_BONES.contains(PIECE_BONES[i]);
        return out;
    }

    /** Whose pieces this mob wears ({@link #WORN}'s wearer); -1: none. */
    private static int wearer(PathfinderMob entity) {
        if (entity instanceof MayorEntity) return WEARER_MAYOR;
        // Travellers are checked before the NpcEntity branch they inherit from.
        if (entity instanceof com.alex.test.entity.visitor.NomadEntity) return WEARER_NOMAD;
        if (entity instanceof com.alex.test.entity.visitor.VisitorEntity) return WEARER_MERCHANT;
        if (entity instanceof NpcEntity npc) return npc.getProfession().ordinal();
        return -1;
    }

    /** The number its skin was picked by in {@link #getTextureResource}, for a pack that lists pieces per skin. */
    private static int skinNumber(PathfinderMob entity) {
        if (entity instanceof MayorEntity) return 0;
        if (entity instanceof com.alex.test.entity.visitor.NomadEntity) return nomadOutfit(entity);
        if (entity instanceof com.alex.test.entity.visitor.VisitorEntity || !(entity instanceof NpcEntity npc)) return 0;
        return switch (npc.getProfession()) {
            case LUMBERJACK -> lumberjackSkin(npc);
            case GUARD, CAPTAIN -> uuidVariant(npc, TEXTURES_GUARD.length);
            case ARCHER -> uuidVariant(npc, TEXTURES_ARCHER.length);
            case FARMER -> farmerSkin(npc);
            case BARD -> bardSkin(npc);
            case MINER -> minerSkin(npc);
            default -> 0;
        };
    }

    /**
     * The rig: the skin's own in its set ({@link VillagerTheme#geoOf}: London and Rustic skins carry their 3D pieces
     * there), else the shared one. Follows the picture drawn, so a skin the player left out goes back to the shared rig.
     */
    @Override
    public ResourceLocation getModelResource(T entity) {
        ResourceLocation own = ownRig(entity);
        if (own != null) return own;
        if (entity instanceof com.alex.test.entity.visitor.NomadEntity)
            return nomadHasHood(entity) ? MODEL_NOMAD : MODEL_NOMAD_HOOD_OFF;
        return MODEL_BASE_VILLAGER;
    }

    @javax.annotation.Nullable
    private ResourceLocation ownRig(T entity) {
        VillagerTheme set = VillagerTheme.setOf(getTextureResource(entity));
        if (set == VillagerTheme.CLASSIC) return null;
        String skin = rigSkin(entity);
        return skin == null ? null : set.geoOf(skin);
    }

    /** {@link FarmerDirtTextures#changeName} of each farmer skin, made once (asked every frame). */
    private static final String[] FARMER_KEYS = farmerKeys();

    private static String[] farmerKeys() {
        String[] out = new String[FarmerDirtTextures.SKIN_COUNT];
        for (int i = 0; i < out.length; i++) out[i] = FarmerDirtTextures.changeName(i);
        return out;
    }

    /** The name of the skin drawn now ({@link #skinKey}), which names its own rig; null: the shared rig. */
    @javax.annotation.Nullable
    private static String rigSkin(PathfinderMob entity) {
        if (entity instanceof MayorEntity) return TEXTURE_MAYOR.key();
        if (entity instanceof com.alex.test.entity.visitor.NomadEntity) {
            int outfit = nomadOutfit(entity);
            return (nomadHasHood(entity) ? TEXTURES_NOMAD_HOOD[outfit] : TEXTURES_NOMAD_HOOD_DOWN[outfit]).key();
        }
        if (entity instanceof com.alex.test.entity.visitor.VisitorEntity) return TEXTURE_MERCHANT.key();
        if (!(entity instanceof NpcEntity npc)) return null;
        return switch (npc.getProfession()) {
            case CAPTAIN -> ownCaptainSkin() ? TEXTURE_CAPTAIN.key() : nth(TEXTURES_GUARD, skinNumber(npc));
            case FARMER -> FARMER_KEYS[farmerSkin(npc)];
            case ENCHANTER -> enchanterSkin(npc).key();
            case CHILD, NONE -> null;
            default -> skinKey(npc.getProfession(), skinNumber(npc));
        };
    }

    private static VillagerTheme.Skin enchanterSkin(NpcEntity npc) {
        return uuidVariant(npc, 2) == 1 && TEXTURE_ENCHANTER_2.get() != null ? TEXTURE_ENCHANTER_2 : TEXTURE_ENCHANTER;
    }

    /**
     * Whether captains wear their own skin now (the set drawn paints one): then no cape is hung on them
     * ({@code CapeGeoLayer}), the skin has its own shoulders.
     */
    public static boolean ownCaptainSkin() {
        return TEXTURE_CAPTAIN.get() != null;
    }

    @Override
    public ResourceLocation getTextureResource(T entity) {
        if (entity instanceof MayorEntity) return TEXTURE_MAYOR.get();
        // Travellers are checked before the NpcEntity branch they inherit from.
        if (entity instanceof com.alex.test.entity.visitor.NomadEntity) {
            int outfit = nomadOutfit(entity);
            return (nomadHasHood(entity) ? TEXTURES_NOMAD_HOOD[outfit] : TEXTURES_NOMAD_HOOD_DOWN[outfit]).get();
        }
        if (entity instanceof com.alex.test.entity.visitor.VisitorEntity) return TEXTURE_MERCHANT.get();
        if (entity instanceof NpcEntity npc) {
            return switch (npc.getProfession()) {
                case LUMBERJACK -> TEXTURES_LUMBERJACK[lumberjackSkin(npc)].get();
                case GUARD -> pickVariant(TEXTURES_GUARD, entity);
                // A captain wears its set's own captain skin, else the guard skins with its cape on top (CapeGeoLayer).
                case CAPTAIN -> {
                    ResourceLocation own = TEXTURE_CAPTAIN.get();
                    yield own != null ? own : pickVariant(TEXTURES_GUARD, entity);
                }
                // Each farmer's own skin at its synced dirt stage.
                case FARMER -> FarmerDirtTextures.texture(farmerSkin(npc), npc.getDirtStage());
                case RANCHER -> TEXTURE_RANCHER.get();
                case COOK -> TEXTURE_COOK.get();
                case BLACKSMITH -> TEXTURE_BLACKSMITH.get();
                case BUTCHER -> TEXTURE_BUTCHER.get();
                case MINER -> TEXTURES_MINER[minerSkin(npc)].get();
                case BUILDER -> TEXTURE_BUILDER.get();
                case BARD -> TEXTURES_BARD[bardSkin(npc)].get();
                case CLERIC -> TEXTURE_CLERIC.get();
                case DRUID -> TEXTURE_DRUID.get();
                case ENCHANTER -> enchanterSkin(npc).get();
                case TEACHER -> TEXTURE_TEACHER.get();
                case TRADESMAN -> TEXTURE_TRADESMAN.get();
                case ARCHER -> pickVariant(TEXTURES_ARCHER, entity);
                // Its hooded midnight robe (tools/art/skins/prof_mage.py).
                case MAGE -> TEXTURE_MAGE.get();
                // Children wear the plain skin: being visibly unequipped is the
                // point, the same as the jobless.
                case CHILD -> TEXTURE_NITWIT.get();
                // The jobless keep the plain skin — having no tabard is the point.
                case NONE -> TEXTURE_NITWIT.get();
            };
        }
        return TEXTURE_NITWIT.get();
    }

    @Override
    public ResourceLocation getAnimationResource(T entity) {
        return ANIMATIONS_BASE_VILLAGER;
    }

    @Override
    public ResourceLocation[] getAnimationResourceFallbacks(T entity) {
        return ANIMATIONS_FLYGUYS;
    }

    /**
     * The values the Molang queries below read for the villager being posed: set here, read
     * while GeckoLib poses it right after (render thread only). The queries' variables are
     * fetched once and given a reader of these, instead of new names and lambdas per frame.
     */
    private static final double[] RANDOM_NOW = new double[4];
    private static double noseVariantsNow;
    private static final Variable[] RANDOM_VARIABLES = new Variable[4];
    private static final DoubleSupplier[] RANDOM_READERS = {
            () -> RANDOM_NOW[0], () -> RANDOM_NOW[1], () -> RANDOM_NOW[2], () -> RANDOM_NOW[3]};
    private static final DoubleSupplier NOSE_READER = () -> noseVariantsNow;
    private static Variable noseVariable;

    /** Four independent, stable 0..100 appearance values available to Molang clips. */
    @Override
    public void applyMolangQueries(AnimationState<T> animationState, double animTime) {
        super.applyMolangQueries(animationState, animTime);
        T entity = animationState.getAnimatable();
        UUID id = entity.getUUID();
        for (int i = 0; i < 4; i++)
            RANDOM_NOW[i] = appearanceRandom(id, i + 1);
        noseVariantsNow = hasNoseVariants(entity) ? 1 : 0;
        if (noseVariable == null) {
            for (int i = 0; i < 4; i++)
                RANDOM_VARIABLES[i] = MathParser.getVariableFor("query.villager_random_" + (i + 1));
            noseVariable = MathParser.getVariableFor("query.villager_has_nose_variants");
        }
        // Set again each time (no new objects): stays ours even if something else set them.
        for (int i = 0; i < 4; i++)
            RANDOM_VARIABLES[i].set(RANDOM_READERS[i]);
        noseVariable.set(NOSE_READER);
    }

    private static boolean hasNoseVariants(PathfinderMob entity) {
        // The mayor always keeps the original long, vanilla-style villager nose.
        if (entity instanceof MayorEntity) return false;
        if (entity instanceof com.alex.test.entity.visitor.NomadEntity) return true;
        if (entity instanceof com.alex.test.entity.visitor.VisitorEntity) return true;
        if (entity instanceof NpcEntity npc) {
            // These painted skins contain both remaining nose UV regions. The jobless
            // skin only contains the original nose.
            return switch (npc.getProfession()) {
                case FARMER, GUARD, CAPTAIN, ARCHER, LUMBERJACK,
                     RANCHER, COOK, BLACKSMITH, BUTCHER, MINER, BUILDER, BARD,
                     CLERIC, DRUID, ENCHANTER, TEACHER, TRADESMAN, MAGE -> true;
                default -> false;
            };
        }
        return false;
    }

    private static boolean nomadHasHood(PathfinderMob entity) {
        return appearanceRandom(entity.getUUID(), 2) < 50;
    }

    private static double appearanceRandom(UUID id, int index) {
        long bits = id.getMostSignificantBits()
                ^ Long.rotateLeft(id.getLeastSignificantBits(), index * 13)
                ^ (0x9E3779B97F4A7C15L * index);
        bits = (bits ^ (bits >>> 30)) * 0xBF58476D1CE4E5B9L;
        bits = (bits ^ (bits >>> 27)) * 0x94D049BB133111EBL;
        bits ^= bits >>> 31;
        // Half steps keep the 70/30 nose threshold unambiguous and the
        // UUID-based appearance stable across reloads.
        return (Long.remainderUnsigned(bits, 10_000) + 0.5) / 100.0;
    }

    /**
     * The posing step GeckoLib runs for this model: our faster copy of its own
     * ({@link FastAnimationProcessor}), which is GeckoLib's own whenever fast posing is off.
     * GeckoLib registers the bones only with its private processor, so
     * {@link #getBakedModel} registers them here too.
     */
    private final FastAnimationProcessor<T> fastProcessor = new FastAnimationProcessor<>(this);
    @javax.annotation.Nullable
    private software.bernie.geckolib.cache.object.BakedGeoModel fastProcessorModel;

    @Override
    public software.bernie.geckolib.animation.AnimationProcessor<T> getAnimationProcessor() {
        return fastProcessor;
    }

    @Override
    public software.bernie.geckolib.cache.object.BakedGeoModel getBakedModel(ResourceLocation location) {
        software.bernie.geckolib.cache.object.BakedGeoModel model = super.getBakedModel(location);
        if (model != fastProcessorModel) {
            fastProcessor.setActiveModel(model);
            fastProcessorModel = model;
        }
        return model;
    }

    @Override
    public void setCustomAnimations(T entity, long instanceId, AnimationState<T> animationState) {
        super.setCustomAnimations(entity, instanceId, animationState);
        // Bone visibility is set once, right after this, in handleAnimations.
        // An archer backing off mid-draw walks its legs rather than gliding in its stance.
        if (entity instanceof NpcEntity npc) {
            AimWalkLegs.apply(npc, getAnimationProcessor()::getBone, animationState.getPartialTick());
        }
    }

    /**
     * GeckoLib 4.8.4's {@code handleAnimations} returns before {@code setCustomAnimations}
     * when the same instance renders twice at one tick time. Iris renders every entity in
     * its shadow pass and again in the main pass, so that happens every frame. The baked
     * bones are shared by every renderer's model, though. A model that only ever renders
     * one entity (the visitor's, for the lone merchant) then kept whichever villager last
     * set the bones, and the merchant rendered without its hat. Setting visibility here
     * as well, after super, runs it on every render call.
     *
     * <p>The pose had the same leak: the merchant (and the mayor) wore whatever arm and
     * leg pose the last villager drawn left on the shared bones. So when any other
     * model or mob posed the bones since this one, GeckoLib's "drew it last" note is
     * cleared and the skip cannot happen. A true repeat with nothing in between still
     * skips, so the shader shadow pass costs nothing extra for most villagers.
     */
    @Override
    public void handleAnimations(T entity, long instanceId, AnimationState<T> animationState, float partialTick) {
        long cost = ClientCostLog.partStart();       // [ClientCost] posing share; 0 when not logging
        // A crowd stand-in (StandIn) is posed by GeckoLib every third frame; between, its kept poses.
        NpcEntity standIn = entity instanceof NpcEntity npc && StandIn.keepsPoseOf(npc) ? npc : null;
        // Plan W8 (PoseRate): on a fast screen the others are posed on 1 frame in 2 (or 3, ...), blended between.
        NpcEntity paced = standIn == null && entity instanceof NpcEntity villager && PoseRate.paces(villager)
                ? villager : null;
        GeoBone[] posed = standIn != null || paced != null ? posedBones() : null;
        if (posed != null && (standIn != null ? StandIn.replay(standIn, posed) : PoseRate.replay(paced, posed))) {
            lastPoser = null;                         // the bones hold a kept pose, not GeckoLib's last
            applyVisibility(entity);
            ClientCostLog.partEnd(ClientCostLog.Part.ANIMATION, cost);
            return;
        }
        if (lastPoser != this || lastPosedInstance != instanceId) {
            // The accessor is an optional mixin (towntopia.geckolib.mixins.json): a GeckoLib that renamed
            // the field leaves it out, and the game still starts; only the second-draw fix is off then.
            if ((Object) this instanceof GeoModelAccessor accessor) accessor.setLastRenderedInstance(-1);
            else warnNoAccessorOnce();
        }
        super.handleAnimations(entity, instanceId, animationState, partialTick);
        lastPoser = this;
        lastPosedInstance = instanceId;
        applyVisibility(entity);
        if (standIn != null) StandIn.keep(standIn, posed);
        else if (paced != null) PoseRate.keep(paced, posed);
        ClientCostLog.partEnd(ClientCostLog.Part.ANIMATION, cost);
    }

    private static boolean noAccessorWarned;

    private static void warnNoAccessorOnce() {
        if (noAccessorWarned) return;
        noAccessorWarned = true;
        com.mojang.logging.LogUtils.getLogger().warn("[VillagerGeoModel] GeckoLib has no 'lastRenderedInstance' to clear "
                + "(the GeoModelAccessor mixin did not apply): under a shader pack a merchant or mayor drawn between a "
                + "villager's two passes may keep that villager's pose. Everything else works.");
    }

    /**
     * Every bone GeckoLib poses, looked up once per baked model (by its head bone), for {@link StandIn}'s kept poses.
     * The same array each time for one rig: StandIn tells a villager's kept pose apart by it.
     */
    private final Map<GeoBone, GeoBone[]> posedByRig = new java.util.IdentityHashMap<>();

    private GeoBone[] posedBones() {
        GeoBone anchor = getAnimationProcessor().getBone("head");
        GeoBone[] known = posedByRig.get(anchor);
        if (known == null) {
            known = getAnimationProcessor().getRegisteredBones().toArray(new GeoBone[0]);
            if (posedByRig.size() > 256) posedByRig.clear();             // rigs of past reloads
            posedByRig.put(anchor, known);
        }
        return known;
    }

    /** Which model and mob last posed the shared bones; render thread only. */
    private static VillagerGeoModel<?> lastPoser;
    private static long lastPosedInstance = -1;

    /**
     * The bones {@link #applyVisibility} sets, looked up by name once per baked model
     * instead of about forty lookups per villager per frame. Null where the rig lacks one
     * (the nomad rigs lack most); {@code pieces} follows {@link #PIECE_BONES}.
     */
    private record VisibilityBones(GeoBone anchor, GeoBone[] pieces, GeoBone[] alwaysHidden, GeoBone[][] ownPieces) {}

    /** Armour slots, in the order of {@link VisibilityBones#ownPieces}. */
    private static final EquipmentSlot[] ARMOUR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};
    /**
     * Words in a per-skin piece's name that keep it showing over armour (and every piece hung on it): carried gear,
     * and faces under a helmet. Clothes (capes, skirts, hems, boots, hats, hair) hide.
     */
    private static final String[] OVER_ARMOUR = {"sword", "dagger", "pouch", "lantern", "shield", "whistle",
            "tash", "moustache", "beard"};

    /**
     * The lookups per rig, by its head bone (a resource reload or another rig brings other bone objects, the head
     * included). One per rig: with per-skin rigs ({@link #getModelResource}) a crowd switches rigs every villager.
     */
    private final Map<GeoBone, VisibilityBones> visibilityByRig = new java.util.IdentityHashMap<>();

    private VisibilityBones visibilityBones() {
        GeoBone anchor = getAnimationProcessor().getBone("head");
        VisibilityBones known = anchor == null ? null : visibilityByRig.get(anchor);
        if (known != null) return known;
        GeoBone[] hiddenAlways = bones(new String[]{BARD_VIOLIN_BONES[0], BARD_VIOLIN_BONES[1],
                BardFluteGeoLayer.FLUTE_LOCATOR, ShieldGeoLayer.SHIELD_BONE});
        VisibilityBones found = new VisibilityBones(anchor, bones(PIECE_BONES), hiddenAlways, ownPieces());
        if (anchor != null) {                              // a rig without a head is looked up each time
            if (visibilityByRig.size() > 256) visibilityByRig.clear();   // rigs of past reloads
            visibilityByRig.put(anchor, found);
        }
        return found;
    }

    /**
     * A per-skin rig's own pieces (bones named {@code vic_*} / {@code rus_*}) by the armour slot that hides them,
     * {@link #ARMOUR_SLOTS} order: on the head under a helmet (like {@link #HAT_BONES}), on the hips and arms under a
     * chestplate, on the thighs under leggings, on the shins under boots. Gear and faces ({@link #OVER_ARMOUR}) are
     * left out, so they show over armour.
     */
    private GeoBone[][] ownPieces() {
        java.util.List<java.util.List<GeoBone>> out = new java.util.ArrayList<>();
        for (int i = 0; i < ARMOUR_SLOTS.length; i++) out.add(new java.util.ArrayList<>());
        for (GeoBone bone : getAnimationProcessor().getRegisteredBones()) {
            if (!isOwnPiece(bone.getName())) continue;
            boolean overArmour = false;
            GeoBone up = bone;
            for (; up != null && isOwnPiece(up.getName()); up = up.getParent())   // up to the body part it hangs on
                overArmour |= showsOverArmour(up.getName());
            int slot = up == null ? -1 : armourSlot(up.getName());
            if (!overArmour && slot >= 0) out.get(slot).add(bone);
        }
        GeoBone[][] bySlot = new GeoBone[ARMOUR_SLOTS.length][];
        for (int i = 0; i < bySlot.length; i++) bySlot[i] = out.get(i).toArray(new GeoBone[0]);
        return bySlot;
    }

    private static boolean isOwnPiece(String name) {
        return name.startsWith("vic_") || name.startsWith("rus_");
    }

    private static boolean showsOverArmour(String name) {
        for (String word : OVER_ARMOUR) if (name.contains(word)) return true;
        return false;
    }

    /** Index in {@link #ARMOUR_SLOTS} of the armour that covers this body bone; -1: none. */
    private static int armourSlot(String bodyBone) {
        return switch (bodyBone) {
            case "head" -> 0;
            case "hips", "left_arm", "bone", "right_arm", "bone2" -> 1;
            case "left_leg", "right_leg" -> 2;
            case "left_shin", "right_shin" -> 3;
            default -> -1;
        };
    }

    private GeoBone[] bones(String[] names) {
        GeoBone[] out = new GeoBone[names.length];
        for (int i = 0; i < names.length; i++)
            out[i] = getAnimationProcessor().getBone(names[i]);
        return out;
    }

    private static void hide(GeoBone bone, boolean hidden) {
        if (bone != null) bone.setHidden(hidden);
    }

    /**
     * Which pieces show for this villager: those its pack lists for its job and skin ({@link #PACK_PIECES}), hats
     * hidden under an armour helmet; see {@link #handleAnimations}.
     */
    private void applyVisibility(T entity) {
        VisibilityBones bones = visibilityBones();
        int wearer = wearer(entity);
        boolean[] worn = null;
        if (wearer >= 0) {
            // The pack of the file drawn now: a preview's forced set, the set in use, or the player's change.
            boolean[][] rows = WORN[VillagerTheme.setOf(getTextureResource(entity)).ordinal()][wearer];
            // Same pick as the skin, so each bard or miner wears the extras its skin paints.
            worn = rows.length == 1 ? rows[0] : rows[Math.floorMod(skinNumber(entity), rows.length)];
        }
        boolean helmet = entity.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof ArmorItem;
        GeoBone[] pieces = bones.pieces();
        for (int i = 0; i < pieces.length; i++)
            hide(pieces[i], worn == null || !worn[i] || (helmet && IS_HAT[i]));
        GeoBone[][] own = bones.ownPieces();
        for (int slot = 0; slot < own.length; slot++) {
            boolean covered = slot == 0 ? helmet
                    : entity.getItemBySlot(ARMOUR_SLOTS[slot]).getItem() instanceof ArmorItem;
            for (GeoBone piece : own[slot])
                hide(piece, covered);
        }
        // Always hidden: BardViolinGeoLayer and BardFluteGeoLayer draw the violin, bow and
        // flute at those bones (boxless locators in base_villager; the nomad rigs still carry
        // the old boxed violin and flute on them, never shown). The shield bone's box is only
        // a stand-in for placing it in Blockbench; ShieldGeoLayer draws the real shield there.
        // Hidden hides the box, not the bone, so the layers still get the bone's position.
        for (GeoBone bone : bones.alwaysHidden())
            hide(bone, true);
    }
}
