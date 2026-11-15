package com.alex.test.network;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.ui.HouseRoomMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.HouseBuilding;
import com.alex.test.village.building.TownHallBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import javax.annotation.Nullable;
import java.util.UUID;

/** Messages of the house screen: moving villagers between beds, and the server's answer. */
public final class HouseRoomPayloads {
    private HouseRoomPayloads() {}

    /** Give {@code bed} to this villager. */
    public record Assign(UUID frameId, BlockPos bed, UUID villagerId) implements CustomPacketPayload {
        public static final Type<Assign> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "house_assign_bed"));
        public static final StreamCodec<FriendlyByteBuf, Assign> CODEC = new StreamCodec<>() {
            @Override public Assign decode(FriendlyByteBuf buf) {
                return new Assign(buf.readUUID(), buf.readBlockPos(), buf.readUUID());
            }
            @Override public void encode(FriendlyByteBuf buf, Assign payload) {
                buf.writeUUID(payload.frameId());
                buf.writeBlockPos(payload.bed());
                buf.writeUUID(payload.villagerId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Take this villager's bed in the house away; it picks a free bed at bedtime. */
    public record Unassign(UUID frameId, UUID villagerId) implements CustomPacketPayload {
        public static final Type<Unassign> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "house_unassign_bed"));
        public static final StreamCodec<FriendlyByteBuf, Unassign> CODEC = new StreamCodec<>() {
            @Override public Unassign decode(FriendlyByteBuf buf) {
                return new Unassign(buf.readUUID(), buf.readUUID());
            }
            @Override public void encode(FriendlyByteBuf buf, Unassign payload) {
                buf.writeUUID(payload.frameId());
                buf.writeUUID(payload.villagerId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The house as the server sees it after a change; {@code refusal} is empty when it went through. */
    public record Sync(UUID frameId, HouseRoomMenu.Snapshot snapshot, String refusal)
            implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "house_sync"));
        public static final StreamCodec<FriendlyByteBuf, Sync> CODEC = new StreamCodec<>() {
            @Override public Sync decode(FriendlyByteBuf buf) {
                return new Sync(buf.readUUID(), HouseRoomMenu.readSnapshot(buf), buf.readUtf(256));
            }
            @Override public void encode(FriendlyByteBuf buf, Sync payload) {
                buf.writeUUID(payload.frameId());
                HouseRoomMenu.writeSnapshot(buf, payload.snapshot());
                buf.writeUtf(payload.refusal(), 256);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Assign.TYPE, Assign.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            HouseRoomMenu menu = openMenu(ctx, payload.frameId(), true);
            if (menu == null) return;
            VillageManager village = menu.serverVillage();
            String refusal = "";
            if (!mayManageBeds((ServerPlayer) ctx.player(), village)) {
                refusal = manageRefusal(village);
            } else if (menu.serverBuilding() instanceof TownHallBuilding hall) {
                // The Mayor is not one of the village's villagers: the one the window names is checked instead.
                MayorEntity mayor = HouseRoomMenu.mayorOf((ServerLevel) ctx.player().level(), village);
                refusal = mayor == null || !mayor.getNpcId().equals(payload.villagerId())
                        ? "The Mayor is not nearby, so its bed cannot be changed now."
                        : village.assignHallBedByPlayer(mayor.getNpcId(), hall, payload.bed());
            } else if (village.getModel(payload.villagerId()) == null) {
                refusal = "That villager is no longer in the village.";
            } else if (menu.serverBuilding() instanceof com.alex.test.village.building.MageTowerBuilding tower) {
                refusal = village.assignTowerBedByPlayer(payload.villagerId(), tower, payload.bed());
            } else if (menu.serverHouse() instanceof HouseBuilding house
                    && !village.assignBedByPlayer(payload.villagerId(), house, payload.bed())) {
                refusal = "The house is full: " + house.getCapacity() + " of " + house.getCapacity()
                        + " beds are taken. Move one of its villagers instead.";
            }
            sync((ServerPlayer) ctx.player(), menu, refusal);
        }));
        registrar.playToServer(Unassign.TYPE, Unassign.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            HouseRoomMenu menu = openMenu(ctx, payload.frameId(), false);
            if (menu == null) return;
            VillageManager village = menu.serverVillage();
            if (!mayManageBeds((ServerPlayer) ctx.player(), village)) {
                sync((ServerPlayer) ctx.player(), menu, manageRefusal(village));
                return;
            }
            var claim = village.bedClaimOf(payload.villagerId());
            if (claim != null && claim.buildingId().equals(payload.frameId())) {
                village.releaseBedFor(payload.villagerId());
            }
            sync((ServerPlayer) ctx.player(), menu, "");
        }));
        registrar.playToClient(Sync.TYPE, Sync.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            // Runs on the client; the open menu is common code, so no client class is needed.
            if (ctx.player() != null && ctx.player().containerMenu instanceof HouseRoomMenu menu
                    && menu.frameId().equals(payload.frameId())) {
                menu.applySnapshot(payload.snapshot(), payload.refusal());
            }
        }));
    }

    /** Village claims round 7a: who sleeps where is the Owner's and Officers' to change. */
    private static boolean mayManageBeds(ServerPlayer player, VillageManager village) {
        return com.alex.test.village.claims.ClaimLock.mayIn(player, village,
                com.alex.test.village.claims.ClaimLock.Act.MANAGE);
    }

    private static String manageRefusal(VillageManager village) {
        return com.alex.test.Tr.c("chat.dynamicvillages.claim.beds_locked",
                "%s has not let you change beds here.", village.claim().ownerName()).getString();
    }

    private static void sync(ServerPlayer player, HouseRoomMenu menu, String refusal) {
        HouseRoomMenu.Snapshot snapshot = HouseRoomMenu.snapshot((ServerLevel) player.level(),
                menu.serverVillage(), menu.serverBuilding());
        menu.applySnapshot(snapshot, refusal);
        PacketDistributor.sendToPlayer(player, new Sync(menu.frameId(), snapshot, refusal));
    }

    /**
     * The house screen a payload edits, when the sender really has that house open. {@code hallGive}:
     * the Town Hall takes this change (giving the Mayor a bed); it takes no other.
     */
    @Nullable
    private static HouseRoomMenu openMenu(IPayloadContext ctx, UUID frameId, boolean hallGive) {
        if (!(ctx.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof HouseRoomMenu menu)
                || !menu.frameId().equals(frameId)
                || !menu.stillValid(player)
                || menu.serverVillage() == null || menu.serverBuilding() == null
                // A Barracks is only inspected: its beds are handed out by its own rules.
                || menu.isBarracks()
                // The Town Hall's beds are the Mayor's: a player may only give it one (10-24).
                || menu.isTownHall() && !hallGive) return null;
        return menu;
    }
}
