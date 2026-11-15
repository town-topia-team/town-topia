package com.alex.test;

import com.alex.test.block.PianoLock;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Creative-mode tabs provided by the mod. */
public final class ModCreativeModeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, DynamicVillages.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> DYNAMIC_VILLAGES =
            CREATIVE_MODE_TABS.register("dynamic_villages", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.dynamicvillages"))
                    .icon(() -> new ItemStack(ModItems.TOWN_HALL.get()))
                    .displayItems((parameters, output) -> ModItems.ITEMS.getEntries().stream()
                            // The Builder's plan only comes from the Builder's desk.
                            .filter(item -> item != ModItems.BUILDER_PLAN)
                            // The Smith's Hammer is only a prop in the Blacksmith's hand.
                            .filter(item -> item != ModItems.SMITH_HAMMER)
                            // And its tongs and the hot bar, in its other hand.
                            .filter(item -> item != ModItems.SMITH_TONGS && item != ModItems.SMITH_BAR)
                            // So are the Cook's spoon and knife, in the Cook's.
                            .filter(item -> item != ModItems.COOK_SPOON && item != ModItems.COOK_KNIFE)
                            // ZalithYT's Piano: in its owners' tabs only, and so in their search only.
                            .filter(item -> PianoLock.isClientOwner() || !PianoLock.isPianoItem(new ItemStack(item.get())))
                            .forEach(item -> output.accept(item.get())))
                    .build());

    private ModCreativeModeTabs() {}

    public static void register(IEventBus modEventBus) {
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
