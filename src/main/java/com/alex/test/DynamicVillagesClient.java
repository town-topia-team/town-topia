package com.alex.test;

import com.alex.test.client.VillageBorderRenderer;
import com.alex.test.client.VillageAlertOutline;
import com.alex.test.client.BuildingLocateHighlight;
import com.alex.test.ui.screen.GenericSettingsScreen;
import com.alex.test.ui.screen.FarmerSettingsScreen;
import com.alex.test.ui.screen.GuardSettingsScreen;
import com.alex.test.ui.screen.mayor.MayorStatsScreen;
import com.alex.test.ui.screen.MerchantScreen;
import com.alex.test.ui.screen.LumberjackSettingsScreen;
import com.alex.test.ui.screen.VillagerScreen;
import com.alex.test.ui.screen.StorageRoomScreen;
import com.alex.test.client.StorageChestButton;
import com.alex.test.client.StorageTokenHint;
import com.alex.test.client.ChairSeatRenderer;
import com.alex.test.client.ArcherBowPull;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = DynamicVillages.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = DynamicVillages.MODID, value = Dist.CLIENT)
public class DynamicVillagesClient {
    public DynamicVillagesClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        // Settings the game keeps for itself (ClientConfig.shownOnScreen) are left out of it.
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new ConfigurationScreen(mod, parent,
                (context, key, element) -> ClientConfig.shownOnScreen(key) ? element : null));
        // The player's own display choices (ClientConfig), kept on this computer.
        LegacyModId.copyOldConfig(ModConfig.Type.CLIENT); // settings from before the TownTopia rename
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

        // Register the border renderer on the NeoForge GAME bus (not mod bus).
        // Only runs on the physical client because this entire class is @Mod(dist = CLIENT).
        NeoForge.EVENT_BUS.register(VillageBorderRenderer.class);
        NeoForge.EVENT_BUS.register(VillageAlertOutline.class);
        NeoForge.EVENT_BUS.register(BuildingLocateHighlight.class);
        NeoForge.EVENT_BUS.register(StorageChestButton.class);
        NeoForge.EVENT_BUS.register(StorageTokenHint.class);
        NeoForge.EVENT_BUS.register(com.alex.test.client.SellBoxNote.class);
        NeoForge.EVENT_BUS.register(com.alex.test.client.PlayerGoodsTooltip.class);
        NeoForge.EVENT_BUS.register(com.alex.test.ui.screen.BuilderPlanClient.class);
        NeoForge.EVENT_BUS.register(com.alex.test.ui.screen.BuildOrderClient.class);
        NeoForge.EVENT_BUS.register(com.alex.test.client.ConstructionSiteOutline.class);
        NeoForge.EVENT_BUS.register(com.alex.test.client.ClaimLandClient.class);
        NeoForge.EVENT_BUS.register(com.alex.test.client.JoinTips.class);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Item properties live in a plain map; register off the parallel setup threads.
        event.enqueueWork(ArcherBowPull::register);
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(DynamicVillages.CHAIR_SEAT.get(), ChairSeatRenderer::new);
        event.registerEntityRenderer(DynamicVillages.HEALING_ORB.get(),
                com.alex.test.client.HealingOrbRenderer::new);
        // The bolt is light, not a thing: nothing is drawn but its particle trail.
        event.registerEntityRenderer(DynamicVillages.ARCANE_BOLT.get(),
                net.minecraft.client.renderer.entity.NoopRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterKeys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        // Builder's plan: Turn, Mirror, Up, Down (Options, Controls, TownTopia).
        com.alex.test.ui.screen.BuilderPlanClient.registerKeys(event);
        // Stuck catcher ghost: step back, step forward, hide the panel.
        com.alex.test.client.StuckGhostView.registerKeys(event);
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(DynamicVillages.VILLAGER_MENU.get(), VillagerScreen::new);
        event.register(DynamicVillages.MAYOR_STATS_MENU.get(), MayorStatsScreen::new);
        event.register(DynamicVillages.MERCHANT_MENU.get(), MerchantScreen::new);
        event.register(
                DynamicVillages.LUMBERJACK_SETTINGS_MENU.get(),
                LumberjackSettingsScreen::new);
        event.register(
                DynamicVillages.GUARD_SETTINGS_MENU.get(),
                GuardSettingsScreen::new);
        event.register(
                DynamicVillages.FARMER_SETTINGS_MENU.get(),
                FarmerSettingsScreen::new);
        event.register(
                DynamicVillages.GENERIC_SETTINGS_MENU.get(),
                GenericSettingsScreen::new);
        event.register(DynamicVillages.STORAGE_ROOM_MENU.get(), StorageRoomScreen::new);
        event.register(DynamicVillages.HOUSE_ROOM_MENU.get(), com.alex.test.ui.screen.HouseRoomScreen::new);
        event.register(DynamicVillages.MINE_ROOM_MENU.get(), com.alex.test.ui.screen.MineRoomScreen::new);
        event.register(DynamicVillages.BUTCHERY_ROOM_MENU.get(), com.alex.test.ui.screen.ButcheryRoomScreen::new);
        event.register(DynamicVillages.RANCH_ROOM_MENU.get(), com.alex.test.ui.screen.RanchRoomScreen::new);
        event.register(DynamicVillages.BUILDER_DESK_MENU.get(), com.alex.test.ui.screen.BuilderDeskScreen::new);
    }
}
