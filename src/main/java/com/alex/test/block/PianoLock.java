package com.alex.test.block;

import com.alex.test.Tr;
import com.alex.test.network.PianoOwnerPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The lock on ZalithYT's Piano and bench, every wood (plan rounds 3-5): only a {@link PianoOwner} places, breaks,
 * sits on, middle click copies or crafts them (CraftingMenuPianoMixin), and only an owner keeps the item. The blocks
 * themselves keep explosions, the Wither and the dragon off (ModBlocks, {@code canEntityDestroy}, the immune tags);
 * the Builder skips them (BuildingCopier, BuildSite, BlueprintMaterials). The item is in owners' creative tabs only
 * (ModCreativeModeTabs) and tagged {@code c:hidden_from_recipe_viewers} for JEI, EMI and REI; owners get the
 * recipes in their recipe book at login.
 *
 * <p>The server decides. Each game hears about its own player only ({@link PianoOwnerPayload}, at login), so a
 * stranger's game never shows a piano for a moment before the server says no.
 */
public final class PianoLock {
    private PianoLock() {}

    /** What the server last said about this game's own player; false until it says. */
    private static volatile boolean clientOwner;

    public static boolean isLocked(BlockState state) {
        return isPianoBlock(state.getBlock());
    }

    public static boolean isPianoItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && isPianoBlock(blockItem.getBlock());
    }

    private static boolean isPianoBlock(Block block) {
        return block instanceof ZalithPianoBlock || block instanceof ZalithPianoBenchBlock;
    }

    /**
     * On the server the real answer; in a game, what the server said about that game's own player (the only
     * player a game asks about: the one placing, clicking or breaking). Machines and dispensers (no player) never are.
     */
    public static boolean isOwner(@Nullable Player player) {
        if (player == null) return false;
        if (player.level().isClientSide()) return clientOwner;
        return player instanceof ServerPlayer serverPlayer
                && PianoOwner.matches(serverPlayer.getUUID(), serverPlayer.getGameProfile().getName(),
                        accountsChecked(serverPlayer));
    }

    /** Normal servers and singleplayer check accounts; offline mode servers do not. */
    public static boolean accountsChecked(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null && server.usesAuthentication();
    }

    public static void sync(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new PianoOwnerPayload(isOwner(player)));
    }

    /**
     * Whether this game's own player is an owner: the creative tab asks. It arrives at login, before the creative
     * inventory first opens in a world (that is when the tab fills, see CreativeModeInventoryScreen).
     */
    public static boolean isClientOwner() {
        return clientOwner;
    }

    /** In a game, from {@link PianoOwnerPayload}. */
    public static void setClientOwner(boolean owner) {
        clientOwner = owner;
    }

    // Said by the server only: a refused click still reaches it, so the game saying it too would show it twice.

    static void refusePlace(@Nullable Player player) {
        tell(player, Tr.c("chat.dynamicvillages.piano.no_place", "Only the piano's owners can place this."));
    }

    static void refuseSit(Player player) {
        tell(player, Tr.c("chat.dynamicvillages.piano.no_sit", "Only the piano's owners can sit here."));
    }

    private static void refuseBreak(Player player) {
        tell(player, Tr.c("chat.dynamicvillages.piano.no_break", "Only the piano's owners can break this."));
    }

    private static void tell(@Nullable Player player, Component message) {
        if (player != null && !player.level().isClientSide()) player.displayClientMessage(message, true);
    }

    /** Breaking, refused on both sides: no cracks for a stranger, and in creative no flicker. */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!isLocked(event.getLevel().getBlockState(event.getPos())) || isOwner(event.getEntity())) return;
        event.setCanceled(true);
        // In survival a held click sends START again every tick; the action bar just stays up.
        if (event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START) refuseBreak(event.getEntity());
    }

    /** The server's last word on any player's break. */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (isLocked(event.getState()) && !isOwner(event.getPlayer())) event.setCanceled(true);
    }

    /** A piano item on the ground stays there for anyone else (it despawns like any item). */
    @SubscribeEvent
    public static void onPickup(ItemEntityPickupEvent.Pre event) {
        if (isPianoItem(event.getItemEntity().getItem()) && !isOwner(event.getPlayer())) {
            event.setCanPickup(TriState.FALSE);
        }
    }

    /**
     * One that reaches anyone else (a /give, a chest an owner filled, a Crafter) is gone the same tick, from the
     * inventory and from the mouse cursor. Owners take another from the creative tab or craft one.
     */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || isOwner(player)) return;
        boolean removed = false;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isPianoItem(inventory.getItem(slot))) {
                inventory.setItem(slot, ItemStack.EMPTY);
                removed = true;
            }
        }
        if (isPianoItem(player.containerMenu.getCarried())) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            removed = true;
        }
        if (removed) {
            tell(player, Tr.c("chat.dynamicvillages.piano.no_keep", "Only the piano's owners can keep this."));
        }
    }

    /** Owners also find every piano recipe in their recipe book (only new ones show the unlocked toast). */
    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        sync(player);
        if (isOwner(player)) player.awardRecipes(pianoRecipes(player));
    }

    private static List<RecipeHolder<?>> pianoRecipes(ServerPlayer player) {
        List<RecipeHolder<?>> recipes = new ArrayList<>();
        for (RecipeHolder<?> holder : player.server.getRecipeManager().getRecipes()) {
            if (isPianoItem(holder.value().getResultItem(player.registryAccess()))) recipes.add(holder);
        }
        return recipes;
    }
}
