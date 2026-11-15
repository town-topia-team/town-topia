package com.alex.test.network;

import com.alex.test.DynamicVillages;
import com.alex.test.client.ClientRaidInfo;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.ui.MayorStatsMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.raid.RaidFrequency;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import javax.annotation.Nullable;

/**
 * The Mayor's raid setting (Settings > Village rules > Raids). Kept out of the
 * Mayor's main snapshot: the screen asks for it when it opens ({@link
 * RequestInfo}), the server answers ({@link Info}), and a click sets how often
 * raids come ({@link SetFrequency}), answered with a fresh {@link Info}.
 *
 * <p>Opening the Mayor of a village is also what lets raids come there at all
 * (see {@code RaidManager.markMayorUsed}).
 */
public final class RaidPayloads {

    /** The page's lines: Minecraft's text codec with the game's registries, so a line may also carry an item. */
    private static final StreamCodec<RegistryFriendlyByteBuf, Component> TEXT = ComponentSerialization.TRUSTED_STREAM_CODEC;

    private RaidPayloads() {}

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(RequestInfo.TYPE, RequestInfo.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> answer(payload.mayorId(), ctx.player(), null)));
        registrar.playToServer(SetFrequency.TYPE, SetFrequency.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> answer(payload.mayorId(), ctx.player(),
                        RaidFrequency.byIndex(payload.frequency()))));
        registrar.playToClient(Info.TYPE, Info.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> ClientRaidInfo.set(payload.mayorId(),
                        payload.frequency(), payload.status(), payload.fame(), payload.totals(), payload.bestOmen(), payload.history())));
    }

    /** Sets the frequency first when one is given, then sends the village's raid line back. */
    private static void answer(int mayorId, Player sender, @Nullable RaidFrequency set) {
        MayorEntity mayor = mayorFor(mayorId, sender);
        if (mayor == null || !(sender instanceof ServerPlayer player)) return;
        VillageManager village = mayor.resolveManager();
        if (village == null) return;
        village.raids().markMayorUsed();
        // Village claims round 7a: refused, the answer below still puts the page back to the real choice.
        if (set != null && set != village.rules().raidFrequency() && ModPayloads.mayManage(player, village)) {
            village.rules().setRaidFrequency(set);
            village.markPersistentDataDirty();
            if (player.level() instanceof ServerLevel level) village.raids().onFrequencyChanged(level);
        }
        PacketDistributor.sendToPlayer(player, new Info(mayorId,
                village.rules().raidFrequency().ordinal(), village.raids().statusLine(),
                village.raids().fame(), village.raids().totalsLine(), village.raids().bestOmenWon(),
                village.raids().historyLines()));
    }

    /** The Mayor the sender really has open and is standing by. Also used by {@link PerformancePayloads}. */
    @Nullable
    static MayorEntity mayorFor(int mayorId, Player sender) {
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

    /** "What are this village's raids doing?" */
    public record RequestInfo(int mayorId) implements CustomPacketPayload {
        public static final Type<RequestInfo> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "raid_request_info"));
        public static final StreamCodec<FriendlyByteBuf, RequestInfo> CODEC = new StreamCodec<>() {
            @Override public RequestInfo decode(FriendlyByteBuf buffer) {
                return new RequestInfo(buffer.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, RequestInfo payload) {
                buffer.writeVarInt(payload.mayorId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** "Raids should come this often" ({@link RaidFrequency} ordinal). */
    public record SetFrequency(int mayorId, int frequency) implements CustomPacketPayload {
        public static final Type<SetFrequency> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "raid_set_frequency"));
        public static final StreamCodec<FriendlyByteBuf, SetFrequency> CODEC = new StreamCodec<>() {
            @Override public SetFrequency decode(FriendlyByteBuf buffer) {
                return new SetFrequency(buffer.readVarInt(), buffer.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, SetFrequency payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeVarInt(payload.frequency());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * The village's raid setting, one line on how things stand, and the Raids
     * page: fame, totals and the last raids (newest first). The lines go as
     * text components (keys with their English), so each player's own game
     * translates them.
     */
    public record Info(int mayorId, int frequency, Component status, int fame, Component totals, int bestOmen,
                       java.util.List<Component> history) implements CustomPacketPayload {
        public static final Type<Info> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "raid_info"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Info> CODEC = new StreamCodec<>() {
            @Override public Info decode(RegistryFriendlyByteBuf buffer) {
                int mayorId = buffer.readVarInt();
                int frequency = buffer.readVarInt();
                Component status = TEXT.decode(buffer);
                int fame = buffer.readVarInt();
                Component totals = TEXT.decode(buffer);
                int bestOmen = buffer.readVarInt();
                int lines = Math.min(buffer.readVarInt(), 32);
                java.util.List<Component> history = new java.util.ArrayList<>();
                for (int i = 0; i < lines; i++) history.add(TEXT.decode(buffer));
                return new Info(mayorId, frequency, status, fame, totals, bestOmen, history);
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, Info payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeVarInt(payload.frequency());
                TEXT.encode(buffer, payload.status());
                buffer.writeVarInt(payload.fame());
                TEXT.encode(buffer, payload.totals());
                buffer.writeVarInt(payload.bestOmen());
                int lines = Math.min(payload.history().size(), 32);
                buffer.writeVarInt(lines);
                for (int i = 0; i < lines; i++) TEXT.encode(buffer, payload.history().get(i));
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
