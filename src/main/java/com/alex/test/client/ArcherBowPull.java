package com.alex.test.client;

import com.alex.test.entity.NpcEntity;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.item.ItemPropertyFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Bends the bow an archer draws. Vanilla picks the bow's pulling sprites from its
 * holder's use of the item ({@code pulling}, and {@code pull} = ticks of use / 20),
 * and an archer never uses its bow: it plays the shot clip and the server fires
 * the arrow. So both properties are wrapped: for a villager mid-shot they come from
 * {@link NpcEntity#bowDraw()}, which follows the drawing hand in the clip; for
 * anyone else (players, skeletons, the inventory screen) vanilla answers as before.
 */
public final class ArcherBowPull {

    private static final ResourceLocation PULL = ResourceLocation.withDefaultNamespace("pull");
    private static final ResourceLocation PULLING = ResourceLocation.withDefaultNamespace("pulling");

    private ArcherBowPull() {}

    /** Call once on the main thread after item properties exist (client setup). */
    public static void register() {
        ItemStack probe = new ItemStack(Items.BOW);
        ItemPropertyFunction pull = ItemProperties.getProperty(probe, PULL);
        ItemPropertyFunction pulling = ItemProperties.getProperty(probe, PULLING);
        ItemProperties.register(Items.BOW, PULL, (stack, level, entity, seed) -> {
            float drawn = drawnBy(entity, stack);
            if (drawn >= 0f) return drawn;
            return pull != null ? pull.call(stack, level, entity, seed) : 0f;
        });
        ItemProperties.register(Items.BOW, PULLING, (stack, level, entity, seed) -> {
            if (drawnBy(entity, stack) >= 0f) return 1f;
            return pulling != null ? pulling.call(stack, level, entity, seed) : 0f;
        });
    }

    /** How far {@code entity} has drawn {@code stack}, or -1 if it is not drawing it. */
    private static float drawnBy(LivingEntity entity, ItemStack stack) {
        if (!(entity instanceof NpcEntity npc) || stack != npc.getMainHandItem()) return -1f;
        return npc.bowDraw();
    }
}
