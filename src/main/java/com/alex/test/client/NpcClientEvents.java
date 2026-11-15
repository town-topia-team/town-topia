package com.alex.test.client;

import com.alex.test.ClientConfig;
import com.alex.test.DynamicVillages;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.minecraft.world.level.block.Blocks;

@EventBusSubscriber(value = Dist.CLIENT)
public class NpcClientEvents {

    @SubscribeEvent
    public static void registerRenderer(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(DynamicVillages.NPC.get(), NpcGeoRenderer::new);
        event.registerEntityRenderer(DynamicVillages.MAYOR.get(), MayorGeoRenderer::new);
        event.registerEntityRenderer(DynamicVillages.VISITOR.get(), VisitorGeoRenderer::new);
        event.registerEntityRenderer(DynamicVillages.NOMAD.get(), NomadGeoRenderer::new);
        event.registerEntityRenderer(DynamicVillages.RAID_WARLORD.get(), RaidWarlordRenderer::new);
        event.registerEntityRenderer(DynamicVillages.MINI_MERCHANT_PREVIEW.get(), MiniMerchantRenderer::new);
        event.registerEntityRenderer(DynamicVillages.MID_MERCHANT_PREVIEW.get(),
                context -> new MiniMerchantRenderer(context, "mid_merchant"));
    }

    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new ThoughtIconPreloader());
        // Also makes every skin set look for its files again (VillagerTheme).
        event.registerReloadListener(new FarmerDirtTextures());
    }

    /** The client config loaded or was edited by hand: villagers follow its skin set at once. */
    @SubscribeEvent
    public static void configLoaded(ModConfigEvent.Loading event) {
        ClientConfig.applyModernDefaultOnce();
        VillagerTheme.refresh();
    }

    @SubscribeEvent
    public static void configChanged(ModConfigEvent.Reloading event) {
        VillagerTheme.refresh();
    }

    /** Muddy wash water in cauldrons farmers have washed in; replaces vanilla's water-cauldron colour. */
    @SubscribeEvent
    public static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register(ClientMuddyCauldrons::waterColor, Blocks.WATER_CAULDRON);
    }

    /** The smith's hot bar: yellow when fresh from the fire, orange as it cools (SmithTongs sets the colour). */
    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register(SmithTongs::barColor, com.alex.test.ModItems.SMITH_BAR.get());
    }
}
