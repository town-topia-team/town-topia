package com.alex.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import software.bernie.geckolib.model.GeoModel;

/**
 * Lets {@link com.alex.test.client.VillagerGeoModel} clear GeckoLib's "drew this
 * mob last" note, so a second draw in the same frame poses the bones again
 * when another model moved them in between.
 */
@Mixin(value = GeoModel.class, remap = false)
public interface GeoModelAccessor {

    @Accessor("lastRenderedInstance")
    void setLastRenderedInstance(long instanceId);
}
