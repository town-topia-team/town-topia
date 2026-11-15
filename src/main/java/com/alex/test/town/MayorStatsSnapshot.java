package com.alex.test.town;

import net.minecraft.network.RegistryFriendlyByteBuf;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;

import java.util.List;

/** Complete read-only payload rendered by the Mayor statistics screen. */
public record MayorStatsSnapshot(
        int buildingCount,
        List<BuildingStatsEntry> buildings,
        /** Every building on its own, grouped by type in the order of {@link #buildings}. */
        List<BuildingInstanceEntry> buildingInstances,
        int villagerCount,
        List<VillagerStatsEntry> villagers,
        /** Villagers plus buildings, excluding the Town Hall — what prices scale on. */
        int villageSize,
        /** The Mayor's shop, priced for this village at the moment it was taken. */
        List<TradeOfferEntry> offers,
        /** Whether the village is currently allowed to have children. */
        boolean birthsEnabled,
        int minerSideBranchDistance,
        /** Village rule: when the player getting hurt rings the watchtower bell ({@code VillageRules.PlayerHurtBell} index). */
        int playerHurtBell,
        /** Village rule: a lookout's alarm ends once the village is clear of monsters. */
        boolean towerAlarmEndsWhenClear,
        /** Village rules and Professions: how the defenders split up to fight. */
        com.alex.test.village.DefenderTeamRules defenderTeams
) {
    public static final MayorStatsSnapshot EMPTY =
            new MayorStatsSnapshot(0, List.of(), List.of(), 0, List.of(), 0, List.of(), true,
                    MinerSettingsSchema.DEFAULT_SIDE_BRANCH_DISTANCE,
                    com.alex.test.village.VillageRules.PlayerHurtBell.DEFAULT.ordinal(), true,
                    com.alex.test.village.DefenderTeamRules.DEFAULT);

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(buildingCount);
        buffer.writeCollection(buildings, (buf, entry) -> entry.write((RegistryFriendlyByteBuf) buf));
        buffer.writeCollection(buildingInstances, (buf, entry) -> entry.write((RegistryFriendlyByteBuf) buf));
        buffer.writeVarInt(villagerCount);
        buffer.writeCollection(villagers, (buf, entry) -> entry.write((RegistryFriendlyByteBuf) buf));
        buffer.writeVarInt(villageSize);
        buffer.writeCollection(offers, (buf, entry) -> entry.write((RegistryFriendlyByteBuf) buf));
        buffer.writeBoolean(birthsEnabled);
        buffer.writeVarInt(minerSideBranchDistance);
        buffer.writeVarInt(playerHurtBell);
        buffer.writeBoolean(towerAlarmEndsWhenClear);
        defenderTeams.write(buffer);
    }

    public static MayorStatsSnapshot read(RegistryFriendlyByteBuf buffer) {
        int buildingCount = buffer.readVarInt();
        List<BuildingStatsEntry> buildings =
                buffer.readList(buf -> BuildingStatsEntry.read((RegistryFriendlyByteBuf) buf));
        List<BuildingInstanceEntry> buildingInstances =
                buffer.readList(buf -> BuildingInstanceEntry.read((RegistryFriendlyByteBuf) buf));
        int villagerCount = buffer.readVarInt();
        List<VillagerStatsEntry> villagers =
                buffer.readList(buf -> VillagerStatsEntry.read((RegistryFriendlyByteBuf) buf));
        int villageSize = buffer.readVarInt();
        List<TradeOfferEntry> offers =
                buffer.readList(buf -> TradeOfferEntry.read((RegistryFriendlyByteBuf) buf));
        boolean birthsEnabled = buffer.readBoolean();
        int minerSideBranchDistance = buffer.readVarInt();
        int playerHurtBell = buffer.readVarInt();
        boolean towerAlarmEndsWhenClear = buffer.readBoolean();
        com.alex.test.village.DefenderTeamRules defenderTeams = com.alex.test.village.DefenderTeamRules.read(buffer);
        return new MayorStatsSnapshot(
                buildingCount, List.copyOf(buildings), List.copyOf(buildingInstances),
                villagerCount, List.copyOf(villagers),
                villageSize, List.copyOf(offers), birthsEnabled,
                minerSideBranchDistance, playerHurtBell, towerAlarmEndsWhenClear, defenderTeams);
    }
}
