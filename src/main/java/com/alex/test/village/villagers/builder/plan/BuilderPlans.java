package com.alex.test.village.villagers.builder.plan;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.network.BuilderPlanPayloads;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the Builder's plan item: one plan per player at most, never saved.
 *
 * <p>The item carries its plan's id. A stack whose id is not the holder's active plan
 * (a plan put away, an old copy, anything after a restart) disappears the moment it
 * ticks in a player's inventory, so a plan left in a chest goes as soon as someone
 * carries it. The plan is put away when it is confirmed or cancelled, thrown or dropped
 * on death, on logout, in another world, after {@link #UNUSED_TICKS} not held, or when
 * the Builder is gone.
 */
public final class BuilderPlans {
    /** Ten minutes not held in a hand. */
    public static final int UNUSED_TICKS = 20 * 60 * 10;
    private static final String TAG_ID = "PlanId";
    private static final String TAG_NAME = "Blueprint";
    private static final String TAG_COPY = "Copy";

    /** A plan out in the world: whose Builder, which blueprint, where it works. */
    public static final class Plan {
        final UUID id;
        public final int villagerId;
        public final UUID villagerUuid;
        /** The Builder's village, so an order works while the Builder is out of sight. */
        @Nullable public final UUID villageId;
        final ResourceKey<Level> dimension;
        final String blueprintName;
        /** A plan for copying a building, not for placing a blueprint. */
        public final boolean copy;
        long lastHeld;

        Plan(UUID id, int villagerId, UUID villagerUuid, @Nullable UUID villageId, ResourceKey<Level> dimension,
             String blueprintName, boolean copy, long now) {
            this.id = id;
            this.villageId = villageId;
            this.copy = copy;
            this.villagerId = villagerId;
            this.villagerUuid = villagerUuid;
            this.dimension = dimension;
            this.blueprintName = blueprintName;
            this.lastHeld = now;
        }
    }

    private static final Map<UUID, Plan> ACTIVE = new HashMap<>();

    private BuilderPlans() {}

    @Nullable
    public static Plan active(ServerPlayer player) {
        return ACTIVE.get(player.getUUID());
    }

    /**
     * Hands this player a plan for this Builder's blueprint, in the hand they hold, and
     * puts away any plan they had. Returns the reason when there is no room for it.
     */
    @Nullable
    public static String give(ServerPlayer player, NpcEntity villager, String blueprintName, boolean copy) {
        // No "ended" to the client here: it already shows the new plan.
        if (ACTIVE.remove(player.getUUID()) != null) removeItems(player);
        Inventory inventory = player.getInventory();
        int slot = -1;
        if (inventory.getItem(inventory.selected).isEmpty()) slot = inventory.selected;
        for (int i = 0; slot < 0 && i < Inventory.getSelectionSize(); i++) {
            if (inventory.getItem(i).isEmpty()) slot = i;
        }
        if (slot < 0) {
            // Every hotbar slot is full: what is in the hand moves into the bag.
            int free = -1;
            for (int i = Inventory.getSelectionSize(); free < 0 && i < inventory.items.size(); i++) {
                if (inventory.items.get(i).isEmpty()) free = i;
            }
            if (free < 0) return "Make room in your inventory for the Builder's plan.";
            inventory.setItem(free, inventory.getItem(inventory.selected));
            inventory.setItem(inventory.selected, ItemStack.EMPTY);
            slot = inventory.selected;
        }
        String name = blueprintName.length() > 64 ? blueprintName.substring(0, 64) : blueprintName;
        var village = com.alex.test.village.villagers.tradesman.settings.TradesmanSalePolicy.villageOf(villager);
        Plan plan = new Plan(UUID.randomUUID(), villager.getId(), villager.getUUID(),
                village == null ? null : village.getVillageId(), player.level().dimension(), name,
                copy, player.serverLevel().getGameTime());
        ACTIVE.put(player.getUUID(), plan);
        ItemStack stack = new ItemStack(ModItems.BUILDER_PLAN.get());
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, plan.id);
        tag.putString(TAG_NAME, name);
        if (copy) tag.putBoolean(TAG_COPY, true);
        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
        inventory.setItem(slot, stack);
        inventory.selected = slot;
        player.connection.send(new ClientboundSetCarriedItemPacket(slot));
        return null;
    }

    /**
     * Puts the plan away: its items leave the inventory, the client stops drawing it, and
     * {@code message} (if any) shows over the hotbar.
     */
    public static void end(ServerPlayer player, @Nullable String message) {
        ACTIVE.remove(player.getUUID());
        removeItems(player);
        PacketDistributor.sendToPlayer(player, new BuilderPlanPayloads.PlanEnded());
        if (message != null) player.displayClientMessage(Component.literal(message), true);
    }

    private static void removeItems(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (isPlan(inventory.getItem(i))) inventory.setItem(i, ItemStack.EMPTY);
        }
        if (isPlan(player.containerMenu.getCarried())) player.containerMenu.setCarried(ItemStack.EMPTY);
    }

    public static boolean isPlan(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.BUILDER_PLAN.get());
    }

    @Nullable
    private static UUID planId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        return tag.hasUUID(TAG_ID) ? tag.getUUID(TAG_ID) : null;
    }

    /** The blueprint's name on a plan stack, for its tooltip; null when it has none. */
    @Nullable
    public static String blueprintName(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        return tag.contains(TAG_NAME) ? tag.getString(TAG_NAME) : null;
    }

    /** A plan for copying a building (its tooltip says so). */
    public static boolean isCopyPlan(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(TAG_COPY);
    }

    /** Every tick a plan spends in a player's inventory. */
    static void tickStack(ItemStack stack, ServerPlayer player, boolean held) {
        Plan plan = ACTIVE.get(player.getUUID());
        UUID id = planId(stack);
        if (plan == null || id == null || !plan.id.equals(id)) {
            stack.setCount(0);
            return;
        }
        if (player.level().dimension() != plan.dimension) {
            end(player, "Builder's plan put away: it only works in the Builder's world.");
            return;
        }
        long now = player.serverLevel().getGameTime();
        if (held || now < plan.lastHeld) plan.lastHeld = now;
        if (now - plan.lastHeld > UNUSED_TICKS) {
            end(player, "Builder's plan put away: not used for 10 minutes.");
            return;
        }
        if (now % 20 == 0) {
            // Only a Builder that is loaded can be seen to be gone; an unloaded one is left be.
            Entity found = player.serverLevel().getEntity(plan.villagerUuid);
            if (found != null && (!found.isAlive() || !(found instanceof NpcEntity npc)
                    || npc.getProfession() != Profession.BUILDER)) {
                end(player, "Builder's plan put away: the Builder is gone.");
            }
        }
    }

    /** A plan item turned up on the ground (thrown, dropped on death): its plan ends. */
    static void dropped(ItemStack stack) {
        UUID id = planId(stack);
        if (id == null) return;
        UUID owner = null;
        for (Map.Entry<UUID, Plan> entry : ACTIVE.entrySet()) {
            if (entry.getValue().id.equals(id)) owner = entry.getKey();
        }
        if (owner == null) return;
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(owner);
        if (player != null) {
            end(player, "Builder's plan put away.");
        } else {
            ACTIVE.remove(owner);
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && ACTIVE.remove(player.getUUID()) != null) {
            removeItems(player);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && ACTIVE.containsKey(player.getUUID())) {
            end(player, "Builder's plan put away: it only works in the Builder's world.");
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ACTIVE.clear();
    }
}
