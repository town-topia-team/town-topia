package com.alex.test.client;

import com.alex.test.ClientConfig;
import com.alex.test.Tr;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * One tip about the mod in chat a few seconds after joining a world
 * (Mods > TownTopia > Config, "showTips", turns it off). Most of what
 * testers reported as "broken" in the first weeks was a rule nobody had told
 * them: the Merchant only buys what the village made, a filter on the only
 * chest stops a job, the bell is an alarm. Only this player sees it.
 */
public final class JoinTips {
    private JoinTips() {}

    /** Long enough after the join for the world to be drawn and the join messages to be read. */
    private static final int DELAY_TICKS = 160;

    private static final List<Supplier<MutableComponent>> TIPS = List.of(
            () -> Tr.c("tip.dynamicvillages.green_names",
                    "Items with a green name were made by your village. The Merchant only buys those, and the Cook only cooks those."),
            () -> Tr.c("tip.dynamicvillages.bell",
                    "Ring a bell inside your village to sound the alarm: guards come running, everyone else goes indoors, and every hostile mob inside the border glows through walls."),
            () -> Tr.c("tip.dynamicvillages.campfire",
                    "Light a campfire in your village. Villagers with free time gather round it and cheer up."),
            () -> Tr.c("tip.dynamicvillages.professions",
                    "A villager remembers up to four jobs. Open its window to switch back to an old one without a new token."),
            () -> Tr.c("tip.dynamicvillages.filters",
                    "A chest filter that leaves no room for a job's goods stops that job. If a Rancher, Farmer or Cook stands idle, check the storage room's filters first."),
            () -> Tr.c("tip.dynamicvillages.library",
                    "Work alone only teaches a villager so much. Past 50 Intelligence it takes books: build a Library, or a School for the children."),
            () -> Tr.c("tip.dynamicvillages.away",
                    "Your village keeps working while you are away from it: fields are harvested, cooks cook and villagers eat. Leave food in storage before a long trip."),
            () -> Tr.c("tip.dynamicvillages.stock",
                    "The Stock tab of the storage room lets you order dishes and tools: \"always keep 16 bread\" and the Cook sees to it."),
            () -> Tr.c("tip.dynamicvillages.tradesman",
                    "Hire a Tradesman early. He sells the village's spare goods to the Merchant even when you are not there."),
            () -> Tr.c("tip.dynamicvillages.ceiling",
                    "Villagers feel crowded under a ceiling two blocks high. Three blocks of headroom counts every floor tile in full."),
            () -> Tr.c("tip.dynamicvillages.guide",
                    "Lost? The guide book explains every building and job, and the wiki at town-topia.com/wiki has more."),
            () -> Tr.c("tip.dynamicvillages.theme",
                    "The Mayor's Theme tab changes how your villagers dress: Modern, Rustic or Victorian, or your own mix."));

    private static final Random RANDOM = new Random();
    private static int countdown = -1;
    private static int lastTip = -1;

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        countdown = DELAY_TICKS;
    }

    /**
     * Fast Item Frames turns item frames into blocks. Tokens are read from
     * item frame entities, so with it a token hung in a frame registers
     * nothing (reported on CurseForge 11-13). Said once per join, whatever
     * the tip setting: a village that silently never forms is worse than a line of chat.
     */
    private static boolean frameModLoaded() {
        return net.neoforged.fml.ModList.get().isLoaded("fastitemframes");
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        countdown = -1;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (countdown < 0 || --countdown >= 0) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        if (frameModLoaded()) {
            minecraft.player.displayClientMessage(Tr.c("tip.dynamicvillages.fast_item_frames",
                    "[TownTopia] The mod Fast Item Frames turns item frames into blocks. TownTopia reads its tokens from real item frames, so tokens may not register while it is installed.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        if (!ClientConfig.showTips()) return;
        int pick = RANDOM.nextInt(TIPS.size());
        if (pick == lastTip) pick = (pick + 1) % TIPS.size();
        lastTip = pick;
        minecraft.player.displayClientMessage(Component.empty()
                .append(Tr.c("tip.dynamicvillages.prefix", "[TownTopia tip] ").withStyle(ChatFormatting.GREEN))
                .append(TIPS.get(pick).get().withStyle(ChatFormatting.GRAY)), false);
    }
}
