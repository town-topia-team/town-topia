package com.alex.test.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.ClientHooks;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A held tool drawn as a flat cut-out of its icon, for crowd stand-ins ({@link StandIn}).
 * Minecraft builds a flat item (swords, axes, bows) as its picture front and back plus one
 * small edge face for every pixel step round its outline: a sword is about 70 faces. Far
 * off only the front and back are kept, drawn exactly as Minecraft would (same place,
 * colours, shimmer and render type). Block items, 3D models and items with their own
 * drawing (shield, trident, spyglass, compass, clock) keep Minecraft's path.
 * Render thread only.
 */
final class FlatItem {

    /** Tools drawn flat since the last frame, for {@link StandIn}'s counts. */
    static int drawn;

    /** Per item model, its front and back faces. Old models from a resource reload drop out once it grows. */
    private static final Map<BakedModel, List<BakedQuad>> CARDS = new IdentityHashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static final Direction[] LISTS = {null, Direction.DOWN, Direction.UP, Direction.NORTH,
            Direction.SOUTH, Direction.WEST, Direction.EAST};

    private FlatItem() {}

    /** Draws {@code stack} flat as {@code entity} holds it; false when it has to go Minecraft's way. */
    static boolean render(LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                          MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (stack.isEmpty() || stack.getItem() instanceof BlockItem || stack.is(Items.TRIDENT)
                || stack.is(Items.SPYGLASS) || stack.is(ItemTags.COMPASSES) || stack.is(Items.CLOCK))
            return false;
        ItemRenderer items = Minecraft.getInstance().getItemRenderer();
        BakedModel model = items.getModel(stack, entity.level(), entity, entity.getId());
        // Only Minecraft's plain item models (JSON, overrides already picked): others may draw their own way.
        if (model.getClass() != SimpleBakedModel.class || model.isCustomRenderer() || model.isGui3d())
            return false;
        List<BakedQuad> card = card(model);
        if (card.isEmpty()) return false;

        // ItemRenderer.render, with the card for the quads.
        poseStack.pushPose();
        BakedModel placed = ClientHooks.handleCameraTransforms(poseStack, model, context, false);
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        for (RenderType type : placed.getRenderTypes(stack, true)) {
            VertexConsumer out = ItemRenderer.getFoilBufferDirect(buffers, type, true, stack.hasFoil());
            items.renderQuadList(poseStack, out, card, stack, packedLight, packedOverlay);
        }
        poseStack.popPose();
        drawn++;
        return true;
    }

    /** The model's faces looking straight front and back (the picture), worked out once per model. */
    private static List<BakedQuad> card(BakedModel model) {
        List<BakedQuad> known = CARDS.get(model);
        if (known != null) return known;
        if (CARDS.size() > 512) CARDS.clear();
        List<BakedQuad> card = new ArrayList<>();
        for (Direction list : LISTS) {
            RANDOM.setSeed(42L);
            for (BakedQuad quad : model.getQuads(null, list, RANDOM)) {
                Direction facing = quad.getDirection();
                if (facing == Direction.NORTH || facing == Direction.SOUTH) card.add(quad);
            }
        }
        List<BakedQuad> kept = List.copyOf(card);
        CARDS.put(model, kept);
        return kept;
    }
}
