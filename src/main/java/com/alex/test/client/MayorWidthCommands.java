package com.alex.test.client;

import com.alex.test.ui.screen.mayor.MayorStatsScreen;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.Locale;

/**
 * {@code /mayorwidth}: try the Mayor window at other widths without changing the game window. Not saved: copy a
 * width that looks right into {@code MayorLook.NORMAL_WIDTH} / {@code THEME_WIDTH}.
 *
 * <pre>
 *   /mayorwidth                  both widths, and what they are on this screen
 *   /mayorwidth &lt;356-460&gt;        every tab (usually 380)
 *   /mayorwidth theme &lt;356-460&gt;  the Theme tab and a set's page (usually 420)
 *   /mayorwidth reset            back to 380 and 420
 * </pre>
 *
 * Never wider than the screen allows; odd numbers round down. Typed in chat, so the Mayor is closed meanwhile: the
 * width shows the next time it opens.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class MayorWidthCommands {

    private MayorWidthCommands() {}

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mayorwidth")
                .executes(ctx -> report(ctx.getSource()));
        root.then(Commands.literal("reset").executes(ctx -> {
            MayorStatsScreen.setTestWidth(0);
            MayorStatsScreen.setTestThemeWidth(0);
            return report(ctx.getSource());
        }));
        // The window's narrowest (MayorLook.MIN_WIDTH) and about as wide as a 1080p screen allows.
        root.then(Commands.literal("theme").then(Commands.argument("width", IntegerArgumentType.integer(356, 460))
                .executes(ctx -> {
                    MayorStatsScreen.setTestThemeWidth(IntegerArgumentType.getInteger(ctx, "width"));
                    return report(ctx.getSource());
                })));
        root.then(Commands.argument("width", IntegerArgumentType.integer(356, 460)).executes(ctx -> {
            MayorStatsScreen.setTestWidth(IntegerArgumentType.getInteger(ctx, "width"));
            return report(ctx.getSource());
        }));
        event.getDispatcher().register(root);
    }

    private static int report(CommandSourceStack source) {
        int screen = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        String text = String.format(Locale.ROOT,
                "Mayor window: %d wide, Theme tab %d (on this screen %d and %d). Shows the next time the Mayor opens.",
                MayorStatsScreen.wantedWidth(false), MayorStatsScreen.wantedWidth(true),
                MayorStatsScreen.windowWidthFor(screen, false), MayorStatsScreen.windowWidthFor(screen, true));
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }
}
