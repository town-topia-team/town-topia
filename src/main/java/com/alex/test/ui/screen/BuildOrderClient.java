package com.alex.test.ui.screen;

import com.alex.test.network.BuildOrderPayloads;
import com.alex.test.ui.BuilderDeskMenu;
import com.alex.test.village.villagers.builder.blueprint.Blueprint;
import com.alex.test.village.villagers.builder.blueprint.BlueprintLimits;
import com.alex.test.village.villagers.builder.blueprint.BlueprintMaterials;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.alex.test.village.villagers.builder.blueprint.BlueprintWriter;
import com.alex.test.village.villagers.builder.project.BlueprintUploads;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Sends one build order at a time: the blueprint is written as structure NBT off the
 * render thread (unturned; the server turns it), sent in pieces a few per tick, then the
 * order. The answer goes to the caller's {@link Done}.
 */
public final class BuildOrderClient {
    private BuildOrderClient() {}

    public interface Done {
        void done(boolean ok, String message);
    }

    private static final int CHUNKS_PER_TICK = 4;
    /** No answer after this long: give up so the buttons work again. */
    private static final long ANSWER_MS = 30_000;

    private record Sending(int uploadId, byte[] bytes, BuildOrderPayloads.Order order) {}

    @Nullable private static Sending sending;
    private static int sentBytes;
    @Nullable private static Done waiting;
    private static long waitingSince;
    private static int nextUploadId = 1;

    /** An order is being written, sent or checked. */
    public static boolean busy() {
        return waiting != null;
    }

    /** {@code give}: the order also takes the blocks it needs from the player's inventory (plain stacks). */
    public static void order(int villagerId, Blueprint blueprint, BlockPos origin, Rotation rotation, Mirror mirror,
                             boolean give, Done done) {
        if (busy()) {
            done.done(false, "Your last order is still on its way.");
            return;
        }
        waiting = done;
        waitingSince = System.currentTimeMillis();
        int uploadId = nextUploadId++;
        Minecraft minecraft = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            try {
                return BlueprintWriter.toBytes(blueprint);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, Util.backgroundExecutor()).whenCompleteAsync((bytes, error) -> {
            if (waiting != done) return;
            if (error != null) {
                finish(false, "The blueprint could not be packed for sending.");
            } else if (bytes.length > BlueprintLimits.MAX_FILE_BYTES) {
                finish(false, "That blueprint is too big to send.");
            } else {
                sending = new Sending(uploadId, bytes, new BuildOrderPayloads.Order(uploadId, villagerId,
                        blueprint.name(), origin, rotation, mirror, give));
                sentBytes = 0;
                PacketDistributor.sendToServer(new BuildOrderPayloads.UploadBegin(uploadId, bytes.length));
            }
        }, minecraft);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (waiting != null && System.currentTimeMillis() - waitingSince > ANSWER_MS) {
            finish(false, "No answer from the server. Try again.");
            return;
        }
        Sending now = sending;
        if (now == null) return;
        for (int i = 0; i < CHUNKS_PER_TICK && sentBytes < now.bytes().length; i++) {
            int end = Math.min(now.bytes().length, sentBytes + BlueprintUploads.CHUNK_BYTES);
            PacketDistributor.sendToServer(new BuildOrderPayloads.UploadChunk(now.uploadId(),
                    Arrays.copyOfRange(now.bytes(), sentBytes, end)));
            sentBytes = end;
        }
        if (sentBytes >= now.bytes().length) {
            PacketDistributor.sendToServer(now.order());
            sending = null;
        }
    }

    /** The server's answer. */
    public static void result(boolean ok, String message) {
        if (waiting != null) {
            finish(ok, message);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setOverlayMessage(Component.literal(message)
                .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED), false);
    }

    /** The answer to a desk button: on the open desk, else over the hotbar. */
    public static void notice(boolean ok, String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof BuilderDeskScreen desk) {
            desk.notice(ok, message);
            return;
        }
        minecraft.gui.setOverlayMessage(Component.literal(message)
                .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED), false);
    }

    /**
     * How many of the blueprint's blocks the player carries: plain stacks (as the server takes
     * them) in the main inventory and off hand, each item up to what the blueprint needs.
     */
    public static int youCarry(@Nullable BlueprintMaterials.Result materials) {
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || materials == null || materials.total() <= 0) return 0;
        java.util.Map<Item, Integer> have = new java.util.HashMap<>();
        for (List<ItemStack> list : List.of(player.getInventory().items, player.getInventory().offhand)) {
            for (ItemStack stack : list) {
                if (!stack.isEmpty() && materials.items().containsKey(stack.getItem())
                        && com.alex.test.village.villagers.builder.project.BuildOrders.plain(stack, stack.getItem())) {
                    have.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }
        int count = 0;
        for (java.util.Map.Entry<Item, Integer> each : have.entrySet()) {
            count += Math.min(each.getValue(), materials.items().get(each.getKey()));
        }
        return count;
    }

    private static void finish(boolean ok, String message) {
        Done done = waiting;
        waiting = null;
        sending = null;
        if (done != null) done.done(ok, message);
    }

    // =========================================================================
    // What the storage has
    // =========================================================================

    /** The storage against a blueprint: blocks it has (up to what is needed), the total, and what is short. */
    public record Stock(int have, int total, List<String> missing) {
        public boolean enough() {
            return have >= total;
        }

        /** "Missing: 80 Cobblestone, 50 Glass and 3 more", or null when nothing is. */
        @Nullable
        public String missingLine() {
            if (missing.isEmpty()) return null;
            StringBuilder out = new StringBuilder("Missing: ");
            int shown = Math.min(3, missing.size());
            for (int i = 0; i < shown; i++) out.append(i > 0 ? ", " : "").append(missing.get(i));
            if (missing.size() > shown) out.append(" and ").append(missing.size() - shown).append(" more");
            return out.toString();
        }
    }

    private record Asked(List<Item> items, BlueprintMaterials.Result needed, java.util.function.Consumer<Stock> answer) {}

    private static final java.util.Map<Integer, Asked> ASKED = new java.util.HashMap<>();
    private static int nextRequestId = 1;

    /** Asks the server what the village storage has of this blueprint's blocks. */
    public static void stock(int villagerId, BlueprintMaterials.Result needed, java.util.function.Consumer<Stock> answer) {
        List<Item> items = new java.util.ArrayList<>(needed.items().keySet());
        if (items.size() > 256) items = items.subList(0, 256);
        List<net.minecraft.resources.ResourceLocation> keys = new java.util.ArrayList<>();
        for (Item item : items) keys.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
        int id = nextRequestId++;
        if (ASKED.size() > 16) ASKED.clear();
        ASKED.put(id, new Asked(items, needed, answer));
        PacketDistributor.sendToServer(new BuildOrderPayloads.MaterialsRequest(id, villagerId, keys));
    }

    public static void materialsReply(int requestId, int[] counts) {
        Asked asked = ASKED.remove(requestId);
        if (asked == null || counts.length != asked.items().size()) return;
        int have = 0;
        List<String> missing = new java.util.ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            Item item = asked.items().get(i);
            int need = asked.needed().items().getOrDefault(item, 0);
            have += Math.min(need, counts[i]);
            if (counts[i] < need) {
                missing.add(String.format(java.util.Locale.ROOT, "%,d %s", need - counts[i],
                        new ItemStack(item).getHoverName().getString()));
            }
        }
        asked.answer().accept(new Stock(have, asked.needed().total(), List.copyOf(missing)));
    }

    /** The Projects tab's rows for the open desk. */
    /** The answer to the Projects tab's Blocks list. */
    public static void orderBlocks(BuildOrderPayloads.OrderBlocksReply reply) {
        if (Minecraft.getInstance().screen instanceof BuilderDeskScreen desk) desk.orderBlocks(reply);
    }

    public static void projects(int containerId, List<BuildOrderPayloads.ProjectRow> rows,
                                List<BuildOrderPayloads.GivenRow> given, String freeBy) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof BuilderDeskMenu desk
                && desk.containerId == containerId) {
            desk.setProjects(rows, given);
            desk.setFreeBy(freeBy);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        waiting = null;
        sending = null;
    }
}
