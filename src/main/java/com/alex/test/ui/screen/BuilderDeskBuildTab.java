package com.alex.test.ui.screen;

import com.alex.test.client.BlueprintFiles;
import com.alex.test.client.CopiedBlueprintSaver;
import com.alex.test.client.DroppedBlueprintFiles;
import com.alex.test.network.BuilderDeskPayloads;
import com.alex.test.network.BuilderPlanPayloads;
import com.alex.test.ui.BuilderDeskMenu;
import com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType;
import com.alex.test.village.villagers.builder.blueprint.Blueprint;
import com.alex.test.village.villagers.builder.blueprint.BlueprintException;
import com.alex.test.village.villagers.builder.blueprint.BlueprintMaterials;
import com.alex.test.village.villagers.builder.blueprint.BlueprintPlacement;
import com.alex.test.village.villagers.builder.blueprint.BlueprintReader;
import com.alex.test.village.villagers.builder.blueprint.BlueprintRoomGuess;
import com.alex.test.village.villagers.builder.copy.BuildingCopier;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.alex.test.ui.screen.StorageUi.ACCENT;
import static com.alex.test.ui.screen.StorageUi.DENIED;
import static com.alex.test.ui.screen.StorageUi.GOLD;
import static com.alex.test.ui.screen.StorageUi.MUTED;
import static com.alex.test.ui.screen.StorageUi.PANEL_BORDER;
import static com.alex.test.ui.screen.StorageUi.PANEL_DARK;
import static com.alex.test.ui.screen.StorageUi.TEXT;
import static com.alex.test.ui.screen.StorageUi.inside;

/**
 * The Build tab of the Builder's desk: the blueprint list on the left (Ready / Files /
 * Copied, search, "Village needs" first), the selected blueprint in 3D on the right and
 * a details strip under it.
 *
 * <p>Files are found and read by {@link BlueprintFiles} off the render thread; picking
 * one reads it in full (also off-thread), works out its materials, type and preview
 * cells there, and only the model recording happens on the render thread, once.
 *
 * <p>With a village border the view has two modes: Village (the whole village with the
 * blueprint as a ghost to turn and place, {@link VillageModelView}) and Blueprint (the
 * blueprint alone, {@link BlueprintPreviewView}). The village model holds GPU buffers,
 * so it is made the first time it shows and freed by {@link #releaseGpu}.
 *
 * <p>"+ Copy a building" turns the tab into copy mode: the list shows the village's
 * buildings, a click on the village map picks any building, and the server
 * ({@link BuildingCopier}) finds its outline and sends the copy back. The view then
 * shows what was found; the details strip has a name box, the side buttons that grow
 * or shrink the box, and Save, which writes the file to the Copied folder.
 *
 * <p>"Place on the map" opens the drop map: the full window view with a narrow strip of
 * the player's files down the left. A file dragged from the strip, or from the computer
 * onto the window ({@link DroppedBlueprintFiles} copies those into the mod's folder), is
 * read and its ghost stands on the column where it was let go.
 */
final class BuilderDeskBuildTab {
    static final int LIST_W = 172;
    private static final int ROW_H = 24, ROW_GAP = 2, GROUP_H = 14, DETAILS_H = 62, GAP = 8;
    private static final int SWITCH_W = 54, CONTROL_H = 16;
    private static final int SEGMENT_Y = 6, SEARCH_Y = 26, LIST_Y = 48;
    /** The fold button at the end of the list's tabs, and the strip the list folds into. */
    private static final int FOLD_BUTTON = 16, FOLDED_W = 22;
    private static final String RESET_LABEL = "Reset view";
    private static final String BUILD_LABEL = "Build";
    /** Gives a Builder's plan to place the picked blueprint in the real world. */
    private static final String WORLD_LABEL = "Preview in world";
    private static final int BUILD_W = 64;
    private static final String SAVE_LABEL = "Save";
    /** Names of the box sides, in {@link BuildingCopier#SIDES} order. */
    private static final String[] SIDE_NAMES = {"West", "East", "Bottom", "Top", "North", "South"};
    /** Ticks between two copy requests, a little over the server's own wait. */
    private static final int COPY_SEND_GAP = 4;
    private static final String NAME_LABEL = "Name";
    /** A copy of a registered building whose room the village can't find right now (no floor to start from). */
    static final String NO_FLOOR_MESSAGE = "The village can't find this building's room right now, so its floor may be missing.";
    /** The square icon button left of Reset view that opens and closes the full window view. */
    private static final int FULL_BUTTON = 20;
    // The drop map: its strip of files, the line over the map, its buttons.
    private static final int STRIP_W = 140, STRIP_LIST_Y = 30;
    /** The Open folder button (under the list, on top of the drop map's strip) and its drawn folder's colours. */
    private static final int FOLDER_H = 20;
    private static final int FOLDER_BACK = 0xFFB07A26, FOLDER_FRONT = 0xFFE6B847, FOLDER_EDGE = 0xFFF6D77A;
    private static final String DROP_LABEL = "Place on the map";
    private static final String DROP_HELP = "Drag a building onto the map. You can also drag files from your computer "
            + "onto this window.";
    private static final String NO_MAP_HELP = "Your village has no border yet, so there is no map. Files you drag "
            + "here still go in the list.";
    private static final String FOLDER_LABEL = "Open folder";
    private static final String CLOSE_LABEL = "Close";
    /** How far a press on a strip file moves before it is a drag, not a click. */
    private static final double DRAG_SLOP = 4;

    private enum Segment {
        FILES("Files"), COPIED("Copied");

        final String label;

        Segment(String label) {
            this.label = label;
        }
    }

    /** The buttons along the bottom of the village view. */
    private enum Control {
        TURN("Rotate", "Rotates the building a quarter turn.", "Key: R"),
        MIRROR("Flip", "Flips it like a mirror. Click again to flip it another way.", "Key: M"),
        UP("Up", "Moves it one block higher.", "Key: Page Up"),
        DOWN("Down", "Moves it one block lower. It may sink into the ground.", "Key: Page Down");

        final String label, help, key;

        Control(String label, String help, String key) {
            this.label = label;
            this.help = help;
            this.key = key;
        }
    }

    /** One line of the list: a group heading, a file, an empty-state text, the copy row, or (copy mode) a building. */
    private record Line(Kind kind, @Nullable BlueprintFiles.Entry entry, String text, int height,
                        @Nullable BuilderDeskMenu.BuildingRow building) {
        Line(Kind kind, @Nullable BlueprintFiles.Entry entry, String text, int height) {
            this(kind, entry, text, height, null);
        }
    }
    private enum Kind { GROUP, FILE, TEXT, COPY, BUILDING, CANCEL, WORLD, DROP }

    /** The copy controls in the details strip. */
    private enum CopyControl { SIDE, LESS, MORE, RESET }

    /**
     * A file read in full: the blueprint and what the desk shows of it, or the
     * player-readable reason it could not be read.
     */
    private record Loaded(BlueprintFiles.Entry entry, @Nullable Blueprint blueprint,
                          @Nullable BlueprintMaterials.Result materials, @Nullable TokenType guess,
                          @Nullable BlueprintPreviewView.Prepared preview, @Nullable String error) {}

    private final BuilderDeskMenu menu;
    private final BlueprintFiles files;
    private final BlueprintPreviewView preview = new BlueprintPreviewView();
    private Font font;
    @Nullable private EditBox search;
    private String query = "";
    private Segment segment = Segment.FILES;
    private int scroll;

    @Nullable private BlueprintFiles.Entry selected;
    @Nullable private CompletableFuture<Loaded> loading;
    @Nullable private Loaded loaded;
    /** The village view, once it has been shown; null again after {@link #releaseGpu}. */
    @Nullable private VillageModelView village;
    private boolean villageMode = true;

    // Copy mode: what is being copied, the box sides, the request in flight, the copy itself.
    private boolean copyMode;
    @Nullable private UUID copyBuilding;
    @Nullable private BlockPos copyPick;
    private final int[] grow = new int[BuildingCopier.SIDES];
    private int side = 4;
    private int requestCounter, pendingRequest = -1, sendWait;
    private boolean resend;
    @Nullable private BuilderDeskPayloads.CopyResult copyHeader;
    @Nullable private byte[] copyBytes;
    @Nullable private String copyMessage;
    private int copyMessageColor = MUTED;
    @Nullable private EditBox nameBox;
    private String autoName = "", typedName = "";
    @Nullable private CompletableFuture<Path> saving;
    /** A file just saved, selected once the folder scan lists it. */
    @Nullable private Path selectWhenListed;
    @Nullable private String savedMessage;
    /** The last order's answer, shown under the blueprint for a while. */
    @Nullable private String orderMessage;
    private boolean orderOk;
    private long orderMessageUntil;
    @Nullable private Runnable onOrdered;
    /** What the storage has of the shown blueprint's blocks; asked again when another one shows. */
    @Nullable private Object stockFor;
    @Nullable private BuildOrderClient.Stock stock;
    /** "Give my blocks" above Build is ticked; starts off. */
    private boolean give;
    /** A Builder's plan came back (confirmed or Back to desk): its turn and spot, for the village view. */
    @Nullable private BuilderPlanClient.Carried pendingSpot;

    /**
     * The view fills the whole game window (list hidden, details strip along the bottom).
     * For big GUI scales (Auto), where the normal view is only a few hundred pixels wide.
     */
    private boolean fullView;
    /** The list is folded into a thin strip, so the map gets the room. */
    private boolean folded;

    /** The drop map is open; it is a full view with a strip of files down the left. */
    private boolean dropMap;
    private final List<Line> strip = new ArrayList<>();
    private int stripScroll;
    /** A strip file pressed with the left button; a drag once the mouse moved {@link #DRAG_SLOP}. */
    @Nullable private BlueprintFiles.Entry dragEntry;
    private double dragDistance;
    /** Files dropped from the computer, being copied into the mod's folder. */
    @Nullable private CompletableFuture<DroppedBlueprintFiles.Result> dropping;
    /** Where those files were let go: the land column (x, z) or null, and whether the first one is picked. */
    @Nullable private int[] dropColumn;
    private boolean dropPlace;
    /** The column the file in {@link #selectWhenListed} goes on, or null for where the map puts it. */
    @Nullable private int[] listedColumn;
    /** A short note over the drop map (a file left out, files added); the help line otherwise. */
    @Nullable private String dropMessage;
    private int dropMessageColor = MUTED;
    private long dropMessageUntil;

    // Layout of the last frame, for clicks and tooltips.
    private int left, top, right, bottom;
    private final List<Line> lines = new ArrayList<>();

    BuilderDeskBuildTab(BuilderDeskMenu menu) {
        this.menu = menu;
        this.files = new BlueprintFiles(Minecraft.getInstance().gameDirectory.toPath());
        this.font = Minecraft.getInstance().font;
        files.refresh();
        // Back from a Builder's plan: the same blueprint, already read, and where it was put.
        BuilderPlanClient.Carried back = BuilderPlanClient.takeHandoff(menu.villagerId());
        if (back != null) {
            selected = back.entry();
            segment = back.entry().source() == BlueprintFiles.Source.COPIED ? Segment.COPIED : Segment.FILES;
            apply(new Loaded(back.entry(), back.blueprint(), back.materials(), back.guess(), back.prepared(), null));
            pendingSpot = back;
        }
        // Back from a copying plan: the saved copy, picked once the Copied folder is read.
        Path copied = BuilderPlanClient.takeCopied(menu.villagerId());
        if (copied != null) {
            segment = Segment.COPIED;
            selectWhenListed = copied;
            savedMessage = "Saved as " + fileBaseName(copied);
        }
    }

    /** Called from the screen's init (also after a window resize) with its new search box. */
    void attach(Font font, EditBox box) {
        this.font = font;
        this.search = box;
        box.setValue(query);
        box.setResponder(value -> {
            query = value;
            scroll = 0;
        });
    }

    /** Called from the screen's init with its name box for copies. */
    void attachName(EditBox box) {
        this.nameBox = box;
        box.setValue(typedName);
        box.setResponder(value -> typedName = value);
    }

    boolean fullView() {
        return fullView;
    }

    void setFullView(boolean on) {
        fullView = on;
        scroll = 0;
        // The drop map is a full view: closing the full view closes it (Esc, F, the corner button, Close).
        if (!on) {
            dropMap = false;
            dragEntry = null;
        }
    }

    /** "Place on the map": the village map over the whole window with the strip of files. */
    private void openDropMap() {
        if (copyMode) leaveCopyMode();
        dropMap = true;
        fullView = true;
        villageMode = true;
        stripScroll = 0;
        savedMessage = null;
        // Look again, so files put in the folders meanwhile show up.
        files.refresh();
    }

    void setVisible(boolean visible) {
        if (search != null) {
            // The list is hidden in the full view, and its search box with it.
            search.visible = visible && !fullView && !folded;
            if (fullView) search.setFocused(false);
            if (!visible) search.setFocused(false);
        }
        if (nameBox != null) {
            boolean shown = visible && copyMode && copyHeader != null;
            nameBox.visible = shown;
            if (!shown) nameBox.setFocused(false);
        }
    }

    void tick() {
        files.poll();
        if (loading != null && loading.isDone()) {
            Loaded result = loading.isCompletedExceptionally() ? null : loading.join();
            loading = null;
            if (result != null && result.entry().equals(selected)) apply(result);
        }
        if (village != null) village.tick();
        tickDrop();
        tickCopy();
    }

    void close() {
        files.close();
        if (loading != null) loading.cancel(false);
        loading = null;
        loaded = null;
        preview.close();
        releaseGpu();
    }

    /**
     * Frees the village model's GPU buffers (screen closed or another tab picked). The
     * land is read again the next time the village view shows.
     */
    void releaseGpu() {
        if (village != null) village.close();
        village = null;
    }

    /** Where the ghost stands now, for ordering (round 4); null when nothing is placed. */
    @Nullable
    BlueprintPlacement placement() {
        return village == null ? null : village.placement();
    }

    /** The Village / Blueprint switch shows when there is a border and a readable blueprint. */
    private boolean switchShown() {
        return menu.snapshot().border() != null && loaded != null && loaded.blueprint() != null;
    }

    /** The drop map is always the map: no Village / Blueprint switch there. */
    private boolean switchVisible() {
        return switchShown() && !dropMap;
    }

    /**
     * While copying, the village shows until there is a copy to look at, so a building can be clicked.
     * The drop map shows it whenever there is a border, before anything is picked too.
     */
    private boolean villageShown() {
        if (dropMap) return menu.snapshot().border() != null;
        if (copyMode && menu.snapshot().border() != null && !switchShown()) return true;
        return villageMode && switchShown();
    }

    /** The village view, made the first time it is shown: reading the land starts then. */
    private VillageModelView village() {
        if (village == null) {
            int[] border = menu.snapshot().border();
            village = new VillageModelView(border == null ? new int[4] : border, menu.snapshot().buildings(),
                    menu.floors());
            village.setPickMode(copyMode);
            if (copyMode) {
                showCopyBox();
            } else if (loaded != null) {
                village.setBlueprint(loaded.blueprint(), loaded.preview());
                if (pendingSpot != null) {
                    village.setPlacement(pendingSpot.origin(), pendingSpot.rotation(), pendingSpot.mirror());
                    pendingSpot = null;
                }
            }
        }
        return village;
    }

    // =========================================================================
    // Selection and loading
    // =========================================================================

    private void select(BlueprintFiles.Entry entry) {
        if (entry.equals(selected)) return;
        selected = entry;
        loaded = null;
        preview.show(null);
        if (village != null) village.setBlueprint(null, null);
        if (loading != null) loading.cancel(false);
        loading = null;
        if (entry.tooBig()) {
            apply(new Loaded(entry, null, null, null, null, String.format(Locale.ROOT,
                    "This file is too big: %.1f MB, the limit is 2 MB.", entry.bytes() / (1024.0 * 1024.0))));
            return;
        }
        loading = CompletableFuture.supplyAsync(() -> load(entry), Util.backgroundExecutor());
    }

    /** Background thread: everything slow about a file, so the render thread only records models. */
    private static Loaded load(BlueprintFiles.Entry entry) {
        try {
            Blueprint blueprint = BlueprintFiles.read(entry);
            return new Loaded(entry, blueprint, BlueprintMaterials.count(blueprint),
                    BlueprintRoomGuess.guess(blueprint), BlueprintPreviewView.prepare(blueprint), null);
        } catch (BlueprintException e) {
            return new Loaded(entry, null, null, null, null, e.getMessage());
        } catch (RuntimeException e) {
            return new Loaded(entry, null, null, null, null, "This file can't be read.");
        }
    }

    private void apply(Loaded result) {
        loaded = result;
        preview.show(result.preview());
        // A copy is looked at on its own; on the village map only its box shows.
        if (village != null && !copyMode) village.setBlueprint(result.blueprint(), result.preview());
        // A dropped file that can't be read: its spot waits for nothing (the reason shows under the map).
        if (village != null && result.blueprint() == null) village.forgetColumn();
        Blueprint blueprint = result.blueprint();
        files.putSummary(result.entry(), blueprint == null
                ? new BlueprintFiles.Summary(null, 0, 0, 0, result.error())
                : new BlueprintFiles.Summary(result.guess(), blueprint.sizeX(), blueprint.sizeY(), blueprint.sizeZ(),
                        null));
    }

    /** Show on map from the Themes tab: that building picked, with the village map showing. */
    void showTheme(BlueprintFiles.Entry entry, Blueprint blueprint, @Nullable BlueprintMaterials.Result materials,
                   @Nullable TokenType guess, @Nullable BlueprintPreviewView.Prepared prepared) {
        if (copyMode) leaveCopyMode();
        if (loading != null) loading.cancel(false);
        loading = null;
        selected = entry;
        savedMessage = null;
        villageMode = true;
        apply(new Loaded(entry, blueprint, materials, guess, prepared, null));
    }

    // =========================================================================
    // Builder's plan
    // =========================================================================

    private boolean canTakePlan() {
        return !copyMode && selected != null && loaded != null && loaded.blueprint() != null
                && loaded.entry().equals(selected) && menu.snapshot().border() != null;
    }

    /** Run after an order went through (the desk shows its Projects tab). */
    void onOrdered(Runnable action) {
        onOrdered = action;
    }

    /** The shown blueprint's blocks in the player's inventory. */
    private int carry() {
        if (copyMode || selected == null || loaded == null || loaded.error() != null) return 0;
        return BuildOrderClient.youCarry(loaded.materials());
    }

    private boolean overTick(double mouseX, double mouseY) {
        return carry() > 0 && inside(mouseX, mouseY, tickX(), tickY(),
                StorageUi.tickBoxWidth(font, BuilderPlanScreen.GIVE_LABEL), 10);
    }

    private boolean canOrder() {
        BlueprintPlacement placement = canTakePlan() && villageShown() ? placement() : null;
        return placement != null && placement.valid() && !BuildOrderClient.busy();
    }

    /** Sends the picked blueprint, at the map's spot, as an order. */
    private void order() {
        if (!canOrder()) return;
        BlueprintPlacement placement = placement();
        orderMessage = "Sending the order...";
        orderOk = true;
        orderMessageUntil = Long.MAX_VALUE;
        BuildOrderClient.order(menu.villagerId(), loaded.blueprint(), placement.origin(), placement.rotation(),
                placement.mirror(), give && carry() > 0, (ok, message) -> {
                    orderMessage = message;
                    orderOk = ok;
                    orderMessageUntil = System.currentTimeMillis() + 6000;
                    if (ok && onOrdered != null) onOrdered.run();
                });
    }

    /**
     * Hands the picked blueprint to the Builder's plan (turned as on the map, at the map's
     * spot if it was placed) and asks the server for the item; the server closes the desk.
     */
    private void takePlan() {
        if (!canTakePlan()) return;
        Rotation rotation = village != null ? village.rotation() : Rotation.NONE;
        Mirror mirror = village != null ? village.mirror() : Mirror.NONE;
        BlockPos origin = null;
        BlueprintPlacement placement = village != null ? village.placement() : null;
        if (placement != null) {
            rotation = placement.rotation();
            mirror = placement.mirror();
            origin = placement.origin();
        }
        BuilderPlanClient.start(new BuilderPlanClient.Carried(menu.villagerId(), selected, loaded.blueprint(),
                loaded.materials(), loaded.guess(), loaded.preview(), menu.snapshot().border(),
                menu.snapshot().buildings(), rotation, mirror, origin));
        PacketDistributor.sendToServer(new BuilderPlanPayloads.TakePlan(menu.villagerId(), loaded.blueprint().name(),
                false));
    }

    // =========================================================================
    // Copying a building
    // =========================================================================

    private void enterCopyMode() {
        copyMode = true;
        scroll = 0;
        savedMessage = null;
        copyBuilding = null;
        copyPick = null;
        clearCopy();
        if (village != null) village.setPickMode(true);
    }

    private void leaveCopyMode() {
        copyMode = false;
        copyBuilding = null;
        copyPick = null;
        clearCopy();
        pendingRequest = -1;
        resend = false;
        // A copy is first shown on its own; picked files open on the village map again.
        villageMode = true;
        if (village != null) village.setPickMode(false);
    }

    /** Forgets the copy shown (a new building was picked, or copy mode ended). */
    private void clearCopy() {
        Arrays.fill(grow, 0);
        copyHeader = null;
        copyBytes = null;
        copyMessage = null;
        selected = null;
        loaded = null;
        if (loading != null) loading.cancel(false);
        loading = null;
        preview.show(null);
        if (village != null) {
            village.setBlueprint(null, null);
            village.setCopyBox(null, null, -1);
        }
    }

    private void pickBuilding(BuilderDeskMenu.BuildingRow row) {
        copyBuilding = row.id();
        copyPick = null;
        startCopy();
    }

    /** A click on the village map: x, the top block's y, z. The server finds what is there. */
    private void pickOnMap(int[] column) {
        copyBuilding = null;
        copyPick = new BlockPos(column[0], column[1], column[2]);
        startCopy();
    }

    private void startCopy() {
        clearCopy();
        typedName = "";
        autoName = "";
        if (nameBox != null) nameBox.setValue("");
        // A result still on its way is for the old building: drop it.
        pendingRequest = -1;
        resend = false;
        copyMessage = "Finding the building...";
        copyMessageColor = MUTED;
        requestCopy();
    }

    /** Asks the server for the copy as it stands (target and sides); one request at a time. */
    private void requestCopy() {
        if (copyBuilding == null && copyPick == null) return;
        if (pendingRequest >= 0 || sendWait > 0) {
            resend = true;
            return;
        }
        pendingRequest = ++requestCounter;
        sendWait = COPY_SEND_GAP;
        PacketDistributor.sendToServer(new BuilderDeskPayloads.CopyScan(menu.villagerId(), pendingRequest,
                copyBuilding, copyPick, grow.clone()));
    }

    private void tickCopy() {
        if (sendWait > 0) sendWait--;
        if (copyMode) {
            if (village != null) {
                int[] column = village.takePick();
                if (column != null) pickOnMap(column);
            }
            BuilderDeskMenu.CopyInbox.Finished done = menu.copyInbox().take();
            if (done != null && done.header().requestId() == pendingRequest) {
                pendingRequest = -1;
                receive(done);
            }
            if (resend && pendingRequest < 0 && sendWait == 0) {
                resend = false;
                requestCopy();
            }
        }
        if (saving != null && saving.isDone()) {
            try {
                Path path = saving.join();
                String name = fileBaseName(path);
                leaveCopyMode();
                segment = Segment.COPIED;
                scroll = 0;
                savedMessage = "Saved as " + name;
                selectWhenListed = path;
                files.refresh();
            } catch (RuntimeException e) {
                copyMessage = "The file could not be saved. Check that the game folder can be written to.";
                copyMessageColor = DENIED;
            }
            saving = null;
        }
        if (selectWhenListed != null && files.scanned() && !files.scanning()) {
            // Copies are under Copied; files dropped from the computer under Files.
            for (BlueprintFiles.Source source : List.of(BlueprintFiles.Source.COPIED, BlueprintFiles.Source.FILES)) {
                for (BlueprintFiles.Entry entry : files.entries(source)) {
                    if (!samePath(entry.path(), selectWhenListed)) continue;
                    select(entry);
                    if (listedColumn != null && villageShown()) village().placeOnColumn(listedColumn[0], listedColumn[1]);
                }
            }
            selectWhenListed = null;
            listedColumn = null;
        }
    }

    private static boolean samePath(Path a, Path b) {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    // =========================================================================
    // The drop map
    // =========================================================================

    /**
     * Files dragged from the computer onto the desk window. {@code spotKnown} is false when
     * another tab was showing: the first file is then picked and stands where the map puts it.
     */
    void filesDropped(List<Path> paths, boolean spotKnown, double mouseX, double mouseY) {
        if (paths.isEmpty()) return;
        // Where it was let go, read with the layout the player was looking at.
        boolean overView = spotKnown && inside(mouseX, mouseY, viewLeft(), viewTop(), viewRight() - viewLeft(),
                viewBottom() - viewTop());
        int[] column = overView && villageShown() && village != null ? village.columnAt(mouseX, mouseY) : null;
        // Over a map still being drawn, or over the building on its own: picked, placed where the map puts it.
        boolean mapWaiting = overView && (!villageShown() || village == null || !village.ready());
        dropColumn = column;
        dropPlace = !spotKnown || column != null || mapWaiting;
        if (!dropMap) openDropMap();
        // A drop still being copied is overtaken; its files still land in the folder and the list.
        if (dropping != null) dropping.cancel(false);
        dropping = DroppedBlueprintFiles.copyIn(Minecraft.getInstance().gameDirectory.toPath(), paths);
        showDropMessage(paths.size() == 1 ? "Adding the file..." : "Adding " + paths.size() + " files...", MUTED);
    }

    /** The dropped files are copied: list them, pick the first if it was let go over the map, say what happened. */
    private void tickDrop() {
        if (dropping == null || !dropping.isDone()) return;
        DroppedBlueprintFiles.Result result = dropping.isCompletedExceptionally() ? null : dropping.join();
        dropping = null;
        if (result == null) {
            showDropMessage("The files could not be added. Check that the game folder can be written to.", DENIED);
            return;
        }
        files.refresh();
        List<Path> added = result.files();
        String problem = !result.problems().isEmpty() ? result.problems().get(0)
                : result.notBuildings() > 0 ? notBuildingLine(result.notBuildings(), added.isEmpty()) : null;
        if (added.isEmpty()) {
            showDropMessage(problem != null ? problem : "Nothing was added.", DENIED);
            return;
        }
        if (dropPlace) {
            selectWhenListed = added.get(0);
            listedColumn = dropColumn;
        }
        String where = menu.snapshot().border() == null ? " The first one is picked." : " The first one is on the map.";
        String line = added.size() == 1
                ? dropPlace ? null : "Added to the list on the left. Drag it onto the map to place it."
                : "Added " + added.size() + " files to the list on the left." + (dropPlace ? where : "");
        if (problem != null) {
            showDropMessage(line == null ? problem : line + " " + problem, GOLD);
        } else if (line != null) {
            showDropMessage(line, ACCENT);
        } else {
            dropMessage = null;
        }
    }

    private static String notBuildingLine(int count, boolean nothingElse) {
        if (nothingElse) {
            return (count == 1 ? "That isn't a building file." : "Those aren't building files.")
                    + " The Builder reads .schem, .litematic and .nbt files.";
        }
        return count == 1 ? "1 file was left out: it isn't a building file."
                : count + " files were left out: they aren't building files.";
    }

    private void showDropMessage(String text, int color) {
        dropMessage = text;
        dropMessageColor = color;
        dropMessageUntil = System.currentTimeMillis() + 8000;
    }

    /** A strip file let go after a drag: its ghost stands on the land under the mouse. */
    private void dropEntry(BlueprintFiles.Entry entry, double mouseX, double mouseY) {
        if (!villageShown()) {
            // No border, no map: it is just picked.
            select(entry);
            return;
        }
        int[] column = village != null ? village.columnAt(mouseX, mouseY) : null;
        if (column == null) {
            if (village != null && !village.ready() && village.contains(mouseX, mouseY)) {
                // The map is still being drawn: picked, and it stands where the map puts it.
                select(entry);
                return;
            }
            showDropMessage("Let go over the land to put it there.", GOLD);
            return;
        }
        select(entry);
        village().placeOnColumn(column[0], column[1]);
    }

    /** The folder Open folder opens: the copies' folder on the Copied tab, else "schematics", where downloads go. */
    private Path listFolder() {
        Path game = Minecraft.getInstance().gameDirectory.toPath();
        return !dropMap && segment == Segment.COPIED ? BlueprintFiles.copiedFolder(game) : game.resolve("schematics");
    }

    private void openFolder() {
        Path folder = listFolder();
        try {
            Files.createDirectories(folder);
        } catch (Exception e) {
            return;
        }
        Util.getPlatform().openFile(folder.toFile());
    }

    private void receive(BuilderDeskMenu.CopyInbox.Finished done) {
        BuilderDeskPayloads.CopyResult result = done.header();
        if (result.error() != null) {
            copyMessage = result.error();
            copyMessageColor = DENIED;
            // A side change that went too far: the sides go back to the copy still shown.
            if (copyHeader != null) System.arraycopy(copyHeader.grow(), 0, grow, 0, grow.length);
            return;
        }
        boolean first = copyHeader == null;
        copyHeader = result;
        copyBytes = done.bytes();
        System.arraycopy(result.grow(), 0, grow, 0, grow.length);
        copyMessage = !result.cut().isEmpty() ? result.cut() : result.noFloor() ? NO_FLOOR_MESSAGE : null;
        copyMessageColor = GOLD;
        String label = "Building".equals(result.label()) ? "Copied building" : result.label();
        if (typedName.isBlank() || typedName.equals(autoName)) {
            typedName = label;
            if (nameBox != null) nameBox.setValue(label);
        }
        autoName = label;
        // The copy on its own first: that is what will be saved.
        if (first) villageMode = false;
        BlueprintFiles.Entry entry = new BlueprintFiles.Entry(Path.of("copy-" + result.requestId()), label, "Copy",
                copyBytes.length, BlueprintFiles.Source.COPIED);
        selected = entry;
        loaded = null;
        if (loading != null) loading.cancel(false);
        byte[] bytes = copyBytes;
        loading = CompletableFuture.supplyAsync(() -> loadCopy(entry, bytes), Util.backgroundExecutor());
        showCopyBox();
    }

    /** Background thread: the copy's bytes read like any file, for the preview and the details. */
    private static Loaded loadCopy(BlueprintFiles.Entry entry, byte[] bytes) {
        try {
            Blueprint blueprint = BlueprintReader.read(bytes, entry.name());
            return new Loaded(entry, blueprint, BlueprintMaterials.count(blueprint),
                    BlueprintRoomGuess.guess(blueprint), BlueprintPreviewView.prepare(blueprint), null);
        } catch (BlueprintException e) {
            return new Loaded(entry, null, null, null, null, e.getMessage());
        } catch (RuntimeException e) {
            return new Loaded(entry, null, null, null, null, "The copy can't be read.");
        }
    }

    private void showCopyBox() {
        if (village == null) return;
        BuilderDeskPayloads.CopyResult result = copyHeader;
        if (result == null) {
            village.setCopyBox(null, null, -1);
            return;
        }
        BlockPos max = result.origin().offset(result.sizeX() - 1, result.sizeY() - 1, result.sizeZ() - 1);
        village.setCopyBox(result.origin(), max, side);
    }

    private void act(CopyControl control, int button) {
        switch (control) {
            case SIDE -> {
                side = Math.floorMod(side + (button == 1 ? -1 : 1), BuildingCopier.SIDES);
                showCopyBox();
            }
            case LESS -> {
                grow[side]--;
                requestCopy();
            }
            case MORE -> {
                grow[side]++;
                requestCopy();
            }
            case RESET -> {
                Arrays.fill(grow, 0);
                requestCopy();
            }
        }
    }

    private boolean canSave() {
        return copyMode && copyBytes != null && saving == null && pendingRequest < 0 && !resend;
    }

    private void save() {
        if (!canSave()) return;
        saving = CopiedBlueprintSaver.save(Minecraft.getInstance().gameDirectory.toPath(), typedName, copyBytes);
        copyMessage = "Saving...";
        copyMessageColor = MUTED;
    }

    /** "Kitchen", "Tower (2)": a saved file's name as the list shows it. */
    private static String fileBaseName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** "WatchtowerBuilding" -> "Watchtower", "ArcheryRangeBuilding" -> "Archery range". */
    private static String typeName(String simpleClass) {
        String base = simpleClass.endsWith("Building") ? simpleClass.substring(0, simpleClass.length() - 8)
                : simpleClass;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                out.append(' ').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.isEmpty() ? "Building" : out.toString();
    }

    // =========================================================================
    // Layout
    // =========================================================================

    /** In the full view there is no list: the view and details start at the left edge. */
    private int listRight() {
        return dropMap ? left + STRIP_W : fullView ? left - GAP : folded ? left + FOLDED_W : left + LIST_W;
    }
    // The drop map's strip: Open folder on top, the files under it.
    private int folderY() { return top + 6; }
    private int stripTop() { return top + STRIP_LIST_Y; }
    private int stripBottom() { return bottom - 6; }
    private int closeW() { return font.width(CLOSE_LABEL) + 14; }
    private int closeX() { return worldX() - 4 - closeW(); }
    private int foldX() { return folded ? left + (FOLDED_W - FOLD_BUTTON) / 2 : left + LIST_W - 6 - FOLD_BUTTON; }
    private int foldY() { return top + SEGMENT_Y; }
    // Bottom right corner of the view: the top row is full with the switch and Reset view.
    // Layers: a small column on the view's right edge, half way down: up, the height, down.
    private static final int LAYER_W = 34;
    private int layerX() { return viewRight() - 4 - LAYER_W; }
    private int layerUpY() { return (viewTop() + viewBottom()) / 2 - 30; }
    private int layerLabelY() { return (viewTop() + viewBottom()) / 2 - 8; }
    private int layerDownY() { return (viewTop() + viewBottom()) / 2 + 10; }

    private int fullX() { return viewRight() - 4 - FULL_BUTTON; }
    private int fullY() { return viewBottom() - 4 - FULL_BUTTON; }
    private int listTop() { return top + LIST_Y; }
    // Open folder stays under the list, out of the scrolling; not while copying (the list is buildings then).
    private boolean listFolderShown() { return !copyMode; }
    private int listFolderY() { return bottom - 6 - FOLDER_H; }
    private int listBottom() { return listFolderShown() ? listFolderY() - 4 : bottom - 6; }
    private int viewLeft() { return listRight() + GAP + 1; }
    private int viewTop() { return top + 1; }
    private int viewRight() { return right - 1; }
    private int viewBottom() { return bottom - DETAILS_H - GAP - 1; }
    private int detailsTop() { return bottom - DETAILS_H; }
    private int resetWidth() { return 26 + font.width(RESET_LABEL); }
    private int resetX() { return viewRight() - 4 - resetWidth(); }
    private int resetY() { return viewTop() + 4; }
    private int buildX() { return right - 8 - BUILD_W; }
    // "Give my blocks": over the Build button, ending at its right edge.
    private int tickX() { return right - 8 - StorageUi.tickBoxWidth(font, BuilderPlanScreen.GIVE_LABEL); }
    private int tickY() { return detailsTop() + 7; }
    private int buildY() { return detailsTop() + (DETAILS_H - 20) / 2; }
    private int worldW() { return font.width(WORLD_LABEL) + 14; }
    private int worldX() { return buildX() - 4 - worldW(); }
    private int segmentWidth() { return (LIST_W - 12 - FOLD_BUTTON - 2 * 2) / 2; }
    private int segmentX(Segment s) { return left + 6 + s.ordinal() * (segmentWidth() + 2); }
    private int switchX(boolean villageSide) { return viewLeft() + 4 + (villageSide ? 0 : SWITCH_W + 2); }
    private int switchY() { return viewTop() + 4; }
    private int controlW(Control control) { return font.width(control.label) + 14; }
    private int controlY() { return viewBottom() - 4 - CONTROL_H; }

    private int copyControlY() { return detailsTop() + DETAILS_H - 6 - CONTROL_H; }
    private int nameX() { return listRight() + GAP + 8 + font.width(NAME_LABEL) + 6; }
    private int nameWidth() { return Math.max(60, Math.min(200, buildX() - 8 - nameX())); }

    private String copyControlLabel(CopyControl control) {
        return switch (control) {
            case SIDE -> SIDE_NAMES[side] + (grow[side] == 0 ? "" : grow[side] > 0 ? " +" + grow[side] : " " + grow[side]);
            case LESS -> "Less";
            case MORE -> "More";
            case RESET -> "Reset sides";
        };
    }

    private int copyControlW(CopyControl control) {
        // The side button keeps one width as its label changes.
        return control == CopyControl.SIDE ? font.width("Bottom +64") + 14 : font.width(copyControlLabel(control)) + 14;
    }

    private int copyControlX(CopyControl control) {
        int x = listRight() + GAP + 8;
        for (CopyControl other : CopyControl.values()) {
            if (other == control) break;
            x += copyControlW(other) + 3;
        }
        return x;
    }

    private int controlX(Control control) {
        int x = viewLeft() + 4;
        for (Control other : Control.values()) {
            if (other == control) break;
            x += controlW(other) + 3;
        }
        return x;
    }

    /** Rebuilds the list's lines for the current segment and search. */
    private void buildLines() {
        lines.clear();
        if (copyMode) {
            buildCopyLines();
            return;
        }
        if (!files.scanned()) {
            lines.add(text("Looking for files..."));
            return;
        }
        List<BlueprintFiles.Entry> all = files.entries(
                segment == Segment.COPIED ? BlueprintFiles.Source.COPIED : BlueprintFiles.Source.FILES);
        if (all.isEmpty()) {
            if (segment == Segment.COPIED) {
                lines.add(text("Nothing here yet. Click \"+ Copy a building\" below to copy one of your village's "
                        + "buildings. Your copies show up here."));
            } else {
                lines.add(text("No building files yet. Drag .schem, .litematic or .nbt files onto this window, "
                        + "or put them in the \"schematics\" folder of your Minecraft folder."));
            }
            lines.add(new Line(Kind.DROP, null, DROP_LABEL, ROW_H));
            lines.add(new Line(Kind.COPY, null, "+ Copy a building", ROW_H));
            return;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<BlueprintFiles.Entry> shown = new ArrayList<>();
        List<BlueprintFiles.Entry> needed = new ArrayList<>();
        for (BlueprintFiles.Entry entry : all) {
            if (!matches(entry, q)) continue;
            shown.add(entry);
            if (needed(entry)) needed.add(entry);
        }
        if (!needed.isEmpty()) {
            lines.add(new Line(Kind.GROUP, null, "Village needs", GROUP_H));
            for (BlueprintFiles.Entry entry : needed) lines.add(new Line(Kind.FILE, entry, "", ROW_H));
        }
        lines.add(new Line(Kind.GROUP, null, "All", GROUP_H));
        if (shown.isEmpty()) lines.add(text("Nothing matches the search."));
        for (BlueprintFiles.Entry entry : shown) lines.add(new Line(Kind.FILE, entry, "", ROW_H));
        lines.add(new Line(Kind.DROP, null, DROP_LABEL, ROW_H));
        lines.add(new Line(Kind.COPY, null, "+ Copy a building", ROW_H));
    }

    /** The drop map's strip: every file, Files then Copied, no search. */
    private void buildStrip() {
        strip.clear();
        if (!files.scanned()) {
            strip.add(stripText("Looking for files..."));
            return;
        }
        for (Segment s : Segment.values()) {
            List<BlueprintFiles.Entry> all = files.entries(
                    s == Segment.COPIED ? BlueprintFiles.Source.COPIED : BlueprintFiles.Source.FILES);
            strip.add(new Line(Kind.GROUP, null, s.label, GROUP_H));
            if (all.isEmpty()) {
                strip.add(stripText(s == Segment.COPIED ? "No copies yet." : "No files yet. Drag some onto this window."));
            }
            for (BlueprintFiles.Entry entry : all) strip.add(new Line(Kind.FILE, entry, "", ROW_H));
        }
    }

    private Line stripText(String value) {
        int count = Math.max(1, font.split(Component.literal(value), STRIP_W - 16).size());
        return new Line(Kind.TEXT, null, value, count * 10 + 6);
    }

    /** Copy mode: the village's buildings to pick from, then Cancel. */
    private void buildCopyLines() {
        lines.add(new Line(Kind.GROUP, null, "Copy a building", GROUP_H));
        lines.add(text(menu.snapshot().border() != null
                ? "Click a building in this list or on the map to copy it."
                : "Click a building in this list to copy it."));
        if (menu.snapshot().border() != null) lines.add(new Line(Kind.WORLD, null, "Copy in the world", ROW_H));
        String q = query.trim().toLowerCase(Locale.ROOT);
        int shown = 0, copyable = 0;
        for (BuilderDeskMenu.BuildingRow row : menu.snapshot().buildings()) {
            // Mines and watchtowers stay on the map but are never copied.
            if (!BuildingCopier.copyable(row.simpleClass())) continue;
            copyable++;
            if (!q.isEmpty() && !row.name().toLowerCase(Locale.ROOT).contains(q)
                    && !typeName(row.simpleClass()).toLowerCase(Locale.ROOT).contains(q)) continue;
            lines.add(new Line(Kind.BUILDING, null, row.name(), ROW_H, row));
            shown++;
        }
        if (shown == 0) {
            lines.add(text(menu.snapshot().buildings().isEmpty() ? "No buildings yet. Only buildings with a token show here."
                    : copyable == 0 ? "No buildings to copy yet. Mines and watchtowers can't be copied."
                    : "Nothing matches the search."));
        }
        lines.add(new Line(Kind.CANCEL, null, "Cancel", ROW_H));
    }

    private Line text(String value) {
        int count = Math.max(1, font.split(Component.literal(value), LIST_W - 16).size());
        return new Line(Kind.TEXT, null, value, count * 10 + 6);
    }

    private boolean matches(BlueprintFiles.Entry entry, String q) {
        if (q.isEmpty() || entry.name().toLowerCase(Locale.ROOT).contains(q)) return true;
        BlueprintFiles.Summary summary = files.summary(entry);
        return summary != null && summary.guess() != null
                && BlueprintRoomGuess.displayName(summary.guess()).toLowerCase(Locale.ROOT).contains(q);
    }

    /** It looks like a building type the village has none of yet. */
    private boolean needed(BlueprintFiles.Entry entry) {
        if (menu.snapshot().villageId() == null) return false;
        BlueprintFiles.Summary summary = files.summary(entry);
        if (summary == null || summary.guess() == null) return false;
        String type = BlueprintRoomGuess.buildingClassName(summary.guess());
        return type != null && !menu.snapshot().buildingTypes().contains(type);
    }

    private static int contentHeight(List<Line> list) {
        int total = 0;
        for (Line line : list) total += line.height() + (line.kind() == Kind.GROUP ? 0 : ROW_GAP);
        return total;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight(lines) - (listBottom() - listTop()));
    }

    private int stripMaxScroll() {
        return Math.max(0, contentHeight(strip) - (stripBottom() - stripTop()));
    }

    /** The strip line under the mouse, or null. */
    @Nullable
    private Line stripLineAt(double mouseX, double mouseY) {
        if (!dropMap || !inside(mouseX, mouseY, left + 6, stripTop(), STRIP_W - 12, stripBottom() - stripTop())) {
            return null;
        }
        int y = stripTop() - stripScroll;
        for (Line line : strip) {
            if (mouseY >= y && mouseY < y + line.height()) return line;
            y += line.height() + (line.kind() == Kind.GROUP ? 0 : ROW_GAP);
        }
        return null;
    }

    /** The list line under the mouse, or null. */
    @Nullable
    private Line lineAt(double mouseX, double mouseY) {
        if (fullView || folded) return null;
        if (!inside(mouseX, mouseY, left + 6, listTop(), LIST_W - 12, listBottom() - listTop())) return null;
        int y = listTop() - scroll;
        for (Line line : lines) {
            if (mouseY >= y && mouseY < y + line.height()) return line;
            y += line.height() + (line.kind() == Kind.GROUP ? 0 : ROW_GAP);
        }
        return null;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    void render(GuiGraphics graphics, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        buildLines();
        scroll = Mth.clamp(scroll, 0, maxScroll());
        if (dropMap) {
            buildStrip();
            stripScroll = Mth.clamp(stripScroll, 0, stripMaxScroll());
            renderStrip(graphics, mouseX, mouseY);
        } else if (!fullView) {
            renderList(graphics, mouseX, mouseY);
        }
        renderView(graphics, mouseX, mouseY);
        renderDetails(graphics, mouseX, mouseY);
        if (dragEntry != null && dragDistance >= DRAG_SLOP) renderDragged(graphics, dragEntry, mouseX, mouseY);
    }

    /** The drop map's strip: Open folder, then the files to drag onto the map. */
    private void renderStrip(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(left, top, listRight(), bottom, PANEL_DARK);
        int x = left + 6, width = STRIP_W - 12;
        renderFolderButton(graphics, x, folderY(), width, mouseX, mouseY);
        boolean bar = stripMaxScroll() > 0;
        if (bar) width -= 6;
        graphics.enableScissor(left, stripTop(), listRight(), stripBottom());
        int y = stripTop() - stripScroll;
        for (Line line : strip) {
            if (y + line.height() >= stripTop() && y < stripBottom()) {
                boolean hover = dragEntry == null && inside(mouseX, mouseY, x, Math.max(y, stripTop()), width,
                        Math.min(y + line.height(), stripBottom()) - Math.max(y, stripTop()));
                switch (line.kind()) {
                    case GROUP -> graphics.drawString(font, line.text(), x + 2, y + 3, ACCENT, false);
                    case TEXT -> {
                        int ty = y + 3;
                        for (FormattedCharSequence part : font.split(Component.literal(line.text()), STRIP_W - 16)) {
                            graphics.drawString(font, part, x + 2, ty, MUTED, false);
                            ty += 10;
                        }
                    }
                    case FILE -> renderFileRow(graphics, line.entry(), x, y, width, hover);
                    default -> { }
                }
            }
            y += line.height() + (line.kind() == Kind.GROUP ? 0 : ROW_GAP);
        }
        graphics.disableScissor();
        if (bar) {
            StorageUi.scrollbar(graphics, listRight() - 8, stripTop(), stripBottom() - stripTop(), stripScroll,
                    stripMaxScroll(), stripBottom() - stripTop());
        }
    }

    /** The small picture that follows the mouse while a strip file is dragged. */
    private void renderDragged(GuiGraphics graphics, BlueprintFiles.Entry entry, int mouseX, int mouseY) {
        String name = StorageUi.trim(font, entry.name(), 110);
        int x = mouseX + 8, y = mouseY + 8, w = 24 + font.width(name), h = 20;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);
        graphics.fill(x, y, x + w, y + h, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, StorageUi.ROW_SELECTED);
        graphics.renderFakeItem(new net.minecraft.world.item.ItemStack(Items.PAPER), x + 2, y + 2);
        graphics.drawString(font, name, x + 20, y + 6, TEXT, false);
        graphics.pose().popPose();
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(left, top, listRight(), bottom, PANEL_DARK);
        StorageUi.button(graphics, font, foldX(), foldY(), FOLD_BUTTON, FOLD_BUTTON, folded ? "»" : "«",
                inside(mouseX, mouseY, foldX(), foldY(), FOLD_BUTTON, FOLD_BUTTON), true);
        if (folded) return;
        for (Segment s : Segment.values()) {
            int sx = segmentX(s);
            StorageUi.segment(graphics, font, sx, top + SEGMENT_Y, segmentWidth(), 16, s.label, segment == s,
                    inside(mouseX, mouseY, sx, top + SEGMENT_Y, segmentWidth(), 16));
        }
        if (search != null) {
            search.setPosition(left + 6, top + SEARCH_Y);
            search.setWidth(LIST_W - 12);
        }

        int x = left + 6, width = LIST_W - 12;
        boolean bar = maxScroll() > 0;
        if (bar) width -= 6;
        graphics.enableScissor(left, listTop(), listRight(), listBottom());
        int y = listTop() - scroll;
        for (Line line : lines) {
            if (y + line.height() >= listTop() && y < listBottom()) {
                boolean hover = inside(mouseX, mouseY, x, Math.max(y, listTop()), width,
                        Math.min(y + line.height(), listBottom()) - Math.max(y, listTop()));
                switch (line.kind()) {
                    case GROUP -> graphics.drawString(font, line.text(), x + 2, y + 3, ACCENT, false);
                    case TEXT -> {
                        int ty = y + 3;
                        for (FormattedCharSequence part : font.split(Component.literal(line.text()), LIST_W - 16)) {
                            graphics.drawString(font, part, x + 2, ty, MUTED, false);
                            ty += 10;
                        }
                    }
                    case FILE -> renderFileRow(graphics, line.entry(), x, y, width, hover);
                    case COPY, CANCEL, WORLD, DROP -> renderCopyRow(graphics, line.text(), x, y, width);
                    case BUILDING -> renderBuildingRow(graphics, line.building(), x, y, width, hover);
                }
            }
            y += line.height() + (line.kind() == Kind.GROUP ? 0 : ROW_GAP);
        }
        graphics.disableScissor();
        if (bar) {
            StorageUi.scrollbar(graphics, listRight() - 8, listTop(), listBottom() - listTop(), scroll, maxScroll(),
                    listBottom() - listTop());
        }
        if (listFolderShown()) renderFolderButton(graphics, left + 6, listFolderY(), LIST_W - 12, mouseX, mouseY);
    }

    /** "Open folder" with a small drawn folder in front (Minecraft has no folder item). */
    private void renderFolderButton(GuiGraphics graphics, int x, int y, int width, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, width, FOLDER_H);
        graphics.fill(x, y, x + width, y + FOLDER_H, hover ? StorageUi.BUTTON_HOVER_BORDER : PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + FOLDER_H - 1, hover ? StorageUi.ROW_HOVER : StorageUi.ROW);
        String label = StorageUi.trim(font, FOLDER_LABEL, width - 26);
        int ix = x + (width - 20 - font.width(label)) / 2, iy = y + 2;
        // The back with its tab, then the front a little lower and lighter, with a bright top edge.
        graphics.fill(ix + 1, iy + 3, ix + 7, iy + 5, FOLDER_BACK);
        graphics.fill(ix + 1, iy + 5, ix + 15, iy + 14, FOLDER_BACK);
        graphics.fill(ix + 1, iy + 7, ix + 15, iy + 14, FOLDER_FRONT);
        graphics.fill(ix + 1, iy + 7, ix + 15, iy + 8, FOLDER_EDGE);
        graphics.drawString(font, label, ix + 20, y + 6, TEXT, false);
    }

    private void renderFileRow(GuiGraphics graphics, BlueprintFiles.Entry entry, int x, int y, int width,
                               boolean hover) {
        StorageUi.row(graphics, x, y, width, ROW_H, entry.equals(selected), hover);
        graphics.drawString(font, StorageUi.trim(font, entry.name(), width - 8), x + 4, y + 3, TEXT, false);
        BlueprintFiles.Summary summary = files.summary(entry);
        String detail;
        int color = MUTED;
        if (summary == null) {
            detail = entry.tooBig() ? "Too big" : files.willRead(entry) ? "Reading..." : entry.folder();
        } else if (summary.error() != null) {
            detail = entry.tooBig() ? "Too big · " + megabytes(entry.bytes()) : "Can't read";
            color = DENIED;
        } else {
            detail = BlueprintRoomGuess.displayName(summary.guess()) + " · " + summary.sizeX() + "×"
                    + summary.sizeZ();
        }
        graphics.drawString(font, StorageUi.trim(font, detail, width - 8), x + 4, y + 13, color, false);
    }

    private void renderBuildingRow(GuiGraphics graphics, @Nullable BuilderDeskMenu.BuildingRow row, int x, int y,
                                   int width, boolean hover) {
        if (row == null) return;
        StorageUi.row(graphics, x, y, width, ROW_H, row.id().equals(copyBuilding), hover);
        graphics.drawString(font, StorageUi.trim(font, row.name(), width - 8), x + 4, y + 3, TEXT, false);
        int sx = row.shellMax().getX() - row.shellMin().getX() + 1, sz = row.shellMax().getZ() - row.shellMin().getZ() + 1;
        String detail = typeName(row.simpleClass()) + " · room " + sx + "×" + sz;
        graphics.drawString(font, StorageUi.trim(font, detail, width - 8), x + 4, y + 13, MUTED, false);
    }

    /** The dashed rows: "Place on the map" and "+ Copy a building" in the lists, "Cancel" while copying. */
    private void renderCopyRow(GuiGraphics graphics, String label, int x, int y, int width) {
        for (int dx = 0; dx < width; dx += 5) {
            int end = Math.min(x + dx + 3, x + width);
            graphics.fill(x + dx, y, end, y + 1, PANEL_BORDER);
            graphics.fill(x + dx, y + ROW_H - 1, end, y + ROW_H, PANEL_BORDER);
        }
        for (int dy = 0; dy < ROW_H; dy += 5) {
            int end = Math.min(y + dy + 3, y + ROW_H);
            graphics.fill(x, y + dy, x + 1, end, PANEL_BORDER);
            graphics.fill(x + width - 1, y + dy, x + width, end, PANEL_BORDER);
        }
        graphics.drawCenteredString(font, label, x + width / 2, y + (ROW_H - 8) / 2, MUTED);
    }

    private void renderView(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean villageView = villageShown();
        if (villageView) {
            VillageModelView model = village();
            model.setViewport(viewLeft(), viewTop(), viewRight(), viewBottom());
            model.render(graphics);
        } else {
            preview.setViewport(viewLeft(), viewTop(), viewRight(), viewBottom());
            preview.render(graphics);
        }
        // Overlay above the model's depth band, so it never hides behind blocks.
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        if (villageView) {
            renderVillageOverlay(graphics, village(), mouseX, mouseY);
        } else {
            renderPreviewOverlay(graphics, mouseX, mouseY);
        }
        if (switchVisible()) {
            StorageUi.segment(graphics, font, switchX(true), switchY(), SWITCH_W, CONTROL_H, "Village", villageMode,
                    inside(mouseX, mouseY, switchX(true), switchY(), SWITCH_W, CONTROL_H));
            StorageUi.segment(graphics, font, switchX(false), switchY(), SWITCH_W, CONTROL_H, "Blueprint",
                    !villageMode, inside(mouseX, mouseY, switchX(false), switchY(), SWITCH_W, CONTROL_H));
        }
        if (dropMap) renderDropLine(graphics);
        renderFullButton(graphics, mouseX, mouseY);
        graphics.pose().popPose();
    }

    /** Over the drop map, where the switch would be: the help line, or for a while what a drop did. */
    private void renderDropLine(GuiGraphics graphics) {
        boolean note = dropMessage != null && System.currentTimeMillis() < dropMessageUntil;
        String text = note ? dropMessage : menu.snapshot().border() == null ? NO_MAP_HELP : DROP_HELP;
        int color = note ? dropMessageColor : TEXT;
        int width = resetX() - viewLeft() - 12, y = switchY() + 2;
        List<FormattedCharSequence> parts = font.split(Component.literal(text), Math.max(40, width));
        for (int i = 0; i < Math.min(2, parts.size()); i++) {
            graphics.drawString(font, parts.get(i), viewLeft() + 5, y + i * 10, color, false);
        }
    }

    /** A square button with only an icon: an eye of ender to open the full view, a barrier to close it. */
    private void renderFullButton(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = fullX(), y = fullY();
        boolean hover = inside(mouseX, mouseY, x, y, FULL_BUTTON, FULL_BUTTON);
        graphics.fill(x, y, x + FULL_BUTTON, y + FULL_BUTTON, hover ? StorageUi.BUTTON_HOVER_BORDER : PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + FULL_BUTTON - 1, y + FULL_BUTTON - 1,
                fullView ? StorageUi.ROW_SELECTED : hover ? StorageUi.ROW_HOVER : StorageUi.ROW);
        graphics.renderFakeItem(new net.minecraft.world.item.ItemStack(fullView ? Items.BARRIER : Items.ENDER_EYE),
                x + 2, y + 2);
    }

    private void renderVillageOverlay(GuiGraphics graphics, VillageModelView model, int mouseX, int mouseY) {
        int cx = (viewLeft() + viewRight()) / 2, cy = (viewTop() + viewBottom()) / 2;
        if (!model.ready()) {
            graphics.drawCenteredString(font, String.format(Locale.ROOT, "Drawing the village... %d%%",
                    (int) (model.progress() * 100)), cx, cy - 4, MUTED);
            return;
        }
        model.renderLabels(graphics, font);
        BuilderDeskScreen.headerButton(graphics, font, resetX(), resetY(), resetWidth(), Items.COMPASS,
                RESET_LABEL, false, inside(mouseX, mouseY, resetX(), resetY(), resetWidth(), 20));
        renderLayers(graphics, model, mouseX, mouseY);
        if (copyMode) {
            String hint = copyHeader != null ? "The green box is what gets copied · click another building to copy that one"
                    : "Click a building to copy it";
            graphics.drawString(font, StorageUi.trim(font, hint, fullX() - viewLeft() - 10), viewLeft() + 5,
                    viewBottom() - 12, MUTED, false);
            drawMapStatus(graphics, model.layerNote());
            return;
        }
        for (Control control : Control.values()) {
            int x = controlX(control), w = controlW(control);
            StorageUi.button(graphics, font, x, controlY(), w, CONTROL_H, control.label,
                    inside(mouseX, mouseY, x, controlY(), w, CONTROL_H), model.shape() != null);
        }
        String hint = "Click the map to pick the spot";
        int hintX = fullX() - 6 - font.width(hint);
        if (hintX > controlX(Control.DOWN) + controlW(Control.DOWN) + 6) {
            graphics.drawString(font, hint, hintX, controlY() + 4, MUTED, false);
        }
        BlueprintGhost.Shape shape = model.shape();
        String status = model.busy() ? "Rotating..."
                : shape != null && !shape.realBlocks() ? "Too big to show every block, so it shows as boxes"
                : model.detailProgress() >= 0 ? String.format(Locale.ROOT, "Loading the blocks... %d%%",
                        (int) (model.detailProgress() * 100))
                : model.waitingPieces() > 0 ? "Parts of the village are too far away to show"
                : model.layerNote();
        drawMapStatus(graphics, status);
    }

    /** The map's one notice line, in the top-left corner under the Village / Blueprint switch. */
    private void drawMapStatus(GuiGraphics graphics, @Nullable String status) {
        if (status == null) return;
        graphics.drawString(font, StorageUi.trim(font, status, resetX() - viewLeft() - 12), viewLeft() + 5,
                switchY() + CONTROL_H + 5, MUTED, false);
    }

    private void renderLayers(GuiGraphics graphics, VillageModelView model, int mouseX, int mouseY) {
        int x = layerX();
        StorageUi.button(graphics, font, x, layerUpY(), LAYER_W, 18, "\u25B2",
                inside(mouseX, mouseY, x, layerUpY(), LAYER_W, 18), model.layerCut());
        boolean cut = model.layerCut();
        StorageUi.button(graphics, font, x, layerLabelY(), LAYER_W, 16, model.layerLabel(),
                inside(mouseX, mouseY, x, layerLabelY(), LAYER_W, 16), true);
        if (cut) graphics.fill(x + 1, layerLabelY() + 14, x + LAYER_W - 1, layerLabelY() + 15, ACCENT);
        StorageUi.button(graphics, font, x, layerDownY(), LAYER_W, 18, "\u25BC",
                inside(mouseX, mouseY, x, layerDownY(), LAYER_W, 18), true);
    }

    /** A click on the Layers column; true when it took the click. */
    private boolean clickLayers(VillageModelView model, double mouseX, double mouseY) {
        int x = layerX();
        boolean fine = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        if (inside(mouseX, mouseY, x, layerUpY(), LAYER_W, 18)) {
            model.layerStep(1, fine);
            return true;
        }
        if (inside(mouseX, mouseY, x, layerDownY(), LAYER_W, 18)) {
            model.layerStep(-1, fine);
            return true;
        }
        if (inside(mouseX, mouseY, x, layerLabelY(), LAYER_W, 16)) {
            model.layerReset();
            return true;
        }
        return false;
    }

    private void renderPreviewOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        int cx = (viewLeft() + viewRight()) / 2, cy = (viewTop() + viewBottom()) / 2;
        BlueprintPreviewView.Prepared shown = preview.shown();
        if (selected == null) {
            graphics.drawCenteredString(font, copyMode ? "Pick a building on the left." : "Pick a building on the left to see it here.",
                    cx, cy - 4, MUTED);
        } else if (loading != null) {
            graphics.drawCenteredString(font, copyMode ? "Reading the copy..." : "Reading the file...", cx, cy - 4,
                    MUTED);
        } else if (shown == null && loaded != null && loaded.error() != null) {
            graphics.drawCenteredString(font, "No preview", cx, cy - 4, MUTED);
        }
        if (shown != null) {
            BuilderDeskScreen.headerButton(graphics, font, resetX(), resetY(), resetWidth(), Items.COMPASS,
                    RESET_LABEL, false, inside(mouseX, mouseY, resetX(), resetY(), resetWidth(), 20));
            graphics.drawString(font, "Drag to spin it · scroll to zoom", viewLeft() + 5, viewBottom() - 12, MUTED,
                    false);
            preview.renderLayers(graphics, font, mouseX, mouseY);
            String note = preview.layerNote();
            if (note != null || shown.capped()) {
                // Under the Village / Blueprint switch when it shows; a layer being drawn says so first.
                int y = switchVisible() || dropMap ? switchY() + CONTROL_H + 5 : viewTop() + 6;
                graphics.drawString(font, note != null ? note : String.format(Locale.ROOT,
                        "Too big: showing %,d of %,d blocks", shown.cells().length, shown.shellCount()),
                        viewLeft() + 5, y, MUTED, false);
            }
        }
    }

    private void renderDetails(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = listRight() + GAP, y = detailsTop();
        graphics.fill(x, y, right, bottom, PANEL_DARK);
        if (copyMode) {
            renderCopyDetails(graphics, mouseX, mouseY);
            return;
        }
        int textX = x + 8, textW = (dropMap ? closeX() : worldX()) - 8 - textX;
        boolean buildHover = inside(mouseX, mouseY, buildX(), buildY(), BUILD_W, 20);
        StorageUi.button(graphics, font, buildX(), buildY(), BUILD_W, 20, BUILD_LABEL, buildHover, canOrder());
        StorageUi.button(graphics, font, worldX(), buildY(), worldW(), 20, WORLD_LABEL,
                inside(mouseX, mouseY, worldX(), buildY(), worldW(), 20), canTakePlan());
        if (dropMap) {
            StorageUi.button(graphics, font, closeX(), buildY(), closeW(), 20, CLOSE_LABEL,
                    inside(mouseX, mouseY, closeX(), buildY(), closeW(), 20), true);
        }
        if (selected == null) {
            // A short how-to while nothing is picked.
            String[] steps = dropMap
                    ? new String[] {"1. Drag a building from the left onto the map.",
                            "2. Turn it with Rotate and Flip. Click the map to move it.",
                            "3. Press Build. The Builder does the rest."}
                    : new String[] {"1. Pick a building on the left or in Themes.", "2. Click the map where it should go.",
                            "3. Press Build. The Builder does the rest."};
            for (int i = 0; i < steps.length; i++) {
                graphics.drawString(font, StorageUi.trim(font, steps[i], textW), textX, y + 6 + i * 12,
                        i == 0 ? TEXT : MUTED, false);
            }
            if (savedMessage != null) {
                graphics.drawString(font, StorageUi.trim(font, savedMessage, textW), textX, y + 44, ACCENT, false);
            }
            return;
        }
        Blueprint blueprint = loaded == null ? null : loaded.blueprint();
        String name = blueprint != null ? blueprint.name() : selected.name();
        String size = blueprint == null ? "" : "  " + blueprint.sizeX() + "×" + blueprint.sizeY() + "×"
                + blueprint.sizeZ();
        String shownName = StorageUi.trim(font, name, textW - font.width(size));
        graphics.drawString(font, shownName, textX, y + 6, TEXT, false);
        graphics.drawString(font, size, textX + font.width(shownName), y + 6, MUTED, false);
        if (loaded == null) {
            graphics.drawString(font, "Reading the file...", textX, y + 18, MUTED, false);
            return;
        }
        if (loaded.error() != null) {
            int ly = y + 18;
            for (FormattedCharSequence part : font.split(Component.literal(loaded.error()), textW)) {
                if (ly > y + DETAILS_H - 10) break;
                graphics.drawString(font, part, textX, ly, DENIED, false);
                ly += 10;
            }
            return;
        }
        TokenType guess = loaded.guess();
        String looks = guess == null ? "Doesn't look like a village building"
                : "Looks like " + article(BlueprintRoomGuess.displayName(guess));
        graphics.drawString(font, StorageUi.trim(font, looks, textW), textX, y + 18, guess == null ? MUTED : ACCENT,
                false);
        BlueprintMaterials.Result materials = loaded.materials();
        String count = String.format(Locale.ROOT, "Needs %,d blocks", materials == null ? 0 : materials.total());
        graphics.drawString(font, count, textX, y + 30, MUTED, false);
        if (materials != null && materials.total() > 0 && stockFor != loaded) {
            Object asking = loaded;
            stockFor = asking;
            stock = null;
            BuildOrderClient.stock(menu.villagerId(), materials, answer -> {
                if (stockFor == asking) stock = answer;
            });
        }
        int lineX = textX + font.width(count);
        if (stock != null && blueprint != null && blueprint.unknownBlockCount() == 0) {
            String have = stock.enough() ? " · storage has them all" : String.format(Locale.ROOT, " · storage has %,d", stock.have());
            String shown = StorageUi.trim(font, have, textX + textW - lineX);
            graphics.drawString(font, shown, lineX, y + 30, stock.enough() ? ACCENT : GOLD, false);
            lineX += font.width(shown);
        }
        int unknown = blueprint == null ? 0 : blueprint.unknownBlockCount();
        if (unknown > 0) {
            String warning = String.format(Locale.ROOT, " · %,d blocks from other mods left out", unknown);
            String shown = StorageUi.trim(font, warning, textX + textW - lineX);
            graphics.drawString(font, shown, lineX, y + 30, GOLD, false);
            lineX += font.width(shown);
        }
        int carry = carry();
        if (carry > 0) {
            graphics.drawString(font, StorageUi.trim(font, String.format(Locale.ROOT, " · you carry %,d", carry),
                    textX + textW - lineX), lineX, y + 30, TEXT, false);
            StorageUi.tickBox(graphics, font, tickX(), tickY(), BuilderPlanScreen.GIVE_LABEL, give,
                    overTick(mouseX, mouseY));
        }
        if (orderMessage != null && System.currentTimeMillis() < orderMessageUntil) {
            graphics.drawString(font, StorageUi.trim(font, orderMessage, textW), textX, y + 42,
                    orderOk ? ACCENT : DENIED, false);
        } else if (villageShown() && village != null) {
            renderPlacementLine(graphics, village, textX, y + 42, textX + textW);
        }
    }

    /** Copy mode: name box, size and blocks, messages, the side buttons, and Save. */
    private void renderCopyDetails(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = detailsTop();
        int textX = listRight() + GAP + 8, textW = buildX() - 8 - textX;
        boolean saveHover = inside(mouseX, mouseY, buildX(), buildY(), BUILD_W, 20);
        StorageUi.button(graphics, font, buildX(), buildY(), BUILD_W, 20, SAVE_LABEL, saveHover, canSave());
        if (copyHeader == null) {
            String first = copyBuilding == null && copyPick == null ? "Pick a building on the left" : "Copying...";
            graphics.drawString(font, first, textX, y + 8, MUTED, false);
            if (copyMessage != null) {
                int ly = y + 20;
                for (FormattedCharSequence part : font.split(Component.literal(copyMessage), textW)) {
                    if (ly > y + DETAILS_H - 10) break;
                    graphics.drawString(font, part, textX, ly, copyMessageColor, false);
                    ly += 10;
                }
            }
            return;
        }
        graphics.drawString(font, NAME_LABEL, textX, y + 8, MUTED, false);
        if (nameBox != null) {
            nameBox.setPosition(nameX(), y + 4);
            nameBox.setWidth(nameWidth());
        }
        String size = copySizeLine(copyHeader);
        graphics.drawString(font, size, textX, y + 25, TEXT, false);
        String note = pendingRequest >= 0 || resend ? "Updating..." : copyMessage;
        int noteColor = pendingRequest >= 0 || resend ? MUTED : copyMessageColor;
        if (note != null) {
            int nx = textX + font.width(size) + 8;
            graphics.drawString(font, StorageUi.trim(font, note, textX + textW - nx), nx, y + 25, noteColor, false);
        }
        for (CopyControl control : CopyControl.values()) {
            int cx = copyControlX(control), w = copyControlW(control);
            if (cx + w > buildX() - 6) break;
            StorageUi.button(graphics, font, cx, copyControlY(), w, CONTROL_H, copyControlLabel(control),
                    inside(mouseX, mouseY, cx, copyControlY(), w, CONTROL_H), true);
        }
    }

    /** Facing, border and clash state of the ghost, in one line of coloured parts. */
    private void renderPlacementLine(GuiGraphics graphics, VillageModelView model, int x, int y, int limit) {
        if (!model.ready()) {
            graphics.drawString(font, "Drawing the village...", x, y, MUTED, false);
            return;
        }
        VillageModelView.Check check = model.check();
        if (check == null) {
            graphics.drawString(font, model.busy() ? "Rotating..." : "Click the map to pick the spot", x, y, MUTED, false);
            return;
        }
        String facing = "Faces " + model.facing().getName() + (model.mirrored() ? ", flipped" : "");
        x = part(graphics, facing, x, y, limit, MUTED);
        x = part(graphics, " · ", x, y, limit, MUTED);
        x = part(graphics, check.insideBorder() ? "Inside the village" : "Outside the village", x, y, limit,
                check.insideBorder() ? ACCENT : DENIED);
        if (check.clashName() != null) {
            x = part(graphics, " · Hits " + check.clashName(), x, y, limit, DENIED);
        }
        if (check.coversPath()) part(graphics, " · Blocks a path", x, y, limit, GOLD);
    }

    /** Draws one part of a line, trimmed to what is left before {@code limit}; returns where the next starts. */
    private int part(GuiGraphics graphics, String text, int x, int y, int limit, int color) {
        if (x >= limit) return x;
        String shown = StorageUi.trim(font, text, limit - x);
        graphics.drawString(font, shown, x, y, color, false);
        return x + font.width(shown);
    }

    private static String article(String name) {
        char first = Character.toLowerCase(name.charAt(0));
        return ("aeiou".indexOf(first) >= 0 ? "an " : "a ") + name;
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Tooltip lines for what is under the mouse, or null. */
    @Nullable
    List<Component> tooltip(int mouseX, int mouseY) {
        if (dragEntry != null && dragDistance >= DRAG_SLOP) return null;
        if (!villageShown() && preview.overLayers(mouseX, mouseY)) return preview.layersTooltip();
        if (dropMap) {
            List<Component> strip = stripTooltip(mouseX, mouseY);
            if (strip != null) return strip;
        }
        if (!fullView && inside(mouseX, mouseY, foldX(), foldY(), FOLD_BUTTON, FOLD_BUTTON)) {
            return List.of(Component.literal(folded ? "Show the list" : "Hide the list"),
                    Component.literal(folded ? "Brings the list of buildings back." : "Gives the map more room.")
                            .withStyle(ChatFormatting.GRAY));
        }
        if (!fullView && !folded && listFolderShown()
                && inside(mouseX, mouseY, left + 6, listFolderY(), LIST_W - 12, FOLDER_H)) {
            return folderTooltip();
        }
        if (inside(mouseX, mouseY, fullX(), fullY(), FULL_BUTTON, FULL_BUTTON)) {
            return fullView
                    ? List.of(Component.literal("Close full view"),
                            Component.literal("Back to the desk with the list.").withStyle(ChatFormatting.GRAY),
                            Component.literal("Key: F or Esc").withStyle(ChatFormatting.GRAY))
                    : List.of(Component.literal("Full view"),
                            Component.literal("Shows the map over the whole window.").withStyle(ChatFormatting.GRAY),
                            Component.literal("Handy with a big GUI scale.").withStyle(ChatFormatting.GRAY),
                            Component.literal("Key: F").withStyle(ChatFormatting.GRAY));
        }
        if (villageShown() && village != null && village.ready() && mouseX >= layerX() && mouseX < layerX() + LAYER_W
                && mouseY >= layerUpY() && mouseY < layerDownY() + 18) {
            List<Component> out = new ArrayList<>();
            out.add(Component.literal("Layers"));
            if (village.layerCut()) {
                out.add(Component.literal("Everything above Y" + village.cutY() + " is cut off.").withStyle(ChatFormatting.GRAY));
            } else {
                out.add(Component.literal("Cuts off the top so you can look").withStyle(ChatFormatting.GRAY));
                out.add(Component.literal("inside houses, caves and mines.").withStyle(ChatFormatting.GRAY));
            }
            out.add(Component.literal("Arrows: one floor up or down.").withStyle(ChatFormatting.GRAY));
            out.add(Component.literal("Hold Shift for one block. Middle: see it all.").withStyle(ChatFormatting.GRAY));
            return out;
        }
        if (copyMode) {
            List<Component> copy = copyTooltip(mouseX, mouseY);
            if (copy != null) return copy;
        }
        if (!copyMode && inside(mouseX, mouseY, worldX(), buildY(), worldW(), 20)) {
            List<Component> out = new ArrayList<>();
            out.add(Component.literal("Preview in the world"));
            out.add(Component.literal("Gives you a plan to hold in your hand.").withStyle(ChatFormatting.GRAY));
            out.add(Component.literal("Look at the ground to see the building there.").withStyle(ChatFormatting.GRAY));
            out.add(Component.literal("Right click to set it down, then order it.").withStyle(ChatFormatting.GRAY));
            if (!canTakePlan()) {
                out.add(Component.literal(menu.snapshot().border() == null ? "The village map isn't ready yet."
                        : "Pick a building on the left first.").withStyle(ChatFormatting.RED));
            }
            return out;
        }
        if (overTick(mouseX, mouseY)) return BuilderPlanScreen.giveTooltip();
        if (inside(mouseX, mouseY, buildX(), buildY(), BUILD_W, 20)) {
            List<Component> out = new ArrayList<>();
            out.add(Component.literal("Build"));
            out.add(Component.literal("The Builder builds it on the spot you").withStyle(ChatFormatting.GRAY));
            out.add(Component.literal("picked, using blocks from the village storage.").withStyle(ChatFormatting.GRAY));
            BlueprintPlacement placement = villageShown() ? placement() : null;
            if (placement == null) {
                out.add(Component.literal("Click the map first to pick a spot.").withStyle(ChatFormatting.RED));
            } else if (!placement.valid() && placement.reason() != null) {
                out.add(Component.literal(placement.reason()).withStyle(ChatFormatting.RED));
            } else if (stock != null && stock.missingLine() != null) {
                out.add(Component.literal(stock.missingLine()).withStyle(ChatFormatting.GOLD));
            }
            return out;
        }
        if (switchVisible()) {
            if (inside(mouseX, mouseY, switchX(true), switchY(), SWITCH_W, CONTROL_H)) {
                return List.of(Component.literal("Village"),
                        Component.literal("Your village, with the building on it.").withStyle(ChatFormatting.GRAY));
            }
            if (inside(mouseX, mouseY, switchX(false), switchY(), SWITCH_W, CONTROL_H)) {
                return List.of(Component.literal("Blueprint"),
                        Component.literal("Just the building on its own.").withStyle(ChatFormatting.GRAY));
            }
        }
        if (villageShown() && village != null && village.ready()) {
            for (Control control : Control.values()) {
                if (inside(mouseX, mouseY, controlX(control), controlY(), controlW(control), CONTROL_H)) {
                    return List.of(Component.literal(control.label),
                            Component.literal(control.help).withStyle(ChatFormatting.GRAY),
                            Component.literal(control.key).withStyle(ChatFormatting.GRAY));
                }
            }
        }
        if (loaded != null && loaded.blueprint() != null && loaded.blueprint().unknownBlockCount() > 0
                && inside(mouseX, mouseY, listRight() + GAP + 8, detailsTop() + 29,
                buildX() - 16 - (listRight() + GAP + 8), 10)) {
            List<Component> out = new ArrayList<>();
            out.add(Component.literal("Blocks from mods you don't have"));
            int n = 0;
            for (Map.Entry<String, Integer> e : loaded.blueprint().unknownBlocks().entrySet()) {
                if (n++ == 3) {
                    out.add(Component.literal("and " + (loaded.blueprint().unknownBlocks().size() - 3) + " more")
                            .withStyle(ChatFormatting.GRAY));
                    break;
                }
                out.add(Component.literal(e.getKey() + ": " + e.getValue()).withStyle(ChatFormatting.GRAY));
            }
            return out;
        }
        Line line = lineAt(mouseX, mouseY);
        if (line == null) return null;
        if (line.kind() == Kind.DROP) {
            return List.of(Component.literal(DROP_LABEL),
                    Component.literal("Opens your village map over the whole window.").withStyle(ChatFormatting.GRAY),
                    Component.literal("Drag a building file onto the map to place it.").withStyle(ChatFormatting.GRAY));
        }
        if (line.kind() == Kind.COPY) {
            return List.of(Component.literal("Copy a building"),
                    Component.literal("Copies one of your buildings so the").withStyle(ChatFormatting.GRAY),
                    Component.literal("Builder can build it again somewhere else.").withStyle(ChatFormatting.GRAY));
        }
        if (line.kind() == Kind.WORLD) {
            return List.of(Component.literal("Copy in the world"),
                    Component.literal("Gives you a plan to hold. Walk to a").withStyle(ChatFormatting.GRAY),
                    Component.literal("building and right click it to copy it.").withStyle(ChatFormatting.GRAY));
        }
        if (line.kind() == Kind.CANCEL) {
            return List.of(Component.literal("Cancel"),
                    Component.literal("Back to the list of buildings.").withStyle(ChatFormatting.GRAY));
        }
        if (line.kind() == Kind.BUILDING && line.building() != null) {
            return List.of(Component.literal(line.building().name()),
                    Component.literal("Click to copy this building.").withStyle(ChatFormatting.GRAY));
        }
        if (line.kind() != Kind.FILE || line.entry() == null) return null;
        return fileTooltip(line.entry());
    }

    private List<Component> fileTooltip(BlueprintFiles.Entry entry) {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal(entry.name()));
        out.add(Component.literal("Folder: " + entry.folder() + " · " + megabytes(entry.bytes()))
                .withStyle(ChatFormatting.GRAY));
        BlueprintFiles.Summary summary = files.summary(entry);
        if (summary != null && summary.error() != null) {
            out.add(Component.literal(summary.error()).withStyle(ChatFormatting.RED));
        }
        return out;
    }

    private List<Component> folderTooltip() {
        if (!dropMap && segment == Segment.COPIED) {
            return List.of(Component.literal(FOLDER_LABEL),
                    Component.literal("Opens the folder with your copies.").withStyle(ChatFormatting.GRAY),
                    Component.literal("From there you can give one to a friend.").withStyle(ChatFormatting.GRAY));
        }
        return List.of(Component.literal(FOLDER_LABEL),
                Component.literal("Opens the schematics folder.").withStyle(ChatFormatting.GRAY),
                Component.literal("Building files you put there").withStyle(ChatFormatting.GRAY),
                Component.literal("show up in this list.").withStyle(ChatFormatting.GRAY));
    }

    /** Tooltips of the drop map's strip and Close button, or null. */
    @Nullable
    private List<Component> stripTooltip(int mouseX, int mouseY) {
        if (inside(mouseX, mouseY, left + 6, folderY(), STRIP_W - 12, FOLDER_H)) return folderTooltip();
        if (inside(mouseX, mouseY, closeX(), buildY(), closeW(), 20)) {
            return List.of(Component.literal(CLOSE_LABEL),
                    Component.literal("Back to the desk with the list.").withStyle(ChatFormatting.GRAY),
                    Component.literal("Key: Esc").withStyle(ChatFormatting.GRAY));
        }
        Line line = stripLineAt(mouseX, mouseY);
        if (line == null || line.kind() != Kind.FILE || line.entry() == null) return null;
        List<Component> out = fileTooltip(line.entry());
        out.add(Component.literal("Drag it onto the map to place it.").withStyle(ChatFormatting.GRAY));
        return out;
    }

    private static String copySizeLine(BuilderDeskPayloads.CopyResult result) {
        return String.format(Locale.ROOT, "%d×%d×%d · %,d blocks", result.sizeX(), result.sizeY(), result.sizeZ(),
                result.blocks());
    }

    /** Tooltips of the copy controls, or null when the mouse is on none of them. */
    @Nullable
    private List<Component> copyTooltip(int mouseX, int mouseY) {
        if (inside(mouseX, mouseY, buildX(), buildY(), BUILD_W, 20)) {
            return List.of(Component.literal("Save"),
                    Component.literal("Keeps this copy. You find it").withStyle(ChatFormatting.GRAY),
                    Component.literal("on the Copied tab after.").withStyle(ChatFormatting.GRAY));
        }
        if (copyHeader == null) return null;
        if (copyMessage != null && pendingRequest < 0 && !resend) {
            // The note beside the size is cut short in a narrow window: the whole line on hover.
            int textX = listRight() + GAP + 8, textW = buildX() - 8 - textX;
            int nx = textX + font.width(copySizeLine(copyHeader)) + 8, w = textX + textW - nx;
            if (font.width(copyMessage) > w && inside(mouseX, mouseY, nx, detailsTop() + 23, w, 11)) {
                return List.of(Component.literal(copyMessage));
            }
        }
        for (CopyControl control : CopyControl.values()) {
            if (!inside(mouseX, mouseY, copyControlX(control), copyControlY(), copyControlW(control), CONTROL_H)) continue;
            return switch (control) {
                case SIDE -> List.of(Component.literal("Side: " + SIDE_NAMES[side]),
                        Component.literal("The side that Less and More change.").withStyle(ChatFormatting.GRAY),
                        Component.literal("Click: next side. Right click: go back.").withStyle(ChatFormatting.GRAY));
                case LESS -> List.of(Component.literal("Less"),
                        Component.literal("Makes the copy one block smaller on this side.").withStyle(ChatFormatting.GRAY));
                case MORE -> List.of(Component.literal("More"),
                        Component.literal("Makes the copy one block bigger on this side.").withStyle(ChatFormatting.GRAY),
                        Component.literal("Use it to take some ground or rock along.").withStyle(ChatFormatting.GRAY));
                case RESET -> List.of(Component.literal("Reset sides"),
                        Component.literal("Undoes every Less and More.").withStyle(ChatFormatting.GRAY));
            };
        }
        return null;
    }

    // =========================================================================
    // Input
    // =========================================================================

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && inside(mouseX, mouseY, fullX(), fullY(), FULL_BUTTON, FULL_BUTTON)) {
            setFullView(!fullView);
            return true;
        }
        if (dropMap && button == 0) {
            if (inside(mouseX, mouseY, left + 6, folderY(), STRIP_W - 12, FOLDER_H)) {
                openFolder();
                return true;
            }
            if (inside(mouseX, mouseY, closeX(), buildY(), closeW(), 20)) {
                setFullView(false);
                return true;
            }
            Line line = stripLineAt(mouseX, mouseY);
            if (line != null && line.kind() == Kind.FILE && line.entry() != null) {
                // A drag or a click: told apart when it is let go.
                dragEntry = line.entry();
                dragDistance = 0;
                return true;
            }
        }
        // The rest of the strip takes its clicks, so they never reach the map.
        if (dropMap && mouseX < listRight()) return true;
        if (copyMode && copyHeader != null && (button == 0 || button == 1)) {
            for (CopyControl control : CopyControl.values()) {
                if (inside(mouseX, mouseY, copyControlX(control), copyControlY(), copyControlW(control), CONTROL_H)) {
                    if (button == 0 || control == CopyControl.SIDE) act(control, button);
                    return true;
                }
            }
        }
        if (button == 0 && copyMode && inside(mouseX, mouseY, buildX(), buildY(), BUILD_W, 20)) {
            save();
            return true;
        }
        if (button == 0 && !copyMode && inside(mouseX, mouseY, buildX(), buildY(), BUILD_W, 20)) {
            order();
            return true;
        }
        if (button == 0 && overTick(mouseX, mouseY)) {
            give = !give;
            return true;
        }
        if (button == 0 && !copyMode && inside(mouseX, mouseY, worldX(), buildY(), worldW(), 20)) {
            takePlan();
            return true;
        }
        if (button == 0 && !fullView && inside(mouseX, mouseY, foldX(), foldY(), FOLD_BUTTON, FOLD_BUTTON)) {
            folded = !folded;
            scroll = 0;
            return true;
        }
        if (button == 0 && !fullView && !folded && listFolderShown()
                && inside(mouseX, mouseY, left + 6, listFolderY(), LIST_W - 12, FOLDER_H)) {
            openFolder();
            return true;
        }
        if (button == 0) {
            // The list's tabs are hidden in the full view; the view's own buttons still work there.
            for (Segment s : fullView || folded ? new Segment[0] : Segment.values()) {
                if (inside(mouseX, mouseY, segmentX(s), top + SEGMENT_Y, segmentWidth(), 16)) {
                    if (copyMode) leaveCopyMode();
                    savedMessage = null;
                    if (segment != s) {
                        segment = s;
                        scroll = 0;
                    }
                    // Look again, so files saved while the desk is open show up; the list stays meanwhile.
                    files.refresh();
                    return true;
                }
            }
            if (switchVisible()) {
                if (inside(mouseX, mouseY, switchX(true), switchY(), SWITCH_W, CONTROL_H)) {
                    villageMode = true;
                    return true;
                }
                if (inside(mouseX, mouseY, switchX(false), switchY(), SWITCH_W, CONTROL_H)) {
                    villageMode = false;
                    return true;
                }
            }
            if (villageShown()) {
                VillageModelView model = village();
                if (model.ready() && inside(mouseX, mouseY, resetX(), resetY(), resetWidth(), 20)) {
                    model.resetCamera();
                    return true;
                }
                if (model.ready() && clickLayers(model, mouseX, mouseY)) return true;
                if (model.ready() && !copyMode) {
                    for (Control control : Control.values()) {
                        if (inside(mouseX, mouseY, controlX(control), controlY(), controlW(control), CONTROL_H)) {
                            act(control);
                            return true;
                        }
                    }
                }
            } else if (preview.shown() != null && inside(mouseX, mouseY, resetX(), resetY(), resetWidth(), 20)) {
                preview.resetCamera();
                preview.layerReset();
                return true;
            } else if (preview.clickLayers(mouseX, mouseY)) {
                return true;
            }
            Line line = lineAt(mouseX, mouseY);
            if (line != null) {
                switch (line.kind()) {
                    case FILE -> {
                        if (line.entry() != null) select(line.entry());
                    }
                    case COPY -> enterCopyMode();
                    case DROP -> openDropMap();
                    case CANCEL -> leaveCopyMode();
                    case WORLD -> {
                        BuilderPlanClient.startCopy(menu.villagerId());
                        PacketDistributor.sendToServer(new BuilderPlanPayloads.TakePlan(menu.villagerId(), "Copy", true));
                    }
                    case BUILDING -> {
                        if (line.building() != null) pickBuilding(line.building());
                    }
                    default -> { }
                }
                return true;
            }
        }
        if (villageShown()) return village().beginDrag(mouseX, mouseY, button);
        return preview.beginDrag(mouseX, mouseY, button);
    }

    boolean mouseDragged(int button, double dragX, double dragY) {
        if (dragEntry != null) {
            if (button == 0) dragDistance += Math.abs(dragX) + Math.abs(dragY);
            return true;
        }
        if (village != null && village.drag(button, dragX, dragY)) return true;
        return preview.drag(button, dragX, dragY);
    }

    /**
     * A left press that hardly moved places the ghost where it was let go. A strip file let go:
     * a click picks it, a drag puts it where it was let go on the map.
     */
    boolean mouseReleased(int button, double mouseX, double mouseY) {
        if (dragEntry != null && button == 0) {
            BlueprintFiles.Entry entry = dragEntry;
            dragEntry = null;
            if (dragDistance < DRAG_SLOP) {
                select(entry);
            } else {
                dropEntry(entry, mouseX, mouseY);
            }
            return true;
        }
        if (village != null && village.endDrag(button, mouseX, mouseY)) return true;
        return preview.endDrag(button);
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (dropMap && inside(mouseX, mouseY, left, stripTop(), STRIP_W, stripBottom() - stripTop())) {
            stripScroll = Mth.clamp(stripScroll - (int) Math.round(scrollY * 14), 0, stripMaxScroll());
            return true;
        }
        if (!fullView && !folded && inside(mouseX, mouseY, left, listTop(), LIST_W, listBottom() - listTop())) {
            scroll = Mth.clamp(scroll - (int) Math.round(scrollY * 14), 0, maxScroll());
            return true;
        }
        if (villageShown()) {
            VillageModelView model = village();
            // Shift + scroll moves the Layers one block; plain scroll zooms.
            if (net.minecraft.client.gui.screens.Screen.hasShiftDown() && model.ready()
                    && model.contains(mouseX, mouseY)) {
                model.layerStep(scrollY > 0 ? 1 : -1, true);
                return true;
            }
            return model.zoomAt(mouseX, mouseY, scrollY);
        }
        // The same on a file or copy: Shift + scroll moves its Layers one layer.
        if (net.minecraft.client.gui.screens.Screen.hasShiftDown() && preview.shown() != null
                && preview.contains(mouseX, mouseY)) {
            preview.layerStep(scrollY > 0 ? 1 : -1);
            return true;
        }
        return preview.zoomAt(mouseX, mouseY, scrollY);
    }

    /** R turns, M mirrors, Page Up and Page Down raise and lower. The screen skips this while typing. */
    boolean keyPressed(int keyCode) {
        if (copyMode || !villageShown() || village == null || !village.ready()) return false;
        Control control = switch (keyCode) {
            case GLFW.GLFW_KEY_R -> Control.TURN;
            case GLFW.GLFW_KEY_M -> Control.MIRROR;
            case GLFW.GLFW_KEY_PAGE_UP -> Control.UP;
            case GLFW.GLFW_KEY_PAGE_DOWN -> Control.DOWN;
            default -> null;
        };
        if (control == null) return false;
        act(control);
        return true;
    }

    private void act(Control control) {
        VillageModelView model = village();
        switch (control) {
            case TURN -> model.turn();
            case MIRROR -> model.cycleMirror();
            case UP -> model.raise(1);
            case DOWN -> model.raise(-1);
        }
    }
}
