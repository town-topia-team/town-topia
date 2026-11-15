package com.alex.test.entity.mayor;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.town.TownStatsService;
import com.alex.test.town.MayorStatsSnapshot;
import com.alex.test.ui.MayorStatsMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent NPC puppet representing a Town Hall's Mayor.
 *
 * <p>The entity reuses {@link NpcEntity}'s navigation, animation, persistence,
 * and rendering contracts. Decisions are delegated to {@link MayorAi}.
 */
public final class MayorEntity extends NpcEntity {

    @Nullable
    private MayorTownHallLink townHallLink;
    @Nullable
    private MayorAi mayorAi;

    public MayorEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public void linkToTownHall(MayorTownHallLink link) {
        if (Objects.equals(this.townHallLink, link)) {
            showMayorName();
            return;
        }
        this.townHallLink = link;
        this.mayorAi = null;
        showMayorName();
    }

    /** "Mayor Mello Ashford" once named (ensureGivenName, from tick), plain "Mayor" until then. */
    private void showMayorName() {
        if (givenName() != null) showGivenName();
        else if (!hasCustomName()) setCustomName(Component.literal("Mayor"));
    }

    @Override
    protected String nameWithTitle(String name) {
        return "Mayor " + name;
    }

    /** "Mayor Mello Ashford", or plain "Mayor" until it is named; for text the server writes (screens). */
    public String titledName() {
        String given = givenName();
        return given != null ? nameWithTitle(given) : "Mayor";
    }

    /** In its night now (on the way to sleep, waiting for a spot, or asleep); see {@link MayorAi#isInNight}. */
    public boolean isInNight() {
        return mayorAi != null ? mayorAi.isInNight() : isSleeping();
    }

    /** The hall floor tile it sleeps on tonight, or null; see {@link MayorAi#hallSleepSpot}. */
    @Nullable
    public BlockPos hallSleepSpot() {
        return mayorAi != null ? mayorAi.hallSleepSpot() : null;
    }

    @Nullable
    public MayorTownHallLink getTownHallLink() {
        return townHallLink;
    }

    public void staggerWakeAfterPlayerSleep(ServerLevel level) {
        if (mayorAi == null && isSleeping()) mayorAi = new MayorAi(this);
        if (mayorAi != null) mayorAi.staggerWakeAfterPlayerSleep(level);
    }

    @Override
    public boolean isVillageMember() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel serverLevel)) return;
        // Mayors skip the villagers' skill setup, where names are given; once per load.
        ensureGivenName();
        if (townHallLink == null) return;
        if (mayorAi == null) mayorAi = new MayorAi(this);
        mayorAi.tick(serverLevel);
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide) {
            if (tryNameTag(player, hand)) return InteractionResult.SUCCESS;
            if (mayorAi == null && townHallLink != null) mayorAi = new MayorAi(this);
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                    && mayorAi != null) {
                mayorAi.onPlayerInteract(serverPlayer);
            }
            player.openMenu(this);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public Component getDisplayName() {
        // Over the head (client): "Mayor Mello Ashford". Screen titles (server): "Mayor".
        if (level().isClientSide && hasCustomName()) return getCustomName();
        return Component.translatable("entity." + DynamicVillages.MODID + ".mayor");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        VillageManager village = resolveManager();
        MayorStatsSnapshot snapshot = TownStatsService.snapshot(village, player);
        VillageManager paused = resolvePausedVillage();
        BlockPos townHall = pausedTownHallPos();
        int townAlerts = (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                ? com.alex.test.village.chat.TownAlertPrefs.of(serverPlayer)
                : com.alex.test.village.chat.TownAlertLevel.DEFAULT).ordinal();
        boolean everyInjury = player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && com.alex.test.village.chat.TownAlertPrefs.everyInjury(serverPlayer);
        boolean unclaimed = level() instanceof ServerLevel serverLevel
                && com.alex.test.village.claims.VillageClaims.showsClaimPage(serverLevel, village);
        // A paused village keeps its owner: its page needs the player's rank too (Buy greyed, round 7a).
        com.alex.test.village.claims.LandView land = level() instanceof ServerLevel serverLevel
                ? com.alex.test.village.claims.LandView.of(serverLevel, village != null ? village : paused) : null;
        return new MayorStatsMenu(id, inventory, this, snapshot, paused == null || townHall == null ? null
                : new MayorStatsMenu.PausedVillage(paused.getVillageId(), townHall), townAlerts, everyInjury,
                village != null ? village.getVillageId() : null, unclaimed, land);
    }

    /**
     * The village this Mayor speaks for while it is paused, waiting for its
     * Town Hall token; null while it runs or when there is none.
     */
    @Nullable
    public VillageManager resolvePausedVillage() {
        if (townHallLink == null || !(level() instanceof ServerLevel serverLevel)
                || serverLevel.getServer() == null) {
            return null;
        }
        VillageManagerRegistry registry = VillageManagerRegistry.get(serverLevel.getServer());
        VillageManager manager = registry.get(townHallLink.villageId());
        return manager != null && manager.getLevel() == serverLevel
                && registry.isVillageInactive(townHallLink.villageId()) ? manager : null;
    }

    /** Where the paused village's Town Hall token belongs; null while the village runs. */
    @Nullable
    public BlockPos pausedTownHallPos() {
        VillageManager paused = resolvePausedVillage();
        if (paused == null || !(level() instanceof ServerLevel serverLevel)) return null;
        UUID frameId = VillageManagerRegistry.get(serverLevel.getServer())
                .getTownHallFrameId(paused.getVillageId());
        BlockPos framePos = frameId == null ? null : paused.getTownHallFramePos(frameId);
        return framePos != null ? framePos : paused.getBellPos();
    }

    @Override
    public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buffer) {
        if (menu instanceof MayorStatsMenu mayorMenu) mayorMenu.writeClientData(buffer);
    }

    /** The village this Mayor speaks for, or null while it is unlinked. */
    @Nullable
    public VillageManager resolveManager() {
        if (townHallLink == null || !(level() instanceof ServerLevel serverLevel)
                || serverLevel.getServer() == null) {
            return null;
        }
        VillageManagerRegistry registry = VillageManagerRegistry.get(serverLevel.getServer());
        VillageManager manager = registry.get(townHallLink.villageId());
        if (manager == null && VillageManager.INSTANCE.getVillageId().equals(townHallLink.villageId())) {
            manager = VillageManager.INSTANCE;
        }
        return manager != null && manager.getLevel() == serverLevel
                && !registry.isVillageInactive(townHallLink.villageId())
                && manager.hasValidTownHall(townHallLink.townHallId()) ? manager : null;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (townHallLink != null) townHallLink.save(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        townHallLink = MayorTownHallLink.load(tag);
        mayorAi = null;
    }
}
