package com.alex.test.village.claims;

import com.alex.test.Tr;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.visitor.AbstractTravelerEntity;
import com.alex.test.network.ClaimLandPayload;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.RanchBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.BrushItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.PlaceOnWaterBlockItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The land lock (plan docs/plans/2026-10-24-village-claims.md). In an owned village's square, with claims on:
 * <ul>
 *   <li>round 5, {@link Act#BUILD}: breaking, placing, buckets, lily pads, tools on blocks (strip, till, path, bone
 *   meal, fire), trampling farmland, item frames (tokens too), paintings, leash knots, armour stands;</li>
 *   <li>round 6, {@link Act#USE}: using blocks (chests and every other container, buttons, levers, beds, bells,
 *   cauldrons, signs, berries), chest minecarts and chest boats, the storage room window;</li>
 *   <li>round 6, {@link Act#DOOR}: doors, trapdoors and fence gates;</li>
 *   <li>round 7a, {@link Act#SHOP} and {@link Act#MANAGE}: the Mayor window's buys and changes, the bed window
 *   (checked where each message is handled, with {@link #mayIn}).</li>
 * </ul>
 * Who may do what: the village's own grid ({@link ClaimRights}, round 9, asked through {@link VillageClaim#allows}).
 * Machines (other mods' fake players) and operators with {@code /villager claim bypass} pass.
 *
 * <p>As {@code PianoLock}: refused on both sides, so a game never shows cracks, a ghost block or a door that
 * flicks open, and only the server says why (a refused click still reaches it). Each game knows the squares
 * where its own player is refused something, with what they are refused there ({@link ClaimLandPayload}); the server
 * re-sends them when claims change, and checks every 5 s anyway.
 */
public final class ClaimLock {
    private ClaimLock() {}

    /** What the player is doing; each rank may do some of these. */
    public enum Act {
        BUILD(true), USE(true), DOOR(true),
        /** Buying from the Mayor (Shop, a new Town Hall token): round 7a. */
        SHOP(false),
        /** Changing how the village runs: Village rules, raids, profession settings, who sleeps where: round 7a. */
        MANAGE(false),
        /** Looking at a building's window through its token (bed, mine, Butchery, Ranch; look only below Officer): round 7b. */
        LOOK(false),
        /** Hurting a villager, the Mayor or a golem: only the Owner (round 8). */
        HARM_RESIDENT(false),
        /** Hurting, leading, shearing, milking, feeding or riding an animal of a Ranch pen: Friend and up (round 8). */
        PEN_ANIMAL(false);

        private final boolean land;

        Act(boolean land) {
            this.land = land;
        }

        /** Done on the land itself: refusals of these go to the player's game with the squares. */
        public boolean land() {
            return land;
        }
    }

    /**
     * A square of chunks where this game's own player is refused something: the owner's name and what they are
     * refused there (bit = {@link Act} ordinal, worked out by the server from the village's grid, round 9).
     */
    public record Locked(int minX, int minZ, int maxX, int maxZ, String ownerName, int refused) {
        boolean refuses(Act act) {
            return (refused >> act.ordinal() & 1) != 0;
        }

        boolean contains(BlockPos pos) {
            int x = pos.getX() >> 4;
            int z = pos.getZ() >> 4;
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /** Ticks between two refusal lines for one player (a held click asks every tick). */
    private static final int TELL_EVERY = 20;
    /** Every this many ticks each player's squares are worked out again and sent if they changed. */
    private static final int RECHECK_TICKS = 100;

    // ---------------------------------------------------------------------
    // The game's copy (client)
    // ---------------------------------------------------------------------

    private static volatile List<Locked> clientLocked = List.of();
    @Nullable private static volatile ResourceLocation clientDimension;

    /** From {@link ClaimLandPayload}, in a game: replaces the whole list. */
    public static void setClientLocked(@Nullable ResourceLocation dimension, List<Locked> squares) {
        clientDimension = dimension;
        clientLocked = List.copyOf(squares);
    }

    // ---------------------------------------------------------------------
    // Who may do what
    // ---------------------------------------------------------------------

    /** Operators who switched the lock off for themselves ({@code /villager claim bypass}); not saved. */
    private static final Set<UUID> bypass = new HashSet<>();

    public static boolean hasBypass(UUID playerId) {
        return bypass.contains(playerId);
    }

    /** Switches this player's bypass and re-sends their squares; true when it is now on. */
    public static boolean toggleBypass(ServerPlayer player) {
        boolean on = !bypass.remove(player.getUUID());
        if (on) bypass.add(player.getUUID());
        syncTo(player);
        return on;
    }

    /** The acts done on the land itself, as a mask over act ordinals. */
    private static final int LAND_ACTS;

    static {
        int mask = 0;
        for (Act act : Act.values()) {
            if (act.land()) mask |= 1 << act.ordinal();
        }
        LAND_ACTS = mask;
    }

    /**
     * On the server: the owned village whose land this is, when this player may not do this there; null when they
     * may (claims off, nobody's land, their rank, bypass, a machine). Paused villages keep their lock.
     */
    @Nullable
    public static VillageManager lockedFor(@Nullable Player player, Level level, BlockPos pos, Act act) {
        if (!(player instanceof ServerPlayer serverPlayer) || player instanceof FakePlayer
                || !(level instanceof ServerLevel serverLevel)) return null;
        VillageManagerRegistry registry = VillageManagerRegistry.get(serverLevel.getServer());
        if (!registry.claimsEnabled() || bypass.contains(serverPlayer.getUUID())) return null;
        VillageManager village = ownedLandAt(registry, serverLevel, pos);
        if (village == null || village.claim().allows(serverPlayer.getUUID(), act)) return null;
        return village;
    }

    /**
     * For a window opened from afar (the Mayor's Buildings page) or a building found by its token: may this player
     * do this in that village? True for no village, claims off, nobody's village, bypass, a machine.
     */
    public static boolean mayIn(ServerPlayer player, @Nullable VillageManager village, Act act) {
        if (village == null || player instanceof FakePlayer || !village.claim().hasOwner()
                || bypass.contains(player.getUUID())
                || !VillageManagerRegistry.get(player.server).claimsEnabled()) return true;
        return village.claim().allows(player.getUUID(), act);
    }

    /**
     * Round 11a: is this player on that village's Hostile list (claims on, no bypass)? Its travellers then turn them
     * away. False for no village, nobody's village, a machine.
     */
    public static boolean isHostileIn(ServerPlayer player, @Nullable VillageManager village) {
        return village != null && !(player instanceof FakePlayer) && village.claim().hasOwner()
                && village.claim().rankOf(player.getUUID()) == ClaimRank.HOSTILE
                && !bypass.contains(player.getUUID())
                && VillageManagerRegistry.get(player.server).claimsEnabled();
    }

    /** A traveller of that village turned a Hostile player away (round 11a): said in chat. */
    public static void tellTravellerRefused(ServerPlayer player, VillageManager village, boolean merchant) {
        player.sendSystemMessage(merchant
                ? Tr.c("chat.dynamicvillages.claim.merchant_hostile",
                        "The merchant will not trade with you: %s's village does not want you here.",
                        village.claim().ownerName())
                : Tr.c("chat.dynamicvillages.claim.nomad_hostile",
                        "The nomad will not talk to you: %s's village does not want you here.",
                        village.claim().ownerName()));
    }

    /** The owned village whose square holds this spot, paused or running; null for none. */
    @Nullable
    public static VillageManager ownedLandAt(VillageManagerRegistry registry, ServerLevel level, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        for (VillageManager village : registry.all()) {
            if (!village.claim().hasOwner() || village.getLevel() == null
                    || !village.getLevel().dimension().equals(level.dimension())) continue;
            int[] land = village.landChunks();
            if (land != null && chunkX >= land[0] && chunkX <= land[2] && chunkZ >= land[1] && chunkZ <= land[3]) {
                return village;
            }
        }
        return null;
    }

    /**
     * Both sides: the owner's name when this player may not do this here, else null. A game knows only its own
     * player, and only from what the server last sent.
     */
    @Nullable
    private static String lockedOwner(@Nullable Player player, Level level, BlockPos pos, Act act) {
        if (player == null) return null;
        if (level.isClientSide()) {
            List<Locked> squares = clientLocked;
            ResourceLocation dimension = clientDimension;
            if (squares.isEmpty() || dimension == null || !level.dimension().location().equals(dimension)) return null;
            for (Locked square : squares) {
                if (square.contains(pos)) return square.refuses(act) ? square.ownerName() : null;
            }
            return null;
        }
        VillageManager village = lockedFor(player, level, pos, act);
        return village != null ? village.claim().ownerName() : null;
    }

    // ---------------------------------------------------------------------
    // Saying no (server only)
    // ---------------------------------------------------------------------

    private static final Map<UUID, Integer> lastTold = new HashMap<>();

    private static void tell(@Nullable Player player, String ownerName) {
        tellLine(player, Tr.c("chat.dynamicvillages.claim.locked", "This land belongs to %s's village.", ownerName));
    }

    /** Above the hotbar, at most once a second per player. */
    private static void tellLine(@Nullable Player player, Component line) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        int now = serverPlayer.server.getTickCount();
        Integer last = lastTold.get(serverPlayer.getUUID());
        if (last != null && now >= last && now - last < TELL_EVERY) return;
        lastTold.put(serverPlayer.getUUID(), now);
        serverPlayer.displayClientMessage(line, true);
    }

    /** A village window refused (storage room): said in chat, since a window covers the line above the hotbar. */
    public static void tellWindowRefused(ServerPlayer player, VillageManager village) {
        player.sendSystemMessage(Tr.c("chat.dynamicvillages.claim.window_locked",
                "[Village] Only people %s trusts may open this.", village.claim().ownerName()));
    }

    /** Why a Shop buy is refused (round 7a), for the Shop's own answer line (plain text, as its other answers). */
    public static String shopRefusal(VillageManager village) {
        return Tr.c("chat.dynamicvillages.claim.shop_locked", "Only people %s trusts may buy from this Mayor.",
                village.claim().ownerName()).getString();
    }

    /** A room window's change refused (round 7b), for the window's own refusal line (plain text). */
    public static String manageRefusal(VillageManager village) {
        return Tr.c("chat.dynamicvillages.claim.room_locked", "%s has not let you change this.",
                village.claim().ownerName()).getString();
    }

    /** A change to how the village runs refused (round 7a): said in chat. */
    public static void tellManageRefused(ServerPlayer player, VillageManager village) {
        player.sendSystemMessage(Tr.c("chat.dynamicvillages.claim.manage_locked",
                "[Village] %s has not let you change this.", village.claim().ownerName()));
    }

    // ---------------------------------------------------------------------
    // Blocks
    // ---------------------------------------------------------------------

    /** Breaking (and punching) a block: refused on both sides, so no cracks and in creative no flicker. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        String owner = lockedOwner(event.getEntity(), event.getLevel(), event.getPos(), Act.BUILD);
        if (owner == null) return;
        event.setCanceled(true);
        if (event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START) tell(event.getEntity(), owner);
    }

    /** The server's last word on a player's break (any other way a break comes). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof Level level)) return;
        String owner = lockedOwner(event.getPlayer(), level, event.getPos(), Act.BUILD);
        if (owner == null) return;
        event.setCanceled(true);
        tell(event.getPlayer(), owner);
    }

    /**
     * Right-clicking a block. Two answers: may the block be used (a chest opened, a button pressed, a door turned:
     * {@link Act#USE} or {@link Act#DOOR}), and may the held item act on it (placing, stripping, tilling, bone
     * meal, fire, frames, armour stands: {@link Act#BUILD}, also checked on the cell the item would fill).
     * Neither refusal stops the item's own use after (eating, a bow), as vanilla goes on to that.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        String useOwner = lockedOwner(player, level, pos, isDoor(state) ? Act.DOOR : Act.USE);
        if (useOwner != null) event.setUseBlock(TriState.FALSE);
        Direction face = event.getHitVec().getDirection();
        String buildOwner = lockedOwner(player, level, pos, Act.BUILD);
        if (buildOwner == null) buildOwner = lockedOwner(player, level, pos.relative(face), Act.BUILD);
        if (buildOwner != null) event.setUseItem(TriState.FALSE);
        // Sneaking with an item skips the block (vanilla); a door opened with a block in hand says nothing.
        boolean reachesBlock = (!player.isSecondaryUseActive() || event.getItemStack().isEmpty())
                && hasUse(state, level, pos);
        if (useOwner != null && reachesBlock) tell(player, useOwner);
        else if (buildOwner != null && useOwner == null && !reachesBlock && changesBlocks(event.getItemStack())) {
            tell(player, buildOwner);
        }
    }

    /** Doors, trapdoors and fence gates, other mods' too (their tags). */
    private static boolean isDoor(BlockState state) {
        Block block = state.getBlock();
        return block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock
                || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES);
    }

    /**
     * Blocks a right-click does something to: worth the line. The refusal itself covers every block; this only
     * keeps a right-click on grass or stone quiet.
     */
    private static boolean hasUse(BlockState state, Level level, BlockPos pos) {
        Block block = state.getBlock();
        return isDoor(state) || level.getBlockEntity(pos) != null || state.getMenuProvider(level, pos) != null
                || block instanceof ButtonBlock || block instanceof LeverBlock || block instanceof DiodeBlock
                || block instanceof NoteBlock || block instanceof CakeBlock || block instanceof CandleCakeBlock
                || block instanceof ComposterBlock || block instanceof AbstractCauldronBlock
                || block instanceof FlowerPotBlock || block instanceof SweetBerryBushBlock || block instanceof CaveVines
                || block instanceof RespawnAnchorBlock;
    }

    /** Items whose use on a block changes the land: worth the line, so a door opened with a sword in hand says nothing. */
    private static boolean changesBlocks(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof BlockItem || item instanceof HangingEntityItem || item instanceof ArmorStandItem
                || item instanceof DiggerItem || item instanceof FlintAndSteelItem || item instanceof FireChargeItem
                || item instanceof BoneMealItem || item instanceof SpawnEggItem || item instanceof EndCrystalItem
                || item instanceof MinecartItem || item instanceof ShearsItem || item instanceof HoneycombItem
                || item instanceof BrushItem || item instanceof BucketItem;
    }

    /**
     * A bucket, a lily pad or frogspawn aims by itself (no block click): look where it would fill, empty or sit on
     * the water, as the item does.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Item item = event.getItemStack().getItem();
        boolean empty;
        if (item instanceof BucketItem bucket) empty = bucket.content == Fluids.EMPTY;
        else if (item instanceof PlaceOnWaterBlockItem) empty = true;
        else return;
        Player player = event.getEntity();
        Level level = event.getLevel();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                empty ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return;
        String owner = lockedOwner(player, level, hit.getBlockPos(), Act.BUILD);
        if (owner == null) owner = lockedOwner(player, level, hit.getBlockPos().relative(hit.getDirection()), Act.BUILD);
        if (owner == null) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        tell(player, owner);
    }

    /** Any placing that got past the click (other mods' tools): put back by the game. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player player) || !(event.getLevel() instanceof Level level)) return;
        String owner = lockedOwner(player, level, event.getPos(), Act.BUILD);
        if (owner == null) return;
        event.setCanceled(true);
        tell(player, owner);
    }

    /** Jumping on a field breaks the farmland: not on someone else's land. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (!(event.getEntity() instanceof Player player) || !(event.getLevel() instanceof Level level)) return;
        if (lockedOwner(player, level, event.getPos(), Act.BUILD) != null) event.setCanceled(true);
    }

    // ---------------------------------------------------------------------
    // Frames (and the tokens in them), paintings, leash knots, armour stands; chest minecarts and chest boats
    // ---------------------------------------------------------------------

    /** What touching this entity counts as; null for anything the lock leaves alone. */
    @Nullable
    private static Act touchAct(Entity entity) {
        if (entity instanceof BlockAttachedEntity || entity instanceof ArmorStand) return Act.BUILD;
        if (entity instanceof ContainerEntity) return Act.USE;
        return null;
    }

    /** Right-click: turning or hanging a frame's item, dressing an armour stand, untying a knot, opening a chest cart. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        refuseTouch(event, event.getTarget());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        refuseTouch(event, event.getTarget());
    }

    private static void refuseTouch(PlayerInteractEvent event, Entity target) {
        // Round 8: a pen animal is not led away, sheared, milked, fed or ridden without the right.
        if (target instanceof Animal && refusedCreature(event.getEntity(), target)
                && event instanceof ICancellableEvent cancellable) {
            cancellable.setCanceled(true);
            if (event instanceof PlayerInteractEvent.EntityInteract interact) interact.setCancellationResult(InteractionResult.FAIL);
            if (event instanceof PlayerInteractEvent.EntityInteractSpecific specific) specific.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        Act act = touchAct(target);
        if (act == null) return;
        // A chest boat carries whoever clicks it; only a sneak-click (or a full boat) opens its chest.
        if (target instanceof ChestBoat && !event.getEntity().isSecondaryUseActive()
                && target.getPassengers().size() < 2) return;
        String owner = lockedOwner(event.getEntity(), event.getLevel(), target.blockPosition(), act);
        if (owner == null || !(event instanceof ICancellableEvent cancellable)) return;
        cancellable.setCanceled(true);
        if (event instanceof PlayerInteractEvent.EntityInteract interact) interact.setCancellationResult(InteractionResult.FAIL);
        if (event instanceof PlayerInteractEvent.EntityInteractSpecific specific) specific.setCancellationResult(InteractionResult.FAIL);
        // Round 7b: a Visitor's click on a token still opens its window to look (the frame does not turn). Only
        // on the plain click: the game sends it after the "at" click either way, so the window opens once.
        if (target instanceof ItemFrame frame && !frame.getItem().isEmpty()
                && event.getEntity() instanceof ServerPlayer player
                && lockedFor(player, event.getLevel(), target.blockPosition(), Act.LOOK) == null) {
            if (event instanceof PlayerInteractEvent.EntityInteractSpecific) return;
            if (VillageManagerRegistry.Events.openWindowToLook(player, frame)) return;
        }
        tell(event.getEntity(), owner);
    }

    /**
     * Hitting one (taking a token out of its frame, breaking a painting, an armour stand or a chest cart), by hand
     * or by a player's arrow: it takes no harm. Explosions are round 10.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onHurtCheck(EntityInvulnerabilityCheckEvent event) {
        Entity target = event.getEntity();
        if (event.isInvulnerable()) return;
        Player player = playerBehind(event.getSource().getEntity());
        if (player == null) return;
        // Round 8: arrows, potions, a tamed wolf, lit TNT: whatever the player sends, the creature takes no harm.
        if (refusedCreature(player, target)) {
            event.setInvulnerable(true);
            return;
        }
        Act act = touchAct(target);
        if (act == null) return;
        String owner = lockedOwner(player, target.level(), target.blockPosition(), act);
        if (owner == null) return;
        event.setInvulnerable(true);
        tell(player, owner);
    }

    // ---------------------------------------------------------------------
    // Villagers, the Mayor, golems and pen animals (round 8, server)
    // ---------------------------------------------------------------------

    /** A hit by hand: refused before the swing lands, so no sound, knockback or worn sword. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        if (refusedCreature(event.getEntity(), event.getTarget())) event.setCanceled(true);
    }

    /** The player behind a hurt: the hitter, an arrow's or TNT's player, a tamed animal's owner; else null. */
    @Nullable
    private static Player playerBehind(@Nullable Entity attacker) {
        if (attacker instanceof Player player) return player;
        if (attacker instanceof OwnableEntity pet && pet.getOwner() instanceof Player owner) return owner;
        return null;
    }

    /**
     * True (and the player is told) when an owned village keeps this creature safe from this player: its own
     * villagers and Mayor wherever they are (travellers are not theirs), golems on its land (Owner only), and
     * animals of its Ranch pens (Friend and up).
     */
    private static boolean refusedCreature(@Nullable Player player, Entity target) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(target.level() instanceof ServerLevel level)) return false;
        VillageManager village;
        Act act;
        if (target instanceof NpcEntity npc) {
            if (npc instanceof AbstractTravelerEntity || !npc.isVillageMember()) return false;
            village = npc.resolveVillageManager(level);
            act = Act.HARM_RESIDENT;
        } else if (target instanceof IronGolem || target instanceof SnowGolem) {
            village = ownedLandAt(VillageManagerRegistry.get(level.getServer()), level, target.blockPosition());
            act = Act.HARM_RESIDENT;
        } else if (target instanceof Animal) {
            village = ownedLandAt(VillageManagerRegistry.get(level.getServer()), level, target.blockPosition());
            act = Act.PEN_ANIMAL;
        } else {
            return false;
        }
        if (village == null || mayIn(serverPlayer, village, act)) return false;
        // Last, as it reads the pens: only for a player who may not touch them.
        if (act == Act.PEN_ANIMAL && !inPen(village, level, target)) return false;
        tellLine(player, act == Act.PEN_ANIMAL
                ? Tr.c("chat.dynamicvillages.claim.pen_locked", "Only people %s trusts may touch the animals in these pens.",
                        village.claim().ownerName())
                : Tr.c("chat.dynamicvillages.claim.harm_locked", "Only %s may hurt this village's villagers and golems.",
                        village.claim().ownerName()));
        return true;
    }

    /** An animal of one of this village's Ranch pens: inside one, seen inside lately, or out as its stray. */
    private static boolean inPen(VillageManager village, ServerLevel level, Entity animal) {
        long now = level.getGameTime();
        BlockPos feet = animal.blockPosition();
        for (Building building : village.getBuildings()) {
            if (!(building instanceof RanchBuilding ranch)) continue;
            if (ranch.isMember(animal.getUUID(), now)) return true;
            // Only a pen whose box holds the animal: asking a far pen for its tiles could load its chunks.
            BlockPos min = ranch.shellMin();
            BlockPos max = ranch.shellMax();
            if (feet.getX() >= min.getX() && feet.getX() <= max.getX() && feet.getZ() >= min.getZ()
                    && feet.getZ() <= max.getZ() && ranch.isPenTile(level, feet)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Telling each game its squares (server)
    // ---------------------------------------------------------------------

    private static volatile boolean dirty;
    private static final Map<UUID, List<Locked>> lastSent = new HashMap<>();

    /** Claims, ranks, the switch, a bypass or a village's land changed: every game hears again next tick. */
    public static void markDirty() {
        dirty = true;
    }

    /** The squares where this player is refused something, in the dimension they are in. */
    static List<Locked> lockedSquaresFor(ServerPlayer player) {
        VillageManagerRegistry registry = VillageManagerRegistry.get(player.server);
        if (!registry.claimsEnabled() || bypass.contains(player.getUUID())) return List.of();
        List<Locked> out = new ArrayList<>();
        ServerLevel level = player.serverLevel();
        for (VillageManager village : registry.all()) {
            if (!village.claim().hasOwner() || village.getLevel() == null
                    || !village.getLevel().dimension().equals(level.dimension())) continue;
            int refused = village.claim().refusedActs(player.getUUID()) & LAND_ACTS;
            if (refused == 0) continue;
            int[] land = village.landChunks();
            if (land != null) out.add(new Locked(land[0], land[1], land[2], land[3], village.claim().ownerName(), refused));
        }
        return out;
    }

    /** Sends this player's squares (at login, after a dimension change or respawn, or when they changed). */
    public static void syncTo(ServerPlayer player) {
        List<Locked> squares = lockedSquaresFor(player);
        lastSent.put(player.getUUID(), squares);
        PacketDistributor.sendToPlayer(player, new ClaimLandPayload(player.serverLevel().dimension().location(), squares));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        boolean recheck = server.getTickCount() % RECHECK_TICKS == 0;
        if (!dirty && !recheck) return;
        boolean all = dirty;
        dirty = false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (all || !lockedSquaresFor(player).equals(lastSent.get(player.getUUID()))) syncTo(player);
        }
    }

    /*
     * Joining, a new dimension, a respawn: sent on the next tick, after the registry's own login handler has
     * loaded the villages waiting in that dimension (sendCurrentBoundsTo) and renamed the player.
     */
    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        markDirty();
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        markDirty();
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        markDirty();
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        lastSent.remove(event.getEntity().getUUID());
        lastTold.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        lastSent.clear();
        lastTold.clear();
        bypass.clear();
        dirty = false;
    }
}
