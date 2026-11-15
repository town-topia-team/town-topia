package com.alex.test.entity.visitor;

import com.alex.test.DynamicVillages;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.visitors.MerchantLogic;
import com.alex.test.village.visitors.MerchantCatalog;
import com.alex.test.village.visitors.MerchantOffer;
import com.alex.test.ui.MerchantMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.villagers.registry.VillagerJobManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.List;

/** Temporary merchant visitor. Nomads use the separate {@link NomadEntity}. */
public final class VisitorEntity extends AbstractTravelerEntity {
    private static final String LEGACY_TAG_KIND = "VisitorKind";
    private static final String TAG_VILLAGE = "VisitorVillage";
    private static final String TAG_ARRIVED = "MerchantArrived";
    private static final String TAG_APPROACH_TICKS = "MerchantApproachTicks";
    private static final String TAG_OFFERS = "MerchantOffers";
    private static final String TAG_OFFERS_READY = "MerchantOffersReady";
    private static final String TAG_CATALOG_VERSION = "MerchantCatalogVersion";
    /** 4: prices come from the data file; offers saved with the old flat price are rebuilt. */
    private static final int CATALOG_VERSION = 4;

    @Nullable private UUID villageId;
    private boolean migrateLegacyNomad;
    private boolean merchantArrived;
    private int approachTicks;
    private List<MerchantOffer> offers = List.of();
    private boolean offersReady;
    /** One of a new village's founding visits: it pays double, up to the table's ceiling. */
    private boolean welcomeVisit;
    /** Emeralds this merchant can still pay out today; -1 means no limit. */
    private int purse = -1;
    private static final String TAG_WELCOME = "MerchantWelcome";
    private static final String TAG_PURSE = "MerchantPurse";

    public VisitorEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public boolean isWelcomeVisit() { return welcomeVisit; }
    public void setWelcomeVisit(boolean welcome) { welcomeVisit = welcome; }

    /** Emeralds left to pay with today, or -1 for a merchant with no purse limit. */
    public int purse() { return purse; }
    public void setPurse(int emeralds) { purse = emeralds; }

    /** Whether the purse covers {@code emeralds}. */
    public boolean canPay(int emeralds) { return purse < 0 || purse >= emeralds; }

    /** Takes {@code emeralds} out of the purse; a merchant without a purse pays freely. */
    public void spend(int emeralds) {
        if (purse >= 0) purse = Math.max(0, purse - emeralds);
    }

    /** Merchants never join a village, so they do not roll starting intelligence. */
    @Override
    protected boolean rollsStartingIntelligence() { return false; }

    @Nullable public UUID getVillageId() { return villageId; }
    public void setVillageId(@Nullable UUID villageId) { this.villageId = villageId; }
    public boolean hasArrivedForTrading() { return merchantArrived; }
    public void setArrivedForTrading(boolean value) { merchantArrived = value; }
    public int incrementApproachTicks() { return ++approachTicks; }
    public void resetApproachTicks() { approachTicks = 0; }
    public List<MerchantOffer> getOffers() { return offers; }

    // A tradesman selling to this merchant holds it in place, facing it, for
    // the length of their talk. Transient: a talk never outlives a reload.
    @Nullable private com.alex.test.entity.NpcEntity tradeTalkPartner;
    private long tradeTalkUntil;

    /** Keeps this merchant still and facing {@code partner} until {@code untilGameTime}. */
    public void holdForTradeTalk(com.alex.test.entity.NpcEntity partner, long untilGameTime) {
        tradeTalkPartner = partner;
        tradeTalkUntil = untilGameTime;
    }

    /** The villager this merchant is talking business with, or null once the hold has lapsed. */
    @Nullable
    public com.alex.test.entity.NpcEntity tradeTalkPartner(long gameTime) {
        if (tradeTalkPartner == null) return null;
        if (gameTime > tradeTalkUntil || !tradeTalkPartner.isAlive()) {
            tradeTalkPartner = null;
            return null;
        }
        return tradeTalkPartner;
    }
    public boolean hasPreparedOffers() { return offersReady; }
    public void prepareOffers(ServerLevel level, VillageManager village) {
        offers = MerchantCatalog.create(level, village);
        offersReady = true;
    }

    @Override
    protected void tickTraveler(ServerLevel level) {
        if (migrateLegacyNomad) {
            migrateLegacyNomad(level);
            return;
        }
        if (villageId != null && VillageManagerRegistry.get(level.getServer())
                .isVillageDormantOrReconciling(villageId)) return;
        if (com.alex.test.village.visitors.TravelerNight.tick(this, level)) return;
        MerchantLogic.tick(this, level);
    }

    @Override
    protected void reconcileLifecycle(ServerLevel level) {
        if (migrateLegacyNomad) return;
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        com.alex.test.village.VillageManager manager = villageId != null
                ? registry.get(villageId) : null;
        if (manager == null) return;
        Long authoritativeDeparture = manager.visitors().departureFor(getUUID());
        if (authoritativeDeparture == null) {
            finishVisit(level, "stale or duplicate merchant");
        } else if (getDepartureAtGameTime() != authoritativeDeparture) {
            setDepartureAtGameTime(authoritativeDeparture);
        }
    }

    @Override
    protected void onVisitEnded(ServerLevel level, String reason) {
        if (villageId == null || level.getServer() == null) return;
        com.alex.test.village.VillageManager manager =
                VillageManagerRegistry.get(level.getServer()).get(villageId);
        if (manager != null) manager.visitors().finishMerchant(getUUID(), level.getGameTime(),
                level.getDayTime(), reason);
    }

    @Override protected String lifecycleName() { return "Merchant"; }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide
                && VillagerJobManager.professionForToken(player.getItemInHand(hand)) != null) {
            player.sendSystemMessage(Component.literal(
                    "Merchants are temporary visitors and cannot be recruited."));
            return InteractionResult.SUCCESS;
        }
        if (level().isClientSide) return InteractionResult.CONSUME;
        if (com.alex.test.village.visitors.TravelerNight.isAsleep(this)) {
            player.sendSystemMessage(Component.literal("The merchant is asleep. Come back in the morning."));
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer && !isReturningHome()) {
            VillageManager village = villageId == null ? null
                    : VillageManagerRegistry.get(serverPlayer.serverLevel().getServer()).get(villageId);
            if (village != null && com.alex.test.village.claims.ClaimLock.isHostileIn(serverPlayer, village)) {
                // Village claims 11a: the owner listed this player as Hostile.
                com.alex.test.village.claims.ClaimLock.tellTravellerRefused(serverPlayer, village, true);
                return InteractionResult.SUCCESS;
            }
            if (village != null) {
                // Merchants from saves predating the catalog get one stable catalog on first use.
                if (!offersReady) prepareOffers((ServerLevel) level(), village);
                markUiOpened(serverPlayer);
                serverPlayer.openMenu(new SimpleMenuProvider(
                        (id, inventory, ignored) -> new MerchantMenu(id, inventory, this, village),
                        Component.literal("Traveling Merchant")),
                        buffer -> MerchantMenu.writeOpeningData(buffer, this, village, serverPlayer));
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    private void migrateLegacyNomad(ServerLevel level) {
        NomadEntity nomad = DynamicVillages.NOMAD.get().create(level);
        if (nomad == null) return;
        nomad.moveTo(getX(), getY(), getZ(), getYRot(), getXRot());
        nomad.setDepartureAtGameTime(getDepartureAtGameTime());
        nomad.setSpawnOrigin(level, getExactSpawnOrigin());
        nomad.setVillageId(villageId);
        nomad.setCustomName(getCustomName());
        nomad.setCustomNameVisible(isCustomNameVisible());
        com.alex.test.village.VillageManager manager = villageId != null
                ? VillageManagerRegistry.get(level.getServer()).get(villageId) : null;
        if (manager != null) {
            nomad.setDepartureAtGameTime(manager.nomads().registerLegacyNomad(
                    level, nomad.getUUID(), nomad.getDepartureAtGameTime()));
        }
        if (level.addFreshEntity(nomad)) {
            DynamicVillages.LOGGER.info("Migrated legacy NOMAD visitor {} to NomadEntity {}.",
                    getUUID(), nomad.getUUID());
            discard();
        } else if (manager != null) {
            manager.nomads().finishNomad(
                    nomad.getUUID(), level.getGameTime(), "legacy migration spawn failed");
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (villageId != null) tag.putUUID(TAG_VILLAGE, villageId);
        tag.putBoolean(TAG_ARRIVED, merchantArrived);
        tag.putInt(TAG_APPROACH_TICKS, approachTicks);
        ListTag savedOffers = new ListTag();
        for (MerchantOffer offer : offers) {
            CompoundTag line = new CompoundTag();
            line.putString("Profession", offer.profession());
            line.putString("Item", offer.itemId());
            line.putInt("Price", offer.fullPrice());
            savedOffers.add(line);
        }
        tag.put(TAG_OFFERS, savedOffers);
        tag.putBoolean(TAG_OFFERS_READY, offersReady);
        tag.putInt(TAG_CATALOG_VERSION, CATALOG_VERSION);
        tag.putBoolean(TAG_WELCOME, welcomeVisit);
        tag.putInt(TAG_PURSE, purse);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        villageId = tag.hasUUID(TAG_VILLAGE) ? tag.getUUID(TAG_VILLAGE) : null;
        merchantArrived = tag.getBoolean(TAG_ARRIVED);
        approachTicks = Math.max(0, tag.getInt(TAG_APPROACH_TICKS));
        ListTag savedOffers = tag.getList(TAG_OFFERS, Tag.TAG_COMPOUND);
        java.util.ArrayList<MerchantOffer> loaded = new java.util.ArrayList<>();
        for (int i = 0; i < savedOffers.size(); i++) {
            CompoundTag line = savedOffers.getCompound(i);
            MerchantOffer offer = new MerchantOffer(line.getString("Profession"),
                    line.getString("Item"), line.getInt("Price"));
            // An offer today's rules refuse would sit on the trade screen as
            // a row that can never be sold to.
            if (com.alex.test.village.visitors.MerchantPricing.isTradable(offer)) loaded.add(offer);
        }
        offersReady = tag.getInt(TAG_CATALOG_VERSION) == CATALOG_VERSION
                && tag.getBoolean(TAG_OFFERS_READY);
        offers = offersReady ? List.copyOf(loaded) : List.of();
        welcomeVisit = tag.getBoolean(TAG_WELCOME);
        purse = tag.contains(TAG_PURSE) ? tag.getInt(TAG_PURSE) : -1;
        // Legacy ordinal 0 was NOMAD and 1 was MERCHANT. New visitors are always merchants.
        migrateLegacyNomad = tag.contains(LEGACY_TAG_KIND) && tag.getInt(LEGACY_TAG_KIND) == 0;
    }
}
