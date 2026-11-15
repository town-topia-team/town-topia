package com.alex.test;

import com.alex.test.village.NavGraph;
import com.alex.test.client.NpcAttributes;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.ChairSeatEntity;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.entity.visitor.VisitorEntity;
import com.alex.test.entity.visitor.NomadEntity;
import com.alex.test.command.VillagerCommands;
import com.alex.test.network.ModPayloads;
import com.alex.test.testkit.BehaviorWatcher;
import com.alex.test.testkit.HeadlessRandomTicker;
import com.alex.test.testkit.TestCommands;
import com.alex.test.ui.GenericSettingsMenu;
import com.alex.test.ui.FarmerSettingsMenu;
import com.alex.test.ui.GuardSettingsMenu;
import com.alex.test.ui.LumberjackSettingsMenu;
import com.alex.test.ui.MayorStatsMenu;
import com.alex.test.ui.MerchantMenu;
import com.alex.test.ui.StorageRoomMenu;
import com.alex.test.village.villagers.guard.behavior.GuardPostEvents;
import com.alex.test.village.villagers.guard.behavior.GuardPostRegistry;
import com.alex.test.village.villagers.guard.behavior.GuardThreatEvents;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionReloadListener;
import com.alex.test.village.tokens.ItemFrameTokenCleanupEvents;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillageSleepEvents;
import com.alex.test.ui.VillagerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;

@Mod(DynamicVillages.MODID)
public class DynamicVillages {

    public static final String MODID = "towntopia";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean serverStopping;

    // Entity types ---------------------------------------------------------------

    /**
     * Hitbox height shared by every villager-shaped entity. Vanilla's 1.95 stood
     * 2.0125 tall on a carpet, taller than a doorway: a villager could not step
     * onto a rug from a door, and a carpeted room two blocks high could not hold
     * one at all. The nav graph plans with this same number.
     */
    public static final float NPC_HEIGHT = (float) NavGraph.ENTITY_HEIGHT;

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<NpcEntity>> NPC =
            ENTITY_TYPES.register("npc",
                    () -> EntityType.Builder.<NpcEntity>of(NpcEntity::new, MobCategory.CREATURE)
                            .sized(0.6F, NPC_HEIGHT)
                            .build("npc"));

    public static final DeferredHolder<EntityType<?>, EntityType<MayorEntity>> MAYOR =
            ENTITY_TYPES.register("mayor",
                    () -> EntityType.Builder.<MayorEntity>of(MayorEntity::new, MobCategory.CREATURE)
                            .sized(0.6F, NPC_HEIGHT)
                            .build("mayor"));

    public static final DeferredHolder<EntityType<?>, EntityType<VisitorEntity>> VISITOR =
            ENTITY_TYPES.register("visitor",
                    () -> EntityType.Builder.<VisitorEntity>of(VisitorEntity::new, MobCategory.CREATURE)
                            .sized(0.6F, NPC_HEIGHT)
                            .build("visitor"));

    public static final DeferredHolder<EntityType<?>, EntityType<NomadEntity>> NOMAD =
            ENTITY_TYPES.register("nomad",
                    () -> EntityType.Builder.<NomadEntity>of(NomadEntity::new, MobCategory.CREATURE)
                            .sized(0.6F, NPC_HEIGHT)
                            .build("nomad"));

    public static final DeferredHolder<EntityType<?>, EntityType<ChairSeatEntity>> CHAIR_SEAT =
            ENTITY_TYPES.register("chair_seat",
                    () -> EntityType.Builder.<ChairSeatEntity>of(ChairSeatEntity::new, MobCategory.MISC)
                            .sized(0.1F, 0.1F)
                            .passengerAttachments(0.1F)
                            .noSummon()
                            .build("chair_seat"));

    /** The cleric's thrown healing orb; tracked every tick so its curve looks smooth. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.alex.test.entity.HealingOrbEntity>> HEALING_ORB =
            ENTITY_TYPES.register("healing_orb",
                    () -> EntityType.Builder.<com.alex.test.entity.HealingOrbEntity>of(
                                    com.alex.test.entity.HealingOrbEntity::new, MobCategory.MISC)
                            .sized(0.3F, 0.3F)
                            .clientTrackingRange(6)
                            .updateInterval(1)
                            .noSummon()
                            .build("healing_orb"));

    /** The mage's Arcane Bolt; tracked every tick so its curve looks smooth. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.alex.test.entity.ArcaneBoltEntity>> ARCANE_BOLT =
            ENTITY_TYPES.register("arcane_bolt",
                    () -> EntityType.Builder.<com.alex.test.entity.ArcaneBoltEntity>of(
                                    com.alex.test.entity.ArcaneBoltEntity::new, MobCategory.MISC)
                            .sized(0.25F, 0.25F)
                            .clientTrackingRange(6)
                            .updateInterval(1)
                            .noSummon()
                            .build("arcane_bolt"));

    /**
     * The Illager Warlord, leader of the Bad Omen raid's last wave. A monster, so
     * guards and archers fight it by default (GuardMobPolicy). 3.0 tall so hits
     * reach its eyes (it is drawn 1.7 times a vanilla illager); that still needs
     * only 3 blocks of headroom. Tracked for 4 chunks: its boss bar shows that far.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<com.alex.test.village.raid.warlord.RaidWarlord>> RAID_WARLORD =
            ENTITY_TYPES.register("raid_warlord",
                    () -> EntityType.Builder.<com.alex.test.village.raid.warlord.RaidWarlord>of(
                                    com.alex.test.village.raid.warlord.RaidWarlord::new, MobCategory.MONSTER)
                            .sized(1.4F, 3.0F)
                            .clientTrackingRange(4)
                            .build("raid_warlord"));

    /** A stand-in showing the Mini Merchant model; spawned by /villager preview minimerchant. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.alex.test.entity.MiniMerchantPreview>> MINI_MERCHANT_PREVIEW =
            ENTITY_TYPES.register("mini_merchant_preview",
                    () -> EntityType.Builder.<com.alex.test.entity.MiniMerchantPreview>of(
                                    com.alex.test.entity.MiniMerchantPreview::new, MobCategory.MISC)
                            .sized(0.6F, 2.2F)
                            .build("mini_merchant_preview"));

    /** The same stand-in showing the Mid Merchant model; spawned by /villager preview midmerchant. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.alex.test.entity.MiniMerchantPreview>> MID_MERCHANT_PREVIEW =
            ENTITY_TYPES.register("mid_merchant_preview",
                    () -> EntityType.Builder.<com.alex.test.entity.MiniMerchantPreview>of(
                                    com.alex.test.entity.MiniMerchantPreview::new, MobCategory.MISC)
                            .sized(0.6F, 1.95F)
                            .build("mid_merchant_preview"));


    // Menu types -----------------------------------------------------------------
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<VillagerMenu>> VILLAGER_MENU =
            MENU_TYPES.register("villager_menu",
                    () -> IMenuTypeExtension.create(VillagerMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<MayorStatsMenu>> MAYOR_STATS_MENU =
            MENU_TYPES.register("mayor_stats_menu",
                    () -> IMenuTypeExtension.create(MayorStatsMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<MerchantMenu>> MERCHANT_MENU =
            MENU_TYPES.register("merchant_menu",
                    () -> IMenuTypeExtension.create(MerchantMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<LumberjackSettingsMenu>>
            LUMBERJACK_SETTINGS_MENU =
            MENU_TYPES.register("lumberjack_settings_menu",
                    () -> IMenuTypeExtension.create(LumberjackSettingsMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<GuardSettingsMenu>>
            GUARD_SETTINGS_MENU =
            MENU_TYPES.register("guard_settings_menu",
                    () -> IMenuTypeExtension.create(GuardSettingsMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<FarmerSettingsMenu>>
            FARMER_SETTINGS_MENU =
            MENU_TYPES.register("farmer_settings_menu",
                    () -> IMenuTypeExtension.create(FarmerSettingsMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<GenericSettingsMenu>>
            GENERIC_SETTINGS_MENU =
            MENU_TYPES.register("generic_settings_menu",
                    () -> IMenuTypeExtension.create(GenericSettingsMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<StorageRoomMenu>> STORAGE_ROOM_MENU =
            MENU_TYPES.register("storage_room_menu",
                    () -> IMenuTypeExtension.create(StorageRoomMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<com.alex.test.ui.HouseRoomMenu>> HOUSE_ROOM_MENU =
            MENU_TYPES.register("house_room_menu",
                    () -> IMenuTypeExtension.create(com.alex.test.ui.HouseRoomMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<com.alex.test.ui.MineRoomMenu>> MINE_ROOM_MENU =
            MENU_TYPES.register("mine_room_menu",
                    () -> IMenuTypeExtension.create(com.alex.test.ui.MineRoomMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<com.alex.test.ui.ButcheryRoomMenu>> BUTCHERY_ROOM_MENU =
            MENU_TYPES.register("butchery_room_menu",
                    () -> IMenuTypeExtension.create(com.alex.test.ui.ButcheryRoomMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<com.alex.test.ui.RanchRoomMenu>> RANCH_ROOM_MENU =
            MENU_TYPES.register("ranch_room_menu",
                    () -> IMenuTypeExtension.create(com.alex.test.ui.RanchRoomMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<com.alex.test.ui.BuilderDeskMenu>> BUILDER_DESK_MENU =
            MENU_TYPES.register("builder_desk_menu",
                    () -> IMenuTypeExtension.create(com.alex.test.ui.BuilderDeskMenu::new));

    // ============================================================================

    public DynamicVillages(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.register(NpcAttributes.class);
        LegacyModId.register(modEventBus);

        ENTITY_TYPES.register(modEventBus);
        MENU_TYPES.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModSounds.register(modEventBus);
        ModCreativeModeTabs.register(modEventBus);

        // Custom payload types, codecs and server-side handlers.
        modEventBus.addListener(ModPayloads::register);
        // ./gradlew runData: settings-screen text into en_us.json for translators.
        modEventBus.addListener(com.alex.test.datagen.SettingsLangData::gather);
        NeoForge.EVENT_BUS.addListener(DynamicVillages::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(com.alex.test.village.building.StorageBuilding::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(com.alex.test.village.visitors.MerchantCatalog::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(com.alex.test.village.economy.MarketPrices::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(DynamicVillages::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(DynamicVillages::onServerStarted);
        NeoForge.EVENT_BUS.addListener(DynamicVillages::onServerStopping);
        NeoForge.EVENT_BUS.addListener(DynamicVillages::onServerStopped);
        NeoForge.EVENT_BUS.register(ItemFrameTokenCleanupEvents.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.tokens.VillageItemTooltipEvents.class);
        NeoForge.EVENT_BUS.register(GuardPostEvents.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.stuckwatch.StuckWatch.class);
        NeoForge.EVENT_BUS.register(GuardThreatEvents.class);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,
                com.alex.test.command.StressTestCommands::keepEndlessAlive);
        NeoForge.EVENT_BUS.register(com.alex.test.village.chat.TownFightBar.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.builder.plan.BuilderPlans.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.builder.project.BuildOrders.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.builder.project.FreeBuild.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.builder.project.ProjectBlueprints.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.builder.copy.PlacedLand.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.raid.RaidEvents.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.golem.GolemEvents.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.cleric.behavior.GuardianAngel.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.cleric.behavior.ClericCastDemo.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.butcher.behavior.ButcherSharpenDemo.class);
        NeoForge.EVENT_BUS.register(com.alex.test.animation.FlyguysDemo.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.guard.GuardSkillEffects.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.archer.ArcherEvents.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.mage.behavior.MageSpells.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.mage.behavior.MageCastDemo.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.raid.warlord.WarlordShowcase.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.mage.behavior.MageLights.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.tradesman.SellBoxEvents.class);
        NeoForge.EVENT_BUS.register(com.alex.test.block.PianoLock.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.claims.ClaimLock.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.claims.LandGuard.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.claims.HostileWatch.class);
        NeoForge.EVENT_BUS.register(com.alex.test.command.ConsoleChatRelay.class);
        NeoForge.EVENT_BUS.register(com.alex.test.command.SpawnedVillagers.class);
        // Automated-testing kit: /vtest commands plus the behaviour timeline sampler.
        NeoForge.EVENT_BUS.register(BehaviorWatcher.class);
        // Headless-only crop random ticks — vanilla skips them with no player online.
        NeoForge.EVENT_BUS.register(HeadlessRandomTicker.class);

        // VillageManager.INSTANCE handles events for the /villager graph scan village.
        NeoForge.EVENT_BUS.register(VillageManager.INSTANCE);
        // VillageManagerRegistry.Events handles town-hall-frame villages.
        NeoForge.EVENT_BUS.register(VillageManagerRegistry.Events.class);
        // A Town Hall token on a generated village's bell takes that village over.
        NeoForge.EVENT_BUS.register(com.alex.test.village.takeover.VanillaVillageTakeover.Events.class);
        NeoForge.EVENT_BUS.register(VillageSleepEvents.class);
        // The village calendar: day numbers that survive /time set (VillageDays).
        NeoForge.EVENT_BUS.register(com.alex.test.village.VillageDaysEvents.class);
        // Visitors camping overnight: breaking their fire upsets them.
        NeoForge.EVENT_BUS.register(com.alex.test.village.visitors.TravelerCampEvents.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.building.CauldronEvents.class);
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.butcher.lead.LeadEvents.class);
        // A butcher's kill: the skill's share on top of the vanilla drops, marked for pickup.
        NeoForge.EVENT_BUS.register(com.alex.test.village.villagers.butcher.ButcherDrops.class);
        // Far villagers stay awake: switches and state reset with the server (FarAwake).
        NeoForge.EVENT_BUS.register(com.alex.test.village.farawake.FarAwake.Events.class);

        LegacyModId.copyOldConfig(ModConfig.Type.COMMON); // settings from before the TownTopia rename
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    public static boolean isServerStopping() {
        return serverStopping;
    }

    private static void onAddReloadListeners(net.neoforged.neoforge.event.AddReloadListenerEvent event) {
        event.addListener(new CropDefinitionReloadListener());
        event.addListener(new com.alex.test.village.economy.MarketPriceReloadListener());
    }

    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        serverStopping = false;
    }

    private static void onServerStarted(ServerStartedEvent event) {
        serverStopping = false;
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        serverStopping = true;
        VillageManager.INSTANCE.pauseRuntimeMovementForShutdown();
        VillageManagerRegistry registry = VillageManagerRegistry.get(event.getServer());
        for (VillageManager manager : registry.all()) {
            manager.pauseRuntimeMovementForShutdown();
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        serverStopping = true;
        com.alex.test.village.brain.VillagerPause.clear();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        VillagerCommands.register(event.getDispatcher());
        com.alex.test.command.PianoOwnerCommand.register(event.getDispatcher()); // development runs only
        TestCommands.register(event.getDispatcher());
        com.alex.test.village.villagers.teacher.SchoolCommands.register(event.getDispatcher());
    }
}
