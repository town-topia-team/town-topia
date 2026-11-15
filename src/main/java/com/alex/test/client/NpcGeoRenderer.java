package com.alex.test.client;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.util.RenderUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * GeckoLib renderer for {@link NpcEntity} (the village-task puppet).
 *
 * <p>Uses {@link VillagerGeoModel} for mesh, texture, and animation resources.
 * Animation controllers are registered on the entity via {@link NpcEntity#registerControllers}.
 * Arms and legs are drawn by {@link BentLimb} so they curve at the elbow and knee
 * instead of creasing.
 */
public class NpcGeoRenderer extends GeoEntityRenderer<NpcEntity> {

    private final BentLimb.Dispatcher bend = new BentLimb.Dispatcher(this);

    /**
     * How big a child is next to an adult.
     *
     * <p>Applied at render time rather than by scaling the entity: the villager
     * rig, its animations and its held-item placements are all authored against
     * the adult proportions, and a child is the same villager in every respect
     * except how tall it is.
     */
    static final float CHILD_SCALE = 0.6F;

    public NpcGeoRenderer(EntityRendererProvider.Context context) {
        super(context, new VillagerGeoModel<>());
        this.renderLayers.addLayer(new VillageAlertOutlineLayer<>(this));
        this.renderLayers.addLayer(new HeldItemGeoLayer<>(this));
        this.renderLayers.addLayer(new GuardArmorGeoLayer<>(this));
        this.renderLayers.addLayer(new ShieldGeoLayer<>(this));
        this.renderLayers.addLayer(new CapeGeoLayer<>(this));
        this.renderLayers.addLayer(new BardBassGeoLayer<>(this));
        this.renderLayers.addLayer(new BardBongosGeoLayer<>(this));
        this.renderLayers.addLayer(new BardViolinGeoLayer<>(this));
        this.renderLayers.addLayer(new BardFluteGeoLayer<>(this));
        this.renderLayers.addLayer(new BardGuitarGeoLayer<>(this));
    }

    @Override
    public void scaleModelForRender(float widthScale, float heightScale, PoseStack poseStack,
                                    NpcEntity animatable, BakedGeoModel model,
                                    boolean isReRender, float partialTick,
                                    int packedLight, int packedOverlay) {
        if (animatable.getProfession() == Profession.CHILD) {
            widthScale *= CHILD_SCALE;
            heightScale *= CHILD_SCALE;
        }
        super.scaleModelForRender(widthScale, heightScale, poseStack, animatable, model,
                isReRender, partialTick, packedLight, packedOverlay);
    }

    @Override
    public void renderCubesOfBone(PoseStack poseStack, GeoBone bone, VertexConsumer buffer,
                                  int packedLight, int packedOverlay, int colour) {
        long cost = bone.isHidden() ? 0L : ClientCostLog.partStart();
        boolean limb = BentLimb.isLimbBone(bone.getName());
        drawCubes(poseStack, bone, buffer, packedLight, packedOverlay, colour, limb);
        // Arms and legs (bent or not) apart from the rest of the body (plan W0).
        ClientCostLog.partEnd(limb ? ClientCostLog.Part.LIMBS : ClientCostLog.Part.CUBES, cost);
    }

    private void drawCubes(PoseStack poseStack, GeoBone bone, VertexConsumer buffer,
                           int packedLight, int packedOverlay, int colour, boolean limb) {
        // Eyes, noses, hats and such are left out once the villager is small on screen
        // (DetailCull). Children of a skipped bone are listed there too: GeckoLib still
        // recurses into them.
        if (!bone.isHidden() && DetailCull.hidesBone(bone.getName())) {
            VillagerFrameStats.detailBone();
            return;
        }

        // Squashed to nothing (the nose this villager does not have): no face could show (plan W1).
        if (!bone.isHidden() && CubeWriter.squashed(bone)) {
            VillagerFrameStats.squashedBone();
            return;
        }

        // Debug view: every bone as its own rigid boxes, posed exactly as the clip says.
        if (this.animatable != null && this.animatable.hasRawLimbs()) {
            if (!bone.isHidden()) countFaces(bone, limb);
            super.renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, colour);
            return;
        }
        if (bend.handled(poseStack, bone, buffer, packedLight, packedOverlay, colour))
            return;

        // GeckoLib skips hidden bones (hats, props) inside super; count only what is drawn.
        int faces = boneCubes(poseStack, bone, buffer, packedLight, packedOverlay, colour);
        if (limb) VillagerFrameStats.limbQuads(faces);
        else VillagerFrameStats.bodyQuads(faces);
    }

    /** The faces GeckoLib's own drawing writes for {@code bone} (every face it baked). */
    private static void countFaces(GeoBone bone, boolean limb) {
        int faces = 0;
        for (GeoCube cube : bone.getCubes())
            faces += CubeWriter.faces(cube);
        if (limb) VillagerFrameStats.limbQuads(faces);
        else VillagerFrameStats.bodyQuads(faces);
    }

    /**
     * GeckoLib's {@code renderCubesOfBone}; with {@link CubeWriter} no copy of the pose per cube.
     *
     * @return the faces written
     */
    private int boneCubes(PoseStack poseStack, GeoBone bone, VertexConsumer buffer,
                          int packedLight, int packedOverlay, int colour) {
        if (bone.isHidden())
            return 0;
        if (!CubeWriter.fast) {
            int faces = 0;
            for (GeoCube cube : bone.getCubes())
                faces += CubeWriter.faces(cube);
            super.renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, colour);
            return faces;
        }
        List<GeoCube> cubes = bone.getCubes();
        PoseStack.Pose frame = poseStack.last();
        // Plans W4 + W5: what this villager's skin shows of each cube (null outside its own body pass).
        int[] masks = SkinFaces.masks(bone);
        int faces = 0;
        for (int i = 0; i < cubes.size(); i++)
            faces += CubeWriter.cube(frame, cubes.get(i), buffer, packedLight, packedOverlay, colour,
                    SkinFaces.mask(masks, i));
        return faces;
    }

    /**
     * Every other cube of ours (bent limbs' blocky halves, armor, instruments) also comes
     * here; {@link CubeWriter} draws it the same, leaving the stack as it was.
     */
    @Override
    public void renderCube(PoseStack poseStack, GeoCube cube, VertexConsumer buffer,
                           int packedLight, int packedOverlay, int colour) {
        if (CubeWriter.fast)
            CubeWriter.cube(poseStack.last(), cube, buffer, packedLight, packedOverlay, colour);
        else
            super.renderCube(poseStack, cube, buffer, packedLight, packedOverlay, colour);
    }

    /** Saved frames of the bone walk ({@link #renderRecursively}), one per depth. Render thread only. */
    private Matrix4f[] walkPose = new Matrix4f[0];
    private Matrix3f[] walkNormal = new Matrix3f[0];
    private int walkDepth;
    private final Quaternionf boneTurn = new Quaternionf();

    /**
     * GeckoLib's walk over the bones ({@code GeoEntityRenderer.renderRecursively}) with two
     * savings (30 FPS plan, round 1b; {@code /villagerfps writer old} for GeckoLib's own):
     * <ul>
     *   <li>A bone with nothing to draw under it is not walked at all: other jobs' hats and
     *       outfits (about half of the ~90 bones), and the eyes and nose once they are left
     *       out for size ({@link DetailCull}). Never a bone a layer draws on.</li>
     *   <li>Each bone's frame is set up on the stack's top frame and put back afterwards,
     *       instead of pushing a copy (three new objects per bone). Bones that keep their
     *       matrices for other code, or are squashed unevenly (that sets Minecraft's normals
     *       flag on the frame), still get GeckoLib's push.</li>
     * </ul>
     */
    @Override
    public void renderRecursively(PoseStack poseStack, NpcEntity animatable, GeoBone bone, RenderType renderType,
                                  MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                                  float partialTick, int packedLight, int packedOverlay, int colour) {
        if (!CubeWriter.fast) {
            super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
                    partialTick, packedLight, packedOverlay, colour);
            return;
        }
        if (nothingToDraw(bone, isReRender)) {
            countDetailBones(bone);
            return;
        }
        if (bone.isTrackingMatrices() || bone.getScaleX() != bone.getScaleY() || bone.getScaleY() != bone.getScaleZ()) {
            super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
                    partialTick, packedLight, packedOverlay, colour);
            return;
        }

        PoseStack.Pose top = poseStack.last();
        int depth = walkDepth++;
        if (depth == walkPose.length) {
            walkPose = java.util.Arrays.copyOf(walkPose, depth + 8);
            walkNormal = java.util.Arrays.copyOf(walkNormal, depth + 8);
            for (int i = depth; i < walkPose.length; i++) {
                walkPose[i] = new Matrix4f();
                walkNormal[i] = new Matrix3f();
            }
        }
        Matrix4f keptPose = walkPose[depth].set(top.pose());
        Matrix3f keptNormal = walkNormal[depth].set(top.normal());
        try {
            // GeckoLib's steps (RenderUtil.prepMatrixForBone order), the turns on one kept quaternion.
            RenderUtil.translateMatrixToBone(poseStack, bone);
            RenderUtil.translateToPivotPoint(poseStack, bone);
            if (bone.getRotZ() != 0) poseStack.mulPose(boneTurn.rotationZ(bone.getRotZ()));
            if (bone.getRotY() != 0) poseStack.mulPose(boneTurn.rotationY(bone.getRotY()));
            if (bone.getRotX() != 0) poseStack.mulPose(boneTurn.rotationX(bone.getRotX()));
            RenderUtil.scaleMatrixForBone(poseStack, bone);
            RenderUtil.translateAwayFromPivotPoint(poseStack, bone);

            buffer = checkAndRefreshBuffer(isReRender, buffer, bufferSource, renderType);
            renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, colour);
            if (!isReRender)
                applyRenderLayersForBone(poseStack, animatable, bone, renderType, bufferSource, buffer,
                        partialTick, packedLight, packedOverlay);
            renderChildBones(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
                    partialTick, packedLight, packedOverlay, colour);
        } finally {
            top.pose().set(keptPose);
            top.normal().set(keptNormal);
            walkDepth--;
        }
    }

    /**
     * Nothing of {@code bone} or under it would be drawn: it is hidden (by its villager's
     * job, or left out for size) with no layer drawing on it, and so is everything below it
     * that GeckoLib would still visit.
     */
    private boolean nothingToDraw(GeoBone bone, boolean isReRender) {
        boolean hidden = bone.isHidden();
        if (!hidden && !DetailCull.hidesBone(bone.getName()))
            return false;                           // its own cubes show
        if (bone.isTrackingMatrices() || (!isReRender && !layersFor(bone).isEmpty()))
            return false;
        if (hidden && bone.isHidingChildren())
            return true;
        List<GeoBone> children = bone.getChildBones();
        for (int i = 0; i < children.size(); i++)
            if (!nothingToDraw(children.get(i), isReRender))
                return false;
        return true;
    }

    /** The left-out bones a skipped walk would have met, for {@link VillagerFrameStats} as before. */
    private static void countDetailBones(GeoBone bone) {
        if (!bone.isHidden() && DetailCull.hidesBone(bone.getName()))
            VillagerFrameStats.detailBone();
        if (bone.isHidingChildren())
            return;
        List<GeoBone> children = bone.getChildBones();
        for (int i = 0; i < children.size(); i++)
            countDetailBones(children.get(i));
    }

    /**
     * How far away a villager still shows its name: {@link DrawBatching#nameDistance}
     * blocks ({@code /villagerfps names}), instead of GeckoLib's 64. The one under the
     * crosshair keeps the full reach, so a far villager can still be read by looking
     * at it. Names are the costliest extra after armor: each one sends the villager's
     * half-built batch to the graphics card on its own (spark, 2026-10-16).
     */
    @Override
    public double getNameRenderCutoffDistance(NpcEntity animatable) {
        double full = super.getNameRenderCutoffDistance(animatable);
        if (isLookedAt(animatable)) return full;
        return Math.min(DrawBatching.nameDistance, full);
    }

    /**
     * A villager's name shows only while the player looks at it ({@link VillagerLabels}).
     * With {@code /villagerstatus on}, every villager in reach shows its name and what it is
     * doing, as the old labels did. A custom name forced visible (previews, demos) still shows.
     * In a crowd only the nearest 30 of those do, plus the one looked at ({@link NameTagLimit}).
     */
    @Override
    public boolean shouldShowName(NpcEntity animatable) {
        if (animatable == nameDecidedFor) {
            nameDecidedFor = null;
            return nameDecided;
        }
        return decideName(animatable);
    }

    /** {@link #renderFinal} already decided this villager's name; Minecraft asks right after. */
    @Nullable
    private NpcEntity nameDecidedFor;
    private boolean nameDecided;

    private boolean decideName(NpcEntity animatable) {
        boolean shown = nameWanted(animatable) && VillagerLabels.nameVisibleToPlayer(animatable)
                && NameTagLimit.allows(animatable, this.entityRenderDispatcher.distanceToSqr(animatable),
                        isLookedAt(animatable));
        if (shown) VillagerFrameStats.named();
        return shown;
    }

    private boolean isLookedAt(NpcEntity npc) {
        return npc == this.entityRenderDispatcher.crosshairPickEntity || npc == VillagerLabels.lookedAt();
    }

    private boolean nameWanted(NpcEntity npc) {
        boolean status = VillagerLabels.statusShown();
        if (!npc.hasCustomName() && !(status && !npc.workStatus().isEmpty())) return false;
        if (isLookedAt(npc)) return true;
        if (!status && !npc.isCustomNameVisible()) return false;
        double cutoff = getNameRenderCutoffDistance(npc);
        return this.entityRenderDispatcher.distanceToSqr(npc) < cutoff * cutoff;
    }

    /** With {@code /villagerstatus on}, "Bram · work" instead of the name alone. */
    @Override
    protected void renderNameTag(NpcEntity entity, Component displayName, PoseStack poseStack,
                                 MultiBufferSource bufferSource, int packedLight, float partialTick) {
        Component text = VillagerLabels.statusShown() ? VillagerLabels.withStatus(entity, displayName) : displayName;
        super.renderNameTag(entity, text, poseStack, bufferSource, packedLight, partialTick);
    }

    /**
     * Thought and conversation bubbles are drawn after the model, from the
     * entity's own origin: GeckoLib has restored the pose stack by the time
     * {@code super.render} returns, so the bubble is positioned in world axes
     * and never picks up the villager's body yaw.
     */
    /** {@code /villagerfps hide on}: no villager drawn at all, to read the frame's cost without them (plan step 0e). */
    public static volatile boolean hidden;

    @Override
    public void render(NpcEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        if (hidden) return;
        VillagerFrameStats.villager(this.entityRenderDispatcher.distanceToSqr(entity));
        // Counted only while the cost log is on: villagers standing where the camera cannot see (step 3's size).
        if (ClientCostLog.on && SectionVisibility.unseen(entity)) VillagerFrameStats.unseen();
        long cost = ClientCostLog.villagerBegin(entity);
        // In the world's entity pass: one buffer per texture for the whole crowd (CrowdBatching).
        MultiBufferSource source = CrowdBatching.wrap(bufferSource);
        try {
            DetailCull.begin(entity, poseStack);
            StandIn.begin(entity);
            try {
                super.render(entity, entityYaw, partialTick, poseStack, source, packedLight);
            } finally {
                StandIn.end();
                DetailCull.end();
            }
            if (StandIn.showsCopyOf(entity))
                renderStandInCopy(entity, entityYaw, partialTick, poseStack, source, packedLight);
            long bubble = ClientCostLog.partStart();
            ThoughtBubbleRenderer.render(entity, poseStack, source, partialTick);
            ClientCostLog.partEnd(ClientCostLog.Part.BUBBLE, bubble);
        } finally {
            ClientCostLog.villagerEnd(cost);
        }
    }

    /**
     * {@code /villagerfps standin show}: the villager looked at drawn once more, a little to
     * its right on screen, as a crowd stand-in (StandIn), to compare the two. Same pose, no
     * name, shadow or bubble.
     */
    private void renderStandInCopy(NpcEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                   MultiBufferSource source, int packedLight) {
        org.joml.Vector3f left = this.entityRenderDispatcher.camera.getLeftVector();
        float flat = (float) Math.sqrt(left.x() * left.x() + left.z() * left.z());
        if (flat < 1e-3f) return;                    // looking straight down: no "beside"
        float step = StandIn.SHOW_OFFSET / flat;
        poseStack.pushPose();
        poseStack.translate(-left.x() * step, 0f, -left.z() * step);
        DetailCull.begin(entity, poseStack);
        StandIn.beginCopy(entity);
        try {
            super.render(entity, entityYaw, partialTick, poseStack, source, packedLight);
        } finally {
            StandIn.end();
            DetailCull.end();
            poseStack.popPose();
        }
    }

    // ---------------------------------------------------------------------------------------
    // [ClientCost] timing (/villagerfps log on): each step of GeckoLib's drawing, timed on the
    // sampled villager only. With logging off, partStart() is 0 and nothing else changes.
    // ---------------------------------------------------------------------------------------

    /** Posing (handleAnimations, timed in VillagerGeoModel) and the walk over every bone. */
    @Override
    public void actuallyRender(PoseStack poseStack, NpcEntity animatable, BakedGeoModel model,
                               @Nullable RenderType renderType, MultiBufferSource bufferSource,
                               @Nullable VertexConsumer buffer, boolean isReRender, float partialTick,
                               int packedLight, int packedOverlay, int colour) {
        if (isReRender) {
            // A whole-model redraw by a layer (the alert outline): counted as that layer's time.
            ClientCostLog.quiet(true);
            try {
                super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer, true,
                        partialTick, packedLight, packedOverlay, colour);
            } finally {
                ClientCostLog.quiet(false);
            }
            return;
        }
        long cost = ClientCostLog.partStart();
        // Plans W4 + W5: the body's empty faces and closed boxes' back faces, for this skin.
        SkinFaces.begin(this, animatable, renderType, poseStack, colour);
        try {
            super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer, false,
                    partialTick, packedLight, packedOverlay, colour);
        } finally {
            SkinFaces.end();
        }
        ClientCostLog.partEnd(ClientCostLog.Part.POSED_BODY, cost);
    }

    /**
     * Every layer's per-bone work together (armor notes, held items, shield), asking
     * each layer only about the bones it draws on ({@link PerBone}). GeckoLib's own
     * loop asks all ten layers about all ninety-odd bones of every villager, every
     * frame, and nearly every answer is "not mine".
     */
    @Override
    public void applyRenderLayersForBone(PoseStack poseStack, NpcEntity animatable, GeoBone bone,
                                         RenderType renderType, MultiBufferSource bufferSource,
                                         VertexConsumer buffer, float partialTick,
                                         int packedLight, int packedOverlay) {
        long cost = ClientCostLog.partStart();
        List<GeoRenderLayer<NpcEntity>> layers = layersFor(bone);
        for (int i = 0; i < layers.size(); i++)
            layers.get(i).renderForBone(poseStack, animatable, bone, renderType, bufferSource, buffer,
                    partialTick, packedLight, packedOverlay);
        ClientCostLog.partEnd(ClientCostLog.Part.BONE_EXTRAS, cost);
    }

    /**
     * A render layer that only ever draws on some bones; the renderer asks it about
     * those alone ({@link #applyRenderLayersForBone}). Asked once per bone and
     * remembered, so the answer must not depend on the villager: it has to cover
     * every villager the layer could draw. A layer without this is asked about every
     * bone, as GeckoLib does.
     */
    interface PerBone {
        boolean usesBone(GeoBone bone);
    }

    /** Per bone, the layers that draw on it. Bones are shared by every villager model. */
    private final Map<GeoBone, List<GeoRenderLayer<NpcEntity>>> layersByBone = new IdentityHashMap<>();
    private int layersByBoneVersion = -1, layersByBoneCount = -1;
    /** Bumped when a layer's bones change ({@link #boneUseChanged}). */
    private static volatile int boneUseVersion;

    /** A layer now draws on other bones (the held item moved by {@code /helditem}): ask again. */
    static void boneUseChanged() {
        boneUseVersion++;
    }

    private List<GeoRenderLayer<NpcEntity>> layersFor(GeoBone bone) {
        List<GeoRenderLayer<NpcEntity>> all = getRenderLayers();
        // A resource reload bakes new bones; the old ones are dropped once the map grows.
        if (layersByBoneVersion != boneUseVersion || layersByBoneCount != all.size() || layersByBone.size() > 1024) {
            layersByBone.clear();
            layersByBoneVersion = boneUseVersion;
            layersByBoneCount = all.size();
        }
        List<GeoRenderLayer<NpcEntity>> layers = layersByBone.get(bone);
        if (layers == null) {
            List<GeoRenderLayer<NpcEntity>> uses = new ArrayList<>();
            for (GeoRenderLayer<NpcEntity> layer : all)
                if (!(layer instanceof PerBone filter) || filter.usesBone(bone)) uses.add(layer);
            layers = List.copyOf(uses);
            layersByBone.put(bone, layers);
        }
        return layers;
    }

    /** Each layer's after-body drawing (armor, cape, instruments), timed layer by layer. */
    @Override
    public void applyRenderLayers(PoseStack poseStack, NpcEntity animatable, BakedGeoModel model,
                                  @Nullable RenderType renderType, MultiBufferSource bufferSource,
                                  @Nullable VertexConsumer buffer, float partialTick,
                                  int packedLight, int packedOverlay) {
        if (!ClientCostLog.timing()) {
            super.applyRenderLayers(poseStack, animatable, model, renderType, bufferSource, buffer,
                    partialTick, packedLight, packedOverlay);
            return;
        }
        if (animatable.isSpectator()) return;           // as GeoEntityRenderer does
        List<GeoRenderLayer<NpcEntity>> layers = getRenderLayers();
        for (int i = 0; i < layers.size(); i++) {
            long start = System.nanoTime();
            layers.get(i).render(poseStack, animatable, model, renderType, bufferSource, buffer,
                    partialTick, packedLight, packedOverlay);
            ClientCostLog.layer(i, layers, System.nanoTime() - start);
        }
    }

    /**
     * The name tag (EntityRenderer.render) and a leash. Minecraft builds the villager's
     * display name and sends NeoForge's name tag event for every mob, every frame, shown or
     * not; here only for a villager whose name shows or that is on a lead (30 FPS plan,
     * round 1c). Another mod forcing names on through that event no longer sees the rest.
     */
    @Override
    public void renderFinal(PoseStack poseStack, NpcEntity animatable, BakedGeoModel model,
                            MultiBufferSource bufferSource, @Nullable VertexConsumer buffer,
                            float partialTick, int packedLight, int packedOverlay, int colour) {
        if (StandIn.drawingCopy()) return;           // the show copy: no second name or lead
        long cost = ClientCostLog.partStart();
        boolean name = decideName(animatable);
        if (name || animatable.getLeashHolder() != null) {
            nameDecidedFor = animatable;
            nameDecided = name;
            try {
                super.renderFinal(poseStack, animatable, model, bufferSource, buffer, partialTick,
                        packedLight, packedOverlay, colour);
            } finally {
                nameDecidedFor = null;
            }
        }
        ClientCostLog.partEnd(ClientCostLog.Part.NAME_TAG, cost);
    }

    /**
     * Nothing: GeckoLib 4.8.4's {@code renderFinal} draws the lead itself right after
     * Minecraft's {@code EntityRenderer.render}, which in 1.21 already draws it for every
     * leashable mob, so each villager's lead was built twice a frame.
     */
    @Override
    public <E extends net.minecraft.world.entity.Entity, M extends net.minecraft.world.entity.Mob> void renderLeash(
            M mob, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, E leashHolder) {
    }
}
