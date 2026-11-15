package com.alex.test.ui.screen;

import com.alex.test.village.villagers.builder.plan.BuilderPlanItem;
import com.alex.test.ModItems;
import com.alex.test.client.BlueprintFiles;
import com.alex.test.network.BuilderPlanPayloads;
import com.alex.test.ui.BuilderDeskMenu;
import com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType;
import com.alex.test.village.villagers.builder.blueprint.Blueprint;
import com.alex.test.village.villagers.builder.blueprint.BlueprintMaterials;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The Builder's plan on the client: what the desk handed over, the ghost in the world
 * ({@link PlanWorldGhost}), the controls while the plan is in the main hand, and the
 * line over the hotbar.
 *
 * <p>The desk calls {@link #start} with the blueprint it has read, then asks the server
 * for the item. Holding it: right click pins or frees the ghost, sneak + scroll turns,
 * the Turn, Mirror, Up and Down keys (Options, Controls, TownTopia) work as on the desk,
 * sneak + right click on a pinned ghost opens {@link BuilderPlanScreen}, sneak + left
 * click puts the plan away. Confirm hands the blueprint and spot back
 * ({@link #takeHandoff}) for the desk that the server opens next.
 */
public final class BuilderPlanClient {
    private BuilderPlanClient() {}

    /**
     * What the desk hands over: the picked file read in full, the village's border and
     * buildings for the checks, and the ghost's turn and (if it was placed) spot.
     */
    record Carried(int villagerId, BlueprintFiles.Entry entry, Blueprint blueprint,
                   @Nullable BlueprintMaterials.Result materials, @Nullable TokenType guess,
                   @Nullable BlueprintPreviewView.Prepared prepared, int[] border,
                   List<BuilderDeskMenu.BuildingRow> buildings, Rotation rotation, Mirror mirror,
                   @Nullable BlockPos origin) {

        Carried at(@Nullable BlockPos spot, Rotation newRotation, Mirror newMirror) {
            return new Carried(villagerId, entry, blueprint, materials, guess, prepared, border, buildings,
                    newRotation, newMirror, spot);
        }
    }

    private static final String CATEGORY = "key.categories.dynamicvillages";
    static final KeyMapping TURN = new KeyMapping("key.dynamicvillages.plan_turn", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY);
    static final KeyMapping MIRROR = new KeyMapping("key.dynamicvillages.plan_mirror", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY);
    static final KeyMapping UP = new KeyMapping("key.dynamicvillages.plan_up", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PAGE_UP, CATEGORY);
    static final KeyMapping DOWN = new KeyMapping("key.dynamicvillages.plan_down", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PAGE_DOWN, CATEGORY);

    static final KeyMapping FORWARD = new KeyMapping("key.dynamicvillages.plan_forward", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UP, CATEGORY);
    static final KeyMapping BACK = new KeyMapping("key.dynamicvillages.plan_back", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, CATEGORY);
    static final KeyMapping LEFT_KEY = new KeyMapping("key.dynamicvillages.plan_left", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, CATEGORY);
    static final KeyMapping RIGHT_KEY = new KeyMapping("key.dynamicvillages.plan_right", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, CATEGORY);
    static final KeyMapping LAYERS = new KeyMapping("key.dynamicvillages.plan_layers", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, CATEGORY);
    static final KeyMapping BIGGER = new KeyMapping("key.dynamicvillages.plan_bigger", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_EQUAL, CATEGORY);
    static final KeyMapping SMALLER = new KeyMapping("key.dynamicvillages.plan_smaller", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_MINUS, CATEGORY);
    static final KeyMapping HELP = new KeyMapping("key.dynamicvillages.plan_help", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY);

    @Nullable private static Carried active;
    @Nullable private static PlanWorldGhost ghost;
    @Nullable private static Carried handoff;
    /** A plan for copying (instead of placing), and the copy pieces the server sends it. */
    @Nullable private static PlanCopyTool copyTool;
    private static final BuilderDeskMenu.CopyInbox COPY_INBOX = new BuilderDeskMenu.CopyInbox();
    /** A copy saved with a plan, for the desk that opens next. */
    private record CopiedBack(int villagerId, java.nio.file.Path path) {}
    @Nullable private static CopiedBack copied;
    /** The panel shows every control and what the colours mean. */
    private static boolean helpOpen;
    @Nullable private static String flash;
    private static long flashUntil;

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(TURN);
        event.register(MIRROR);
        event.register(UP);
        event.register(DOWN);
        event.register(FORWARD);
        event.register(BACK);
        event.register(LEFT_KEY);
        event.register(RIGHT_KEY);
        event.register(LAYERS);
        event.register(BIGGER);
        event.register(SMALLER);
        event.register(HELP);
    }

    // =========================================================================
    // Hand-over with the desk and the server
    // =========================================================================

    /** From the desk, right before it asks for the item: this is what the plan shows. */
    static void start(Carried carried) {
        clearGhost();
        active = carried;
        ghost = new PlanWorldGhost(carried);
    }

    /** From the desk's copy list, right before it asks for the item: a plan for copying. */
    static void startCopy(int villagerId) {
        clearGhost();
        active = null;
        copyTool = new PlanCopyTool(villagerId);
    }

    /** Where copies made outside the desk arrive (the desk has its own). */
    public static BuilderDeskMenu.CopyInbox copyInbox() {
        return COPY_INBOX;
    }

    /** The copy was saved: the plan goes, and the desk opens on the Copied tab with it picked. */
    static void finishCopy(java.nio.file.Path path) {
        if (copyTool != null) copied = new CopiedBack(copyTool.villagerId, path);
        String file = path.getFileName().toString();
        Minecraft.getInstance().gui.setOverlayMessage(Component.literal("Saved as " + file + " in Copied")
                .withStyle(ChatFormatting.GREEN), false);
        send(BuilderPlanPayloads.BACK_TO_DESK, BlockPos.ZERO, 0, 0);
    }

    /** For a desk that just opened: a copy a plan saved, once. */
    @Nullable
    static java.nio.file.Path takeCopied(int villagerId) {
        CopiedBack back = copied;
        if (back == null || back.villagerId() != villagerId) return null;
        copied = null;
        return back.path();
    }

    /** The server put the plan away (or never gave it): stop drawing it. */
    public static void ended() {
        clearGhost();
        active = null;
        if (Minecraft.getInstance().screen instanceof BuilderPlanScreen) Minecraft.getInstance().setScreen(null);
    }

    /** For a desk that just opened: the blueprint and spot a plan came back with, once. */
    @Nullable
    static Carried takeHandoff(int villagerId) {
        Carried back = handoff;
        if (back == null || back.villagerId() != villagerId) return null;
        handoff = null;
        return back;
    }

    /** From the confirm screen. */
    static void act(int action) {
        PlanWorldGhost shown = ghost;
        Carried carried = active;
        if (shown == null || carried == null) {
            send(BuilderPlanPayloads.CANCEL, BlockPos.ZERO, 0, 0);
            return;
        }
        BlueprintGhost.Shape shape = shown.shape();
        BlockPos origin = shown.origin();
        if (action == BuilderPlanPayloads.CONFIRM) {
            if (!shown.valid() || shape == null || origin == null) return;
            handoff = carried.at(origin, shown.rotation(), shown.mirror());
        } else if (action == BuilderPlanPayloads.BACK_TO_DESK) {
            // The desk opens on the same blueprint, turned as it was here, at its old spot.
            handoff = carried.at(carried.origin(), shown.rotation(), shown.mirror());
        }
        send(action, origin == null ? BlockPos.ZERO : origin, shape == null ? 0 : shape.sizeX(),
                shape == null ? 0 : shape.sizeZ());
    }

    /** Arrows: the ghost, or the copy box, one block that way. */
    private static void nudge(net.minecraft.core.Direction way) {
        if (copyTool != null) {
            copyTool.move(way.getStepX(), 0, way.getStepZ());
        } else if (ghost != null) {
            ghost.nudge(way.getStepX(), way.getStepZ());
        }
    }

    /** Page Up / Page Down: the ghost, or the copy box, one block up or down. */
    private static void raise(int dy) {
        if (copyTool != null) {
            copyTool.move(0, dy, 0);
        } else if (ghost != null) {
            ghost.raise(dy);
        }
    }

    /**
     * "Build it" in the confirm box: orders the blueprint at the pinned spot, with the player's
     * blocks too when {@code give}. The server ends the plan.
     */
    static void order(boolean give) {
        PlanWorldGhost shown = ghost;
        Carried carried = active;
        if (shown == null || carried == null || BuildOrderClient.busy()) return;
        BlockPos origin = shown.origin();
        if (!shown.valid() || shown.shape() == null || origin == null) return;
        BuildOrderClient.order(carried.villagerId(), carried.blueprint(), origin, shown.rotation(), shown.mirror(), give,
                (ok, message) -> {
                    Minecraft.getInstance().gui.setOverlayMessage(Component.literal(message)
                            .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED), false);
                    if (!ok) flash(message);
                });
    }

    private static void send(int action, BlockPos origin, int sizeX, int sizeZ) {
        PacketDistributor.sendToServer(new BuilderPlanPayloads.PlanAction(action, origin, sizeX, sizeZ));
    }

    @Nullable
    static PlanWorldGhost ghost() {
        return ghost;
    }

    private static void clearGhost() {
        BuilderPlanItem.glow = false;
        if (ghost != null) ghost.close();
        ghost = null;
        if (copyTool != null) copyTool.close();
        copyTool = null;
    }

    private static boolean holding(Minecraft minecraft) {
        return minecraft.player != null && minecraft.player.getMainHandItem().is(ModItems.BUILDER_PLAN.get());
    }

    // =========================================================================
    // Events
    // =========================================================================

    /** Keys held down since their press: the game repeats a held key as more clicks. */
    private static final java.util.Set<KeyMapping> HELD = new java.util.HashSet<>();

    /** True on a new press only, not on the repeats a held key sends. */
    private static boolean pressedOnce(KeyMapping key) {
        boolean clicked = false;
        while (key.consumeClick()) clicked = true;
        boolean fresh = clicked && !HELD.contains(key);
        if (key.isDown()) {
            HELD.add(key);
        } else {
            HELD.remove(key);
        }
        return fresh;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean holding = holding(minecraft) && minecraft.screen == null && (active != null || copyTool != null);
        // Pressed keys queue up; with no plan in hand they do nothing, and are dropped.
        // Rotate, flip, help and layers act once per press; holding them does nothing more.
        if (pressedOnce(TURN) && holding && ghost != null) ghost.turn(false);
        if (pressedOnce(MIRROR) && holding && ghost != null) ghost.cycleMirror();
        while (UP.consumeClick()) if (holding) raise(1);
        while (DOWN.consumeClick()) if (holding) raise(-1);
        while (BIGGER.consumeClick()) if (holding && copyTool != null) copyTool.resize(1);
        while (SMALLER.consumeClick()) if (holding && copyTool != null) copyTool.resize(-1);
        if (pressedOnce(HELP) && holding) helpOpen = !helpOpen;
        if (pressedOnce(LAYERS) && holding && ghost != null) ghost.toggleLayers();
        // Arrows move it one block, as the player looks: up is away, left is to the left.
        net.minecraft.core.Direction ahead = minecraft.player != null ? minecraft.player.getDirection()
                : net.minecraft.core.Direction.NORTH;
        while (FORWARD.consumeClick()) if (holding) nudge(ahead);
        while (BACK.consumeClick()) if (holding) nudge(ahead.getOpposite());
        while (LEFT_KEY.consumeClick()) if (holding) nudge(ahead.getCounterClockWise());
        while (RIGHT_KEY.consumeClick()) if (holding) nudge(ahead.getClockWise());
        if (ghost != null && holding(minecraft)) ghost.tick(minecraft);
        BuilderPlanItem.glow = ghost != null && ghost.pinned() && ghost.valid()
                || copyTool != null && copyTool.bytes() != null;
        if (copyTool != null && holding(minecraft)) copyTool.tick(minecraft);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!holding(minecraft)) return;
        if (ghost != null) ghost.render(minecraft, event.getCamera().getPosition());
        if (copyTool != null) copyTool.render(minecraft, event.getCamera().getPosition());
    }

    /** Right and left clicks with the plan in the main hand are the plan's, not the world's. */
    @SubscribeEvent
    public static void onClick(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!holding(minecraft) || minecraft.player == null) return;
        boolean sneaking = minecraft.player.isShiftKeyDown();
        if (event.isAttack()) {
            if (!sneaking && copyTool != null) {
                // Copying: a left click marks the first corner of a box, it never breaks the block.
                event.setCanceled(true);
                event.setSwingHand(false);
                copyTool.cornerLooked(minecraft);
                return;
            }
            if (!sneaking) return;
            event.setCanceled(true);
            event.setSwingHand(false);
            send(BuilderPlanPayloads.CANCEL, BlockPos.ZERO, 0, 0);
            return;
        }
        if (!event.isUseItem()) return;
        // Doors, chests, buttons and villagers in normal reach work as without the plan.
        if (!sneaking && passesThrough(minecraft)) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        PlanCopyTool tool = copyTool;
        if (tool != null) {
            if (!sneaking) {
                tool.pickLooked(minecraft);
            } else if (tool.bytes() != null) {
                String label = tool.label();
                minecraft.setScreen(new BuilderPlanSaveScreen(label == null ? "Copied building" : label, tool.bytes()));
            }
            return;
        }
        PlanWorldGhost shown = ghost;
        if (shown == null) return;
        if (sneaking && shown.pinned()) {
            if (shown.valid()) {
                minecraft.setScreen(new BuilderPlanScreen(shown));
            } else {
                String reason = shown.reason();
                flash(reason == null ? "It can't go here" : reason);
            }
            return;
        }
        shown.togglePin();
    }

    /** The crosshair is on a villager or animal, or on a block that does something when used. */
    private static boolean passesThrough(Minecraft minecraft) {
        net.minecraft.world.phys.HitResult hit = minecraft.hitResult;
        if (hit instanceof net.minecraft.world.phys.EntityHitResult) return true;
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit)
                || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || minecraft.level == null) return false;
        BlockPos pos = blockHit.getBlockPos();
        net.minecraft.world.level.block.state.BlockState state = minecraft.level.getBlockState(pos);
        net.minecraft.world.level.block.Block block = state.getBlock();
        return state.getMenuProvider(minecraft.level, pos) != null
                || minecraft.level.getBlockEntity(pos) instanceof net.minecraft.world.Container
                || block instanceof net.minecraft.world.level.block.DoorBlock
                || block instanceof net.minecraft.world.level.block.TrapDoorBlock
                || block instanceof net.minecraft.world.level.block.FenceGateBlock
                || block instanceof net.minecraft.world.level.block.ButtonBlock
                || block instanceof net.minecraft.world.level.block.LeverBlock
                || block instanceof net.minecraft.world.level.block.BellBlock
                || block instanceof net.minecraft.world.level.block.BedBlock;
    }

    /** Sneak + scroll turns the ghost instead of changing the hotbar slot. */
    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (ghost == null || copyTool != null || minecraft.screen != null || !holding(minecraft)
                || !minecraft.player.isShiftKeyDown() || event.getScrollDeltaY() == 0) return;
        event.setCanceled(true);
        if (ghost.layer() >= 0) {
            ghost.stepLayer(event.getScrollDeltaY() > 0 ? 1 : -1);
        } else {
            ghost.turn(event.getScrollDeltaY() < 0);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearGhost();
        active = null;
        handoff = null;
        copied = null;
    }

    // =========================================================================
    // The controls panel
    // =========================================================================

    @SubscribeEvent
    public static void onRenderGui(net.neoforged.neoforge.client.event.RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen != null || !holding(minecraft)) return;
        if (active == null && copyTool == null) return;
        PlanHud.render(event.getGuiGraphics(), copyTool != null ? copyTool.hud(helpOpen) : hud());
    }

    /** A short red line on the panel (why a confirm was refused), for a few seconds. */
    static void flash(String text) {
        flash = text;
        flashUntil = System.currentTimeMillis() + 3000;
    }

    @Nullable
    static String flashed() {
        return flash != null && System.currentTimeMillis() < flashUntil ? flash : null;
    }

    private static PlanHud.Content hud() {
        PlanWorldGhost shown = ghost;
        if (shown == null) return null;
        PlanHud.Content out = new PlanHud.Content(shown.name());
        String sneak = PlanHud.sneak(), help = PlanHud.cap(HELP);
        if (shown.shape() == null) return out.status(Component.literal("Getting it ready..."));
        out.status(statusLine(shown));
        if (shown.layer() >= 0 && shown.shape() != null) {
            out.status(Component.literal("Layer " + (shown.layer() + 1) + " of " + shown.shape().sizeY()));
        }
        if (shown.pinned() && shown.valid()) out.row(0xFF7FE08A, "Build it", sneak, PlanHud.RIGHT);
        out.row(shown.pinned() ? "Move it again" : "Set it down here", PlanHud.RIGHT);
        out.anyOf("Move", PlanHud.cap(FORWARD), PlanHud.cap(LEFT_KEY), PlanHud.cap(BACK), PlanHud.cap(RIGHT_KEY));
        out.row("Rotate", PlanHud.cap(TURN));
        if (shown.layer() >= 0) out.row("Layer up / down", sneak, PlanHud.SCROLL);
        if (!helpOpen) return out.row("More", help);
        if (shown.layer() < 0) out.row("Rotate", sneak, PlanHud.SCROLL);
        out.row("Flip", PlanHud.cap(MIRROR));
        out.row(shown.layer() >= 0 ? "Show it all" : "One layer at a time", PlanHud.cap(LAYERS));
        out.anyOf("Up / down", PlanHud.cap(UP), PlanHud.cap(DOWN));
        out.row("Put away", sneak, PlanHud.LEFT);
        out.row("Less", help);
        out.note("Faces " + shown.facing().getName() + (shown.mirror() != Mirror.NONE ? ", flipped." : "."));
        out.note("Green: it fits. Red: it can't go there.");
        out.note("Red boxes: blocks the Builder has to clear.");
        out.note("Doors, chests and villagers close by still work.");
        return out;
    }

    /** One line: what is wrong, or that it fits (and how many blocks are in the way). */
    private static Component statusLine(PlanWorldGhost shown) {
        String flashed = flashed();
        if (flashed != null) return Component.literal(flashed).withStyle(ChatFormatting.RED);
        if (BuildOrderClient.busy()) return Component.literal("Sending the order...").withStyle(ChatFormatting.GOLD);
        if (!shown.placed()) return Component.literal("Look at the ground");
        if (!shown.valid()) {
            String reason = shown.reason();
            return Component.literal(reason == null ? "Can't go here" : reason).withStyle(ChatFormatting.RED);
        }
        Component line = Component.literal("Fits here").withStyle(ChatFormatting.GREEN);
        int blocked = shown.inTheWay();
        if (blocked > 0) {
            line = line.copy().append(Component.literal(" · " + blocked + " in the way").withStyle(ChatFormatting.GOLD));
        }
        return line;
    }

    /** A key's short name as the player bound it, like "R" or "Page Up". */
    static String key(KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString();
    }
}
