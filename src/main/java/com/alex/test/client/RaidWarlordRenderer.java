package com.alex.test.client;

import com.alex.test.village.raid.warlord.RaidWarlord;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Draws the Illager Warlord: a vanilla-sized illager rig drawn {@link #MODEL_SCALE}
 * times bigger (as the Giant is a big zombie), with glowing eyes. Its hit box is
 * the entity type's, not this scale.
 */
public final class RaidWarlordRenderer extends GeoEntityRenderer<RaidWarlord> {

    /** Same as MODEL_SCALE in tools/art/generate_warlord.py. */
    public static final float MODEL_SCALE = 1.7F;

    public RaidWarlordRenderer(EntityRendererProvider.Context context) {
        super(context, new RaidWarlordModel());
        withScale(MODEL_SCALE);
        this.shadowRadius = 1.4F;
        addRenderLayer(new WarlordEyesLayer(this));
    }

    /** No vanilla sideways tip when it dies: the death clip kneels and falls forward. */
    @Override
    protected float getDeathMaxRotation(RaidWarlord animatable) {
        return 0.0F;
    }
}
