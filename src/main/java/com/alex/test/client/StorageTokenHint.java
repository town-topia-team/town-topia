package com.alex.test.client;

import com.alex.test.Tr;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Crosshair hint for a registered token that opens a management screen: the storage room,
 * the bed screen of houses, barracks, the Mage Tower and the Town Hall, and the Mine screen.
 * The token's physical UI entry point.
 */
public final class StorageTokenHint {
    private StorageTokenHint() {}

    /** What right-clicking this token manages, or null when it opens nothing. */
    @javax.annotation.Nullable
    private static String managed(@javax.annotation.Nullable ItemFrameTokenRegistry.TokenType type) {
        if (type == null) return null;
        return switch (type) {
            case STORAGE, TWO_BED_HOUSE, FOUR_BED_HOUSE, SIX_BED_HOUSE, BARRACKS, MAGE_TOWER, MINE, TOWN_HALL, BUTCHERY, RANCH ->
                    com.alex.test.village.building.BuildingRequirements.displayName(type);
            default -> null;
        };
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null
                || !(minecraft.hitResult instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof ItemFrame frame)
                || minecraft.player.distanceToSqr(frame) >= 64.0
                || !Boolean.TRUE.equals(frame.getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE))) {
            return;
        }
        String managed = managed(ItemFrameTokenRegistry.tokenType(frame.getItem()));
        if (managed == null) return;
        // Same look as the storage screen: dark bordered card, the token's icon, green key hint.
        var graphics = event.getGuiGraphics();
        var font = minecraft.font;
        String key = Tr.s("hud.dynamicvillages.manage_hint.key", "Right click");
        String rest = Tr.s("hud.dynamicvillages.manage_hint", " to manage %s", managed);
        int width = 20 + font.width(key) + font.width(rest) + 12;
        int x = (graphics.guiWidth() - width) / 2;
        // Above the crosshair.
        int y = graphics.guiHeight() / 2 - 34;
        graphics.fill(x - 1, y - 1, x + width + 1, y + 21, 0xFF080808);
        graphics.fill(x, y, x + width, y + 20, 0xFF454545);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 19, 0xF0202020);
        graphics.fill(x + 1, y + 18, x + width - 1, y + 19, 0xFF6BAF72);
        // The bare item: the registration glint belongs on the frame, not in the hint.
        graphics.renderFakeItem(new net.minecraft.world.item.ItemStack(frame.getItem().getItem()), x + 4, y + 2);
        graphics.drawString(font, key, x + 24, y + 6, 0xFF6BAF72, false);
        graphics.drawString(font, rest, x + 24 + font.width(key), y + 6, 0xFFF3F3F3, false);
    }
}
