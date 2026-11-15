package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.village.raid.warlord.RaidWarlord;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/**
 * The Warlord's glowing eyes, from {@code raid_warlord_glowmask.png}; enraged,
 * from {@code raid_warlord_enraged_glowmask.png}, which burns brighter. GeckoLib
 * finds a glow mask next to a base texture, so the enraged mask sits beside a
 * copy of the skin, {@code raid_warlord_enraged.png}.
 */
public final class WarlordEyesLayer extends AutoGlowingGeoLayer<RaidWarlord> {

    private static final ResourceLocation ENRAGED = ResourceLocation.fromNamespaceAndPath(
            DynamicVillages.MODID, "textures/entity/raid_warlord_enraged.png");

    public WarlordEyesLayer(GeoRenderer<RaidWarlord> renderer) {
        super(renderer);
    }

    @Override
    protected ResourceLocation getTextureResource(RaidWarlord animatable) {
        return animatable.isEnraged() ? ENRAGED : super.getTextureResource(animatable);
    }
}
