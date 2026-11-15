package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.village.raid.warlord.RaidWarlord;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

/**
 * The Warlord's GeckoLib model ({@code geo/entity/raid_warlord.geo.json} and its
 * animation and texture). The head turns to where it looks, but only so far:
 * 25 degrees either way and never tipped up, or the helmet would swing into the
 * banner standing 3 pixels off its back (looking down only swings it away).
 * Clips never move the head bone, so setting it outright here loses nothing.
 */
public final class RaidWarlordModel extends DefaultedEntityGeoModel<RaidWarlord> {

    private static final float MAX_YAW = RaidWarlord.MAX_HEAD_YAW;
    private static final float MAX_PITCH_DOWN = 40.0F;

    public RaidWarlordModel() {
        super(ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "raid_warlord"));
    }

    @Override
    public void setCustomAnimations(RaidWarlord animatable, long instanceId, AnimationState<RaidWarlord> state) {
        GeoBone head = getAnimationProcessor().getBone("head");
        if (head == null) return;
        EntityModelData data = state.getData(DataTickets.ENTITY_MODEL_DATA);
        // Pitch is positive looking down, as GeckoLib's own head turning applies it.
        head.setRotX(Mth.clamp(data.headPitch(), 0.0F, MAX_PITCH_DOWN) * Mth.DEG_TO_RAD);
        head.setRotY(Mth.clamp(data.netHeadYaw(), -MAX_YAW, MAX_YAW) * Mth.DEG_TO_RAD);
    }
}
