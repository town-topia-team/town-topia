package com.alex.test.network;

import com.alex.test.DynamicVillages;
import com.alex.test.ui.ButcheryRoomMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The Butchery screen's packets: "Try again now" on a paused Butchery, picking where the butcher
 * stands, and the server's fresh snapshot.
 */
public final class ButcheryRoomPayloads {
    private ButcheryRoomPayloads() {}

    /** The player fixed the room: culls here again before the morning (round 3). */
    public record TryAgain(UUID frameId) implements CustomPacketPayload {
        public static final Type<TryAgain> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "butchery_try_again"));
        public static final StreamCodec<FriendlyByteBuf, TryAgain> CODEC = new StreamCodec<>() {
            @Override public TryAgain decode(FriendlyByteBuf buf) {
                return new TryAgain(buf.readUUID());
            }
            @Override public void encode(FriendlyByteBuf buf, TryAgain payload) {
                buf.writeUUID(payload.frameId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The player picked where the butcher stands; {@code tile} null = automatic again (round 8). */
    public record ChooseSpot(UUID frameId, @Nullable BlockPos tile) implements CustomPacketPayload {
        public static final Type<ChooseSpot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "butchery_choose_spot"));
        public static final StreamCodec<FriendlyByteBuf, ChooseSpot> CODEC = new StreamCodec<>() {
            @Override public ChooseSpot decode(FriendlyByteBuf buf) {
                return new ChooseSpot(buf.readUUID(), buf.readBoolean() ? buf.readBlockPos() : null);
            }
            @Override public void encode(FriendlyByteBuf buf, ChooseSpot payload) {
                buf.writeUUID(payload.frameId());
                buf.writeBoolean(payload.tile() != null);
                if (payload.tile() != null) buf.writeBlockPos(payload.tile());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Sync(UUID frameId, ButcheryRoomMenu.Snapshot snapshot) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "butchery_sync"));
        public static final StreamCodec<FriendlyByteBuf, Sync> CODEC = new StreamCodec<>() {
            @Override public Sync decode(FriendlyByteBuf buf) {
                return new Sync(buf.readUUID(), ButcheryRoomMenu.readSnapshot(buf));
            }
            @Override public void encode(FriendlyByteBuf buf, Sync payload) {
                buf.writeUUID(payload.frameId());
                ButcheryRoomMenu.writeSnapshot(buf, payload.snapshot());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(TryAgain.TYPE, TryAgain.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            // Only from the sender's own open window on that Butchery.
            if (!(ctx.player() instanceof ServerPlayer player)
                    || !(player.containerMenu instanceof ButcheryRoomMenu menu)
                    || !menu.frameId().equals(payload.frameId())
                    || !menu.stillValid(player) || menu.serverButchery() == null) return;
            // Village claims round 7b: only the owner and their Officers change a Butchery.
            if (!menu.serverMayChange(player)) return;
            menu.serverButchery().clearPause(player.serverLevel().getGameTime());
            DynamicVillages.LOGGER.info("[Butchery] Try again now at {}: culls there again",
                    menu.doorPos().toShortString());
            menu.syncNow(player);
        }));
        registrar.playToServer(ChooseSpot.TYPE, ChooseSpot.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)
                    || !(player.containerMenu instanceof ButcheryRoomMenu menu)
                    || !menu.frameId().equals(payload.frameId()) || !menu.stillValid(player)) return;
            if (!menu.serverMayChange(player)) return;
            menu.chooseSpot(player, payload.tile());
        }));
        registrar.playToClient(Sync.TYPE, Sync.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            // Runs on the client; the open menu is common code, so no client class is needed.
            if (ctx.player() != null && ctx.player().containerMenu instanceof ButcheryRoomMenu menu
                    && menu.frameId().equals(payload.frameId())) {
                menu.applySnapshot(payload.snapshot());
            }
        }));
    }
}
