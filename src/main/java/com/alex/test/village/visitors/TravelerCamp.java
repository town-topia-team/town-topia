package com.alex.test.village.visitors;

import com.alex.test.DynamicVillages;
import com.alex.test.ModBlocks;
import com.alex.test.Tr;
import com.alex.test.block.TravelerCampfireBlock;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.NavReachability;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.GoHomeActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.TavernBuilding;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where a village's visitors spend the night, and what upsets them.
 *
 * <p>A merchant or nomad group that stays the night (see
 * {@link TravelerLifecycleRules.StayPlan}) camps from dusk: the village finds an
 * empty 3x3 patch of natural ground near one of its buildings, lights a
 * {@link TravelerCampfireBlock} in the middle, and the travellers settle on
 * the eight tiles around it ({@link TravelerNight}). They leave the next
 * morning, and the fire goes with the last of them. With no spot to be found
 * they stay up and leave once the village has gone to sleep.
 *
 * <p>A player who breaks the fire is warned once; breaking it again sends the
 * campers off at once, nomads among them refusing to be recruited, and no
 * merchant or nomads come the next day. The founding nomads complain but stay.
 *
 * <p>Saved inside the nomad schedule ({@link NomadSpawnManager#saveSchedule}),
 * so the village record keeps its format.
 */
public final class TravelerCamp {
    /** From this clock on (6 PM) visitors staying the night head for camp. */
    public static final long CAMP_FROM_CLOCK = 12_000L;
    /** From this clock on (about 8:30 PM) campers sit down to sleep. */
    public static final long SLEEP_FROM_CLOCK = 14_500L;
    /** With no camp spot, staying visitors leave by midnight at the latest. */
    private static final long NO_CAMP_LEAVE_BY_CLOCK = 18_000L;
    /** A thunderstorm in this stretch (5 to 5:30 PM), before anyone leaves, keeps them overnight. */
    private static final long STORM_CHECK_FROM = 11_000L;
    private static final long STORM_CHECK_TO = 11_500L;
    /** The "packing up" line comes this long (50 s) before a visitor leaves. */
    private static final long PACKING_UP_TICKS = 1_000L;
    /** A second break within this long after the warning counts (30 s). */
    private static final long WARNING_LASTS_TICKS = 600L;
    private static final int TICK_INTERVAL = 20;
    private static final double ANNOUNCE_RADIUS = 128.0;
    /** The tiles around the fire, in the order campers take them: sides first, then corners. */
    private static final int[][] RING = {
            {0, -1}, {1, 0}, {0, 1}, {-1, 0}, {1, -1}, {1, 1}, {-1, 1}, {-1, -1}};
    /** How far from a building's door a camp is looked for. */
    private static final int SEARCH_MIN = 4;
    private static final int SEARCH_MAX = 14;
    /** Fewer blocks than this between the fire and any door is too close. */
    private static final int DOOR_CLEARANCE = 4;
    private static final int MAX_CENTERS_CHECKED = 1_500;

    private final VillageManager village;
    @Nullable private BlockPos fire;
    /** Village day of the evening a camp was set up, or found impossible. */
    private long nightDay = Long.MIN_VALUE;
    private boolean noSpotTonight;
    /** Campers in the order they took their tiles around the fire. */
    private final List<UUID> campers = new ArrayList<>();
    /** A village day on which no merchant or nomads come: someone broke a camp's fire. */
    private long snubDay = Long.MIN_VALUE;
    /** The scripted day 4: whoever comes that day stays the night. */
    private long scriptedStayDay = Long.MIN_VALUE;
    private long stormCheckedDay = Long.MIN_VALUE;

    private int tickTimer;
    private final Map<UUID, Long> warnedAt = new HashMap<>();
    /** Leave times already given a "packing up" line. */
    private final Set<Long> packingAnnounced = new HashSet<>();
    private long nomadPackingQuietUntil;
    private long merchantPackingQuietUntil;
    /** Village day the tavern last welcomed its visitors (one line a night). */
    private long welcomedDay = Long.MIN_VALUE;
    /** Tonight's guest beds (head positions) and who has them. */
    private final Map<BlockPos, Booking> bookings = new java.util.LinkedHashMap<>();
    /** Guests who lay down in their bed tonight. */
    private final Set<UUID> sleptInBed = new HashSet<>();
    /** Village day of the night the beds in {@link #bookings} were taken for. */
    private long bookingDay = Long.MIN_VALUE;
    private final Map<UUID, Long> bedWarnedAt = new HashMap<>();
    private final Map<UUID, Long> bedBreakWarnedAt = new HashMap<>();
    /** A second click on a booked bed within this long (10 s) sleeps in it anyway. */
    private static final long BED_WARNING_LASTS_TICKS = 200L;
    /**
     * Presses closer together than this (0.4 s) are one button held down:
     * Minecraft repeats a held use every 4 ticks and a held break in creative
     * every 5, so holding it would otherwise slip past the warning at once.
     */
    private static final long HELD_BUTTON_TICKS = 8L;
    /** Each player's last press on a booked bed and on a travelers' fire. */
    private final Map<UUID, Long> lastBedPress = new HashMap<>();
    private final Map<UUID, Long> lastFirePress = new HashMap<>();
    /** When each player's click on a booked bed was last refused (the off hand follows it). */
    private final Map<UUID, Long> bedRefusedAt = new HashMap<>();
    /** What a merchant leaves for a night in a guest bed. */
    private static final int MERCHANT_BED_PAYMENT = 2;
    /** What a nomad may leave: simple food. */
    private static final net.minecraft.world.item.Item[] NOMAD_GIFTS = {
            Items.BREAD, Items.APPLE, Items.BAKED_POTATO, Items.CARROT};
    private static final long NOMAD_PACKING_QUIET_TICKS = 2_400L;

    public TravelerCamp(VillageManager village) { this.village = village; }

    // =========================================================================
    // Queries
    // =========================================================================

    @Nullable public BlockPos fire() { return fire; }
    public boolean noSpotTonight() { return noSpotTonight; }
    public int camperCount() { return campers.size(); }
    public long snubDay() { return snubDay; }

    /** No merchant or nomads arrive on {@code day}: a camp's fire was broken the night before. */
    public boolean isSnubbed(long day) { return day == snubDay; }

    public void markScriptedStayDay(long day) {
        scriptedStayDay = day;
        village.markPersistentDataDirty();
    }

    /**
     * Lets tonight's camp be set up again when there is no fire: a merchant
     * spawned by command after the evening's camp was already settled (no
     * spot, nobody staying, or the fire broken) still gets a try.
     */
    public void allowNewCampTonight() {
        if (fire != null) return;
        nightDay = Long.MIN_VALUE;
        noSpotTonight = false;
        village.markPersistentDataDirty();
    }

    /** How long a merchant (one bed) arriving now stays. */
    public TravelerLifecycleRules.StayPlan planFor(ServerLevel level) {
        return planFor(level, 1);
    }

    /**
     * How long a visitor (or a group needing {@code bedsNeeded} beds) arriving
     * now stays. Free guest beds for all of them make an overnight stay more
     * likely; a tavern without enough free beds counts as none.
     */
    public TravelerLifecycleRules.StayPlan planFor(ServerLevel level, int bedsNeeded) {
        long dayTime = level.getDayTime();
        if (TravelerLifecycleRules.dayIndex(dayTime) == scriptedStayDay) {
            return TravelerLifecycleRules.StayPlan.NIGHT;
        }
        TavernBuilding tavern = tavernOf();
        boolean beds = tavern != null && freeBeds(level, tavern).size() >= Math.max(1, bedsNeeded);
        return TravelerLifecycleRules.rollStayPlan(dayTime, beds, level.random.nextDouble());
    }

    // =========================================================================
    // Guest beds
    // =========================================================================

    /** A guest bed taken for tonight: by whom, and whether that is the merchant. */
    private record Booking(UUID visitor, boolean merchant) {}

    @Nullable
    private TavernBuilding tavernOf() {
        for (Building building : village.getBuildings()) {
            if (building instanceof TavernBuilding tavern && tavern.isValid()) return tavern;
        }
        return null;
    }

    /**
     * The tavern room's beds that are no villager's: a tavern built into a
     * house can reach the house's bedrooms, and a guest in a villager's own
     * bed sends that villager to the Town Hall for the night.
     */
    private List<BlockPos> guestBeds(ServerLevel level, TavernBuilding tavern) {
        List<BlockPos> beds = tavern.guestBeds(level);
        beds.removeIf(village::isVillagerBed);
        return beds;
    }

    /** The tavern's guest beds nobody lies in and nobody has taken for tonight. */
    private List<BlockPos> freeBeds(ServerLevel level, TavernBuilding tavern) {
        List<BlockPos> free = new ArrayList<>();
        for (BlockPos bed : guestBeds(level, tavern)) {
            if (bookings.containsKey(bed)) continue;
            BlockState state = level.getBlockState(bed);
            if (state.getBlock() instanceof BedBlock && !state.getValue(BedBlock.OCCUPIED)) free.add(bed);
        }
        return free;
    }

    /** The guest bed this visitor has for tonight, or null. */
    @Nullable
    public BlockPos bedFor(UUID visitor) {
        for (Map.Entry<BlockPos, Booking> entry : bookings.entrySet()) {
            if (entry.getValue().visitor().equals(visitor)) return entry.getKey();
        }
        return null;
    }

    private boolean isBooked(UUID visitor) { return bedFor(visitor) != null; }

    /** The guest lay down in its bed: it leaves a thank-you in the morning. */
    public void markSleptInBed(UUID visitor) {
        if (sleptInBed.add(visitor)) village.markPersistentDataDirty();
    }

    /** "2 (1 taken tonight)", or "none". For {@code /villager day}. */
    public String guestBedSummary(ServerLevel level) {
        TavernBuilding tavern = tavernOf();
        int beds = tavern == null ? 0 : guestBeds(level, tavern).size();
        return beds == 0 ? "none" : beds + " (" + bookings.size() + " taken tonight)";
    }

    /**
     * At dusk, before the camp: the merchant takes a free guest bed if there is
     * one, and the nomads take beds only if there is one for each of them;
     * otherwise they camp together. Whoever got a bed leaves {@code staying}.
     */
    private void bookGuestBeds(ServerLevel level, Map<UUID, Long> staying) {
        TavernBuilding tavern = tavernOf();
        if (tavern == null) return;
        List<BlockPos> free = freeBeds(level, tavern);
        UUID merchantId = village.visitors().activeMerchantId();
        boolean merchantBooked = false;
        if (merchantId != null && staying.containsKey(merchantId) && !isBooked(merchantId) && !free.isEmpty()) {
            bookings.put(free.remove(0).immutable(), new Booking(merchantId, true));
            bookingDay = TravelerLifecycleRules.dayIndex(level.getDayTime());
            merchantBooked = true;
        }
        List<UUID> nomads = new ArrayList<>();
        for (UUID id : staying.keySet()) {
            if (!id.equals(merchantId) && !isBooked(id)) nomads.add(id);
        }
        boolean nomadsBooked = false;
        if (!nomads.isEmpty() && free.size() >= nomads.size()) {
            for (UUID id : nomads) bookings.put(free.remove(0).immutable(), new Booking(id, false));
            bookingDay = TravelerLifecycleRules.dayIndex(level.getDayTime());
            nomadsBooked = true;
        }
        staying.keySet().removeIf(this::isBooked);
        if (merchantBooked || nomadsBooked) {
            village.markPersistentDataDirty();
            DynamicVillages.LOGGER.info("[VillageManager {}] Guest beds taken tonight: {}.", shortId(), bookings.size());
            Component line = merchantBooked && nomadsBooked
                    ? Tr.c("chat.dynamicvillages.travelers.beds_taken.both",
                            "[Village] The merchant and the nomads have taken guest beds at the tavern tonight.")
                    : merchantBooked
                    ? Tr.c("chat.dynamicvillages.travelers.beds_taken.merchant",
                            "[Village] The merchant has taken a guest bed at the tavern tonight.")
                    : Tr.c("chat.dynamicvillages.travelers.beds_taken.nomads",
                            "[Village] The nomads have taken guest beds at the tavern tonight.");
            announce(level, line.copy().withStyle(ChatFormatting.AQUA));
        }
    }

    /**
     * A guest booked into a villager's own bed (saved before such beds were
     * left out, or a house rescan that took the bed in after booking) gives
     * it back. It takes a free guest bed if there is one, else joins the camp
     * or spends the night at its usual spot.
     */
    private void giveBackVillagerBeds(ServerLevel level) {
        List<Map.Entry<BlockPos, Booking>> wrong = new ArrayList<>();
        for (Map.Entry<BlockPos, Booking> entry : bookings.entrySet()) {
            if (village.isVillagerBed(entry.getKey())) wrong.add(entry);
        }
        if (wrong.isEmpty()) return;
        for (Map.Entry<BlockPos, Booking> entry : wrong) {
            bookings.remove(entry.getKey());
            sleptInBed.remove(entry.getValue().visitor());
        }
        TavernBuilding tavern = tavernOf();
        List<BlockPos> free = tavern == null ? List.of() : freeBeds(level, tavern);
        for (Map.Entry<BlockPos, Booking> entry : wrong) {
            Booking booking = entry.getValue();
            BlockPos moved = free.isEmpty() ? null : free.remove(0).immutable();
            if (moved != null) bookings.put(moved, booking);
            DynamicVillages.LOGGER.info("[VillageManager {}] {} {} gave back villager bed {}: {}.",
                    shortId(), booking.merchant() ? "Merchant" : "Nomad",
                    booking.visitor().toString().substring(0, 8), entry.getKey().toShortString(),
                    moved != null ? "guest bed " + moved.toShortString() + " instead" : "no guest bed free");
        }
        village.markPersistentDataDirty();
    }

    /** Morning: guests who slept in their bed leave a thank-you, and the beds are free again. */
    private void settleBeds(ServerLevel level) {
        boolean merchantPaid = false;
        int nomadsPaid = 0;
        GiftSpot where = null;
        for (Map.Entry<BlockPos, Booking> entry : bookings.entrySet()) {
            Booking booking = entry.getValue();
            if (!sleptInBed.contains(booking.visitor())) continue;
            List<ItemStack> gifts = new ArrayList<>();
            if (booking.merchant()) {
                // Village income: stamped, so the Mayor's shop takes it.
                ItemStack payment = new ItemStack(Items.EMERALD, MERCHANT_BED_PAYMENT);
                com.alex.test.village.tokens.VillageTokenBinding.bind(payment, village);
                gifts.add(payment);
                merchantPaid = true;
            } else {
                net.minecraft.world.item.Item food = NOMAD_GIFTS[level.random.nextInt(NOMAD_GIFTS.length)];
                // Given to the village: its green name and ID, so villagers may
                // eat it. Bread and baked potato are Poor Man's: no cook made them.
                ItemStack gift = new ItemStack(food, 1 + level.random.nextInt(2));
                com.alex.test.village.tokens.VillageFood.markVillagerMade(gift);
                com.alex.test.village.tokens.VillageTokenBinding.bind(gift, village);
                gifts.add(gift);
                nomadsPaid++;
            }
            for (ItemStack gift : gifts) where = leave(level, entry.getKey(), gift);
        }
        bookings.clear();
        sleptInBed.clear();
        village.markPersistentDataDirty();
        if (where == null) return;
        if (merchantPaid) {
            Component line = switch (where) {
                case TAVERN_CHEST -> Tr.c("chat.dynamicvillages.travelers.merchant_paid.tavern_chest",
                        "[Village] The merchant left %s emeralds in the tavern chest for the night's bed.",
                        MERCHANT_BED_PAYMENT);
                case STORAGE -> Tr.c("chat.dynamicvillages.travelers.merchant_paid.storage",
                        "[Village] The merchant left %s emeralds in the storage room for the night's bed.",
                        MERCHANT_BED_PAYMENT);
                case BED -> Tr.c("chat.dynamicvillages.travelers.merchant_paid.bed",
                        "[Village] The merchant left %s emeralds by the bed for the night's bed.",
                        MERCHANT_BED_PAYMENT);
            };
            announce(level, line.copy().withStyle(ChatFormatting.AQUA));
        }
        if (nomadsPaid > 0) {
            Component line = switch (where) {
                case TAVERN_CHEST -> Tr.c("chat.dynamicvillages.travelers.nomads_paid.tavern_chest",
                        "[Village] The nomads left some food in the tavern chest to thank you for the beds.");
                case STORAGE -> Tr.c("chat.dynamicvillages.travelers.nomads_paid.storage",
                        "[Village] The nomads left some food in the storage room to thank you for the beds.");
                case BED -> Tr.c("chat.dynamicvillages.travelers.nomads_paid.bed",
                        "[Village] The nomads left some food by the bed to thank you for the beds.");
            };
            announce(level, line.copy().withStyle(ChatFormatting.AQUA));
        }
    }

    /** Where a guest's thank-you ended up: each place has its own sentences. */
    private enum GiftSpot { TAVERN_CHEST, STORAGE, BED }

    /** Puts a guest's thank-you somewhere safe; says where. */
    private GiftSpot leave(ServerLevel level, BlockPos bed, ItemStack gift) {
        TavernBuilding tavern = tavernOf();
        BlockPos chest = tavern != null ? tavern.containerInside(level) : null;
        if (chest != null && level.getBlockEntity(chest) instanceof net.minecraft.world.Container container) {
            ItemStack rest = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, container, gift, null);
            if (rest.isEmpty()) return GiftSpot.TAVERN_CHEST;
            gift = rest;
        }
        for (Building building : village.getBuildings()) {
            if (building instanceof com.alex.test.village.building.StorageBuilding storage && storage.isValid()
                    && storage.depositItems(level, gift)) {
                return GiftSpot.STORAGE;
            }
        }
        net.minecraft.world.Containers.dropItemStack(level, bed.getX() + 0.5, bed.getY() + 0.6, bed.getZ() + 0.5, gift);
        return GiftSpot.BED;
    }

    /**
     * A player clicks a bed. One taken for tonight by a guest not yet lying in
     * it is refused once with a warning; clicking again within 10 seconds lets
     * the player sleep there, and the guest is upset (see {@link #tick}).
     * Returns whether to refuse this click.
     */
    public static boolean onPlayerClicksBed(ServerLevel level, Player player, BlockPos pos) {
        BlockPos bed = headOf(level, pos);
        TravelerCamp camp = campWithBooking(level, bed);
        if (camp == null) return false;
        Booking booking = camp.bookings.get(bed);
        if (camp.sleptInBed.contains(booking.visitor())) return false; // occupied: vanilla says so
        long now = level.getGameTime();
        boolean held = isHeldPress(camp.lastBedPress, player, now);
        Long warned = camp.bedWarnedAt.get(player.getUUID());
        if (warned != null && now - warned <= BED_WARNING_LASTS_TICKS) {
            if (held) {
                // The first click, still held down: not yet a second one.
                camp.bedRefusedAt.put(player.getUUID(), now);
                return true;
            }
            camp.bedWarnedAt.remove(player.getUUID());
            return false;
        }
        camp.bedWarnedAt.put(player.getUUID(), now);
        camp.bedRefusedAt.put(player.getUUID(), now);
        player.sendSystemMessage((booking.merchant()
                ? Tr.c("chat.dynamicvillages.travelers.bed_booked.merchant", "This bed is booked for the merchant "
                        + "tonight. Click again to sleep here anyway (they will be upset).")
                : Tr.c("chat.dynamicvillages.travelers.bed_booked.nomads", "This bed is booked for the nomads "
                        + "tonight. Click again to sleep here anyway (they will be upset)."))
                .withStyle(ChatFormatting.GOLD));
        return true;
    }

    /** Records a press; true when it follows the last one too closely to be a new press. */
    private static boolean isHeldPress(Map<UUID, Long> lastPress, Player player, long now) {
        Long last = lastPress.put(player.getUUID(), now);
        return last != null && now - last < HELD_BUTTON_TICKS;
    }

    /**
     * A player breaks a bed taken for tonight: warned once, then the guest is
     * upset as for its fire. Returns whether the bed may go.
     */
    public static boolean onPlayerBreaksBed(ServerLevel level, Player player, BlockPos pos) {
        BlockPos bed = headOf(level, pos);
        TravelerCamp camp = campWithBooking(level, bed);
        if (camp == null) return true;
        long now = level.getGameTime();
        boolean held = isHeldPress(camp.lastBedPress, player, now);
        Long warned = camp.bedBreakWarnedAt.get(player.getUUID());
        if (warned == null || now - warned > WARNING_LASTS_TICKS) {
            camp.bedBreakWarnedAt.put(player.getUUID(), now);
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.travelers.bed_break_warning",
                    "A traveler has this bed tonight. Breaking it will upset them.").withStyle(ChatFormatting.GOLD));
            return false;
        }
        if (held) return false; // the first break, held down in creative: not yet a second one
        camp.bedBreakWarnedAt.remove(player.getUUID());
        camp.upsetOverBed(level, player, camp.bookings.get(bed));
        return true;
    }

    /** The player's click on a booked bed was refused this very tick (the same click's other hand). */
    public static boolean wasJustWarnedAboutBed(ServerLevel level, Player player) {
        long now = level.getGameTime();
        for (VillageManager manager : VillageManagerRegistry.get(level.getServer()).all()) {
            Long refused = manager.travelerCamp().bedRefusedAt.get(player.getUUID());
            if (refused != null && refused == now) return true;
        }
        return false;
    }

    @Nullable
    private static TravelerCamp campWithBooking(ServerLevel level, BlockPos bed) {
        for (VillageManager manager : VillageManagerRegistry.get(level.getServer()).all()) {
            if (manager.getLevel() == level && manager.travelerCamp().bookings.containsKey(bed)) {
                return manager.travelerCamp();
            }
        }
        return null;
    }

    /** The head half of the bed at {@code pos}. */
    private static BlockPos headOf(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return pos.relative(state.getValue(BedBlock.FACING));
        }
        return pos;
    }

    /**
     * Someone took (slept in or broke) a guest's bed. The merchant leaves at
     * once; for a nomad, the nomads staying the night leave and will not be
     * recruited, the founding group excepted. Nobody comes the next day.
     */
    private void upsetOverBed(ServerLevel level, Player player, Booking booking) {
        long now = level.getGameTime();
        long dayTime = level.getDayTime();
        snubDay = TravelerLifecycleRules.dayIndex(dayTime) + 1;
        if (booking.merchant()) {
            village.visitors().leaveNow(now);
            bookings.values().removeIf(b -> b.merchant());
            say(level, player, Tr.c("chat.dynamicvillages.travelers.bed_taken.merchant",
                    "[Merchant] Someone took the bed I was given! I'm leaving tonight, "
                    + "and don't expect me tomorrow."));
        } else {
            boolean left = false;
            boolean stayed = false;
            for (Map.Entry<UUID, Long> entry : village.nomads().savedActiveNomads().entrySet()) {
                if (entry.getValue() <= now || !TravelerLifecycleRules.staysPastSunrise(entry.getValue(), now, dayTime)) {
                    continue;
                }
                if (TravelerLifecycleRules.leavesAfterTonight(entry.getValue(), now, dayTime)) {
                    village.nomads().leaveNow(entry.getKey(), now);
                    village.nomads().markOffended(entry.getKey());
                    UUID id = entry.getKey();
                    bookings.values().removeIf(b -> b.visitor().equals(id));
                    campers.remove(id);
                    left = true;
                } else {
                    stayed = true;
                }
            }
            if (left) {
                say(level, player, Tr.c("chat.dynamicvillages.travelers.bed_taken.nomads",
                        "[Nomads] Someone took our beds! We're leaving, and we won't join "
                        + "a village like this. Nobody will visit tomorrow."));
            }
            if (stayed) {
                say(level, player, Tr.c("chat.dynamicvillages.travelers.bed_taken.founding",
                        "[Nomads] Someone took our beds! We'll stay, but we won't forget this."));
            }
        }
        village.markPersistentDataDirty();
        DynamicVillages.LOGGER.info("[VillageManager {}] {} took a guest's bed: no visitors on day {}.",
                shortId(), player.getName().getString(), snubDay);
    }

    /** The tile a camper settles on, or null when it has none tonight. */
    @Nullable
    public BlockPos slotFor(UUID travelerId) {
        if (fire == null) return null;
        int index = campers.indexOf(travelerId);
        if (index < 0 || index >= RING.length) return null;
        return fire.offset(RING[index][0], 0, RING[index][1]);
    }

    // =========================================================================
    // Tick
    // =========================================================================

    public void tick(ServerLevel level) {
        if (DynamicVillages.isServerStopping() || ++tickTimer < TICK_INTERVAL) return;
        tickTimer = 0;
        long now = level.getGameTime();
        long dayTime = level.getDayTime();
        long clock = Math.floorMod(dayTime, TravelerLifecycleRules.DAY_TICKS);
        long today = TravelerLifecycleRules.dayIndex(dayTime);

        if (clock >= STORM_CHECK_FROM && clock < STORM_CHECK_TO && stormCheckedDay != today) {
            stormCheckedDay = today;
            village.markPersistentDataDirty();
            if (level.isThundering()) keepEveryoneOvernight(level, now, dayTime);
        }
        announcePackingUp(level, now);

        if (!bookings.isEmpty()) {
            // The night the beds were taken for is over (whenever the village
            // next ticks, even the following evening after the game was closed).
            if (today != bookingDay) {
                settleBeds(level);
            } else {
                giveBackVillagerBeds(level);
                // A player lying in a guest bed before its guest got there.
                for (Map.Entry<BlockPos, Booking> entry : List.copyOf(bookings.entrySet())) {
                    // Gone already: the guest's group left over an earlier bed this pass.
                    if (!bookings.containsKey(entry.getKey())) continue;
                    if (sleptInBed.contains(entry.getValue().visitor())) continue;
                    for (ServerPlayer player : level.players()) {
                        if (player.isSleeping() && player.getSleepingPos().filter(entry.getKey()::equals).isPresent()) {
                            upsetOverBed(level, player, entry.getValue());
                            break;
                        }
                    }
                }
            }
        }

        Map<UUID, Long> staying = stayingVisitors(now, dayTime);
        // A guest gone before bedtime (recruited, killed, sent off) frees its
        // bed: nobody would come for it, and a player sleeping there would
        // upset the guests still here.
        if (bookings.values().removeIf(b -> !staying.containsKey(b.visitor()) && !sleptInBed.contains(b.visitor()))) {
            village.markPersistentDataDirty();
        }
        if (fire != null) {
            if (campers.stream().noneMatch(staying::containsKey)) {
                strikeCamp(level, "everyone has left");
            } else {
                for (UUID id : staying.keySet()) {
                    if (!campers.contains(id) && !isBooked(id) && campers.size() < RING.length) {
                        campers.add(id);
                        village.markPersistentDataDirty();
                    }
                }
            }
        }
        if (clock >= CAMP_FROM_CLOCK && nightDay != today && !staying.isEmpty()) {
            setUpCamp(level, today, staying);
        }
        if (noSpotTonight && nightDay == today && clock >= CAMP_FROM_CLOCK
                && (clock >= NO_CAMP_LEAVE_BY_CLOCK || villageAsleep())) {
            noSpotTonight = false;
            village.markPersistentDataDirty();
            sendOffMorningGuests(level, now, dayTime);
        }
    }

    /** Visitors here now who stay past the coming sunrise, with their leave times. */
    private Map<UUID, Long> stayingVisitors(long now, long dayTime) {
        Map<UUID, Long> staying = new java.util.LinkedHashMap<>();
        UUID merchant = village.visitors().activeMerchantId();
        if (merchant != null) {
            Long departure = village.visitors().departureFor(merchant);
            if (departure != null && departure > now
                    && TravelerLifecycleRules.staysPastSunrise(departure, now, dayTime)) {
                staying.put(merchant, departure);
            }
        }
        for (Map.Entry<UUID, Long> entry : village.nomads().savedActiveNomads().entrySet()) {
            long departure = entry.getValue();
            if (departure > now && TravelerLifecycleRules.staysPastSunrise(departure, now, dayTime)) {
                staying.put(entry.getKey(), departure);
            }
        }
        return staying;
    }

    // =========================================================================
    // Setting up and striking camp
    // =========================================================================

    private void setUpCamp(ServerLevel level, long today, Map<UUID, Long> stayingTonight) {
        nightDay = today;
        village.markPersistentDataDirty();
        // Guest beds first; whoever has none camps.
        Map<UUID, Long> staying = new java.util.LinkedHashMap<>(stayingTonight);
        bookGuestBeds(level, staying);
        if (staying.isEmpty()) return;
        boolean merchant = staying.containsKey(village.visitors().activeMerchantId());
        boolean nomads = staying.size() > (merchant ? 1 : 0);
        BlockPos spot = findCampFire(level);
        if (spot == null) {
            noSpotTonight = true;
            DynamicVillages.LOGGER.info("[VillageManager {}] No camp spot for {} staying visitor(s).",
                    shortId(), staying.size());
            Component line = merchant && nomads
                    ? Tr.c("chat.dynamicvillages.travelers.no_camp.both", "[Village] The merchant and the nomads "
                            + "found nowhere to camp and will head off once the village is asleep.")
                    : merchant
                    ? Tr.c("chat.dynamicvillages.travelers.no_camp.merchant", "[Village] The merchant "
                            + "found nowhere to camp and will head off once the village is asleep.")
                    : Tr.c("chat.dynamicvillages.travelers.no_camp.nomads", "[Village] The nomads "
                            + "found nowhere to camp and will head off once the village is asleep.");
            announce(level, line.copy().withStyle(ChatFormatting.AQUA));
            nudgeTowardGuestBeds(level);
            return;
        }
        noSpotTonight = false;
        BlockState here = level.getBlockState(spot);
        if (!here.isAir()) level.destroyBlock(spot, false);
        Direction facing = Direction.Plane.HORIZONTAL.getRandomDirection(level.random);
        level.setBlock(spot, ModBlocks.TRAVELER_CAMPFIRE.get().defaultBlockState()
                .setValue(CampfireBlock.LIT, true)
                .setValue(CampfireBlock.SIGNAL_FIRE, false)
                .setValue(CampfireBlock.WATERLOGGED, false)
                .setValue(CampfireBlock.FACING, facing), 3);
        village.markNavigationDirtyAt(spot);
        fire = spot.immutable();
        campers.clear();
        campers.addAll(staying.keySet());
        while (campers.size() > RING.length) campers.remove(campers.size() - 1);
        DynamicVillages.LOGGER.info("[VillageManager {}] Travelers camp at {} ({} camper(s)).",
                shortId(), fire.toShortString(), campers.size());
        int x = fire.getX(), y = fire.getY(), z = fire.getZ();
        Component camping = merchant && nomads
                ? Tr.c("chat.dynamicvillages.travelers.camping.both",
                        "[Village] The merchant and the nomads are camping for the night at %1$s, %2$s, %3$s.", x, y, z)
                : merchant
                ? Tr.c("chat.dynamicvillages.travelers.camping.merchant",
                        "[Village] The merchant is camping for the night at %1$s, %2$s, %3$s.", x, y, z)
                : Tr.c("chat.dynamicvillages.travelers.camping.nomads",
                        "[Village] The nomads are camping for the night at %1$s, %2$s, %3$s.", x, y, z);
        announce(level, camping.copy().withStyle(ChatFormatting.AQUA));
        nudgeTowardGuestBeds(level);
    }

    /**
     * Visitors camp (or have nowhere at all) while the village has a tavern:
     * the beds it lacks are why. One gray line, once a night, since camp is
     * only set up once a night.
     */
    private void nudgeTowardGuestBeds(ServerLevel level) {
        if (tavernOf() == null) return;
        announce(level, Tr.c("chat.dynamicvillages.travelers.tavern_beds_hint",
                        "Beds in your tavern would give them somewhere to sleep.")
                .withStyle(ChatFormatting.GRAY));
    }

    /** Puts the fire out and forgets the camp; the campers have gone or are leaving. */
    private void strikeCamp(ServerLevel level, String reason) {
        if (fire != null && level.hasChunkAt(fire)) {
            if (level.getBlockState(fire).getBlock() instanceof TravelerCampfireBlock) {
                level.removeBlock(fire, false);
                village.markNavigationDirtyAt(fire);
            }
        } else if (fire != null) {
            return; // its chunk is unloaded: put it out once it loads again
        }
        DynamicVillages.LOGGER.info("[VillageManager {}] Camp struck: {}.", shortId(), reason);
        fire = null;
        campers.clear();
        village.markPersistentDataDirty();
    }

    // =========================================================================
    // Finding a spot
    // =========================================================================

    /**
     * The fire's position: the middle of an empty, flat 3x3 patch of natural
     * ground (grass, dirt, sand, gravel, snow) inside the village, near one of
     * its buildings but clear of doors, rooms, stalls, fields and paths, with
     * room to stand on every tile and a walk there from the gathering spot.
     * Buildings nearest the gathering spot are tried first.
     */
    @Nullable
    private BlockPos findCampFire(ServerLevel level) {
        NavGraph graph = village.getGraph();
        if (graph == null || graph.isEmpty()) return null;
        BlockPos gate = village.getVisitorGatheringSpot();
        List<Building> buildings = new ArrayList<>();
        for (Building building : village.getBuildings()) {
            if (building.isValid() && building.getDoorPos() != null) buildings.add(building);
        }
        buildings.sort(Comparator.comparingDouble(b -> b.getDoorPos().distSqr(gate)));
        int checked = 0;
        for (Building building : buildings) {
            BlockPos door = building.getDoorPos();
            for (int r = SEARCH_MIN; r <= SEARCH_MAX; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        if (++checked > MAX_CENTERS_CHECKED) return null;
                        BlockPos spot = campFireAt(level, graph, gate, buildings,
                                door.getX() + dx, door.getY(), door.getZ() + dz);
                        if (spot != null) return spot;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private BlockPos campFireAt(ServerLevel level, NavGraph graph, BlockPos gate,
                                List<Building> buildings, int cx, int y0, int cz) {
        int groundY = Integer.MIN_VALUE;
        for (int y = y0 + 3; y >= y0 - 4; y--) {
            BlockPos ground = new BlockPos(cx, y, cz);
            if (!level.hasChunkAt(ground)) return null;
            if (isNaturalGround(level.getBlockState(ground)) && isOpenAbove(level, ground)) {
                groundY = y;
                break;
            }
        }
        if (groundY == Integer.MIN_VALUE) return null;
        BlockPos center = new BlockPos(cx, groundY + 1, cz);
        for (Building building : buildings) {
            BlockPos door = building.getDoorPos();
            if (Math.max(Math.abs(door.getX() - cx), Math.abs(door.getZ() - cz)) < DOOR_CLEARANCE
                    && Math.abs(door.getY() - center.getY()) <= 3) return null;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos ground = new BlockPos(cx + dx, groundY, cz + dz);
                BlockPos feet = ground.above();
                if (!level.hasChunkAt(ground) || !village.contains(feet)) return null;
                if (!isNaturalGround(level.getBlockState(ground)) || !isOpenAbove(level, ground)) return null;
                if (village.isProtectedRoomBlock(feet)) return null;
                for (Building building : buildings) {
                    if (building.getNavMeshBlocks().contains(feet) || building.isNearRoom(feet)) return null;
                }
            }
        }
        // Nobody standing where the fire would light: they would be in the flames.
        if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(center)).isEmpty()) return null;
        // The campers walk there on the graph: the tiles beside the fire must
        // be on it and joined to the gathering spot.
        BlockPos side = center.offset(RING[0][0], 0, RING[0][1]);
        NavNode node = graph.nearestRoutable(side, 0, 1);
        if (node == null || !node.isRoutable() || node.degree() == 0) return null;
        if (!NavReachability.canReach(graph, node.feetPos(), gate)) return null;
        return center;
    }

    /** Ground the travellers may camp on: what a world grows, never what a player paves. */
    private static boolean isNaturalGround(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.CLAY);
    }

    /** Two blocks of room above, with at most short grass or a thin layer of snow in the way. */
    private static boolean isOpenAbove(ServerLevel level, BlockPos ground) {
        BlockPos feet = ground.above();
        BlockState at = level.getBlockState(feet);
        boolean clear = at.isAir() || at.is(Blocks.SHORT_GRASS) || at.is(Blocks.FERN)
                || at.is(Blocks.SNOW) && at.getValue(SnowLayerBlock.LAYERS) <= 1;
        return clear && level.getFluidState(feet).isEmpty() && level.getBlockState(feet.above()).isAir();
    }

    // =========================================================================
    // Leaving
    // =========================================================================

    /** A storm at dusk: whoever would leave this evening stays the night instead. */
    private void keepEveryoneOvernight(ServerLevel level, long now, long dayTime) {
        boolean merchant = village.visitors().stayOvernight(level, now, dayTime);
        int nomads = village.nomads().stayOvernight(level, now, dayTime);
        if (!merchant && nomads == 0) return;
        DynamicVillages.LOGGER.info("[VillageManager {}] Storm at dusk: visitors stay the night.", shortId());
        Component line = merchant && nomads > 0
                ? Tr.c("chat.dynamicvillages.travelers.storm.both",
                        "[Village] The storm keeps the merchant and the nomads in the village tonight.")
                : merchant
                ? Tr.c("chat.dynamicvillages.travelers.storm.merchant",
                        "[Village] The storm keeps the merchant in the village tonight.")
                : Tr.c("chat.dynamicvillages.travelers.storm.nomads",
                        "[Village] The storm keeps the nomads in the village tonight.");
        announce(level, line.copy().withStyle(ChatFormatting.AQUA));
    }

    /** No camp: those who were to leave in the morning go now; the founding nomads stay up. */
    private void sendOffMorningGuests(ServerLevel level, long now, long dayTime) {
        boolean merchant = false;
        int nomads = 0;
        UUID merchantId = village.visitors().activeMerchantId();
        if (merchantId != null) {
            Long departure = village.visitors().departureFor(merchantId);
            if (departure != null && TravelerLifecycleRules.leavesAfterTonight(departure, now, dayTime)
                    && !isBooked(merchantId)) {
                village.visitors().leaveNow(now);
                merchant = true;
            }
        }
        for (Map.Entry<UUID, Long> entry : village.nomads().savedActiveNomads().entrySet()) {
            if (TravelerLifecycleRules.leavesAfterTonight(entry.getValue(), now, dayTime)
                    && !isBooked(entry.getKey())) {
                village.nomads().leaveNow(entry.getKey(), now);
                nomads++;
            }
        }
        if (merchant || nomads > 0) {
            DynamicVillages.LOGGER.info("[VillageManager {}] No camp: {} visitor(s) leave as the village sleeps.",
                    shortId(), nomads + (merchant ? 1 : 0));
        }
    }

    private void announcePackingUp(ServerLevel level, long now) {
        packingAnnounced.removeIf(at -> at < now - PACKING_UP_TICKS);
        UUID merchantId = village.visitors().activeMerchantId();
        if (merchantId != null) {
            Long departure = village.visitors().departureFor(merchantId);
            // The quiet time as for the nomads: a leave time that keeps moving (a booked stay, a
            // late customer) is a new time each move, and each one said the line again.
            if (departure != null && departure > now && departure - now <= PACKING_UP_TICKS
                    && packingAnnounced.add(departure) && now >= merchantPackingQuietUntil) {
                merchantPackingQuietUntil = now + NOMAD_PACKING_QUIET_TICKS;
                announce(level, Tr.c("chat.dynamicvillages.travelers.packing.merchant",
                                "[Village] The merchant is packing up and will leave soon.")
                        .withStyle(ChatFormatting.YELLOW));
            }
        }
        boolean nomadsSoon = false;
        for (long departure : village.nomads().savedActiveNomads().values()) {
            if (departure > now && departure - now <= PACKING_UP_TICKS && packingAnnounced.add(departure)) {
                nomadsSoon = true;
            }
        }
        // One line per group: their leave times are spread over a minute or two.
        if (nomadsSoon && now >= nomadPackingQuietUntil) {
            nomadPackingQuietUntil = now + NOMAD_PACKING_QUIET_TICKS;
            announce(level, Tr.c("chat.dynamicvillages.travelers.packing.nomads",
                            "[Village] The nomads are packing up and will leave soon.")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    /** Every villager that sleeps at night is asleep (guards on duty do not count). */
    private boolean villageAsleep() {
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.getProfession().isDefender()) continue;
            boolean asleep = npc.isSleeping()
                    || model.brain().currentActivity() instanceof GoHomeActivity home && home.isResting();
            if (!asleep) return false;
        }
        return true;
    }

    // =========================================================================
    // Upsetting the campers
    // =========================================================================

    /**
     * A player breaks (or douses) a travellers' fire. The first time it is
     * refused with a warning; a second time within 30 seconds it goes, and the
     * campers are upset. Returns whether the fire may go. A fire no camp
     * claims any more goes without a word.
     */
    public static boolean onPlayerBreaksFire(ServerLevel level, Player player, BlockPos pos) {
        for (VillageManager manager : VillageManagerRegistry.get(level.getServer()).all()) {
            TravelerCamp camp = manager.travelerCamp();
            if (manager.getLevel() == level && pos.equals(camp.fire)) {
                return camp.playerBreaksFire(level, player);
            }
        }
        return true;
    }

    private boolean playerBreaksFire(ServerLevel level, Player player) {
        long now = level.getGameTime();
        Map<UUID, Long> staying = stayingVisitors(now, level.getDayTime());
        if (campers.stream().noneMatch(staying::containsKey)) {
            fire = null;
            campers.clear();
            village.markPersistentDataDirty();
            return true;
        }
        boolean held = isHeldPress(lastFirePress, player, now);
        Long warned = warnedAt.get(player.getUUID());
        if (warned == null || now - warned > WARNING_LASTS_TICKS) {
            warnedAt.put(player.getUUID(), now);
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.travelers.fire_warning",
                    "Travelers are camping here. Breaking their fire will upset them.").withStyle(ChatFormatting.GOLD));
            return false;
        }
        // The first break or shovel douse, held down: not yet a second one.
        if (held) return false;
        warnedAt.remove(player.getUUID());
        upset(level, player, staying);
        return true;
    }

    private void upset(ServerLevel level, Player player, Map<UUID, Long> staying) {
        long now = level.getGameTime();
        long dayTime = level.getDayTime();
        snubDay = TravelerLifecycleRules.dayIndex(dayTime) + 1;
        boolean merchantLeft = false;
        boolean nomadsLeft = false;
        boolean foundingStayed = false;
        UUID merchantId = village.visitors().activeMerchantId();
        for (UUID id : campers) {
            Long departure = staying.get(id);
            if (departure == null) continue;
            if (id.equals(merchantId)) {
                village.visitors().leaveNow(now);
                merchantLeft = true;
            } else if (TravelerLifecycleRules.leavesAfterTonight(departure, now, dayTime)) {
                village.nomads().leaveNow(id, now);
                village.nomads().markOffended(id);
                nomadsLeft = true;
            } else {
                foundingStayed = true; // the founding group: upset, but it stays and can still join
            }
        }
        fire = null;
        campers.clear();
        village.markPersistentDataDirty();
        DynamicVillages.LOGGER.info("[VillageManager {}] {} broke the travelers' fire: no visitors on day {}.",
                shortId(), player.getName().getString(), snubDay);
        if (merchantLeft) {
            say(level, player, Tr.c("chat.dynamicvillages.travelers.fire_broken.merchant",
                    "[Merchant] You broke our fire! I'm leaving tonight, and no traveler will come tomorrow."));
        }
        if (nomadsLeft) {
            say(level, player, Tr.c("chat.dynamicvillages.travelers.fire_broken.nomads",
                    "[Nomads] You put out our fire! We're leaving, and we won't join "
                    + "a village like this. Nobody will visit tomorrow."));
        }
        if (foundingStayed) {
            say(level, player, Tr.c("chat.dynamicvillages.travelers.fire_broken.founding",
                    "[Nomads] That was our fire! We'll stay, but we won't forget this."));
        }
    }

    private void say(ServerLevel level, Player player, Component text) {
        Component line = text.copy().withStyle(ChatFormatting.RED);
        if (player instanceof ServerPlayer serverPlayer && !isInAnnounceRange(serverPlayer)) {
            serverPlayer.sendSystemMessage(line);
        }
        announce(level, line);
    }

    /**
     * The first visitor of the night walks into the tavern: one line, with the
     * bard's welcome tune when a bard is there.
     */
    public void welcomeAtTavern(ServerLevel level, com.alex.test.village.building.TavernBuilding tavern) {
        long today = TravelerLifecycleRules.dayIndex(level.getDayTime());
        if (welcomedDay == today) return;
        welcomedDay = today;
        boolean bard = false;
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc != null && npc.isAlive()
                    && npc.getProfession() == com.alex.test.village.villagers.core.Profession.BARD
                    && tavern.getNavMeshBlocks().contains(npc.blockPosition())) {
                bard = true;
                break;
            }
        }
        announce(level, (bard
                ? Tr.c("chat.dynamicvillages.travelers.welcome_bard",
                        "[Village] The bard strikes up a welcome tune as the travelers come into the tavern.")
                : Tr.c("chat.dynamicvillages.travelers.welcome",
                        "[Village] The travelers are spending the evening at the tavern."))
                .withStyle(ChatFormatting.AQUA));
    }

    // =========================================================================
    // Chat
    // =========================================================================

    private void announce(ServerLevel level, Component message) {
        for (ServerPlayer player : level.players()) {
            if (isInAnnounceRange(player)) player.sendSystemMessage(message);
        }
    }

    private boolean isInAnnounceRange(ServerPlayer player) {
        BlockPos bell = village.getBellPos();
        return village.contains(player.blockPosition())
                || player.distanceToSqr(bell.getX() + 0.5, bell.getY() + 0.5, bell.getZ() + 0.5)
                        <= ANNOUNCE_RADIUS * ANNOUNCE_RADIUS;
    }

    private String shortId() { return village.getVillageId().toString().substring(0, 8); }

    // =========================================================================
    // Saving
    // =========================================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (fire != null) tag.put("fire", NbtUtils.writeBlockPos(fire));
        tag.putLong("nightDay", nightDay);
        tag.putBoolean("noSpot", noSpotTonight);
        ListTag list = new ListTag();
        for (UUID id : campers) list.add(NbtUtils.createUUID(id));
        tag.put("campers", list);
        tag.putLong("snubDay", snubDay);
        tag.putLong("scriptedStayDay", scriptedStayDay);
        tag.putLong("stormCheckedDay", stormCheckedDay);
        ListTag beds = new ListTag();
        for (Map.Entry<BlockPos, Booking> entry : bookings.entrySet()) {
            CompoundTag bed = new CompoundTag();
            bed.put("bed", NbtUtils.writeBlockPos(entry.getKey()));
            bed.putUUID("visitor", entry.getValue().visitor());
            bed.putBoolean("merchant", entry.getValue().merchant());
            bed.putBoolean("slept", sleptInBed.contains(entry.getValue().visitor()));
            beds.add(bed);
        }
        tag.put("guestBeds", beds);
        tag.putLong("bookingDay", bookingDay);
        return tag;
    }

    public void load(CompoundTag tag) {
        fire = NbtUtils.readBlockPos(tag, "fire").orElse(null);
        nightDay = tag.contains("nightDay") ? tag.getLong("nightDay") : Long.MIN_VALUE;
        noSpotTonight = tag.getBoolean("noSpot");
        campers.clear();
        for (Tag id : tag.getList("campers", Tag.TAG_INT_ARRAY)) campers.add(NbtUtils.loadUUID(id));
        snubDay = tag.contains("snubDay") ? tag.getLong("snubDay") : Long.MIN_VALUE;
        scriptedStayDay = tag.contains("scriptedStayDay") ? tag.getLong("scriptedStayDay") : Long.MIN_VALUE;
        stormCheckedDay = tag.contains("stormCheckedDay") ? tag.getLong("stormCheckedDay") : Long.MIN_VALUE;
        bookings.clear();
        sleptInBed.clear();
        bookingDay = tag.contains("bookingDay") ? tag.getLong("bookingDay") : nightDay;
        for (Tag entry : tag.getList("guestBeds", Tag.TAG_COMPOUND)) {
            CompoundTag bed = (CompoundTag) entry;
            BlockPos pos = NbtUtils.readBlockPos(bed, "bed").orElse(null);
            if (pos == null || !bed.hasUUID("visitor")) continue;
            bookings.put(pos, new Booking(bed.getUUID("visitor"), bed.getBoolean("merchant")));
            if (bed.getBoolean("slept")) sleptInBed.add(bed.getUUID("visitor"));
        }
    }
}
