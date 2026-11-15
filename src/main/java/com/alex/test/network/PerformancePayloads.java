package com.alex.test.network;

import com.alex.test.DynamicVillages;
import com.alex.test.client.ClientPerformanceInfo;
import com.alex.test.performance.PerformanceCost;
import com.alex.test.performance.PerformanceOption;
import com.alex.test.performance.PerformanceSettings;
import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The Mayor's Performance page (Settings > Village rules > Performance): world-wide choices from
 * {@link PerformanceOption}. The page asks when it opens and every 2 s while open ({@link RequestInfo}),
 * a click asks for one change ({@link SetOption}); the server always answers with what it really runs
 * ({@link Info}). Only the server owner ({@link PerformanceSettings#mayChange}) changes anything: the
 * server checks again here, whatever the client's buttons showed.
 */
public final class PerformancePayloads {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Options in one answer at most, read and written. */
    private static final int MAX_VALUES = 32;
    /** A time not sent: not the server owner, or the option has no meter or is not running. */
    public static final long NONE = -1;

    private PerformancePayloads() {}

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(RequestInfo.TYPE, RequestInfo.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> answer(payload.mayorId(), ctx.player(), null, false)));
        registrar.playToServer(SetOption.TYPE, SetOption.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> answer(payload.mayorId(), ctx.player(),
                        payload.key(), payload.on())));
        registrar.playToClient(Info.TYPE, Info.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> ClientPerformanceInfo.set(payload.mayorId(),
                        payload.mayChange(), payload.tickNanos(), payload.tickTargetNanos(), payload.values())));
    }

    /**
     * Changes one option first when {@code key} is given and allowed, then sends every live value back. The
     * server owner also gets the costs: the server's average tick and each running option's share (last 5 s).
     */
    private static void answer(int mayorId, Player sender, @Nullable String key, boolean on) {
        if (RaidPayloads.mayorFor(mayorId, sender) == null || !(sender instanceof ServerPlayer player)) return;
        boolean mayChange = PerformanceSettings.mayChange(player);
        if (key != null) change(player, mayChange, key, on);
        MinecraftServer server = player.server;
        List<Value> values = new ArrayList<>();
        for (PerformanceOption option : PerformanceOption.of(PerformanceOption.Group.SERVER)) {
            boolean live = option.live();
            // An option that does nothing right now (off, or what it needs is off) shows no cost.
            boolean running = live && (option.needs() == null || option.needs().live());
            PerformanceCost cost = mayChange && running ? option.cost(server) : null;
            values.add(new Value(option.key(), live, option.stopped(),
                    cost == null ? NONE : cost.averageNanos(), cost == null ? NONE : cost.worstNanos()));
        }
        long tickNanos = mayChange ? server.getAverageTickTimeNanos() : NONE;
        long tickTargetNanos = mayChange ? server.tickRateManager().nanosecondsPerTick() : NONE;
        PacketDistributor.sendToPlayer(player, new Info(mayorId, mayChange, tickNanos, tickTargetNanos, values));
    }

    private static void change(ServerPlayer player, boolean mayChange, String key, boolean on) {
        PerformanceOption option = PerformanceOption.byKey(key);
        String who = player.getGameProfile().getName();
        String refused = !mayChange ? "not the server owner"
                : option == null ? "no such option"
                : option.group() != PerformanceOption.Group.SERVER ? "not a server option"
                : option.needs() != null && !option.needs().live() ? option.needs().key() + " is off"
                : null;
        if (refused != null) {
            // The page greys these out, so this is a lost race (op taken away) or a changed client.
            LOGGER.info("[Performance] {} asked for {} {}: refused, {}.", who, key, on ? "on" : "off", refused);
            return;
        }
        PerformanceSettings.get(player.server).set(option, on);
        option.apply(on);
        LOGGER.info("[Performance] {} set {} {} (saved for this world).", who, option.key(), on ? "on" : "off");
    }

    /** "What does the server run?" */
    public record RequestInfo(int mayorId) implements CustomPacketPayload {
        public static final Type<RequestInfo> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "performance_request"));
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

    /** "Turn this option on or off" ({@link PerformanceOption#key}). */
    public record SetOption(int mayorId, String key, boolean on) implements CustomPacketPayload {
        public static final Type<SetOption> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "performance_set"));
        public static final StreamCodec<FriendlyByteBuf, SetOption> CODEC = new StreamCodec<>() {
            @Override public SetOption decode(FriendlyByteBuf buffer) {
                return new SetOption(buffer.readVarInt(), buffer.readUtf(64), buffer.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buffer, SetOption payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeUtf(payload.key(), 64);
                buffer.writeBoolean(payload.on());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * One option as the server runs it now; {@code stopped}: a safety cut-out switched it off. Cost: its average
     * and worst tick over the last 5 s in nanoseconds, {@link #NONE} when not sent.
     */
    public record Value(String key, boolean on, boolean stopped, long costAverageNanos, long costWorstNanos) {}

    /**
     * Every option's live value, and whether this player may change them. {@code tickNanos}: the server's average
     * tick over the last 5 s, {@code tickTargetNanos}: one tick's share of a second (50 ms); {@link #NONE} for
     * anyone but the server owner.
     */
    public record Info(int mayorId, boolean mayChange, long tickNanos, long tickTargetNanos, List<Value> values)
            implements CustomPacketPayload {
        public static final Type<Info> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "performance_info"));
        public static final StreamCodec<FriendlyByteBuf, Info> CODEC = new StreamCodec<>() {
            @Override public Info decode(FriendlyByteBuf buffer) {
                int mayorId = buffer.readVarInt();
                boolean mayChange = buffer.readBoolean();
                long tickNanos = buffer.readLong();
                long tickTargetNanos = buffer.readLong();
                int count = Math.min(buffer.readVarInt(), MAX_VALUES);
                List<Value> values = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    values.add(new Value(buffer.readUtf(64), buffer.readBoolean(), buffer.readBoolean(),
                            buffer.readLong(), buffer.readLong()));
                }
                return new Info(mayorId, mayChange, tickNanos, tickTargetNanos, values);
            }
            @Override public void encode(FriendlyByteBuf buffer, Info payload) {
                buffer.writeVarInt(payload.mayorId());
                buffer.writeBoolean(payload.mayChange());
                buffer.writeLong(payload.tickNanos());
                buffer.writeLong(payload.tickTargetNanos());
                int count = Math.min(payload.values().size(), MAX_VALUES);
                buffer.writeVarInt(count);
                for (int i = 0; i < count; i++) {
                    Value value = payload.values().get(i);
                    buffer.writeUtf(value.key(), 64);
                    buffer.writeBoolean(value.on());
                    buffer.writeBoolean(value.stopped());
                    buffer.writeLong(value.costAverageNanos());
                    buffer.writeLong(value.costWorstNanos());
                }
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
