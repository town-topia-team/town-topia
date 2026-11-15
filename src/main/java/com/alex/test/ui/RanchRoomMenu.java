package com.alex.test.ui;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.network.RanchRoomPayloads;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.butcher.lead.LeadSupply;
import com.alex.test.village.villagers.butcher.lead.LedAnimals;
import com.alex.test.village.villagers.butcher.lead.RanchStrays;
import com.alex.test.village.villagers.rancher.RanchChecks;
import com.alex.test.village.villagers.rancher.RanchHappenings;
import com.alex.test.village.villagers.rancher.RanchClaims;
import com.alex.test.village.villagers.rancher.RanchKeepers;
import com.alex.test.village.villagers.rancher.RanchOutside;
import com.alex.test.village.villagers.rancher.RanchRoundUp;
import com.alex.test.village.villagers.rancher.RanchStatus;
import com.alex.test.village.villagers.rancher.RancherBalance;
import com.alex.test.village.villagers.rancher.behavior.RancherActivity;
import com.alex.test.village.villagers.supplies.BucketNeeds;
import com.alex.test.village.villagers.tools.GetToolActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * One Ranch, looked at from its token: the pen's floor, pond and gate, and the animals standing in it.
 * Round 3 adds why each kind is bred or not, round 4 the village's Ranchers, round 5 the strays and
 * loose animals outside, round 6 the Butchery and keep counts for the check list, round 7 the pen's
 * recent happenings, round 8 opening it from the Mayor. Nothing here changes
 * the pen or a Rancher (docs/plans/2026-10-24-ranch-window.md).
 *
 * <p>The snapshot is sent when the screen opens and again once a second while the screen is open
 * and something changed (an animal walked, a baby was born, the pen was scanned again).
 */
public final class RanchRoomMenu extends AbstractContainerMenu implements NpcUiMenu {

    /** {@link Beast#leader} of an animal tied to a fence post by a lead (no player name has a '#'). */
    public static final String FENCE_POST = "#fence";

    /**
     * One animal in the pen. {@code type} is its entity id ("minecraft:pig"); the position is its
     * feet, rounded to a 16th of a block so a grazing animal does not resend the pen every second;
     * {@code facing} is the nearest side it looks to ({@link Direction#get2DDataValue}).
     *
     * <p>The clocks are game times, so they stay the same while nothing happens (0 = none):
     * {@code grownAt} a baby grows up, {@code breedAt} an adult may breed again, {@code loveUntil} it
     * stops looking for a mate, {@code productAt} a grown chicken's next egg or a grown cow's next hide
     * is due (0 or past = ready). {@code wool} is a woolly sheep's colour id, else -1. Names are empty
     * when nobody: {@code leader} leads it, {@code tender} (a Rancher) is working on it, {@code name} is
     * the name a player gave it. {@code entityId} is its network id, so the window can draw the client's own copy.
     */
    public record Beast(String type, float x, float y, float z, int facing, boolean baby, long grownAt,
                        long breedAt, long loveUntil, long productAt, int wool, String leader, String tender,
                        String name, boolean saddled, int entityId) {
        public BlockPos tile() {
            return BlockPos.containing(x, y + 0.2, z);
        }
    }

    /**
     * Everything the screen shows. {@code capacity} is how many of each kind the pen holds;
     * {@code milkWanted} the buckets of milk the village's cooks want now (cows are milked only then);
     * {@code status} why each kind is bred or not; {@code keepers} the village's Ranchers near a
     * player; {@code outside} the pen's strays and the loose animals near it; {@code checks} where the
     * extra animals go and what the Butchers keep (all empty while the pen is not loaded); {@code recent}
     * the pen's last few happenings, newest first (kept even while it is not loaded).
     */
    public record Snapshot(boolean valid, List<BlockPos> floor, List<BlockPos> water, BlockPos frame,
                           int capacity, List<Beast> animals, int milkWanted, RanchStatus.Report status,
                           List<RanchKeepers.Keeper> keepers, RanchOutside.Result outside,
                           RanchChecks.Report checks, List<RanchHappenings.Happening> recent) {}

    private static final RanchStatus.Report NO_STATUS =
            new RanchStatus.Report(List.of(), new RanchStatus.Products(false, false));
    /** The reasons are worked out this often, not every second: they count what storage holds. */
    private static final long STATUS_TICKS = 100;
    /** Whether storage holds a lead each Rancher without one may take, asked again every {@link #STATUS_TICKS}. */
    private final Map<UUID, Boolean> leadInStorage = new HashMap<>();
    private long leadDue;

    @Nullable private final VillageManager village;
    @Nullable private final RanchBuilding ranch;
    private final Player viewer;
    private final UUID frameId;
    private final BlockPos gatePos;
    /** The Mayor that opened this screen from its building overview (round 8); null when opened from the token. */
    @Nullable private final MayorEntity mayor;
    /** Entity id of that Mayor on both sides, or -1. */
    private final int mayorId;
    private Snapshot snapshot;
    /** Bumped whenever a new snapshot arrives, so the screen knows to redraw its model. */
    private int version;
    private long nextRefresh;
    @Nullable private RanchStatus.Report status;
    private long statusDue;

    public RanchRoomMenu(int id, Inventory inventory, VillageManager village, RanchBuilding ranch,
                         @Nullable MayorEntity mayor) {
        super(DynamicVillages.RANCH_ROOM_MENU.get(), id);
        this.village = village;
        this.ranch = ranch;
        this.viewer = inventory.player;
        this.frameId = ranch.getItemFrameId();
        this.gatePos = ranch.getDoorPos();
        this.mayor = mayor;
        this.mayorId = mayor == null ? -1 : mayor.getId();
        ServerLevel level = viewer.level() instanceof ServerLevel server ? server : null;
        this.snapshot = snapshot(level, village, ranch, status(level), this::leadInStorage);
    }

    /** Looks through the chests once per {@link #STATUS_TICKS} for each Rancher, not every second. */
    private boolean leadInStorage(Activity.Context ctx) {
        long now = ctx.level().getGameTime();
        if (now >= leadDue) {
            leadInStorage.clear();
            leadDue = now + STATUS_TICKS;
        }
        return leadInStorage.computeIfAbsent(ctx.npc().getUUID(),
                id -> GetToolActivity.canAcquire(ctx, LeadSupply.REQUIREMENT));
    }

    /** The reasons, worked out again once {@link #STATUS_TICKS} have passed. */
    private RanchStatus.Report status(@Nullable ServerLevel level) {
        if (level == null || village == null || ranch == null || !ranch.isValid()
                || !level.isLoaded(ranch.getDoorPos())) return NO_STATUS;
        if (status == null || level.getGameTime() >= statusDue) {
            status = RanchStatus.compute(level, village, ranch);
            statusDue = level.getGameTime() + STATUS_TICKS;
        }
        return status;
    }

    public RanchRoomMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        super(DynamicVillages.RANCH_ROOM_MENU.get(), id);
        this.village = null;
        this.ranch = null;
        this.mayor = null;
        this.viewer = inventory.player;
        this.frameId = buffer.readUUID();
        this.gatePos = buffer.readBlockPos();
        this.snapshot = readSnapshot(buffer);
        this.mayorId = buffer.readVarInt();
    }

    public void writeOpeningData(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(frameId);
        buffer.writeBlockPos(gatePos);
        writeSnapshot(buffer, snapshot);
        buffer.writeVarInt(mayorId);
    }

    public static String titleFor(RanchBuilding ranch) {
        return ranch.getDisplayName();
    }

    // =========================================================================
    // Snapshot
    // =========================================================================

    /**
     * The pen as the last scan left it, and the animals inside it now. No animal is looked for
     * while the gate's chunk is not loaded (the pen's water may be scanned once otherwise).
     */
    public static Snapshot snapshot(@Nullable ServerLevel level, VillageManager village, RanchBuilding ranch,
                                    RanchStatus.Report status, Predicate<Activity.Context> leadInStorage) {
        List<BlockPos> floor = ranch.getNavMeshBlocks().stream().sorted().toList();
        List<Beast> animals = new ArrayList<>();
        int milkWanted = 0;
        List<RanchKeepers.Keeper> keepers = List.of();
        RanchOutside.Result outside = RanchOutside.NONE;
        RanchChecks.Report checks = RanchChecks.NONE;
        if (level != null && ranch.isValid() && level.isLoaded(ranch.getDoorPos())) {
            List<Animal> inside = new ArrayList<>(ranch.animalsInside(level));
            inside.sort(Comparator.comparing(Animal::getUUID));
            long eggInterval = eggInterval(village);
            for (Animal animal : inside) animals.add(beast(level, animal, eggInterval));
            milkWanted = BucketNeeds.milkWanted(village, level);
            keepers = RanchKeepers.compute(level, village, ranch, leadInStorage);
            outside = RanchOutside.compute(level, village, ranch, leadInStorage);
            checks = RanchChecks.compute(level, village, ranch);
        }
        List<BlockPos> water = ranch.knownWater().stream().sorted().toList();
        return new Snapshot(ranch.isValid(), floor, water, ranch.getItemFramePos(), ranch.capacityPerSpecies(),
                List.copyOf(animals), milkWanted, status, keepers, outside, checks, RanchHappenings.recent(ranch));
    }

    private static Beast beast(ServerLevel level, Animal animal, long eggInterval) {
        long now = level.getGameTime();
        int age = animal.getAge();
        long grownAt = animal.isBaby() ? now - age : 0;
        long breedAt = !animal.isBaby() && age > 0 ? now + age : 0;
        long loveUntil = animal.isInLove() ? now + animal.getInLoveTime() : 0;
        long productAt = 0;
        if (!animal.isBaby() && animal.getType() == EntityType.CHICKEN) {
            Long last = RancherActivity.lastEggTick(animal);
            productAt = last == null ? 0 : last + eggInterval;
        } else if (!animal.isBaby() && animal instanceof Cow) {
            Long last = RancherActivity.lastShedTick(animal);
            productAt = last == null ? 0 : last + RancherActivity.SHED_INTERVAL_TICKS;
        }
        int wool = animal instanceof Sheep sheep && !sheep.isSheared() ? sheep.getColor().getId() : -1;
        LedAnimals.Lead lead = LedAnimals.leadOf(animal);
        String leader = lead != null ? nameOf(level.getEntity(lead.holder()))
                : animal.isLeashed() ? nameOf(animal.getLeashHolder()) : "";
        UUID tending = RanchClaims.claimant(animal.getUUID(), now);
        String tender = tending == null ? "" : nameOf(level.getEntity(tending));
        String name = animal.hasCustomName() ? animal.getCustomName().getString() : "";
        return new Beast(BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).toString(),
                sixteenth(animal.getX()), sixteenth(animal.getY()), sixteenth(animal.getZ()),
                Direction.fromYRot(animal.getYRot()).get2DDataValue(), animal.isBaby(), grownAt, breedAt,
                loveUntil, productAt, wool, leader, tender, name, RanchStrays.isSaddled(animal), animal.getId());
    }

    /**
     * How long a grown chicken takes between eggs here: the shortest wait any loaded Rancher of the
     * village has (Egg Finder shortens it), the plain wait when none is loaded.
     */
    private static long eggInterval(VillageManager village) {
        long best = RancherBalance.EGG_INTERVAL_TICKS;
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || npc.getProfession() != Profession.RANCHER || model.brain() == null) continue;
            best = Math.min(best, RancherBalance.eggIntervalTicks(model.brain().rancherSkillLevel()));
        }
        return best;
    }

    /** A villager's own name, a player's name, {@link #FENCE_POST}, "?" for anyone else; empty for nobody. */
    private static String nameOf(@Nullable Entity entity) {
        if (entity == null) return "";
        if (entity instanceof NpcEntity npc) return npc.givenName() != null ? npc.givenName() : "?";
        if (entity instanceof Player player) return player.getGameProfile().getName();
        if (entity instanceof LeashFenceKnotEntity) return FENCE_POST;
        return "?";
    }

    private static float sixteenth(double value) {
        return Math.round(value * 16) / 16f;
    }

    public static void writeSnapshot(FriendlyByteBuf buffer, Snapshot snapshot) {
        buffer.writeBoolean(snapshot.valid());
        buffer.writeCollection(snapshot.floor(), (buf, pos) -> buf.writeBlockPos(pos));
        buffer.writeCollection(snapshot.water(), (buf, pos) -> buf.writeBlockPos(pos));
        buffer.writeBlockPos(snapshot.frame());
        buffer.writeVarInt(snapshot.capacity());
        buffer.writeCollection(snapshot.animals(), (buf, beast) -> {
            buf.writeUtf(beast.type());
            buf.writeFloat(beast.x());
            buf.writeFloat(beast.y());
            buf.writeFloat(beast.z());
            buf.writeByte(beast.facing());
            buf.writeBoolean(beast.baby());
            buf.writeVarLong(beast.grownAt());
            buf.writeVarLong(beast.breedAt());
            buf.writeVarLong(beast.loveUntil());
            buf.writeVarLong(beast.productAt());
            buf.writeVarInt(beast.wool());
            buf.writeUtf(beast.leader());
            buf.writeUtf(beast.tender());
            buf.writeUtf(beast.name());
            buf.writeBoolean(beast.saddled());
            buf.writeVarInt(beast.entityId());
        });
        buffer.writeVarInt(snapshot.milkWanted());
        writeStatus(buffer, snapshot.status());
        buffer.writeCollection(snapshot.keepers(), (buf, keeper) -> {
            buf.writeUtf(keeper.name());
            buf.writeVarInt(keeper.doing().ordinal());
            buf.writeUtf(keeper.kind() == null ? "" : EntityType.getKey(keeper.kind()).toString());
            buf.writeBoolean(keeper.elsewhere());
            buf.writeBoolean(keeper.work());
            buf.writeBoolean(keeper.collecting());
            buf.writeBoolean(keeper.breeding());
            buf.writeBoolean(keeper.roundUp());
            buf.writeVarInt(keeper.range());
            buf.writeVarInt(keeper.managed());
            buf.writeBoolean(keeper.shears());
            buf.writeVarInt(keeper.lead().ordinal());
            buf.writeVarInt(keeper.buckets());
        });
        buffer.writeCollection(snapshot.outside().listed(), (buf, out) -> {
            buf.writeUtf(out.type());
            buf.writeFloat(out.x());
            buf.writeFloat(out.y());
            buf.writeFloat(out.z());
            buf.writeByte(out.facing());
            buf.writeBoolean(out.baby());
            buf.writeVarInt(out.wool());
            buf.writeBoolean(out.stray());
            buf.writeBoolean(out.letGo());
            buf.writeVarInt(out.distance());
            buf.writeVarInt(out.why().reason().ordinal());
            buf.writeVarInt(out.why().a());
            buf.writeVarInt(out.why().b());
            buf.writeUtf(out.leader());
            buf.writeVarInt(out.entityId());
        });
        buffer.writeVarInt(snapshot.outside().more());
        RanchChecks.Report checks = snapshot.checks();
        buffer.writeVarInt(checks.link().ordinal());
        buffer.writeVarInt(checks.distance());
        buffer.writeBoolean(checks.paused());
        buffer.writeVarInt(checks.butchers().ordinal());
        buffer.writeCollection(checks.keep(), FriendlyByteBuf::writeInt);
        buffer.writeCollection(snapshot.recent(), (buf, line) -> {
            buf.writeVarInt(line.kind().ordinal());
            buf.writeUtf(line.who());
            buf.writeUtf(line.animal());
            buf.writeVarInt(line.count());
            buf.writeVarInt(line.amount());
            buf.writeVarInt(line.why().ordinal());
            buf.writeVarLong(line.at());
        });
    }

    private static RanchHappenings.Happening readHappening(FriendlyByteBuf buf) {
        RanchHappenings.Kind[] kinds = RanchHappenings.Kind.values();
        RanchHappenings.GaveUp[] whys = RanchHappenings.GaveUp.values();
        RanchHappenings.Kind kind = kinds[Math.min(kinds.length - 1, Math.max(0, buf.readVarInt()))];
        String who = buf.readUtf();
        String animal = buf.readUtf();
        int count = buf.readVarInt(), amount = buf.readVarInt();
        RanchHappenings.GaveUp why = whys[Math.min(whys.length - 1, Math.max(0, buf.readVarInt()))];
        return new RanchHappenings.Happening(kind, who, animal, count, amount, why, buf.readVarLong());
    }

    private static RanchChecks.Report readChecks(FriendlyByteBuf buffer) {
        RanchChecks.Link[] links = RanchChecks.Link.values();
        RanchChecks.Butchers[] butchers = RanchChecks.Butchers.values();
        RanchChecks.Link link = links[Math.min(links.length - 1, Math.max(0, buffer.readVarInt()))];
        int distance = buffer.readVarInt();
        boolean paused = buffer.readBoolean();
        RanchChecks.Butchers who = butchers[Math.min(butchers.length - 1, Math.max(0, buffer.readVarInt()))];
        return new RanchChecks.Report(link, distance, paused, who, buffer.readList(FriendlyByteBuf::readInt));
    }

    private static RanchOutside.Outsider readOutsider(FriendlyByteBuf buf) {
        RanchRoundUp.Reason[] reasons = RanchRoundUp.Reason.values();
        String type = buf.readUtf();
        float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
        int facing = buf.readByte();
        boolean baby = buf.readBoolean();
        int wool = buf.readVarInt();
        boolean stray = buf.readBoolean(), letGo = buf.readBoolean();
        int distance = buf.readVarInt();
        RanchRoundUp.Reason reason = reasons[Math.min(reasons.length - 1, Math.max(0, buf.readVarInt()))];
        RanchRoundUp.Why why = new RanchRoundUp.Why(reason, buf.readVarInt(), buf.readVarInt());
        String leader = buf.readUtf();
        return new RanchOutside.Outsider(type, x, y, z, facing, baby, wool, stray, letGo, distance, why, leader, buf.readVarInt());
    }

    private static RanchKeepers.Keeper readKeeper(FriendlyByteBuf buf) {
        RanchKeepers.Doing[] doings = RanchKeepers.Doing.values();
        RanchKeepers.Lead[] leads = RanchKeepers.Lead.values();
        String name = buf.readUtf();
        RanchKeepers.Doing doing = doings[Math.min(doings.length - 1, Math.max(0, buf.readVarInt()))];
        String kind = buf.readUtf();
        return new RanchKeepers.Keeper(name, doing, kind.isEmpty() ? null : EntityType.byString(kind).orElse(null),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                leads[Math.min(leads.length - 1, Math.max(0, buf.readVarInt()))], buf.readVarInt());
    }

    private static void writeStatus(FriendlyByteBuf buffer, RanchStatus.Report status) {
        buffer.writeCollection(status.kinds(), (buf, line) -> {
            buf.writeUtf(EntityType.getKey(line.kind()).toString());
            buf.writeVarInt(line.reason().ordinal());
            buf.writeVarLong(line.readyAt());
            buf.writeVarInt(line.carried());
            buf.writeVarInt(line.perPair());
            buf.writeCollection(line.stock(), (b, stock) -> {
                b.writeUtf(BuiltInRegistries.ITEM.getKey(stock.item()).toString());
                b.writeVarInt(stock.stored());
            });
        });
        buffer.writeBoolean(status.products().collectingOff());
        buffer.writeBoolean(status.products().noShears());
    }

    private static RanchStatus.Report readStatus(FriendlyByteBuf buffer) {
        RanchStatus.Reason[] reasons = RanchStatus.Reason.values();
        List<RanchStatus.KindLine> kinds = buffer.readList(buf -> {
            EntityType<?> kind = EntityType.byString(buf.readUtf()).orElse(EntityType.PIG);
            RanchStatus.Reason reason = reasons[Math.min(reasons.length - 1, Math.max(0, buf.readVarInt()))];
            long readyAt = buf.readVarLong();
            int carried = buf.readVarInt();
            int perPair = buf.readVarInt();
            List<RanchStatus.Stock> stock = buf.readList(b -> new RanchStatus.Stock(
                    BuiltInRegistries.ITEM.get(ResourceLocation.parse(b.readUtf())), b.readVarInt()));
            return new RanchStatus.KindLine(kind, reason, readyAt, carried, perPair, stock);
        });
        return new RanchStatus.Report(kinds, new RanchStatus.Products(buffer.readBoolean(), buffer.readBoolean()));
    }

    public static Snapshot readSnapshot(FriendlyByteBuf buffer) {
        boolean valid = buffer.readBoolean();
        List<BlockPos> floor = buffer.readList(buf -> buf.readBlockPos());
        List<BlockPos> water = buffer.readList(buf -> buf.readBlockPos());
        BlockPos frame = buffer.readBlockPos();
        int capacity = buffer.readVarInt();
        List<Beast> animals = buffer.readList(buf -> new Beast(buf.readUtf(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readByte(), buf.readBoolean(), buf.readVarLong(), buf.readVarLong(),
                buf.readVarLong(), buf.readVarLong(), buf.readVarInt(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                buf.readBoolean(), buf.readVarInt()));
        int milkWanted = buffer.readVarInt();
        RanchStatus.Report status = readStatus(buffer);
        List<RanchKeepers.Keeper> keepers = buffer.readList(RanchRoomMenu::readKeeper);
        List<RanchOutside.Outsider> outside = buffer.readList(RanchRoomMenu::readOutsider);
        RanchOutside.Result outsideResult = new RanchOutside.Result(outside, buffer.readVarInt());
        return new Snapshot(valid, floor, water, frame, capacity, animals, milkWanted, status, keepers,
                outsideResult, readChecks(buffer), buffer.readList(RanchRoomMenu::readHappening));
    }

    // =========================================================================
    // Access
    // =========================================================================

    /** Client: a new snapshot from the server. */
    public void applySnapshot(Snapshot next) {
        snapshot = next;
        version++;
    }

    public UUID frameId() { return frameId; }
    public BlockPos gatePos() { return gatePos; }
    public Snapshot snapshot() { return snapshot; }
    public int version() { return version; }
    /** The Mayor's entity id when opened from its Buildings page, else -1. */
    public int mayorId() { return mayorId; }

    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (!(viewer instanceof ServerPlayer player) || village == null || ranch == null
                || !stillValid(player) || player.serverLevel().getGameTime() < nextRefresh) return;
        nextRefresh = player.serverLevel().getGameTime() + 20;
        Snapshot next = snapshot(player.serverLevel(), village, ranch, status(player.serverLevel()), this::leadInStorage);
        if (next.equals(snapshot)) return;
        snapshot = next;
        PacketDistributor.sendToPlayer(player, new RanchRoomPayloads.Sync(frameId, next));
    }

    @Override public boolean stillValid(Player player) {
        if (ranch == null) return true;
        if (village == null || village.getLevel() != player.level() || !village.getBuildings().contains(ranch)) {
            return false;
        }
        // Opened from the Mayor: the player stands at the Mayor, not at the pen.
        if (mayor != null) return mayor.isAlive() && player.distanceToSqr(mayor) < 64.0;
        return player.distanceToSqr(gatePos.getX() + 0.5, gatePos.getY() + 0.5, gatePos.getZ() + 0.5) < 1024.0;
    }

    /** The Mayor that opened it keeps facing the player while the window is open. */
    @Override public boolean isForNpc(NpcEntity npc) {
        return mayor != null && mayor == npc;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
}
