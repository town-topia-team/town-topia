package com.alex.test.entity.visitor;

import com.alex.test.DynamicVillages;
import com.alex.test.village.visitors.NomadLogic;
import com.alex.test.village.visitors.NomadRecruitment;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.stats.HungerSystem;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.stats.WellbeingSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import com.alex.test.ui.VillagerMenu;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/** An unassigned traveller which only becomes a resident through token recruitment. */
public final class NomadEntity extends AbstractTravelerEntity {
    private static final String TAG_VILLAGE = "NomadVillage";
    private static final String LEGACY_TAG_SPAWN_ORIGIN = "NomadSpawnOrigin";
    @Nullable private UUID villageId;

    public NomadEntity(EntityType<? extends PathfinderMob> type, Level level) { super(type, level); }

    @Nullable public UUID getVillageId() { return villageId; }
    public void setVillageId(@Nullable UUID villageId) { this.villageId = villageId; }

    @Override
    protected void tickTraveler(ServerLevel level) {
        ensurePublicStats();
        if (com.alex.test.village.visitors.TravelerNight.tick(this, level)) return;
        NomadLogic.tick(this, level);
    }

    @Override
    protected void reconcileLifecycle(ServerLevel level) {
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        VillageManager manager = villageId != null ? registry.get(villageId) : null;
        if (manager == null && villageId == null) {
            manager = registry.findVillageAt(level, getSpawnOrigin());
            if (manager == null) manager = registry.findVillageAt(level, blockPosition());
            if (manager != null) {
                villageId = manager.getVillageId();
                setDepartureAtGameTime(manager.nomads().registerLegacyNomad(
                        level, getUUID(), getDepartureAtGameTime()));
            }
        }
        if (manager == null) return;
        Long authoritativeDeparture = manager.nomads().departureFor(getUUID());
        if (authoritativeDeparture == null) {
            finishVisit(level, "stale or unregistered nomad");
        } else if (getDepartureAtGameTime() != authoritativeDeparture) {
            setDepartureAtGameTime(authoritativeDeparture);
        }
    }

    @Override
    protected void onVisitEnded(ServerLevel level, String reason) {
        if (villageId == null || level.getServer() == null) return;
        VillageManager manager = VillageManagerRegistry.get(level.getServer()).get(villageId);
        if (manager != null) manager.nomads().finishNomad(getUUID(), level.getGameTime(), reason);
    }

    @Override protected String lifecycleName() { return "Nomad"; }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (level().isClientSide) return InteractionResult.CONSUME;
        if (com.alex.test.village.visitors.TravelerNight.isAsleep(this)) {
            player.sendSystemMessage(Component.literal("This nomad is asleep. Come back in the morning."));
            return InteractionResult.SUCCESS;
        }
        // Village claims 11a: no talk and no hiring for a player the village's owner listed as Hostile.
        if (player instanceof ServerPlayer serverPlayer && villageId != null && serverPlayer.getServer() != null) {
            VillageManager village = VillageManagerRegistry.get(serverPlayer.getServer()).get(villageId);
            if (com.alex.test.village.claims.ClaimLock.isHostileIn(serverPlayer, village)) {
                com.alex.test.village.claims.ClaimLock.tellTravellerRefused(serverPlayer, village, false);
                return InteractionResult.SUCCESS;
            }
        }
        InteractionResult recruitment = NomadRecruitment.handleProfessionTokenUse(this, player, hand);
        if (recruitment.consumesAction()) return recruitment;
        if (player instanceof ServerPlayer serverPlayer) {
            ensurePublicStats();
            markUiOpened(serverPlayer);
            serverPlayer.openMenu(this,
                    buffer -> VillagerMenu.writeOpeningData(buffer, this, serverPlayer));
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public Component getDisplayName() {
        Component custom = getCustomName();
        return custom != null ? custom : Component.translatable("entity." + DynamicVillages.MODID + ".nomad");
    }

    private void ensurePublicStats() {
        if (!getBrainData().contains(HungerSystem.TAG_HUNGER)) {
            getBrainData().putFloat(HungerSystem.TAG_HUNGER, 100f);
        }
        if (!getBrainData().contains(WellbeingSystem.TAG_WELLBEING)) {
            if (getBrainData().contains("happiness") || getBrainData().contains("motivation")) {
                VillagerStats migrated = new VillagerStats();
                migrated.load(getBrainData());
                migrated.save(getBrainData());
            } else {
                getBrainData().putFloat(WellbeingSystem.TAG_WELLBEING, 100f);
            }
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (villageId != null) tag.putUUID(TAG_VILLAGE, villageId);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // A Nomad is a non-member by type. Clear any stale resident link from
        // an older/broken save rather than allowing the generic NPC loader to
        // revive it on the next server start.
        setVillageLink(null);
        setProfession(Profession.NONE);
        villageId = tag.hasUUID(TAG_VILLAGE) ? tag.getUUID(TAG_VILLAGE) : null;
        if (tag.contains(LEGACY_TAG_SPAWN_ORIGIN) && level() instanceof ServerLevel serverLevel) {
            setSpawnOrigin(serverLevel,
                    net.minecraft.core.BlockPos.of(tag.getLong(LEGACY_TAG_SPAWN_ORIGIN)));
        }
    }
}
