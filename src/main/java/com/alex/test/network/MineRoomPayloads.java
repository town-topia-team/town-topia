package com.alex.test.network;

import com.alex.test.DynamicVillages;
import com.alex.test.ui.MineRoomMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.villagers.miner.MineSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import javax.annotation.Nullable;
import java.util.UUID;

/** Messages of the Mine screen: the mine's own settings, and the server's answer. */
public final class MineRoomPayloads {
    private MineRoomPayloads() {}

    /** Give the mine these settings: side and main tunnel lengths, paused or not. */
    public record SetSettings(UUID frameId, MineSettings settings) implements CustomPacketPayload {
        public static final Type<SetSettings> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mine_set_settings"));
        public static final StreamCodec<FriendlyByteBuf, SetSettings> CODEC = new StreamCodec<>() {
            @Override public SetSettings decode(FriendlyByteBuf buf) {
                return new SetSettings(buf.readUUID(), MineRoomMenu.readSettings(buf));
            }
            @Override public void encode(FriendlyByteBuf buf, SetSettings payload) {
                buf.writeUUID(payload.frameId());
                MineRoomMenu.writeSettings(buf, payload.settings());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The mine as the server sees it after a change; {@code refusal} is empty when it went through. */
    public record Sync(UUID frameId, MineRoomMenu.Snapshot snapshot, String refusal)
            implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mine_sync"));
        public static final StreamCodec<FriendlyByteBuf, Sync> CODEC = new StreamCodec<>() {
            @Override public Sync decode(FriendlyByteBuf buf) {
                return new Sync(buf.readUUID(), MineRoomMenu.readSnapshot(buf), buf.readUtf(256));
            }
            @Override public void encode(FriendlyByteBuf buf, Sync payload) {
                buf.writeUUID(payload.frameId());
                MineRoomMenu.writeSnapshot(buf, payload.snapshot());
                buf.writeUtf(payload.refusal(), 256);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(SetSettings.TYPE, SetSettings.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            // A late message for a mine that is gone, or a screen already
            // closed, finds no open menu and is dropped.
            MineRoomMenu menu = openMenu(ctx, payload.frameId());
            if (menu == null) return;
            VillageManager village = menu.serverVillage();
            // Village claims round 7b: only the owner and their Officers change a mine.
            if (!com.alex.test.village.claims.ClaimLock.mayIn((ServerPlayer) ctx.player(), village,
                    com.alex.test.village.claims.ClaimLock.Act.MANAGE)) {
                sync((ServerPlayer) ctx.player(), menu, com.alex.test.village.claims.ClaimLock.manageRefusal(village));
                return;
            }
            // Two players on one mine: each change is applied as it comes,
            // so the last one wins; the other screen re-syncs within a second.
            if (village.mineSettings().set(payload.frameId(), payload.settings())) {
                // The layout revision bump is what makes miners and druids replan.
                village.onMineLayoutChanged();
                village.markPersistentDataDirty();
            }
            sync((ServerPlayer) ctx.player(), menu, "");
        }));
        registrar.playToClient(Sync.TYPE, Sync.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            // Runs on the client; the open menu is common code, so no client class is needed.
            if (ctx.player() != null && ctx.player().containerMenu instanceof MineRoomMenu menu
                    && menu.frameId().equals(payload.frameId())) {
                menu.applySnapshot(payload.snapshot(), payload.refusal());
            }
        }));
    }

    private static void sync(ServerPlayer player, MineRoomMenu menu, String refusal) {
        MineRoomMenu.Snapshot snapshot = MineRoomMenu.snapshot(player.serverLevel(),
                menu.serverVillage(), menu.serverMine());
        menu.applySnapshot(snapshot, refusal);
        PacketDistributor.sendToPlayer(player, new Sync(menu.frameId(), snapshot, refusal));
    }

    /** The Mine screen a payload edits, when the sender really has that mine open. */
    @Nullable
    private static MineRoomMenu openMenu(IPayloadContext ctx, UUID frameId) {
        if (!(ctx.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof MineRoomMenu menu)
                || !menu.frameId().equals(frameId)
                || !menu.stillValid(player)
                || menu.serverVillage() == null || menu.serverMine() == null) return null;
        return menu;
    }
}
