package com.alex.test.network;

import com.alex.test.Tr;
import com.alex.test.client.VillageAlertOutline;
import com.alex.test.client.VillageBorderData;
import com.alex.test.client.StorageChestButton;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.town.MayorShop;
import com.alex.test.ui.BuilderDeskMenu;
import com.alex.test.ui.FarmerSettingsMenu;
import com.alex.test.ui.GenericSettingsMenu;
import com.alex.test.ui.GuardSettingsMenu;
import com.alex.test.ui.LumberjackSettingsMenu;
import com.alex.test.ui.MayorStatsMenu;
import com.alex.test.ui.MerchantMenu;
import com.alex.test.ui.NpcUiMenu;
import com.alex.test.ui.StorageRoomMenu;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageRoomAccess;
import com.alex.test.entity.visitor.VisitorEntity;
import com.alex.test.village.visitors.MerchantCatalog;
import com.alex.test.village.visitors.MerchantTransactions;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.ui.VillagerMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.farmer.settings.FarmerPlantingResources;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.alex.test.village.villagers.farmer.settings.FarmerSettingsChangeHandler;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.guard.settings.GuardSettingsChangeHandler;
import com.alex.test.village.villagers.lumberjack.data.TreeTypeRegistry;
import com.alex.test.village.villagers.lumberjack.settings.LumberjackSettings;
import com.alex.test.village.villagers.lumberjack.settings.LumberjackSettingsChangeHandler;
import com.alex.test.village.villagers.registry.VillagerJobManager;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.settings.ProfessionSettingsChangeHandler;
import com.alex.test.village.villagers.settings.SettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchemaRegistry;
import com.alex.test.village.villagers.tradesman.settings.TradesmanSalePolicy;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers every custom payload the mod sends, and handles the ones that
 * arrive on the server.
 *
 * <p>Registered on the mod event bus by {@link com.alex.test.DynamicVillages}.
 * The protocol version ({@code "1"}) is set in {@link #register}; payload
 * types and codecs are defined by the payload classes of this package.
 * Client-bound handlers touch client-side classes only inside their enqueued
 * lambdas, exactly as they did when this code lived in {@code DynamicVillages}.
 */
public final class ModPayloads {

    private ModPayloads() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(BardPianoPayload.TYPE, BardPianoPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> com.alex.test.client.BardPianoSound.apply(payload)));
        registrar.playToClient(BardTrackPayload.TYPE, BardTrackPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> com.alex.test.client.BardTrackSound.apply(payload)));
        registrar.playToClient(BandSongPayload.TYPE, BandSongPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> com.alex.test.client.BandSongSound.apply(payload)));
        HouseRoomPayloads.register(registrar);
        MineRoomPayloads.register(registrar);
        ButcheryRoomPayloads.register(registrar);
        RanchRoomPayloads.register(registrar);
        MayorBuildingPayloads.register(registrar);
        BuilderDeskPayloads.register(registrar);
        BuilderPlanPayloads.register(registrar);
        BuildOrderPayloads.register(registrar);
        StuckWatchPayloads.register(registrar);
        registrar.playToClient(
                VillageBoundsPayload.TYPE,
                VillageBoundsPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> VillageBorderData.update(payload.bounds())));
        registrar.playToClient(
                MuddyCauldronPayload.TYPE,
                MuddyCauldronPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(
                        () -> com.alex.test.client.ClientMuddyCauldrons.apply(payload)));
        registrar.playToClient(
                VillageVillagerAlertPayload.TYPE,
                VillageVillagerAlertPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> VillageAlertOutline.track(payload)));
        registrar.playToClient(
                StorageRoomPayloads.Available.TYPE,
                StorageRoomPayloads.Available.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> StorageChestButton.available(payload.pos())));
        registrar.playToClient(
                SellBoxNotePayload.TYPE,
                SellBoxNotePayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> com.alex.test.client.SellBoxNote.receive(payload)));
        registrar.playToClient(PianoOwnerPayload.TYPE, PianoOwnerPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> com.alex.test.block.PianoLock.setClientOwner(payload.owner())));
        registrar.playToClient(ClaimLandPayload.TYPE, ClaimLandPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> com.alex.test.village.claims.ClaimLock.setClientLocked(
                        payload.dimension(), payload.squares())));
        registrar.playToServer(
                StorageRoomPayloads.Probe.TYPE,
                StorageRoomPayloads.Probe.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player
                            && StorageRoomAccess.canOpenFromContainer(player, payload.pos())) {
                        PacketDistributor.sendToPlayer(player, new StorageRoomPayloads.Available(payload.pos()));
                    }
                }));
        registrar.playToServer(
                StorageRoomPayloads.OpenContainer.TYPE,
                StorageRoomPayloads.OpenContainer.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) {
                        StorageRoomAccess.openFromContainer(player, payload.pos());
                    }
                }));
        registrar.playToServer(
                StorageRoomPayloads.SetFilter.TYPE,
                StorageRoomPayloads.SetFilter.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    StorageRoomMenu menu = openStorageMenuToManage(ctx, payload.frameId());
                    StorageBuilding.DepositFilter[] values = StorageBuilding.DepositFilter.values();
                    if (menu == null || payload.filter() < 0 || payload.filter() >= values.length) return;
                    for (net.minecraft.core.BlockPos pos : payload.positions()) {
                        menu.serverStorage().toggleDepositFilter((net.minecraft.server.level.ServerLevel)
                                ctx.player().level(), pos, values[payload.filter()], payload.on());
                    }
                }));
        registrar.playToServer(
                StorageRoomPayloads.SetRoomFilter.TYPE,
                StorageRoomPayloads.SetRoomFilter.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    StorageRoomMenu menu = openStorageMenuToManage(ctx, payload.frameId());
                    if (menu == null) return;
                    menu.serverStorage().setDepositFiltersForAll((net.minecraft.server.level.ServerLevel)
                            ctx.player().level(), StorageBuilding.DepositFilter.fromMask(payload.filters()));
                }));
        registrar.playToServer(
                StorageRoomPayloads.SetItems.TYPE,
                StorageRoomPayloads.SetItems.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    StorageRoomMenu menu = openStorageMenuToManage(ctx, payload.frameId());
                    if (menu == null) return;
                    java.util.List<net.minecraft.world.item.Item> items = new java.util.ArrayList<>();
                    for (net.minecraft.resources.ResourceLocation id : payload.items()) {
                        net.minecraft.world.item.Item item =
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
                        if (item != net.minecraft.world.item.Items.AIR && !items.contains(item)) items.add(item);
                    }
                    for (net.minecraft.core.BlockPos pos : payload.positions()) {
                        menu.serverStorage().setChosenItems((net.minecraft.server.level.ServerLevel)
                                ctx.player().level(), pos, items);
                    }
                }));
        registrar.playToServer(
                StorageRoomPayloads.SetLocked.TYPE,
                StorageRoomPayloads.SetLocked.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    StorageRoomMenu menu = openStorageMenuToManage(ctx, payload.frameId());
                    if (menu == null) return;
                    for (net.minecraft.core.BlockPos pos : payload.positions()) {
                        menu.serverStorage().setLocked((net.minecraft.server.level.ServerLevel)
                                ctx.player().level(), pos, payload.locked());
                    }
                }));
        registrar.playToServer(
                StorageRoomPayloads.StockAdd.TYPE,
                StorageRoomPayloads.StockAdd.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> editStock(ctx, payload.frameId(), (reserves, village, level) ->
                        reserves.addRow(village.getVillageId(),
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(payload.item()),
                                payload.once(), payload.forPlayer(), payload.count(),
                                (ServerPlayer) ctx.player(), level.getGameTime()))));
        registrar.playToServer(
                StorageRoomPayloads.StockEdit.TYPE,
                StorageRoomPayloads.StockEdit.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> editStock(ctx, payload.frameId(), (reserves, village, level) ->
                        reserves.editRow(village.getVillageId(), payload.row(), payload.count(),
                                payload.once(), payload.forPlayer()))));
        registrar.playToServer(
                StorageRoomPayloads.StockRemove.TYPE,
                StorageRoomPayloads.StockRemove.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> editStock(ctx, payload.frameId(), (reserves, village, level) ->
                        reserves.removeRow(village.getVillageId(), payload.row(), level.getGameTime()))));
        registrar.playToServer(
                StorageRoomPayloads.StockRequest.TYPE,
                StorageRoomPayloads.StockRequest.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> editStock(ctx, payload.frameId(), (reserves, village, level) -> { })));
        registrar.playToClient(
                StorageRoomPayloads.StockStatus.TYPE,
                StorageRoomPayloads.StockStatus.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    // Common code: the open menu is the only place the reply can land.
                    if (ctx.player() != null && ctx.player().containerMenu instanceof StorageRoomMenu menu) {
                        menu.setStockStatus(payload);
                    }
                }));
        // The Stock tab's Enchanter page: book orders (BookOrders).
        registrar.playToServer(
                BookOrderPayloads.Add.TYPE,
                BookOrderPayloads.Add.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> editBookOrders(ctx, payload.frameId(), (orders, village, level) ->
                        orders.add(village.getVillageId(), payload.kind(), payload.level(), payload.once(),
                                payload.count(), (ServerPlayer) ctx.player(), level.getGameTime()))));
        registrar.playToServer(
                BookOrderPayloads.Edit.TYPE,
                BookOrderPayloads.Edit.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> editBookOrders(ctx, payload.frameId(), (orders, village, level) ->
                        orders.edit(village.getVillageId(), payload.order(), payload.count(), payload.once()))));
        registrar.playToServer(
                BookOrderPayloads.Remove.TYPE,
                BookOrderPayloads.Remove.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> editBookOrders(ctx, payload.frameId(), (orders, village, level) ->
                        orders.remove(village.getVillageId(), payload.order(), level.getDayTime()))));
        registrar.playToClient(
                BookOrderPayloads.Status.TYPE,
                BookOrderPayloads.Status.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() != null && ctx.player().containerMenu instanceof StorageRoomMenu menu) {
                        menu.setBookStatus(payload);
                    }
                }));
        registrar.playToServer(
                LocateVillagerPayload.TYPE,
                LocateVillagerPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleLocateVillager(payload, ctx.player())));
        registrar.playToServer(
                ProfessionReplacementPayload.TYPE,
                ProfessionReplacementPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player
                            && player.level().getEntity(payload.villagerId()) instanceof NpcEntity villager) {
                        VillagerJobManager.selectProfessionReplacement(villager, player, payload.slot());
                    }
                }));
        registrar.playToServer(
                LumberjackSettingsPayloads.Open.TYPE,
                LumberjackSettingsPayloads.Open.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (!(ctx.player() instanceof ServerPlayer player)
                            || !(player.containerMenu instanceof VillagerMenu menu)
                            || menu.getVillagerId() != payload.villagerId()
                            || !menu.canAccessSettings()
                            || !(player.level().getEntity(payload.villagerId())
                            instanceof NpcEntity villager)
                            || villager.getProfession() != Profession.LUMBERJACK
                            || !villager.isAlive()
                            || !NpcUiMenu.villagerStillThere(player, villager)) {
                        return;
                    }

                    villager.markUiOpened(player);
                    player.openMenu(
                            new SimpleMenuProvider(
                                    (id, inventory, ignored) ->
                                            new LumberjackSettingsMenu(id, inventory, villager),
                                    Tr.c("screen.dynamicvillages.title.lumberjack_settings", "Lumberjack Settings")),
                            buffer -> LumberjackSettingsMenu.writeOpeningData(buffer, villager));
                }));
        registrar.playToServer(
                GuardSettingsPayloads.Open.TYPE,
                GuardSettingsPayloads.Open.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (!(ctx.player() instanceof ServerPlayer player)
                            || !(player.containerMenu instanceof VillagerMenu menu)
                            || menu.getVillagerId() != payload.villagerId()
                            || !menu.canAccessSettings()
                            || !(player.level().getEntity(payload.villagerId())
                            instanceof NpcEntity villager)
                            || !villager.getProfession().isGuardLike()
                            || !villager.isAlive()
                            || !NpcUiMenu.villagerStillThere(player, villager)) {
                        return;
                    }

                    villager.markUiOpened(player);
                    player.openMenu(
                            new SimpleMenuProvider(
                                    (id, inventory, ignored) ->
                                            new GuardSettingsMenu(id, inventory, villager),
                                    Tr.c("screen.dynamicvillages.title.guard_settings", "Guard Settings")),
                            buffer -> GuardSettingsMenu.writeOpeningData(buffer, villager));
                }));
        registrar.playToServer(
                FarmerSettingsPayloads.Open.TYPE,
                FarmerSettingsPayloads.Open.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (!(ctx.player() instanceof ServerPlayer player)
                            || !(player.containerMenu instanceof VillagerMenu menu)
                            || menu.getVillagerId() != payload.villagerId()
                            || !menu.canAccessSettings()
                            || !(player.level().getEntity(payload.villagerId())
                            instanceof NpcEntity villager)
                            || villager.getProfession() != Profession.FARMER
                            || !villager.isAlive()
                            || !NpcUiMenu.villagerStillThere(player, villager)) {
                        return;
                    }

                    villager.markUiOpened(player);
                    player.openMenu(
                            new SimpleMenuProvider(
                                    (id, inventory, ignored) ->
                                            new FarmerSettingsMenu(id, inventory, villager),
                                    Tr.c("screen.dynamicvillages.title.farmer_settings", "Farmer Settings")),
                            buffer -> FarmerSettingsMenu.writeOpeningData(buffer, villager));
                }));
        registrar.playToServer(
                FarmerSettingsPayloads.Update.TYPE,
                FarmerSettingsPayloads.Update.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (!(ctx.player() instanceof ServerPlayer player)
                            || !(player.containerMenu instanceof FarmerSettingsMenu menu)
                            || menu.villagerId() != payload.villagerId()
                            || !(player.level().getEntity(payload.villagerId())
                            instanceof NpcEntity villager)
                            || !menu.isForNpc(villager)
                            || villager.getProfession() != Profession.FARMER
                            || !villager.isAlive()
                            || !NpcUiMenu.villagerStillThere(player, villager)
                            || !villager.canPlayerAccessSettings(player)) {
                        return;
                    }

                    FarmerSettings settings = FarmerSettings.of(villager);
                    boolean recognized = true;
                    if (FarmerSettings.WORKS_SETTING_ID.equals(payload.settingId())) {
                        if (payload.mode() < 0 || payload.mode() > 1) return;
                        settings.setWorks(payload.enabled());
                    } else if (FarmerSettings.TURN_OFF_PLANTING_SETTING_ID.equals(payload.settingId())) {
                        if (payload.mode() < 0 || payload.mode() > 1) return;
                        // The switch reads "Planting": ON means planting.
                        settings.setTurnOffPlanting(!payload.enabled());
                    } else if (FarmerSettings.HARVESTING_SETTING_ID.equals(payload.settingId())) {
                        if (payload.mode() < 0 || payload.mode() > 1) return;
                        // The switch reads "Harvesting": ON means harvesting.
                        settings.setTurnOffHarvesting(!payload.enabled());
                    } else if (FarmerSettings.TILLING_SETTING_ID.equals(payload.settingId())) {
                        if (payload.mode() < 0 || payload.mode() > 1) return;
                        settings.setTilling(payload.enabled());
                    } else if (FarmerSettings.HOE_CRAFT_SETTING_ID.equals(payload.settingId())) {
                        if (payload.mode() < 0 || payload.mode() > 1) return;
                        settings.setAllowHoeCrafting(payload.enabled());
                    } else if (FarmerSettings.SHEARS_SETTING_ID.equals(payload.settingId())) {
                        if (payload.mode() < 0 || payload.mode() > 1) return;
                        settings.setAllowShears(payload.enabled());
                    } else if (FarmerSettings.USE_BONE_MEAL_SETTING_ID.equals(payload.settingId())) {
                        if (payload.mode() < 0 || payload.mode() > 1) return;
                        settings.setUseBoneMeal(payload.enabled());
                    } else if (FarmerSettings.COMPOST_SETTING_ID.equals(payload.settingId())) {
                        if (payload.mode() < 0 || payload.mode() > 1) return;
                        settings.setCompost(payload.enabled());
                    } else {
                        FarmerSettings.HoeTier tier =
                                FarmerSettings.HoeTier.bySettingId(payload.settingId());
                        FarmerSettings.AxeTier axeTier =
                                FarmerSettings.AxeTier.bySettingId(payload.settingId());
                        if (tier != null) {
                            if (payload.mode() < 0 || payload.mode() > 1) return;
                            settings.setHoeTierAllowed(tier, payload.enabled());
                        } else if (axeTier != null) {
                            if (payload.mode() < 0 || payload.mode() > 1) return;
                            settings.setAxeTierAllowed(axeTier, payload.enabled());
                        } else {
                            net.minecraft.world.item.Item plantingItem =
                                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                                            .get(payload.settingId());
                            if (payload.mode() >= FarmerSettings.PLANT_LEAVE
                                    && payload.mode() <= FarmerSettings.PLANT_CUT_ONLY
                                    && plantingItem != null
                                    && FarmerPlantingResources.isSupported(plantingItem)
                                    && payload.settingId().equals(
                                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                                            .getKey(plantingItem))) {
                                settings.setPlantMode(plantingItem, payload.mode());
                            } else {
                                recognized = false;
                            }
                        }
                    }
                    if (recognized) FarmerSettingsChangeHandler.onSettingsChanged(villager);
                }));
        registrar.playToServer(
                GuardSettingsPayloads.Update.TYPE,
                GuardSettingsPayloads.Update.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (!(ctx.player() instanceof ServerPlayer player)
                            || !(player.containerMenu instanceof GuardSettingsMenu menu)
                            || menu.villagerId() != payload.villagerId()
                            || !(player.level().getEntity(payload.villagerId())
                            instanceof NpcEntity villager)
                            || !villager.getProfession().isGuardLike()
                            || !villager.isAlive()
                            || !NpcUiMenu.villagerStillThere(player, villager)
                            || !villager.canPlayerAccessSettings(player)) {
                        return;
                    }

                    GuardSettings settings = GuardSettings.of(villager);
                    if (payload.behaviorSetting()) {
                        if (GuardSettingsMenu.PROTECT_PLAYER_ID.equals(payload.settingId())) {
                            settings.setProtectPlayer(payload.enabled());
                        } else if (GuardSettingsMenu.FOLLOW_PLAYER_ID.equals(payload.settingId())) {
                            if (settings.protectPlayer()) {
                                settings.setFollowPlayer(payload.enabled());
                            }
                        } else if (GuardSettingsMenu.ATTACK_HOSTILE_MOBS_ID.equals(payload.settingId())) {
                            settings.setAttackHostileMobs(payload.enabled());
                        } else if (GuardSettingsMenu.PATROL_AT_NIGHT_ONLY_ID.equals(payload.settingId())) {
                            settings.setPatrolAtNightOnly(payload.enabled());
                        } else if (GuardSettingsMenu.USE_GUARD_POSTS_ID.equals(payload.settingId())) {
                            if (!settings.followPlayer()) {
                                settings.setUseGuardPosts(payload.enabled());
                            }
                        }
                        GuardSettingsChangeHandler.onBehaviorSettingChanged(villager);
                    } else if (payload.mobSetting()) {
                        settings.setMobAllowed(payload.settingId(), payload.enabled());
                        com.alex.test.village.villagers.guard.alert.GuardThreatRules.invalidateVillagePolicies();
                    } else {
                        GuardSettings.SwordTier tier =
                                GuardSettings.SwordTier.bySettingId(payload.settingId());
                        if (tier != null) {
                            settings.setSwordTierAllowed(tier, payload.enabled());
                            GuardSettingsChangeHandler.onSwordSettingChanged(villager);
                        }
                    }
                }));
        registrar.playToServer(
                GuardSettingsPayloads.UpdateGuardPostTime.TYPE,
                GuardSettingsPayloads.UpdateGuardPostTime.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (!(ctx.player() instanceof ServerPlayer player)
                            || !(player.containerMenu instanceof GuardSettingsMenu menu)
                            || menu.villagerId() != payload.villagerId()
                            || !(player.level().getEntity(payload.villagerId())
                            instanceof NpcEntity villager)
                            || !villager.getProfession().isGuardLike()
                            || !villager.isAlive()
                            || !NpcUiMenu.villagerStillThere(player, villager)
                            || !villager.canPlayerAccessSettings(player)) {
                        return;
                    }

                    GuardSettings settings = GuardSettings.of(villager);
                    if (settings.useGuardPosts() && !settings.followPlayer()) {
                        settings.setGuardPostStaySeconds(payload.seconds());
                    }
                }));
        registrar.playToServer(
                LumberjackSettingsPayloads.Update.TYPE,
                LumberjackSettingsPayloads.Update.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (!(ctx.player() instanceof ServerPlayer player)
                            || !(player.containerMenu instanceof LumberjackSettingsMenu menu)
                            || menu.villagerId() != payload.villagerId()
                            || !(player.level().getEntity(payload.villagerId())
                            instanceof NpcEntity villager)
                            || villager.getProfession() != Profession.LUMBERJACK
                            || !villager.isAlive()
                            || !NpcUiMenu.villagerStillThere(player, villager)
                            || !villager.canPlayerAccessSettings(player)) {
                        return;
                    }

                    LumberjackSettings settings = LumberjackSettings.of(villager);
                    if (payload.workSetting()) {
                        if (payload.mode() < 0 || payload.mode() > 1) return;
                        settings.setWorks(payload.enabled());
                        LumberjackSettingsChangeHandler.onWorkSettingChanged(
                                villager, payload.enabled());
                    } else if (LumberjackSettings.AXE_CRAFT_SETTING_ID.equals(payload.treeType())) {
                        if (payload.mode() < 0 || payload.mode() > 1) return;
                        settings.setAllowAxeCrafting(payload.enabled());
                    } else if (LumberjackSettings.PLANT_SAPLINGS_SETTING_ID.equals(payload.treeType())) {
                        if (payload.mode() < 0 || payload.mode() > 1) return;
                        settings.setPlantSaplings(payload.enabled());
                    } else {
                        LumberjackSettings.AxeTier tier =
                                LumberjackSettings.AxeTier.bySettingId(payload.treeType());
                        if (tier != null) {
                            if (payload.mode() < 0 || payload.mode() > 1) return;
                            settings.setAxeTierAllowed(tier, payload.enabled());
                        } else if (TreeTypeRegistry.byId(payload.treeType()) != null
                                && payload.mode() >= LumberjackSettings.TREE_SKIP
                                && payload.mode() <= LumberjackSettings.TREE_CUT_ONLY) {
                            settings.setTreeMode(payload.treeType(), payload.mode());
                            LumberjackSettingsChangeHandler.onTreeSettingChanged(
                                    villager, payload.treeType(), payload.enabled());
                        }
                    }
                }));
        registrar.playToServer(
                GenericSettingsPayloads.Open.TYPE,
                GenericSettingsPayloads.Open.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (!(ctx.player() instanceof ServerPlayer player)) return;
                    // Opened from the villager screen. The Builder has none here: its settings
                    // live in its desk's Settings tab.
                    boolean fromVillager = player.containerMenu instanceof VillagerMenu menu
                            && menu.getVillagerId() == payload.villagerId()
                            && menu.canAccessSettings();
                    if (!fromVillager
                            || !(player.level().getEntity(payload.villagerId())
                            instanceof NpcEntity villager)
                            || !villager.isAlive()
                            || !NpcUiMenu.villagerStillThere(player, villager)
                            || villager.getProfession() == Profession.BUILDER) {
                        return;
                    }

                    // No schema registered for this profession yet -- safe no-op,
                    // so this generic path never fires until a profession opts in
                    // via SettingsSchemaRegistry.
                    if (SettingsSchemaRegistry.forProfession(villager.getProfession()).isEmpty()) {
                        return;
                    }


                    villager.markUiOpened(player);
                    player.openMenu(
                            new SimpleMenuProvider(
                                    (id, inventory, ignored) ->
                                            new GenericSettingsMenu(id, inventory, villager),
                                    Component.literal(villager.getProfession().displayName() + " Settings")),
                            buffer -> GenericSettingsMenu.writeOpeningData(buffer, villager));
                }));
        registrar.playToServer(
                GenericSettingsPayloads.Update.TYPE,
                GenericSettingsPayloads.Update.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (!(ctx.player() instanceof ServerPlayer player)) return;
                    // From the settings window, or from the Builder's desk Settings tab (that desk
                    // was opened with the settings checks; the permission is checked again here).
                    boolean fromWindow = player.containerMenu instanceof GenericSettingsMenu menu
                            && menu.villagerId() == payload.villagerId();
                    boolean fromDesk = player.containerMenu instanceof BuilderDeskMenu desk
                            && desk.villagerId() == payload.villagerId();
                    if (!(fromWindow || fromDesk)
                            || !(player.level().getEntity(payload.villagerId())
                            instanceof NpcEntity villager)
                            || !villager.isAlive()
                            || !NpcUiMenu.villagerStillThere(player, villager)
                            || !villager.canPlayerAccessSettings(player)
                            || (fromDesk && (villager.getProfession() != Profession.BUILDER
                                    || !villager.canPlayerAccessSettings(player)))) {
                        return;
                    }

                    SettingsSchemaRegistry.forProfession(villager.getProfession())
                            .flatMap(schema -> schema.findRow(payload.settingId())
                                    .map(row -> java.util.Map.entry(schema, row)))
                            .ifPresent(entry -> {
                                applyGenericSettingsUpdate(
                                        villager, entry.getKey(), entry.getValue(), payload.encodedValue());
                                ProfessionSettingsChangeHandler.onSettingsChanged(villager);
                            });
                }));
        registrar.playToServer(
                GenericSettingsPayloads.SaleItemUpdate.TYPE,
                GenericSettingsPayloads.SaleItemUpdate.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (!(ctx.player() instanceof ServerPlayer player)
                            || !(player.containerMenu instanceof GenericSettingsMenu menu)
                            || menu.villagerId() != payload.villagerId()
                            || menu.schema().profession() != Profession.TRADESMAN
                            || !(player.level().getEntity(payload.villagerId()) instanceof NpcEntity villager)
                            || villager.getProfession() != Profession.TRADESMAN
                            || !villager.isAlive()
                            || !NpcUiMenu.villagerStillThere(player, villager)
                            || !villager.canPlayerAccessSettings(player)
                            || menu.saleItems().stream().noneMatch(row -> row.id().equals(payload.itemId()))
                            || !TradesmanSalePolicy.isListable(villager, payload.itemId())) return;

                    net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getOptional(payload.itemId()).orElse(null);
                    if (item == null) return;
                    TradesmanSalePolicy.setAllowed(villager, item, payload.allowed());
                    ProfessionSettingsChangeHandler.onSettingsChanged(villager);
                }));
        registrar.playToServer(
                GenericSettingsPayloads.SaleKeepUpdate.TYPE,
                GenericSettingsPayloads.SaleKeepUpdate.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (!(ctx.player() instanceof ServerPlayer player)
                            || !(player.containerMenu instanceof GenericSettingsMenu menu)
                            || menu.villagerId() != payload.villagerId()
                            || menu.schema().profession() != Profession.TRADESMAN
                            || !(player.level().getEntity(payload.villagerId()) instanceof NpcEntity villager)
                            || villager.getProfession() != Profession.TRADESMAN
                            || !villager.isAlive()
                            || !NpcUiMenu.villagerStillThere(player, villager)
                            || !villager.canPlayerAccessSettings(player)
                            || menu.saleItems().stream().noneMatch(row -> row.id().equals(payload.itemId()))
                            || !TradesmanSalePolicy.isListable(villager, payload.itemId())) return;

                    net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getOptional(payload.itemId()).orElse(null);
                    if (item == null) return;
                    TradesmanSalePolicy.setKeepAmount(villager, item, payload.keep());
                    ProfessionSettingsChangeHandler.onSettingsChanged(villager);
                }));
        registrar.playToClient(
                MayorTradePayloads.Result.TYPE,
                MayorTradePayloads.Result.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    // Runs on the client. The open menu is the only place the
                    // reply can land, and it is common code, so no client-only
                    // class is needed to receive it.
                    if (ctx.player() != null
                            && ctx.player().containerMenu instanceof MayorStatsMenu menu) {
                        menu.applyTradeResult(payload.success(), payload.message(),
                                payload.villageSize(), payload.offers());
                    }
                }));
        registrar.playToClient(
                MerchantTradePayloads.Result.TYPE,
                MerchantTradePayloads.Result.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() != null
                            && ctx.player().containerMenu instanceof MerchantMenu menu
                            && menu.merchantId() == payload.merchantId())
                        menu.applyResult(payload.success(), payload.message(),
                                payload.counts(), payload.prices(), payload.purse(), payload.credit());
                }));
        registrar.playToServer(
                MerchantTradePayloads.Sell.TYPE,
                MerchantTradePayloads.Sell.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleMerchantSale(payload, ctx.player())));
        registrar.playToServer(
                MayorTradePayloads.Buy.TYPE,
                MayorTradePayloads.Buy.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleMayorPurchase(payload, ctx.player())));
        registrar.playToServer(
                MayorTradePayloads.BuyReplacementTownHall.TYPE,
                MayorTradePayloads.BuyReplacementTownHall.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleReplacementTownHall(payload, ctx.player())));
        registrar.playToServer(
                MayorTradePayloads.ClaimVillage.TYPE,
                MayorTradePayloads.ClaimVillage.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleClaimVillage(payload, ctx.player())));
        registrar.playToServer(
                MayorTradePayloads.ClaimPeople.TYPE,
                MayorTradePayloads.ClaimPeople.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleClaimPeople(payload, ctx.player())));
        registrar.playToServer(
                MayorTradePayloads.ClaimRight.TYPE,
                MayorTradePayloads.ClaimRight.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleClaimRight(payload, ctx.player())));
        registrar.playToServer(
                MayorTradePayloads.ClaimGuard.TYPE,
                MayorTradePayloads.ClaimGuard.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleClaimGuard(payload, ctx.player())));
        registrar.playToClient(
                MayorTradePayloads.ClaimPeopleResult.TYPE,
                MayorTradePayloads.ClaimPeopleResult.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    // Runs on the client, common code, as the shop's Result.
                    if (ctx.player() != null
                            && ctx.player().containerMenu instanceof MayorStatsMenu menu
                            && menu.mayorId() == payload.mayorId()) {
                        menu.applyPeopleResult(payload.reply(), payload.name(), payload.land().orElse(null));
                    }
                }));
        registrar.playToServer(
                MayorTradePayloads.SetBirths.TYPE,
                MayorTradePayloads.SetBirths.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleSetBirths(payload, ctx.player())));
        registrar.playToServer(
                MayorTradePayloads.SetBellRules.TYPE,
                MayorTradePayloads.SetBellRules.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleSetBellRules(payload, ctx.player())));
        registrar.playToServer(
                MayorTradePayloads.SetDefenderTeams.TYPE,
                MayorTradePayloads.SetDefenderTeams.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleSetDefenderTeams(payload, ctx.player())));
        registrar.playToServer(
                MayorTradePayloads.SetTownAlerts.TYPE,
                MayorTradePayloads.SetTownAlerts.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    // The player's own choice, not the village's: no Mayor needed to check against.
                    if (ctx.player() instanceof ServerPlayer player) {
                        com.alex.test.village.chat.TownAlertPrefs.set(player,
                                com.alex.test.village.chat.TownAlertLevel.byIndex(payload.level()));
                    }
                }));
        registrar.playToServer(
                MayorTradePayloads.SetEveryInjuryAlerts.TYPE,
                MayorTradePayloads.SetEveryInjuryAlerts.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) {
                        com.alex.test.village.chat.TownAlertPrefs.setEveryInjury(player, payload.on());
                    }
                }));
        registrar.playToServer(
                MayorTradePayloads.SetMinerBranchDistance.TYPE,
                MayorTradePayloads.SetMinerBranchDistance.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleSetMinerBranchDistance(payload, ctx.player())));
        registrar.playToClient(
                MayorTradePayloads.MinerBranchDistanceResult.TYPE,
                MayorTradePayloads.MinerBranchDistanceResult.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() != null
                            && ctx.player().containerMenu instanceof MayorStatsMenu menu
                            && menu.mayorId() == payload.mayorId()) {
                        menu.setMinerSideBranchDistanceLocally(payload.blocks());
                    }
                }));
        RaidPayloads.register(registrar);
        PerformancePayloads.register(registrar);
        VillageLogPayloads.register(registrar);
    }

    private static void handleMerchantSale(MerchantTradePayloads.Sell payload,
                                           net.minecraft.world.entity.player.Player sender) {
        if (!(sender instanceof ServerPlayer player)
                || !(player.containerMenu instanceof MerchantMenu menu)
                || menu.merchantId() != payload.merchantId()
                || !(player.level().getEntity(payload.merchantId()) instanceof VisitorEntity merchant)
                || !menu.isFor(merchant)) return;
        MerchantTransactions.Outcome outcome = MerchantTransactions.sell(
                player, merchant, payload.offerIndex(), payload.half());
        VillageManager village = merchant.getVillageId() == null ? null
                : VillageManagerRegistry.get(player.serverLevel().getServer()).get(merchant.getVillageId());
        var storage = village == null ? java.util.List.<com.alex.test.village.building.StorageInventory>of()
                : MerchantCatalog.containers(player.serverLevel(), village);
        PacketDistributor.sendToPlayer(player, new MerchantTradePayloads.Result(
                merchant.getId(), outcome.success(), outcome.message(),
                MerchantCatalog.counts(merchant.getOffers(), storage, village),
                com.alex.test.village.visitors.MerchantPricing.liveCentsList(player.serverLevel(), village, merchant),
                merchant.purse(),
                village == null ? 0 : com.alex.test.village.visitors.MerchantPricing.playerChange(
                        player.serverLevel(), village, player.getUUID())));
    }

    /** The Mayor's one standing order: whether the village is having children. */
    private static void handleSetBirths(MayorTradePayloads.SetBirths payload,
                                        net.minecraft.world.entity.player.Player sender) {
        MayorEntity mayor = resolveShopMayor(payload.mayorId(), sender);
        if (mayor == null) return;
        VillageManager village = mayor.resolveManager();
        if (village == null || !mayManage(sender, village)) return;
        village.breeding().setBirthsEnabled(payload.enabled());
    }

    /** The watchtower bell's two village rules, set from the Mayor's Village rules page. */
    private static void handleSetBellRules(MayorTradePayloads.SetBellRules payload,
                                           net.minecraft.world.entity.player.Player sender) {
        MayorEntity mayor = resolveShopMayor(payload.mayorId(), sender);
        if (mayor == null) return;
        VillageManager village = mayor.resolveManager();
        if (village == null || !mayManage(sender, village)) return;
        village.rules().setPlayerHurtBell(
                com.alex.test.village.VillageRules.PlayerHurtBell.byIndex(payload.playerHurtBell()));
        village.rules().setTowerAlarmEndsWhenClear(payload.towerAlarmEndsWhenClear());
        village.markPersistentDataDirty();
    }

    /** How the defenders split up to fight, set from the Mayor's Village rules and Professions pages. */
    private static void handleSetDefenderTeams(MayorTradePayloads.SetDefenderTeams payload,
                                               net.minecraft.world.entity.player.Player sender) {
        MayorEntity mayor = resolveShopMayor(payload.mayorId(), sender);
        if (mayor == null) return;
        VillageManager village = mayor.resolveManager();
        if (village == null || !mayManage(sender, village)) return;
        village.rules().setDefenderTeams(payload.rules());
        village.markPersistentDataDirty();
    }

    private static void handleSetMinerBranchDistance(
            MayorTradePayloads.SetMinerBranchDistance payload,
            net.minecraft.world.entity.player.Player sender) {
        MayorEntity mayor = resolveShopMayor(payload.mayorId(), sender);
        if (mayor == null || !(sender instanceof ServerPlayer player)) return;
        VillageManager village = mayor.resolveManager();
        if (village == null) return;
        // Refused: the answer below still puts the page back to the real distance.
        if (mayManage(player, village)) village.setMinerSideBranchDistance(payload.blocks());
        PacketDistributor.sendToPlayer(player,
                new MayorTradePayloads.MinerBranchDistanceResult(
                        payload.mayorId(), village.minerSideBranchDistance()));
    }

    /**
     * Village claims round 7a: may the sender change how this village runs (Owner or Officer)? Says why when not.
     * Checked by every Mayor settings message, whatever the window showed.
     */
    static boolean mayManage(net.minecraft.world.entity.player.Player sender, VillageManager village) {
        if (!(sender instanceof ServerPlayer player)) return false;
        if (com.alex.test.village.claims.ClaimLock.mayIn(player, village,
                com.alex.test.village.claims.ClaimLock.Act.MANAGE)) return true;
        com.alex.test.village.claims.ClaimLock.tellManageRefused(player, village);
        return false;
    }

    /** The storage screen a payload edits, when the sender really has that room open. */
    @javax.annotation.Nullable
    private static StorageRoomMenu openStorageMenu(net.neoforged.neoforge.network.handling.IPayloadContext ctx,
                                                   java.util.UUID frameId) {
        if (!(ctx.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof StorageRoomMenu menu)
                || !menu.frameId().equals(frameId)
                || !menu.stillValid(player)
                || menu.serverStorage() == null) return null;
        return menu;
    }

    /**
     * Village claims round 7b: the same, for a change to the room's chests (filters, chosen items, locks).
     * Only the owner and their Officers make those; anyone else is told why and nothing changes.
     */
    @javax.annotation.Nullable
    private static StorageRoomMenu openStorageMenuToManage(net.neoforged.neoforge.network.handling.IPayloadContext ctx,
                                                           java.util.UUID frameId) {
        StorageRoomMenu menu = openStorageMenu(ctx, frameId);
        if (menu == null) return null;
        VillageManager village = StorageRoomAccess.villageOf((net.minecraft.server.level.ServerLevel) ctx.player().level(),
                menu.serverStorage());
        return village == null || mayManage(ctx.player(), village) ? menu : null;
    }

    private interface StockChange {
        void apply(com.alex.test.village.economy.StockReserves reserves, VillageManager village,
                   net.minecraft.server.level.ServerLevel level);
    }

    /** Applies a Stock tab change for the player's open storage screen, then sends back the new state. */
    private static void editStock(net.neoforged.neoforge.network.handling.IPayloadContext ctx,
                                  java.util.UUID frameId, StockChange change) {
        StorageRoomMenu menu = openStorageMenu(ctx, frameId);
        if (menu == null) return;
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) ctx.player().level();
        VillageManager village = StorageRoomAccess.villageOf(level, menu.serverStorage());
        if (village == null) return;
        change.apply(com.alex.test.village.economy.StockReserves.get(level.getServer()), village, level);
        PacketDistributor.sendToPlayer((ServerPlayer) ctx.player(),
                com.alex.test.village.economy.StockOrderStatus.build(level, village, frameId));
        // The Enchanter page refreshes with the rest of the Stock tab.
        PacketDistributor.sendToPlayer((ServerPlayer) ctx.player(),
                com.alex.test.village.villagers.enchanter.BookOrderStatus.build(level, village, frameId));
    }

    private interface BookOrderChange {
        void apply(com.alex.test.village.villagers.enchanter.BookOrders orders, VillageManager village,
                   net.minecraft.server.level.ServerLevel level);
    }

    /** Applies an Enchanter page change for the player's open storage screen, then sends back its new state. */
    private static void editBookOrders(net.neoforged.neoforge.network.handling.IPayloadContext ctx,
                                       java.util.UUID frameId, BookOrderChange change) {
        StorageRoomMenu menu = openStorageMenu(ctx, frameId);
        if (menu == null) return;
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) ctx.player().level();
        VillageManager village = StorageRoomAccess.villageOf(level, menu.serverStorage());
        if (village == null) return;
        change.apply(com.alex.test.village.villagers.enchanter.BookOrders.get(level.getServer()), village, level);
        PacketDistributor.sendToPlayer((ServerPlayer) ctx.player(),
                com.alex.test.village.villagers.enchanter.BookOrderStatus.build(level, village, frameId));
    }

    private static void handleLocateVillager(LocateVillagerPayload payload,
                                             net.minecraft.world.entity.player.Player sender) {
        MayorEntity mayor = resolveShopMayor(payload.mayorId(), sender);
        if (mayor == null || !(sender instanceof ServerPlayer player)) return;

        VillageManager village = mayor.resolveManager();
        var model = village == null ? null : village.getModel(payload.villagerId());
        NpcEntity villager = model == null ? null : model.puppet();
        if (villager == null || !villager.isAlive() || villager.isRemoved()
                || villager.level() != player.serverLevel()
                || !payload.villagerId().equals(villager.getNpcId())) {
            player.sendSystemMessage(Tr.c("screen.dynamicvillages.title.this_villager_is_not_currently_in", "This villager is not currently in the world."));
            return;
        }

        PacketDistributor.sendToPlayer(player,
                new VillageVillagerAlertPayload(villager.getUUID(), 20 * 30, false));
        player.sendSystemMessage(Component.literal(
                "villager has been outlined for 30 seconds in green"));
    }

    /** Sells a paused village's replacement Town Hall token; the answer lands on the Mayor's paused page. */
    private static void handleReplacementTownHall(MayorTradePayloads.BuyReplacementTownHall payload,
                                                  net.minecraft.world.entity.player.Player sender) {
        MayorEntity mayor = resolveShopMayor(payload.mayorId(), sender);
        if (mayor == null || !(sender instanceof ServerPlayer player)) return;

        VillageManager paused = mayor.resolvePausedVillage();
        MayorShop.Purchase purchase = com.alex.test.village.claims.ClaimLock.mayIn(player, paused,
                com.alex.test.village.claims.ClaimLock.Act.SHOP)
                ? MayorShop.buyReplacementTownHall(player, paused, mayor.pausedTownHallPos(), payload.withEmeralds())
                : new MayorShop.Purchase(false, com.alex.test.village.claims.ClaimLock.shopRefusal(paused), 0, 0);
        if (purchase.success()) player.inventoryMenu.broadcastFullState();
        mayor.markUiOpened(player);
        PacketDistributor.sendToPlayer(player, new MayorTradePayloads.Result(
                purchase.success(), purchase.message(),
                MayorShop.villageSize(null), MayorShop.offers(null)));
    }

    /**
     * Claim on the Mayor's Claim page: the first player to press it owns the village. The Mayor opens again
     * (as BackToMayor does), so the new owner gets the whole window and anyone who lost the race sees why.
     */
    private static void handleClaimVillage(MayorTradePayloads.ClaimVillage payload,
                                           net.minecraft.world.entity.player.Player sender) {
        MayorEntity mayor = resolveShopMayor(payload.mayorId(), sender);
        if (mayor == null || !(sender instanceof ServerPlayer player)) return;
        VillageManager village = mayor.resolveManager();
        var result = com.alex.test.village.claims.VillageClaims.claim(player, village);
        // A second press (sent before the window opened again) changes nothing and opens nothing.
        if (result == com.alex.test.village.claims.VillageClaims.ClaimResult.ALREADY_YOURS) return;
        if (result == com.alex.test.village.claims.VillageClaims.ClaimResult.CLAIMED) {
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.claim.yours",
                    "[Village] You own this village now."));
        } else if (result == com.alex.test.village.claims.VillageClaims.ClaimResult.TAKEN) {
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.claim.taken",
                    "[Village] %s claimed this village first.", village.claim().ownerName()));
        }
        player.openMenu(mayor);
        mayor.markUiOpened(player);
    }

    /**
     * A change on the Land tab's People page (village claims, round 4). Only the owner may change the list; the
     * answer carries the list as it is now. Giving the village up opens the Mayor again (its Claim page, claims on).
     * A typed name nobody online has is looked up off the server thread (the game's name cache, or Mojang) and
     * finished back on it, checking the window and the owner again.
     */
    private static void handleClaimPeople(MayorTradePayloads.ClaimPeople payload,
                                          net.minecraft.world.entity.player.Player sender) {
        MayorEntity mayor = resolveShopMayor(payload.mayorId(), sender);
        if (mayor == null || !(sender instanceof ServerPlayer player)) return;
        VillageManager village = mayor.resolveManager();
        var action = com.alex.test.village.claims.VillageClaims.PeopleAction.byIndex(payload.action());
        if (action == null) return;
        if (!com.alex.test.village.claims.VillageClaims.managesPeople(player, village)) {
            replyPeople(player, mayor, village, com.alex.test.village.claims.VillageClaims.PeopleReply.NOT_OWNER, "");
            return;
        }
        var rank = com.alex.test.village.claims.ClaimRank.byIndex(payload.rank());
        java.util.UUID target = payload.target().orElse(null);
        String targetName = target == null ? "" : nameOf(village, target);
        com.alex.test.village.claims.VillageClaims.PeopleReply reply;
        switch (action) {
            case ADD_NAME -> {
                String name = payload.name().trim();
                if (!net.minecraft.util.StringUtil.isValidPlayerName(name) || name.isEmpty()) {
                    replyPeople(player, mayor, village, com.alex.test.village.claims.VillageClaims.PeopleReply.NOT_FOUND, name);
                    return;
                }
                ServerPlayer online = player.server.getPlayerList().getPlayerByName(name);
                if (online != null) {
                    String found = online.getGameProfile().getName();
                    reply = com.alex.test.village.claims.VillageClaims.addPerson(player, village, online.getUUID(), found, rank);
                    replyPeople(player, mayor, village, reply, found);
                    return;
                }
                var cache = player.server.getProfileCache();
                if (cache == null) {
                    replyPeople(player, mayor, village, com.alex.test.village.claims.VillageClaims.PeopleReply.NOT_FOUND, name);
                    return;
                }
                int mayorId = payload.mayorId();
                cache.getAsync(name).thenAcceptAsync(profile -> {
                    // The window may have closed, or the village changed hands, while the name was looked up.
                    if (player.hasDisconnected()) return;
                    MayorEntity again = resolveShopMayor(mayorId, player);
                    if (again == null) return;
                    VillageManager stillHere = again.resolveManager();
                    if (!com.alex.test.village.claims.VillageClaims.managesPeople(player, stillHere)) {
                        replyPeople(player, again, stillHere, com.alex.test.village.claims.VillageClaims.PeopleReply.NOT_OWNER, "");
                        return;
                    }
                    if (profile.isEmpty()) {
                        replyPeople(player, again, stillHere, com.alex.test.village.claims.VillageClaims.PeopleReply.NOT_FOUND, name);
                        return;
                    }
                    var found = profile.get();
                    replyPeople(player, again, stillHere, com.alex.test.village.claims.VillageClaims.addPerson(
                            player, stillHere, found.getId(), found.getName(), rank), found.getName());
                }, player.server);
                return;
            }
            case ADD_ONLINE -> {
                ServerPlayer online = target == null ? null : player.server.getPlayerList().getPlayer(target);
                if (online == null) {
                    reply = com.alex.test.village.claims.VillageClaims.PeopleReply.NOT_FOUND;
                } else {
                    targetName = online.getGameProfile().getName();
                    reply = com.alex.test.village.claims.VillageClaims.addPerson(player, village, target, targetName, rank);
                }
            }
            case SET_RANK -> reply = target == null ? com.alex.test.village.claims.VillageClaims.PeopleReply.NOT_LISTED
                    : com.alex.test.village.claims.VillageClaims.setRank(player, village, target, rank);
            case REMOVE -> reply = target == null ? com.alex.test.village.claims.VillageClaims.PeopleReply.NOT_LISTED
                    : com.alex.test.village.claims.VillageClaims.remove(player, village, target);
            case HAND_OVER -> reply = target == null ? com.alex.test.village.claims.VillageClaims.PeopleReply.NOT_LISTED
                    : com.alex.test.village.claims.VillageClaims.handOver(player, village, target);
            case GIVE_UP -> {
                com.alex.test.village.claims.VillageClaims.giveUp(player, village);
                player.sendSystemMessage(Tr.c("chat.dynamicvillages.claim.gave_up",
                        "[Village] You gave this village up. Nobody owns it now."));
                player.openMenu(mayor);
                mayor.markUiOpened(player);
                return;
            }
            default -> {
                return;
            }
        }
        replyPeople(player, mayor, village, reply, targetName);
    }

    /** A listed player's saved name, or "" (the owner's own name for the owner). */
    private static String nameOf(VillageManager village, java.util.UUID playerId) {
        var claim = village.claim();
        if (claim.isOwner(playerId)) return claim.ownerName();
        var member = claim.members().get(playerId);
        return member != null ? member.name() : "";
    }

    /** One switch of the Land tab's Rights page (village claims round 9): the owner only, answered as a People change. */
    private static void handleClaimRight(MayorTradePayloads.ClaimRight payload,
                                         net.minecraft.world.entity.player.Player sender) {
        MayorEntity mayor = resolveShopMayor(payload.mayorId(), sender);
        if (mayor == null || !(sender instanceof ServerPlayer player)) return;
        VillageManager village = mayor.resolveManager();
        var acts = com.alex.test.village.claims.ClaimLock.Act.values();
        if (payload.act() < 0 || payload.act() >= acts.length) return;
        if (!com.alex.test.village.claims.VillageClaims.managesPeople(player, village)) {
            replyPeople(player, mayor, village, com.alex.test.village.claims.VillageClaims.PeopleReply.NOT_OWNER, "");
            return;
        }
        var reply = com.alex.test.village.claims.VillageClaims.setRight(player, village,
                com.alex.test.village.claims.ClaimRank.byIndex(payload.rank()), acts[payload.act()], payload.on());
        replyPeople(player, mayor, village, reply, "");
    }

    /** One switch of the Land tab's Protection page (village claims round 10): the owner only, answered as a People change. */
    private static void handleClaimGuard(MayorTradePayloads.ClaimGuard payload,
                                         net.minecraft.world.entity.player.Player sender) {
        MayorEntity mayor = resolveShopMayor(payload.mayorId(), sender);
        if (mayor == null || !(sender instanceof ServerPlayer player)) return;
        VillageManager village = mayor.resolveManager();
        var kind = com.alex.test.village.claims.LandGuard.Kind.byIndex(payload.kind());
        if (kind == null) return;
        if (!com.alex.test.village.claims.VillageClaims.managesPeople(player, village)) {
            replyPeople(player, mayor, village, com.alex.test.village.claims.VillageClaims.PeopleReply.NOT_OWNER, "");
            return;
        }
        replyPeople(player, mayor, village,
                com.alex.test.village.claims.VillageClaims.setGuard(player, village, kind, payload.on()), "");
    }

    private static void replyPeople(ServerPlayer player, MayorEntity mayor, @javax.annotation.Nullable VillageManager village,
                                    com.alex.test.village.claims.VillageClaims.PeopleReply reply, String name) {
        var land = com.alex.test.village.claims.LandView.of(player.serverLevel(), village);
        PacketDistributor.sendToPlayer(player, new MayorTradePayloads.ClaimPeopleResult(
                mayor.getId(), reply.ordinal(), name == null ? "" : name, java.util.Optional.ofNullable(land)));
    }

    /**
     * The Mayor a shop payload is addressed to, or null if the player is not
     * entitled to trade with it right now.
     */
    private static MayorEntity resolveShopMayor(
            int mayorId, net.minecraft.world.entity.player.Player sender) {
        if (!(sender instanceof ServerPlayer player)
                || !(player.containerMenu instanceof MayorStatsMenu menu)
                || menu.mayorId() != mayorId
                || !(player.level().getEntity(mayorId) instanceof MayorEntity mayor)
                || !mayor.isAlive()
                || player.distanceToSqr(mayor) >= 64.0) {
            return null;
        }
        return mayor;
    }

    /**
     * Sells one shop line to the player and answers with a re-priced catalogue.
     *
     * <p>The price is derived here from the village the Mayor belongs to; the
     * payload only names an item, so a hand-made packet can buy at the honest
     * price or not at all. Every reply carries fresh offers because a purchase
     * that becomes a building or a villager raises the price of the next one.
     */
    private static void handleMayorPurchase(MayorTradePayloads.Buy payload,
                                            net.minecraft.world.entity.player.Player sender) {
        MayorEntity mayor = resolveShopMayor(payload.mayorId(), sender);
        if (mayor == null || !(sender instanceof ServerPlayer player)) return;

        VillageManager village = mayor.resolveManager();
        // Village claims round 7a: only people the owner trusts buy here.
        MayorShop.Purchase purchase = com.alex.test.village.claims.ClaimLock.mayIn(player, village,
                com.alex.test.village.claims.ClaimLock.Act.SHOP)
                ? MayorShop.buy(player, village, payload.itemId())
                : new MayorShop.Purchase(false, com.alex.test.village.claims.ClaimLock.shopRefusal(village), 0, 0);
        if (purchase.success()) {
            // Sync the item and spent emeralds without closing the slotless Mayor menu.
            player.inventoryMenu.broadcastFullState();
        }
        mayor.markUiOpened(player);
        PacketDistributor.sendToPlayer(player, new MayorTradePayloads.Result(
                purchase.success(),
                purchase.message(),
                MayorShop.villageSize(village),
                MayorShop.offers(village, player)));
    }

    /** Parses {@code encodedValue} per the row's declared kind and writes it via {@link ProfessionSettings}. */
    private static void applyGenericSettingsUpdate(
            NpcEntity villager, SettingsSchema schema, SettingsSchema.Row row, String encodedValue) {
        ProfessionSettings settings = ProfessionSettings.of(villager, schema);
        switch (row.kind()) {
            // A heading has nothing to save; findRow never returns one, this is a backstop.
            case HEADER -> {
                return;
            }
            case MASTER_TOGGLE, BEHAVIOR_TOGGLE -> settings.setBool(
                    row.settingId(), Boolean.parseBoolean(encodedValue));
              case NUMERIC_STEPPER, CHOICE -> {
                try {
                    settings.setInt(row.settingId(), Integer.parseInt(encodedValue));
                } catch (NumberFormatException ignored) {
                    // Malformed payload -- leave the stored value untouched.
                }
            }
            case TIER_TOGGLES, ALLOW_LIST -> {
                int split = encodedValue.lastIndexOf(':');
                if (split < 0) return;
                String entryId = encodedValue.substring(0, split);
                boolean allowed = Boolean.parseBoolean(encodedValue.substring(split + 1));
                settings.setAllowed(row.settingId(), entryId, allowed);
            }
        }
        // The archer's shift and guard posts live in its GuardSettings.
        if (schema == com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema.SCHEMA) {
            com.alex.test.village.villagers.archer.ArcherSettingsBridge.applyToGuardSettings(villager);
        }
        // So are the mage's.
        if (schema == com.alex.test.village.villagers.mage.settings.MageSettingsSchema.SCHEMA) {
            com.alex.test.village.villagers.mage.MageSettingsBridge.applyToGuardSettings(villager);
        }
    }
}
