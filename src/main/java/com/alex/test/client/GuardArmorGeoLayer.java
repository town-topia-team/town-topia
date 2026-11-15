package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.util.RenderUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The villagers' own armor: whatever is in the head, chest, legs and feet slots,
 * drawn with the guard armor models instead of vanilla's.
 *
 * <p>Models are {@code geo/armor/guard_<piece>.geo.json} and textures
 * {@code textures/entity/armor/guard/<material>_<piece>.png}, both made by
 * {@code tools/armor/build_guard_armor.py} from the Blockbench files in
 * {@code tools/armor/source}. Each armor bone is drawn when the renderer reaches
 * the villager bone it rides on, in that bone's frame. Arm and leg pieces go
 * through {@link BentLimb#renderWorn} so they bend with the limb.
 *
 * <p>Leather is tinted by its dye and has an undyed overlay (studs, soles).
 * Enchanted pieces get the vanilla armor shimmer. Armor from other mods uses
 * the iron look.
 *
 * <p>Detail by size on screen ({@link DetailCull}): the pieces are built of panels
 * one model pixel thick, plus trims, rivets and straps that are one pixel across in
 * two directions (58 of the 135 cubes). Far off, those trims are left out
 * ({@link #isTrim}); the panels never are, since they cover the villager underneath.
 * From mid range ({@link DetailCull#boxArmor}) each arm and leg piece is one plain box
 * ({@link ArmorFarModel}) that still bends with the limb; smaller still, those pieces are
 * drawn stiff ({@link BentLimb#renderWorn}).
 */
public class GuardArmorGeoLayer<T extends NpcEntity> extends GeoRenderLayer<T> implements NpcGeoRenderer.PerBone {

    private enum Piece {
        HELMET(EquipmentSlot.HEAD, "helmet"),
        CHESTPLATE(EquipmentSlot.CHEST, "chestplate"),
        LEGGINGS(EquipmentSlot.LEGS, "leggings"),
        BOOTS(EquipmentSlot.FEET, "boots");

        final EquipmentSlot slot;
        final String name;
        final ResourceLocation model;

        Piece(EquipmentSlot slot, String name) {
            this.slot = slot;
            this.name = name;
            this.model = ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "geo/armor/guard_" + name + ".geo.json");
        }
    }

    /** One armor bone, and which part of the limb it covers (null off the limbs). */
    private record Part(Piece piece, String bone, BentLimb.WornOn on) {}

    private static final String TORSO = "#torso";

    /** Villager bone -> the armor drawn from it. */
    private static final Map<String, List<Part>> PARTS = Map.of(
            "head", List.of(new Part(Piece.HELMET, "armor_head", null),
                            new Part(Piece.HELMET, "armor_feather", null)),
            TORSO, List.of(new Part(Piece.CHESTPLATE, "armor_hips", null)),
            "left_arm", limbArm("left"),
            "right_arm", limbArm("right"),
            "left_leg", limbLeg("left"),
            "right_leg", limbLeg("right"));

    private static List<Part> limbArm(String side) {
        return List.of(new Part(Piece.CHESTPLATE, "armor_" + side + "_arm", BentLimb.WornOn.UPPER),
                       new Part(Piece.CHESTPLATE, "armor_" + side + "_forearm", BentLimb.WornOn.LOWER),
                       new Part(Piece.CHESTPLATE, "armor_" + side + "_elbow", BentLimb.WornOn.JOINT));
    }

    private static List<Part> limbLeg(String side) {
        return List.of(new Part(Piece.LEGGINGS, "armor_" + side + "_leg", BentLimb.WornOn.UPPER),
                       new Part(Piece.LEGGINGS, "armor_" + side + "_knee", BentLimb.WornOn.JOINT),
                       new Part(Piece.LEGGINGS, "armor_" + side + "_shin", BentLimb.WornOn.LOWER),
                       new Part(Piece.BOOTS, "armor_" + side + "_foot", BentLimb.WornOn.LOWER));
    }

    /** Materials with their own textures; anything else wears iron's. */
    private static final Set<String> MATERIALS = Set.of("leather", "chainmail", "iron", "gold", "diamond", "netherite");

    /** Per material, its textures by piece: plate at {@code 2 * ordinal}, leather's overlay after it. */
    private static final Map<String, ResourceLocation[]> TEXTURES = new HashMap<>();
    /** Each armor item's material name, worked out once. Render thread only. */
    private static final Map<Item, String> MATERIAL_OF = new java.util.IdentityHashMap<>();

    /** Armor bones by name, per baked model; rebuilt when a resource reload bakes new ones. */
    private static final Map<BakedGeoModel, Map<String, ArmorBone>> BONES = new java.util.IdentityHashMap<>();

    /**
     * An armor bone and its cubes without the trims, both in the order they were authored;
     * and its far stand-ins ({@link ArmorFarModel}): without trims, and with the trims that
     * poke out. Pieces the far box can't stand in for keep their own cubes there. Last, the crowd
     * stand-in's cubes (helmet and chest as plain boxes, {@link StandIn}).
     */
    private record ArmorBone(GeoBone bone, List<GeoCube> withoutTrims, List<GeoCube> far, List<GeoCube> farWithTrims,
                             List<GeoCube> standIn) {}

    /** Armor bones whose biggest panel stays apart in the stand-in box ({@link ArmorFarModel#standIn}): the helmet's cap. */
    private static final Set<String> STAND_IN_KEEPS_BIGGEST = Set.of("armor_head");

    /** A cube this thin across its middle size, in model pixels, is a trim ({@link #isTrim}). */
    private static final double TRIM_SIZE = 1.05;

    /** Drawing order of the noted pieces: every plate first, leather's studs over them, the shimmer last. */
    private static final int PASS_BASE = 0;
    private static final int PASS_OVERLAY = 1;
    private static final int PASS_GLINT = 2;

    /**
     * An armor piece met while the body was drawn, to draw after it; see
     * {@link DrawBatching}. {@code texture} is null for the enchantment shimmer.
     */
    private record Pending(PoseStack.Pose pose, GeoBone bone, Part part, List<GeoCube> cubes,
                           ResourceLocation texture, int pass, int colour, int light) {}

    private final List<Pending> pending = new ArrayList<>();
    /** The villager {@link #pending} belongs to; anything left from another is dropped. */
    private T pendingFor;
    private final List<ResourceLocation> groupTextures = new ArrayList<>();
    private final PoseStack replay = new PoseStack();
    /** The villager counted as armored this frame ({@link VillagerFrameStats}), and the frame. */
    private T countedFor;
    private int countedFrame = -1;

    public GuardArmorGeoLayer(GeoRenderer<T> renderer) {
        super(renderer);
    }

    @Override
    public void renderForBone(PoseStack poseStack, T entity, GeoBone bone,
                              RenderType renderType, MultiBufferSource bufferSource,
                              VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        if (entity.isInvisible() || bone.isHidden()) return;
        List<Part> parts = PARTS.get(isTorso(bone) ? TORSO : bone.getName());
        if (parts == null) return;

        boolean later = DrawBatching.afterBody;
        if (later && pendingFor != entity) {
            pending.clear();
            pendingFor = entity;
        }
        boolean trimsOff = DetailCull.dropsArmorTrims();
        PoseStack.Pose kept = null;
        for (Part part : parts) {
            ItemStack stack = entity.getItemBySlot(part.piece().slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem armor)) continue;
            ArmorBone armorBone = armorBone(part.piece(), part.bone());
            if (armorBone == null) continue;
            if (countedFor != entity || countedFrame != VillagerFrameStats.frame()) {
                countedFor = entity;
                countedFrame = VillagerFrameStats.frame();
                VillagerFrameStats.armored(trimsOff, DetailCull.stiffArmor());
            }

            List<GeoCube> cubes = trimsOff ? armorBone.withoutTrims() : armorBone.bone().getCubes();
            // Arm and leg pieces as one plain box: once stiff, and from mid range (plan P3), where
            // the box is still swept along the bent limb like the panels were.
            if (part.on() != null && DetailCull.armorFarModel && (DetailCull.stiffArmor() || DetailCull.boxArmor()))
                cubes = trimsOff ? armorBone.far() : armorBone.farWithTrims();
            // A crowd stand-in wears its helmet and chestplate as a few plain boxes too.
            else if (part.on() == null && StandIn.drawing())
                cubes = armorBone.standIn();
            if (cubes.isEmpty()) continue;
            String material = materialOf(armor);
            boolean leather = material.equals("leather");
            int colour = leather
                    ? 0xFF000000 | DyedItemColor.getOrDefault(stack, DyedItemColor.LEATHER_COLOR)
                    : 0xFFFFFFFF;
            ResourceLocation plate = texture(material, part.piece(), false);
            ResourceLocation studs = leather ? texture(material, part.piece(), true) : null;
            boolean shimmer = stack.hasFoil();

            if (later) {
                // Every piece on this bone shares its frame: one copy.
                if (kept == null) kept = DrawBatching.keep(poseStack);
                pending.add(new Pending(kept, bone, part, cubes, plate, PASS_BASE, colour, packedLight));
                if (studs != null)
                    pending.add(new Pending(kept, bone, part, cubes, studs, PASS_OVERLAY, 0xFFFFFFFF, packedLight));
                if (shimmer)
                    pending.add(new Pending(kept, bone, part, cubes, null, PASS_GLINT, 0xFFFFFFFF, packedLight));
                continue;
            }

            draw(poseStack, entity, bone, part, cubes,
                    bufferSource.getBuffer(RenderType.armorCutoutNoCull(plate)), packedLight, colour);
            if (studs != null)
                draw(poseStack, entity, bone, part, cubes,
                        bufferSource.getBuffer(RenderType.armorCutoutNoCull(studs)), packedLight, 0xFFFFFFFF);
            if (shimmer)
                draw(poseStack, entity, bone, part, cubes,
                        bufferSource.getBuffer(RenderType.armorEntityGlint()), packedLight, 0xFFFFFFFF);
        }
    }

    /**
     * Draws the pieces noted while the body was drawn, one texture at a time:
     * each texture is asked for once, so the graphics card gets one batch per
     * texture instead of two per armored bone. GeckoLib calls this once the body
     * is done, for the same villager, so its bones and {@link BentLimb}'s limb
     * shapes still hold this villager's pose.
     */
    @Override
    public void render(PoseStack poseStack, T entity, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick,
                       int packedLight, int packedOverlay) {
        if (pending.isEmpty()) return;
        if (pendingFor != entity) {
            pending.clear();
            pendingFor = null;
            return;
        }
        for (int pass = PASS_BASE; pass <= PASS_GLINT; pass++) {
            groupTextures.clear();
            for (Pending piece : pending) {
                if (piece.pass() == pass && !groupTextures.contains(piece.texture())) groupTextures.add(piece.texture());
            }
            for (ResourceLocation texture : groupTextures) {
                VertexConsumer out = bufferSource.getBuffer(texture == null
                        ? RenderType.armorEntityGlint() : RenderType.armorCutoutNoCull(texture));
                for (Pending piece : pending) {
                    if (piece.pass() != pass || !Objects.equals(piece.texture(), texture)) continue;
                    DrawBatching.moveTo(replay, piece.pose());
                    draw(replay, entity, piece.bone(), piece.part(), piece.cubes(), out, piece.light(), piece.colour());
                }
            }
        }
        pending.clear();
        pendingFor = null;
    }

    private void draw(PoseStack poseStack, T entity, GeoBone bone, Part part, List<GeoCube> cubes,
                      VertexConsumer buffer, int packedLight, int colour) {
        int overlay = OverlayTexture.NO_OVERLAY;
        GeoRenderer<T> renderer = getRenderer();
        if (part.on() == null) {
            drawPlain(renderer, poseStack, cubes, buffer, packedLight, overlay, colour);
            return;
        }
        if (entity.hasRawLimbs()) {
            // Debug view: limbs are two rigid boxes posed by their own bones.
            GeoBone lower = BentLimb.lowerOf(bone);
            poseStack.pushPose();
            if (part.on() != BentLimb.WornOn.UPPER && lower != null)
                RenderUtil.prepMatrixForBone(poseStack, lower);
            drawPlain(renderer, poseStack, cubes, buffer, packedLight, overlay, colour);
            poseStack.popPose();
            return;
        }
        BentLimb.renderWorn(renderer, poseStack, bone, cubes, part.on(), DetailCull.stiffArmor(),
                buffer, packedLight, overlay, colour);
    }

    /** Only the bones in {@link #PARTS}: the head, the torso and the four limbs' upper bones. */
    @Override
    public boolean usesBone(GeoBone bone) {
        return PARTS.containsKey(isTorso(bone) ? TORSO : bone.getName());
    }

    private static void drawPlain(GeoRenderer<?> renderer, PoseStack poseStack, List<GeoCube> cubes,
                                  VertexConsumer buffer, int packedLight, int overlay, int colour) {
        VillagerFrameStats.armorQuads(ArmorFarModel.quads(cubes));
        for (GeoCube cube : cubes)
            CubeWriter.draw(renderer, poseStack, cube, buffer, packedLight, overlay, colour);
    }

    private static ArmorBone armorBone(Piece piece, String name) {
        BakedGeoModel model = GeckoLibCache.getBakedModels().get(piece.model);
        if (model == null) return null;
        Map<String, ArmorBone> bones = BONES.get(model);
        if (bones == null) {
            if (BONES.size() > 8) BONES.clear();   // old bakes from earlier resource reloads
            bones = new HashMap<>();
            for (GeoBone b : model.topLevelBones()) {
                List<GeoCube> withoutTrims = new ArrayList<>();
                List<GeoCube> trims = new ArrayList<>();
                for (GeoCube cube : b.getCubes())
                    (isTrim(cube) ? trims : withoutTrims).add(cube);
                List<GeoCube> box = ArmorFarModel.merge(withoutTrims);
                List<GeoCube> far = box == null ? List.copyOf(withoutTrims) : box;
                List<GeoCube> farWithTrims = box == null ? b.getCubes() : new ArrayList<>(far);
                if (box != null) farWithTrims.addAll(ArmorFarModel.outside(trims, box.get(0)));
                List<GeoCube> standIn = ArmorFarModel.standIn(withoutTrims, STAND_IN_KEEPS_BIGGEST.contains(b.getName()));
                bones.put(b.getName(), new ArmorBone(b, List.copyOf(withoutTrims), far, List.copyOf(farWithTrims),
                        standIn == null ? List.copyOf(withoutTrims) : standIn));
            }
            BONES.put(model, bones);
        }
        return bones.get(name);
    }

    /**
     * A trim, rivet or strap: at most a pixel across in two of its three sizes (the
     * middle one, inflation counted). A plate panel is a pixel thick too, but wide in
     * the other two, and stays: leaving it out would open a hole onto the villager.
     */
    private static boolean isTrim(GeoCube cube) {
        double grow = 2 * cube.inflate();
        double x = cube.size().x + grow, y = cube.size().y + grow, z = cube.size().z + grow;
        double middle = Math.max(Math.min(x, y), Math.min(Math.max(x, y), z));
        return middle <= TRIM_SIZE;
    }

    private static String materialOf(ArmorItem armor) {
        String known = MATERIAL_OF.get(armor);
        if (known != null) return known;
        String name = armor.getMaterial().unwrapKey().map(k -> k.location().getPath()).orElse("iron");
        String material = MATERIALS.contains(name) ? name : "iron";
        MATERIAL_OF.put(armor, material);
        return material;
    }

    /** No text built per call: each material's textures are made once and kept. */
    private static ResourceLocation texture(String material, Piece piece, boolean overlay) {
        ResourceLocation[] textures = TEXTURES.computeIfAbsent(material, m -> new ResourceLocation[Piece.values().length * 2]);
        int slot = piece.ordinal() * 2 + (overlay ? 1 : 0);
        ResourceLocation texture = textures[slot];
        if (texture == null) {
            texture = ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "textures/entity/armor/guard/"
                    + material + "_" + piece.name + (overlay ? "_overlay" : "") + ".png");
            textures[slot] = texture;
        }
        return texture;
    }

    /** Same torso rule as {@link CapeGeoLayer}: {@code hips} on base_villager, {@code body} on the old rig. */
    private static boolean isTorso(GeoBone bone) {
        return "hips".equals(bone.getName())
                || ("body".equals(bone.getName()) && !bone.getCubes().isEmpty());
    }
}
