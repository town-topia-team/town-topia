package com.alex.test.ui;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.town.MayorStatsSnapshot;
import com.alex.test.town.TradeOfferEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Server-backed menu for the Mayor's town ledger and shop.
 *
 * <p>The ledger half is read-only. The shop half holds no slots either — a
 * purchase is a payload, not a slot drag — but its rows do change while the
 * screen is open, so {@link #snapshot} is replaced wholesale when the server
 * answers a purchase with a re-priced catalogue.
 */
public final class MayorStatsMenu extends AbstractContainerMenu implements NpcUiMenu {

    @Nullable
    private final MayorEntity mayor;
    /** Entity id of the mayor, valid on both sides — the shop addresses it by id. */
    private final int mayorId;
    private MayorStatsSnapshot snapshot;
    /**
     * The village while it is paused without its Town Hall token; null while
     * it runs. The screen then shows only the paused page, with the
     * replacement token for sale.
     */
    @Nullable
    private final PausedVillage paused;

    /**
     * The viewing player's own town alerts ({@code TownAlertLevel} index):
     * theirs, not the village's, so it travels beside the snapshot.
     */
    private int townAlerts;
    /** The viewing player's own "every villager injury" switch, beside {@link #townAlerts}. */
    private boolean everyInjury;
    /** The running village's ID, so the shop counts only its emeralds, as the server does. */
    @Nullable
    private final UUID villageId;
    /**
     * The running village has no owner and claims are on: the screen shows only the Claim page
     * ({@code VillageClaims.showsClaimPage}). Never with {@link #paused}, whose page comes first.
     */
    private final boolean unclaimed;
    /** Claim was pressed and the server has not answered yet (client only): the button waits. */
    private boolean claimSent;
    /**
     * The Land tab's owner, square, neighbours and people; null for a village with no Town Hall (then no Land tab).
     * Replaced on the client by a People change's answer ({@link #applyPeopleResult}).
     */
    @Nullable
    private com.alex.test.village.claims.LandView land;
    /** Counts {@link #land} replacements (client), so the screen sees its tabs may have changed. */
    private int landChanges;
    /** The last People answer ({@code VillageClaims.PeopleReply} index, -1 none) and the name it is about (client). */
    private int peopleReply = -1;
    private String peopleReplyName = "";

    /** A paused village: its ID, so payment is counted as the server counts it, and where its token goes. */
    public record PausedVillage(UUID villageId, BlockPos townHall) {}

    /** Last purchase outcome, rendered on the shop tab. Client-side only. */
    private String status = "";
    private boolean statusSuccess;

    public MayorStatsMenu(int id, Inventory inventory, MayorEntity mayor,
                          MayorStatsSnapshot snapshot, @Nullable PausedVillage paused, int townAlerts,
                          boolean everyInjury, @Nullable UUID villageId, boolean unclaimed,
                          @Nullable com.alex.test.village.claims.LandView land) {
        super(DynamicVillages.MAYOR_STATS_MENU.get(), id);
        this.mayor = mayor;
        this.mayorId = mayor.getId();
        this.snapshot = snapshot;
        this.paused = paused;
        this.townAlerts = townAlerts;
        this.everyInjury = everyInjury;
        this.villageId = villageId;
        this.unclaimed = unclaimed && paused == null;
        this.land = land;
    }

    public MayorStatsMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        super(DynamicVillages.MAYOR_STATS_MENU.get(), id);
        this.mayor = null;
        this.mayorId = buffer.readVarInt();
        this.snapshot = MayorStatsSnapshot.read(buffer);
        this.paused = buffer.readBoolean()
                ? new PausedVillage(buffer.readUUID(), buffer.readBlockPos()) : null;
        this.townAlerts = buffer.readVarInt();
        this.everyInjury = buffer.readBoolean();
        this.villageId = buffer.readBoolean() ? buffer.readUUID() : null;
        this.unclaimed = buffer.readBoolean();
        this.land = buffer.readBoolean() ? com.alex.test.village.claims.LandView.read(buffer) : null;
    }

    public void writeClientData(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(mayorId);
        snapshot.write(buffer);
        buffer.writeBoolean(paused != null);
        if (paused != null) {
            buffer.writeUUID(paused.villageId());
            buffer.writeBlockPos(paused.townHall());
        }
        buffer.writeVarInt(townAlerts);
        buffer.writeBoolean(everyInjury);
        buffer.writeBoolean(villageId != null);
        if (villageId != null) buffer.writeUUID(villageId);
        buffer.writeBoolean(unclaimed);
        buffer.writeBoolean(land != null);
        if (land != null) land.write(buffer);
    }

    /** The running village has no owner yet: the screen shows only the Claim page. */
    public boolean unclaimed() {
        return unclaimed;
    }

    /** The Land tab's facts; null when the village has no Town Hall (the tab is then left out). */
    @Nullable
    public com.alex.test.village.claims.LandView land() {
        return land;
    }

    public int landChanges() {
        return landChanges;
    }

    public int peopleReply() {
        return peopleReply;
    }

    public String peopleReplyName() {
        return peopleReplyName;
    }

    /** The server answered a People change: the line to show, and the land and people as they are now. */
    public void applyPeopleResult(int reply, String name,
                                  @Nullable com.alex.test.village.claims.LandView land) {
        this.peopleReply = reply;
        this.peopleReplyName = name == null ? "" : name;
        this.land = land;
        landChanges++;
    }

    public boolean claimSent() {
        return claimSent;
    }

    public void markClaimSent() {
        claimSent = true;
    }

    /** The running village's ID; null when the Mayor has none. */
    @Nullable
    public UUID villageId() {
        return villageId;
    }

    /** Where the paused village's token goes; null while the village runs. */
    @Nullable
    public BlockPos pausedTownHall() {
        return paused != null ? paused.townHall() : null;
    }

    @Nullable
    public UUID pausedVillageId() {
        return paused != null ? paused.villageId() : null;
    }

    public MayorStatsSnapshot snapshot() {
        return snapshot;
    }

    public int townAlerts() {
        return townAlerts;
    }

    /** The player's own alert level, on the client's copy, as with births. */
    public void setTownAlertsLocally(int level) {
        this.townAlerts = level;
    }

    public boolean everyInjury() {
        return everyInjury;
    }

    public void setEveryInjuryLocally(boolean on) {
        this.everyInjury = on;
    }

    public int mayorId() {
        return mayorId;
    }

    /**
     * Flips the births flag on the client's copy of the snapshot.
     *
     * <p>The screen is a rendering of a server snapshot that is only re-sent
     * when the menu is opened or a trade lands, so without this the label would
     * keep saying "on" until the player closed and reopened the ledger, and the
     * click would look like it had done nothing. The server is still the
     * authority: the next real snapshot overwrites this.
     */
    public void setBirthsEnabledLocally(boolean enabled) {
        this.snapshot = new MayorStatsSnapshot(
                snapshot.buildingCount(), snapshot.buildings(), snapshot.buildingInstances(),
                snapshot.villagerCount(), snapshot.villagers(),
                snapshot.villageSize(), snapshot.offers(), enabled,
                snapshot.minerSideBranchDistance(), snapshot.playerHurtBell(),
                snapshot.towerAlarmEndsWhenClear(), snapshot.defenderTeams());
    }

    public void setMinerSideBranchDistanceLocally(int distance) {
        this.snapshot = new MayorStatsSnapshot(
                snapshot.buildingCount(), snapshot.buildings(), snapshot.buildingInstances(),
                snapshot.villagerCount(), snapshot.villagers(),
                snapshot.villageSize(), snapshot.offers(),
                snapshot.birthsEnabled(), distance, snapshot.playerHurtBell(),
                snapshot.towerAlarmEndsWhenClear(), snapshot.defenderTeams());
    }

    /** The two watchtower bell rules, on the client's copy, as with births. */
    public void setBellRulesLocally(int playerHurtBell, boolean towerAlarmEndsWhenClear) {
        this.snapshot = new MayorStatsSnapshot(
                snapshot.buildingCount(), snapshot.buildings(), snapshot.buildingInstances(),
                snapshot.villagerCount(), snapshot.villagers(),
                snapshot.villageSize(), snapshot.offers(),
                snapshot.birthsEnabled(), snapshot.minerSideBranchDistance(), playerHurtBell,
                towerAlarmEndsWhenClear, snapshot.defenderTeams());
    }

    /** The defender team rules, on the client's copy, as with births. */
    public void setDefenderTeamsLocally(com.alex.test.village.DefenderTeamRules rules) {
        this.snapshot = new MayorStatsSnapshot(
                snapshot.buildingCount(), snapshot.buildings(), snapshot.buildingInstances(),
                snapshot.villagerCount(), snapshot.villagers(),
                snapshot.villageSize(), snapshot.offers(),
                snapshot.birthsEnabled(), snapshot.minerSideBranchDistance(), snapshot.playerHurtBell(),
                snapshot.towerAlarmEndsWhenClear(), rules);
    }

    /** Applies a purchase reply: new prices, new size, and a line to show. */
    public void applyTradeResult(boolean success, String message, int villageSize,
                                 List<TradeOfferEntry> offers) {
        this.snapshot = new MayorStatsSnapshot(
                snapshot.buildingCount(), snapshot.buildings(), snapshot.buildingInstances(),
                snapshot.villagerCount(), snapshot.villagers(),
                villageSize, offers, snapshot.birthsEnabled(),
                snapshot.minerSideBranchDistance(), snapshot.playerHurtBell(),
                snapshot.towerAlarmEndsWhenClear(), snapshot.defenderTeams());
        this.status = message;
        this.statusSuccess = success;
    }

    public String status() {
        return status;
    }

    public boolean statusSuccess() {
        return statusSuccess;
    }

    /** Server-side ownership check used by the Mayor's lightweight UI movement lock. */
    public boolean isForMayor(MayorEntity mayor) {
        return this.mayor == mayor;
    }

    @Override
    public boolean stillValid(Player player) {
        return mayor == null || (mayor.isAlive() && player.distanceToSqr(mayor) < 64.0);
    }

    @Override
    public boolean isForNpc(NpcEntity npc) {
        return mayor == npc;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
