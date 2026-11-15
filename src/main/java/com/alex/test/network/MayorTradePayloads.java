package com.alex.test.network;

import com.alex.test.DynamicVillages;
import com.alex.test.town.TradeOfferEntry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * The Mayor shop's two messages.
 *
 * <p>{@link Buy} is the only thing the client may ask for, and it names an
 * item, never a price — the server re-derives what the village charges. Every
 * {@code Buy}, successful or not, is answered with a {@link Result}, which
 * carries a freshly priced catalogue: buying raises the village's size, so the
 * prices on screen are stale the moment a purchase lands.
 */
public final class MayorTradePayloads {

    private MayorTradePayloads() {}

    /** "Sell me this." */
    public record Buy(int mayorId, String itemId) implements CustomPacketPayload {
        public static final Type<Buy> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_shop_buy"));
        public static final StreamCodec<FriendlyByteBuf, Buy> CODEC = new StreamCodec<>() {
            @Override public Buy decode(FriendlyByteBuf buffer) {
                return new Buy(buffer.readVarInt(), buffer.readUtf(128));
            }
            @Override public void encode(FriendlyByteBuf buffer, Buy payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeUtf(payload.itemId(), 128);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * "Make me a new Town Hall token." Only answered while the Mayor's village
     * is paused without one; paid with emeralds or with what crafting one takes.
     */
    public record BuyReplacementTownHall(int mayorId, boolean withEmeralds) implements CustomPacketPayload {
        public static final Type<BuyReplacementTownHall> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_buy_replacement_town_hall"));
        public static final StreamCodec<FriendlyByteBuf, BuyReplacementTownHall> CODEC = new StreamCodec<>() {
            @Override public BuyReplacementTownHall decode(FriendlyByteBuf buffer) {
                return new BuyReplacementTownHall(buffer.readVarInt(), buffer.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buffer, BuyReplacementTownHall payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeBoolean(payload.withEmeralds());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** "Claim this village" on the Mayor of a village nobody owns (village claims, round 2). */
    public record ClaimVillage(int mayorId) implements CustomPacketPayload {
        public static final Type<ClaimVillage> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_claim_village"));
        public static final StreamCodec<FriendlyByteBuf, ClaimVillage> CODEC = new StreamCodec<>() {
            @Override public ClaimVillage decode(FriendlyByteBuf buffer) {
                return new ClaimVillage(buffer.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, ClaimVillage payload) {
                buffer.writeVarInt(payload.mayorId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * A change on the Land tab's People page (village claims, round 4): {@code action} is a
     * {@code VillageClaims.PeopleAction}; {@code target} the listed or online player (or none), {@code name} a typed
     * name, {@code rank} a {@code ClaimRank}. The server checks the sender owns the village.
     */
    public record ClaimPeople(int mayorId, int action, java.util.Optional<java.util.UUID> target, String name, int rank)
            implements CustomPacketPayload {
        public static final Type<ClaimPeople> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_claim_people"));
        public static final StreamCodec<FriendlyByteBuf, ClaimPeople> CODEC = new StreamCodec<>() {
            @Override public ClaimPeople decode(FriendlyByteBuf buffer) {
                int mayorId = buffer.readVarInt();
                int action = buffer.readVarInt();
                java.util.Optional<java.util.UUID> target = buffer.readBoolean()
                        ? java.util.Optional.of(buffer.readUUID()) : java.util.Optional.empty();
                return new ClaimPeople(mayorId, action, target, buffer.readUtf(16), buffer.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, ClaimPeople payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeVarInt(payload.action());
                buffer.writeBoolean(payload.target().isPresent());
                payload.target().ifPresent(buffer::writeUUID);
                buffer.writeUtf(payload.name(), 16);
                buffer.writeVarInt(payload.rank());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * One switch on the Land tab's Rights page (village claims, round 9): may {@code rank} (a {@code ClaimRank})
     * do {@code act} (a {@code ClaimLock.Act})? Answered with {@link ClaimPeopleResult}. The server checks the
     * sender owns the village.
     */
    /**
     * One switch on the Land tab's Protection page (village claims, round 10): {@code kind} is a
     * {@code LandGuard.Kind}. Answered with {@link ClaimPeopleResult}. The server checks the sender owns the village.
     */
    public record ClaimGuard(int mayorId, int kind, boolean on) implements CustomPacketPayload {
        public static final Type<ClaimGuard> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_claim_guard"));
        public static final StreamCodec<FriendlyByteBuf, ClaimGuard> CODEC = new StreamCodec<>() {
            @Override public ClaimGuard decode(FriendlyByteBuf buffer) {
                return new ClaimGuard(buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buffer, ClaimGuard payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeVarInt(payload.kind());
                buffer.writeBoolean(payload.on());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ClaimRight(int mayorId, int act, int rank, boolean on) implements CustomPacketPayload {
        public static final Type<ClaimRight> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_claim_right"));
        public static final StreamCodec<FriendlyByteBuf, ClaimRight> CODEC = new StreamCodec<>() {
            @Override public ClaimRight decode(FriendlyByteBuf buffer) {
                return new ClaimRight(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buffer, ClaimRight payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeVarInt(payload.act());
                buffer.writeVarInt(payload.rank());
                buffer.writeBoolean(payload.on());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * The answer to {@link ClaimPeople}: how it went ({@code VillageClaims.PeopleReply}), the player's name for the
     * line, and the Land tab's facts as they are now (none when the village has no Town Hall).
     */
    public record ClaimPeopleResult(int mayorId, int reply, String name,
                                    java.util.Optional<com.alex.test.village.claims.LandView> land)
            implements CustomPacketPayload {
        public static final Type<ClaimPeopleResult> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_claim_people_result"));
        public static final StreamCodec<FriendlyByteBuf, ClaimPeopleResult> CODEC = new StreamCodec<>() {
            @Override public ClaimPeopleResult decode(FriendlyByteBuf buffer) {
                int mayorId = buffer.readVarInt();
                int reply = buffer.readVarInt();
                String name = buffer.readUtf(64);
                java.util.Optional<com.alex.test.village.claims.LandView> land = buffer.readBoolean()
                        ? java.util.Optional.of(com.alex.test.village.claims.LandView.read(buffer))
                        : java.util.Optional.empty();
                return new ClaimPeopleResult(mayorId, reply, name, land);
            }
            @Override public void encode(FriendlyByteBuf buffer, ClaimPeopleResult payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeVarInt(payload.reply());
                buffer.writeUtf(payload.name(), 64);
                buffer.writeBoolean(payload.land().isPresent());
                payload.land().ifPresent(land -> land.write(buffer));
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** "Stop (or resume) having children." */
    public record SetBirths(int mayorId, boolean enabled) implements CustomPacketPayload {
        public static final Type<SetBirths> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_set_births"));
        public static final StreamCodec<FriendlyByteBuf, SetBirths> CODEC = new StreamCodec<>() {
            @Override public SetBirths decode(FriendlyByteBuf buffer) {
                return new SetBirths(buffer.readVarInt(), buffer.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buffer, SetBirths payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeBoolean(payload.enabled());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The two watchtower bell village rules (Settings > Village rules); the server saves them. */
    public record SetBellRules(int mayorId, int playerHurtBell, boolean towerAlarmEndsWhenClear)
            implements CustomPacketPayload {
        public static final Type<SetBellRules> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_set_bell_rules"));
        public static final StreamCodec<FriendlyByteBuf, SetBellRules> CODEC = new StreamCodec<>() {
            @Override public SetBellRules decode(FriendlyByteBuf buffer) {
                return new SetBellRules(buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buffer, SetBellRules payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeVarInt(payload.playerHurtBell());
                buffer.writeBoolean(payload.towerAlarmEndsWhenClear());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * How the defenders split up to fight (Settings > Village rules and
     * Settings > Professions); the server saves the whole set.
     */
    public record SetDefenderTeams(int mayorId, com.alex.test.village.DefenderTeamRules rules)
            implements CustomPacketPayload {
        public static final Type<SetDefenderTeams> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_set_defender_teams"));
        public static final StreamCodec<FriendlyByteBuf, SetDefenderTeams> CODEC = new StreamCodec<>() {
            @Override public SetDefenderTeams decode(FriendlyByteBuf buffer) {
                return new SetDefenderTeams(buffer.readVarInt(),
                        com.alex.test.village.DefenderTeamRules.read(buffer));
            }
            @Override public void encode(FriendlyByteBuf buffer, SetDefenderTeams payload) {
                buffer.writeVarInt(payload.mayorId());
                payload.rules().write(buffer);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * The sender's own town alerts ({@code TownAlertLevel} index), set on the
     * Mayor's Village rules page; saved for that player only.
     */
    public record SetTownAlerts(int level) implements CustomPacketPayload {
        public static final Type<SetTownAlerts> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_set_town_alerts"));
        public static final StreamCodec<FriendlyByteBuf, SetTownAlerts> CODEC = new StreamCodec<>() {
            @Override public SetTownAlerts decode(FriendlyByteBuf buffer) {
                return new SetTownAlerts(buffer.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, SetTownAlerts payload) {
                buffer.writeVarInt(payload.level());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * The sender's own "every villager injury" switch (every injury not from a
     * monster, a player's hits included), set beside town alerts; saved for that player only.
     */
    public record SetEveryInjuryAlerts(boolean on) implements CustomPacketPayload {
        public static final Type<SetEveryInjuryAlerts> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_set_every_injury_alerts"));
        public static final StreamCodec<FriendlyByteBuf, SetEveryInjuryAlerts> CODEC = new StreamCodec<>() {
            @Override public SetEveryInjuryAlerts decode(FriendlyByteBuf buffer) {
                return new SetEveryInjuryAlerts(buffer.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buffer, SetEveryInjuryAlerts payload) {
                buffer.writeBoolean(payload.on());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Village-wide Miner setting; the server clamps and persists the value. */
    public record SetMinerBranchDistance(int mayorId, int blocks) implements CustomPacketPayload {
        public static final Type<SetMinerBranchDistance> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID,
                        "mayor_set_miner_branch_distance"));
        public static final StreamCodec<FriendlyByteBuf, SetMinerBranchDistance> CODEC = new StreamCodec<>() {
            @Override public SetMinerBranchDistance decode(FriendlyByteBuf buffer) {
                return new SetMinerBranchDistance(buffer.readVarInt(), buffer.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, SetMinerBranchDistance payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeVarInt(payload.blocks());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record MinerBranchDistanceResult(int mayorId, int blocks) implements CustomPacketPayload {
        public static final Type<MinerBranchDistanceResult> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID,
                        "mayor_miner_branch_distance_result"));
        public static final StreamCodec<FriendlyByteBuf, MinerBranchDistanceResult> CODEC = new StreamCodec<>() {
            @Override public MinerBranchDistanceResult decode(FriendlyByteBuf buffer) {
                return new MinerBranchDistanceResult(buffer.readVarInt(), buffer.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, MinerBranchDistanceResult payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeVarInt(payload.blocks());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The outcome, plus the catalogue re-priced for the village as it now is. */
    public record Result(
            boolean success,
            String message,
            int villageSize,
            List<TradeOfferEntry> offers) implements CustomPacketPayload {

        public static final Type<Result> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_shop_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = new StreamCodec<>() {
            @Override public Result decode(RegistryFriendlyByteBuf buffer) {
                boolean success = buffer.readBoolean();
                String message = buffer.readUtf(256);
                int villageSize = buffer.readVarInt();
                List<TradeOfferEntry> offers = buffer.readList(
                        buf -> TradeOfferEntry.read((RegistryFriendlyByteBuf) buf));
                return new Result(success, message, villageSize, List.copyOf(offers));
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, Result payload) {
                buffer.writeBoolean(payload.success());
                buffer.writeUtf(payload.message(), 256);
                buffer.writeVarInt(payload.villageSize());
                buffer.writeCollection(payload.offers(),
                        (buf, entry) -> entry.write((RegistryFriendlyByteBuf) buf));
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
