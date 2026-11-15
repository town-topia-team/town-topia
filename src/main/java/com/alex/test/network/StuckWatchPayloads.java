package com.alex.test.network;

import com.alex.test.DynamicVillages;
import com.alex.test.village.stuckwatch.BlackBox;
import com.alex.test.village.stuckwatch.StuckWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/**
 * Stuck catcher: the server sends a catch's recording to the client's ghost
 * viewer ({@link Show}), moves its cursor ({@link Cursor}, from
 * {@code /villager stuckwatch at}), shows a rewound villager in place while
 * frozen ({@link Snap}) and takes the ghost down on resume ({@link Clear}).
 */
public final class StuckWatchPayloads {

    private StuckWatchPayloads() {}

    public record Route(int seq, List<BlockPos> waypoints) {}

    /** One tick of the recording, as the ghost draws it; {@code text} is the panel's lines. */
    public record Frame(long tick, double x, double y, double z, float yaw, double vx, double vy, double vz,
                        int flags, int steerCode, int routeSeq, int wp, long navNext, boolean hasWanted,
                        double wantX, double wantY, double wantZ, float mvWantX, float mvWantZ,
                        float mvGotX, float mvGotZ, long blockedPos, String text) {}

    public record Show(int entityId, String title, String reason, long catchTick,
                       List<Route> routes, List<Frame> frames) implements CustomPacketPayload {

        public static final Type<Show> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "stuckwatch_show"));

        public static final StreamCodec<FriendlyByteBuf, Show> CODEC = new StreamCodec<>() {
            @Override
            public Show decode(FriendlyByteBuf buf) {
                int entityId = buf.readVarInt();
                String title = buf.readUtf();
                String reason = buf.readUtf();
                long catchTick = buf.readLong();
                int routeCount = buf.readVarInt();
                List<Route> routes = new ArrayList<>(routeCount);
                for (int i = 0; i < routeCount; i++) {
                    int seq = buf.readVarInt();
                    int n = buf.readVarInt();
                    List<BlockPos> waypoints = new ArrayList<>(n);
                    for (int j = 0; j < n; j++) waypoints.add(BlockPos.of(buf.readLong()));
                    routes.add(new Route(seq, waypoints));
                }
                int frameCount = buf.readVarInt();
                List<Frame> frames = new ArrayList<>(frameCount);
                for (int i = 0; i < frameCount; i++) {
                    frames.add(new Frame(buf.readLong(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readFloat(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readLong(),
                            buf.readBoolean(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readLong(),
                            buf.readUtf()));
                }
                return new Show(entityId, title, reason, catchTick, routes, frames);
            }

            @Override
            public void encode(FriendlyByteBuf buf, Show show) {
                buf.writeVarInt(show.entityId());
                buf.writeUtf(show.title());
                buf.writeUtf(show.reason());
                buf.writeLong(show.catchTick());
                buf.writeVarInt(show.routes().size());
                for (Route route : show.routes()) {
                    buf.writeVarInt(route.seq());
                    buf.writeVarInt(route.waypoints().size());
                    for (BlockPos pos : route.waypoints()) buf.writeLong(pos.asLong());
                }
                buf.writeVarInt(show.frames().size());
                for (Frame f : show.frames()) {
                    buf.writeLong(f.tick());
                    buf.writeDouble(f.x());
                    buf.writeDouble(f.y());
                    buf.writeDouble(f.z());
                    buf.writeFloat(f.yaw());
                    buf.writeDouble(f.vx());
                    buf.writeDouble(f.vy());
                    buf.writeDouble(f.vz());
                    buf.writeVarInt(f.flags());
                    buf.writeVarInt(f.steerCode());
                    buf.writeVarInt(f.routeSeq());
                    buf.writeVarInt(f.wp());
                    buf.writeLong(f.navNext());
                    buf.writeBoolean(f.hasWanted());
                    buf.writeDouble(f.wantX());
                    buf.writeDouble(f.wantY());
                    buf.writeDouble(f.wantZ());
                    buf.writeFloat(f.mvWantX());
                    buf.writeFloat(f.mvWantZ());
                    buf.writeFloat(f.mvGotX());
                    buf.writeFloat(f.mvGotZ());
                    buf.writeLong(f.blockedPos());
                    buf.writeUtf(f.text());
                }
            }
        };

        /** The ghost of one catch: its frames and the routes they walked. */
        public static Show of(StuckWatch.StuckCatch c) {
            List<Route> routes = new ArrayList<>();
            for (BlackBox.Route r : c.routes) routes.add(new Route(r.seq(), List.copyOf(r.waypoints())));
            List<Frame> frames = new ArrayList<>(c.frames.size());
            for (BlackBox.Frame f : c.frames) {
                frames.add(new Frame(f.tick, f.x, f.y, f.z, f.yaw, f.vx, f.vy, f.vz, f.flags, f.steerCode,
                        f.routeSeq, f.wp, f.navNext, f.hasWanted, f.wantX, f.wantY, f.wantZ,
                        (float) f.mvWantX, (float) f.mvWantZ, (float) f.mvGotX, (float) f.mvGotZ,
                        f.blockedPos, f.panelText()));
            }
            return new Show(c.entityId, "#" + c.id + " " + c.name + " (" + c.profession + ")",
                    c.at.kind().label + ": " + c.at.reason(), c.tick, routes, frames);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Moves the ghost to {@code offset} ticks from the catch (0 = the catch, -40 = two seconds before). */
    public record Cursor(int offset) implements CustomPacketPayload {
        public static final Type<Cursor> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "stuckwatch_cursor"));
        public static final StreamCodec<FriendlyByteBuf, Cursor> CODEC = new StreamCodec<>() {
            @Override
            public Cursor decode(FriendlyByteBuf buf) {
                return new Cursor(buf.readVarInt());
            }

            @Override
            public void encode(FriendlyByteBuf buf, Cursor cursor) {
                buf.writeVarInt(cursor.offset());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Rewind: puts the villager straight at its rewound spot on screen. Minecraft
     * slides a moved mob there over its next few ticks, and a frozen world has
     * none, so without this it stays drawn where it was until the world runs.
     */
    public record Snap(int entityId, double x, double y, double z, float yRot, float bodyRot, float headRot)
            implements CustomPacketPayload {
        public static final Type<Snap> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "stuckwatch_snap"));
        public static final StreamCodec<FriendlyByteBuf, Snap> CODEC = new StreamCodec<>() {
            @Override
            public Snap decode(FriendlyByteBuf buf) {
                return new Snap(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readFloat(), buf.readFloat(), buf.readFloat());
            }

            @Override
            public void encode(FriendlyByteBuf buf, Snap snap) {
                buf.writeVarInt(snap.entityId());
                buf.writeDouble(snap.x());
                buf.writeDouble(snap.y());
                buf.writeDouble(snap.z());
                buf.writeFloat(snap.yRot());
                buf.writeFloat(snap.bodyRot());
                buf.writeFloat(snap.headRot());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Clear() implements CustomPacketPayload {
        public static final Type<Clear> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "stuckwatch_clear"));
        public static final StreamCodec<FriendlyByteBuf, Clear> CODEC = StreamCodec.unit(new Clear());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(Show.TYPE, Show.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> com.alex.test.client.StuckGhostView.show(payload)));
        registrar.playToClient(Cursor.TYPE, Cursor.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> com.alex.test.client.StuckGhostView.cursor(payload)));
        registrar.playToClient(Clear.TYPE, Clear.CODEC,
                (payload, ctx) -> ctx.enqueueWork(com.alex.test.client.StuckGhostView::clear));
        registrar.playToClient(Snap.TYPE, Snap.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> com.alex.test.client.StuckGhostView.snap(payload)));
    }
}
