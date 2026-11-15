package com.alex.test.client;

import com.alex.test.Tr;
import com.alex.test.network.StorageRoomPayloads;
import com.alex.test.village.building.StorageContainers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Adds the management button only after the server confirms this container belongs to storage. */
public final class StorageChestButton {
    private static Screen awaitingScreen;
    private static BlockPos awaitingPos;
    private static Button button;
    private static BlockPos recentlyClicked;
    private static long clickTime;

    private StorageChestButton() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ClientLevel level)) return;
        BlockPos pos = event.getPos();
        if (!StorageContainers.isStorageBlock(level.getBlockState(pos))) return;
        recentlyClicked = pos.immutable();
        clickTime = level.getGameTime();
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen previousScreen = awaitingScreen;
        BlockPos previousPos = awaitingPos;
        awaitingScreen = null;
        awaitingPos = null;
        button = null;
        Screen screen = event.getScreen();
        Minecraft minecraft = Minecraft.getInstance();
        if (!(screen instanceof AbstractContainerScreen<?> container) || minecraft.level == null
                || screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen) return;
        // Modded chests (Iron Chests) have their own screen classes. Only the vanilla chest
        // screen may fall back to the looked-at block: another mod's screen could be a
        // backpack opened while facing a chest. A resize re-inits the same screen.
        boolean vanillaScreen = screen instanceof ContainerScreen;
        long sinceClick = minecraft.level.getGameTime() - clickTime;
        BlockPos pos;
        if (screen == previousScreen && previousPos != null) {
            pos = previousPos;
        } else if (recentlyClicked != null && sinceClick >= 0 && sinceClick <= (vanillaScreen ? 5 : 20)) {
            pos = recentlyClicked;
        } else {
            pos = vanillaScreen && minecraft.hitResult instanceof BlockHitResult hit ? hit.getBlockPos() : null;
        }
        if (pos == null) return;
        if (!StorageContainers.isStorageBlock(minecraft.level.getBlockState(pos))) return;
        recentlyClicked = null;

        awaitingScreen = screen;
        awaitingPos = pos.immutable();
        // Sits in the title row, right-aligned opposite the chest's name, and short enough
        // to stay clear of the first slot row.
        Component label = Tr.c("screen.dynamicvillages.storage.manage_chest", "Manage Chest");
        int width = minecraft.font.width(label) + 10;
        // Sophisticated Storage keeps its sort buttons in the title row and its upgrade and
        // settings tabs down the sides, so its button sits just above the window instead.
        boolean sophisticated = screen.getClass().getName().startsWith("net.p3pp3rf1y.");
        int x = container.getGuiLeft() + container.getXSize() - (sophisticated ? 0 : 7) - width;
        int y = sophisticated ? Math.max(0, container.getGuiTop() - 15) : container.getGuiTop() + 3;
        button = Button.builder(label, ignored -> {
            if (minecraft.screen == screen) {
                PacketDistributor.sendToServer(new StorageRoomPayloads.OpenContainer(pos));
            }
        }).bounds(x, y, width, 13)
                .tooltip(Tooltip.create(Tr.c("screen.dynamicvillages.storage.manage_chest.desc",
                        "Manage the storage room this container belongs to")))
                .build();
        button.visible = false;
        event.addListener(button);
        PacketDistributor.sendToServer(new StorageRoomPayloads.Probe(pos));
    }

    /** Whether {@code screen} is a container the server confirmed belongs to a storage room. */
    public static boolean isStorageRoomScreen(Screen screen) {
        return screen != null && screen == awaitingScreen && button != null && button.visible;
    }

    public static void available(BlockPos pos) {
        if (button != null && pos.equals(awaitingPos)
                && Minecraft.getInstance().screen == awaitingScreen) {
            button.visible = true;
        }
    }
}
