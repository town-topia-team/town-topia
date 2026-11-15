package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.ModItems;
import com.alex.test.ui.screen.GuideBookScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Opens the guide when the local player right clicks the guide book. The pages,
 * the wiki links and their look live in {@link GuideBookScreen}.
 */
@EventBusSubscriber(modid = DynamicVillages.MODID, value = Dist.CLIENT)
public final class GuideBookClient {
    private GuideBookClient() {}

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        // The integrated server fires this too; only the client thread opens screens.
        if (!event.getLevel().isClientSide()) return;
        if (!event.getItemStack().is(ModItems.GUIDE_BOOK.get())) return;
        Minecraft.getInstance().setScreen(new GuideBookScreen());
    }
}
