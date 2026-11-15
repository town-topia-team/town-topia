package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.MiniMerchantPreview;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Draws {@link MiniMerchantPreview} with {@code geo/entity/mini_merchant.geo.json} and
 * {@code textures/entity/mini_merchant.png}, or another merchant model's files of the same
 * name (the Mid Merchant uses {@code mid_merchant}). GeckoLib turns the "head" bone (and the
 * top hat on it) toward where the merchant looks.
 */
public final class MiniMerchantRenderer extends GeoEntityRenderer<MiniMerchantPreview> {

    public MiniMerchantRenderer(EntityRendererProvider.Context context) {
        this(context, "mini_merchant");
    }

    public MiniMerchantRenderer(EntityRendererProvider.Context context, String model) {
        super(context, new DefaultedEntityGeoModel<>(
                ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, model), true));
        this.shadowRadius = 0.5F;
    }
}
