package com.alex.test.network;

import com.alex.test.DynamicVillages;
import com.alex.test.client.ClientVillageLog;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.log.VillageLog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The Mayor's Village Log tab. The window asks when the tab opens and every 5 s while it shows ({@link RequestLog},
 * with the version it already has); the server answers with the whole log ({@link Log}) only when it changed since.
 * Like the Raids page, only for a player who has that Mayor open and stands by it.
 */
public final class VillageLogPayloads {

    /** The version sent with the empty list of a Mayor whose village cannot be found (a log's is never 0). */
    private static final long NO_VILLAGE = 0L;

    private VillageLogPayloads() {}

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(RequestLog.TYPE, RequestLog.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> answer(payload, ctx.player())));
        registrar.playToClient(Log.TYPE, Log.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> ClientVillageLog.set(payload.mayorId(), payload.version(),
                        VillageLog.readEntries(payload.data()))));
    }

    private static void answer(RequestLog request, Player sender) {
        MayorEntity mayor = RaidPayloads.mayorFor(request.mayorId(), sender);
        if (mayor == null || !(sender instanceof ServerPlayer player)) return;
        VillageManager village = mayor.resolveManager();
        if (village == null) {
            // An empty list, so the tab does not say "Asking the Mayor..." for ever.
            if (request.knownVersion() != NO_VILLAGE) {
                PacketDistributor.sendToPlayer(player, new Log(request.mayorId(), NO_VILLAGE, new CompoundTag()));
            }
            return;
        }
        VillageLog log = village.rules().log();
        if (log.version() == request.knownVersion()) return;
        PacketDistributor.sendToPlayer(player, new Log(request.mayorId(), log.version(), log.save()));
    }

    /** "Send me this village's log, unless it is still the version I have" (-1: I have none). */
    public record RequestLog(int mayorId, long knownVersion) implements CustomPacketPayload {
        public static final Type<RequestLog> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "village_log_request"));
        public static final StreamCodec<FriendlyByteBuf, RequestLog> CODEC = new StreamCodec<>() {
            @Override public RequestLog decode(FriendlyByteBuf buffer) {
                return new RequestLog(buffer.readVarInt(), buffer.readVarLong());
            }
            @Override public void encode(FriendlyByteBuf buffer, RequestLog payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeVarLong(payload.knownVersion());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The whole log, as it is saved ({@link VillageLog#save}); at most 200 entries. */
    public record Log(int mayorId, long version, CompoundTag data) implements CustomPacketPayload {
        public static final Type<Log> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "village_log"));
        public static final StreamCodec<FriendlyByteBuf, Log> CODEC = new StreamCodec<>() {
            @Override public Log decode(FriendlyByteBuf buffer) {
                int mayorId = buffer.readVarInt();
                long version = buffer.readVarLong();
                CompoundTag data = buffer.readNbt();
                return new Log(mayorId, version, data == null ? new CompoundTag() : data);
            }
            @Override public void encode(FriendlyByteBuf buffer, Log payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeVarLong(payload.version());
                buffer.writeNbt(payload.data());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
