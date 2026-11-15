package com.alex.test.village.economy.away;

import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.villagers.butcher.lead.LedAnimals;
import com.alex.test.village.villagers.butcher.lead.RanchStrays;
import com.alex.test.village.villagers.rancher.RanchAnimals;
import com.alex.test.village.villagers.rancher.RanchBreedTurns;
import com.alex.test.village.villagers.rancher.RancherBalance;
import com.alex.test.village.villagers.rancher.behavior.RancherActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The village's pens for one catch-up (round 5 of the plan): every cow, sheep,
 * pig and chicken in a loaded pen as a record, worked by the away ranchers
 * ({@code jobs/RancherAway}), babies born and grown day by day, the grass the
 * shorn sheep eat; written to the world at WRITE as "if still X, make Y"
 * ({@link AwayWorld}). A pen whose chunks or animals are not loaded is left out.
 *
 * <p>Time is run ticks: 0 the moment the village fell asleep, the counted time
 * only. The egg and hide timers read from the animals go onto that clock (never
 * later than 0); at WRITE they go back onto the game clock from the moment of
 * return, so the time not counted never pays a second egg (old bug R3).
 *
 * <p>Live rules it copies, and where they live:
 * <ul>
 *   <li>The pen's animals ({@code RanchBuilding.animalsInside}) and room per kind
 *       ({@code capacityPerSpecies}: heads, as {@code RancherActivity.halfHeads} counts them).</li>
 *   <li>Breeding: both parents rest {@link #BREED_REST_TICKS} (vanilla's age 6000), the baby starts
 *       at {@code AgeableMob.BABY_START_AGE} and grows a tick a tick, a quarter faster on a day a
 *       Good Breeder works ({@code RancherActivity.boostBabies}); a grown one breeds from the next day.
 *       Animals fed just before everyone left pair at the start, their food already eaten.</li>
 *   <li>Wool back: a shorn sheep eats grass (vanilla {@code EatBlockGoal}: one try in 1000 every
 *       other tick, about every {@link #REGROW_TICKS} while it stands on grass, so slower in a pen
 *       with little grass): short grass first (gone), else the grass block under it turns to dirt
 *       and grows back {@link #GRASS_BACK_TICKS} later while other grass is left to spread from.
 *       A stone pen gives one shear a sheep (old bug R2).</li>
 *   <li>Culls (round 6, {@code jobs/ButcherAway}): the animal leaves the records at once, so the
 *       ranchers' room and the next butcher see it gone; at WRITE it is removed from the world only if
 *       it is still nobody's pet or ride and on no lead.</li>
 * </ul>
 * Not done away: lambs growing faster by eating, woolly sheep eating, strays.
 */
public final class AwayPens {

    /** A timer never set: ready at once. */
    public static final long NONE = Long.MIN_VALUE;
    /** Never comes. */
    public static final long NEVER = Long.MAX_VALUE;
    /** A shorn sheep's wait for a mouthful while it stands on grass. */
    static final long REGROW_TICKS = 2000L;
    /** An eaten grass block grows back by spreading (my call: about 5 minutes). */
    static final long GRASS_BACK_TICKS = 6000L;
    /** A parent's rest after a birth (vanilla {@code Animal.spawnChildFromBreeding}). */
    static final int BREED_REST_TICKS = 6000;
    /** How long an animal fed before everyone left stays in love (vanilla {@code Animal.setInLove}). */
    static final long LOVE_TICKS = 600L;
    /** The kinds a rancher tends ({@code RanchAnimals.breedFoodFor}); breeding turns that tie go in this order. */
    public static final List<EntityType<?>> KINDS =
            List.of(EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN);

    private final ServerLevel level;
    /** Game time at run tick 0. */
    private final long startGameTime;
    private final long startVillageTime;
    /** Run tick at the end of the counted time. */
    private final long endTicks;
    private final List<Pen> pens = new ArrayList<>();
    /** A Good Breeder worked today: every pen's babies grow faster (live boosts every pen). */
    private boolean goodBreederToday;

    // For the log and the preview.
    private int notLoaded;
    int births;
    int grownUp;
    int grassEaten;
    int babiesWritten;
    int culled;
    int cullsWritten;

    private AwayPens(ServerLevel level, long startGameTime, long startVillageTime, long endTicks) {
        this.level = level;
        this.startGameTime = startGameTime;
        this.startVillageTime = startVillageTime;
        this.endTicks = endTicks;
    }

    // =========================================================================
    // Records
    // =========================================================================

    /** One pen and what is in it. */
    public static final class Pen {
        final RanchBuilding ranch;
        final int capacity;
        final int tiles;
        final List<Beast> beasts = new ArrayList<>();
        final List<Patch> patches = new ArrayList<>();
        /** Animals read from the world that a butcher culled: removed at WRITE. */
        final List<Animal> culled = new ArrayList<>();
        /** Run tick each kind was last bred this run (live {@code RanchBreedTurns}). */
        final Map<EntityType<?>, Long> lastBred = new HashMap<>();
        private int patchTurn;

        Pen(RanchBuilding ranch) {
            this.ranch = ranch;
            this.capacity = ranch.capacityPerSpecies();
            this.tiles = Math.max(1, ranch.getNavMeshBlocks().size());
        }

        public RanchBuilding ranch() { return ranch; }

        public List<Beast> beasts() { return beasts; }

        /** Animals of one kind the pen keeps (live breeds only below it). */
        public int capacity() { return capacity; }

        /** Heads of {@code kind}, babies and the born ones too. */
        public int heads(EntityType<?> kind) {
            int heads = 0;
            for (Beast beast : beasts) if (beast.type == kind) heads++;
            return heads;
        }

        /** Tiles with something a sheep can eat at {@code t}. */
        int edibleAt(long t) {
            int edible = 0;
            for (Patch patch : patches) if (patch.edible(t)) edible++;
            return edible;
        }

        /** A sheep eats at {@code t}; false when there was nothing to eat. */
        boolean eat(long t) {
            int count = patches.size();
            int grassLeft = 0;
            for (Patch patch : patches) if (patch.grassAt(t)) grassLeft++;
            for (int i = 0; i < count; i++) {
                int at = (patchTurn + i) % count;
                Patch patch = patches.get(at);
                if (patch.shortGrass != null && !patch.shortEaten) {
                    patch.shortEaten = true;
                } else if (patch.grassAt(t)) {
                    // Dirt turns back to grass only from grass beside it: the last block stays dirt.
                    patch.grassBackAt = grassLeft > 1 ? t + GRASS_BACK_TICKS : NEVER;
                } else {
                    continue;
                }
                patchTurn = (at + 1) % count;
                return true;
            }
            return false;
        }

        /** The first eaten grass block back after {@code t}, or {@link #NEVER}. */
        long nextGrassBack(long t) {
            long next = NEVER;
            for (Patch patch : patches) {
                if (patch.grass != null && patch.grassBackAt > t && patch.grassBackAt < next) next = patch.grassBackAt;
            }
            return next;
        }

        /** When a sheep shorn at {@code t} tries for its next mouthful; {@link #NEVER} in a pen with no grass to come. */
        long regrowAt(long t) {
            int edible = edibleAt(t);
            if (edible > 0) return t + Math.max(1L, REGROW_TICKS * tiles / edible);
            return nextGrassBack(t);
        }
    }

    /** One animal: read from the pen, or born while away (made at WRITE). */
    public static final class Beast {
        final EntityType<?> type;
        @Nullable Animal entity;
        @Nullable final Beast parentA;
        @Nullable final Beast parentB;
        long bornAt = NONE;
        boolean baby;
        /** A baby's vanilla age, below 0. */
        int age;
        /** An adult may breed again from this run tick. */
        long restUntil = NONE;
        boolean inLove;
        boolean named;
        boolean saddled;
        boolean led;
        boolean bred;
        @Nullable DyeColor color;
        boolean sheared;
        long woollyAt = NEVER;
        long eggStamp = NONE;
        long shedStamp = NONE;
        boolean eggChanged;
        boolean shedChanged;
        // As read, for what WRITE changes.
        boolean readBaby;
        int readAge;
        boolean readSheared;

        Beast(EntityType<?> type, @Nullable Beast parentA, @Nullable Beast parentB) {
            this.type = type;
            this.parentA = parentA;
            this.parentB = parentB;
        }

        public EntityType<?> type() { return type; }
        public boolean baby() { return baby; }
        /** On a villager's lead when read: on its way somewhere, never worked ({@code RancherActivity.free}). */
        public boolean led() { return led; }
        public boolean named() { return named; }
        public boolean saddled() { return saddled; }
        public boolean sheared() { return sheared; }
        /** When a shorn sheep next tries to eat; {@link #NEVER} when it cannot. */
        public long woollyAt() { return woollyAt; }
        /** Run tick of the last owned egg, or {@link #NONE}. */
        public long eggStamp() { return eggStamp; }
        /** Run tick of the last combed hide, or {@link #NONE}. */
        public long shedStamp() { return shedStamp; }
        public long restUntil() { return restUntil; }

        /** This sheep's wool. */
        public Item wool() {
            return RanchAnimals.woolFor(color == null ? DyeColor.WHITE : color);
        }

        /** Vanilla age at the end of the counted time. */
        int finalAge(long endTicks) {
            if (baby) return Math.min(-1, age);
            if (restUntil == NONE || restUntil <= endTicks) return 0;
            return (int) Math.min(Integer.MAX_VALUE, restUntil - endTicks);
        }

        boolean changed(long endTicks) {
            return bred || eggChanged || shedChanged || baby != readBaby || sheared != readSheared
                    || finalAge(endTicks) != readAge;
        }
    }

    /** A pen tile a sheep can eat from: short grass on it, a grass block under it, or both. */
    private static final class Patch {
        final BlockPos feet;
        @Nullable final BlockState shortGrass;
        @Nullable final BlockState grass;
        boolean shortEaten;
        /** Run tick the grass block is grass again; {@link #NONE} while it is. */
        long grassBackAt = NONE;

        Patch(BlockPos feet, @Nullable BlockState shortGrass, @Nullable BlockState grass) {
            this.feet = feet;
            this.shortGrass = shortGrass;
            this.grass = grass;
        }

        boolean grassAt(long t) {
            return grass != null && grassBackAt <= t;
        }

        boolean edible(long t) {
            return shortGrass != null && !shortEaten || grassAt(t);
        }
    }

    // =========================================================================
    // READ
    // =========================================================================

    static AwayPens read(ServerLevel level, VillageManager mgr, long startGameTime, long startVillageTime,
                         long countedTicks) {
        AwayPens pens = new AwayPens(level, startGameTime, startVillageTime, countedTicks);
        Set<UUID> seen = new HashSet<>();
        for (Building building : mgr.getBuildings()) {
            if (!(building instanceof RanchBuilding ranch) || !ranch.isValid()) continue;
            if (!loaded(level, ranch)) {
                pens.notLoaded++;
                continue;
            }
            Pen pen = new Pen(ranch);
            for (Animal animal : ranch.animalsInside(level)) {
                // Two pens sharing a fence see the same animal: it is counted once.
                if (!KINDS.contains(animal.getType()) || !seen.add(animal.getUUID())) continue;
                pen.beasts.add(pens.readBeast(animal));
            }
            for (BlockPos tile : ranch.getNavMeshBlocks()) {
                BlockState feet = level.getBlockState(tile);
                BlockState below = level.getBlockState(tile.below());
                BlockState shortGrass = feet.is(Blocks.SHORT_GRASS) ? feet : null;
                BlockState grass = below.is(Blocks.GRASS_BLOCK) ? below : null;
                if (shortGrass != null || grass != null) pen.patches.add(new Patch(tile.immutable(), shortGrass, grass));
            }
            // A sheep shorn before everyone left starts looking for grass at once.
            for (Beast beast : pen.beasts) if (beast.sheared) beast.woollyAt = pen.regrowAt(0L);
            pens.pens.add(pen);
        }
        return pens;
    }

    /** Every chunk under the pen and its fence, with its animals, is loaded: a catch-up never loads one. */
    private static boolean loaded(ServerLevel level, RanchBuilding ranch) {
        BlockPos min = ranch.shellMin();
        BlockPos max = ranch.shellMax();
        if (!level.hasChunksAt(min.getX(), min.getZ(), max.getX(), max.getZ())) return false;
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                if (!level.areEntitiesLoaded(ChunkPos.asLong(cx, cz))) return false;
            }
        }
        return true;
    }

    private Beast readBeast(Animal animal) {
        Beast beast = new Beast(animal.getType(), null, null);
        beast.entity = animal;
        beast.baby = beast.readBaby = animal.isBaby();
        beast.readAge = animal.getAge();
        if (beast.baby) beast.age = Math.min(-1, animal.getAge());
        else if (animal.getAge() > 0) beast.restUntil = animal.getAge();
        beast.inLove = animal.isInLove();
        beast.named = animal.hasCustomName();
        beast.saddled = RanchStrays.isSaddled(animal);
        beast.led = LedAnimals.isLed(animal);
        if (animal instanceof Sheep sheep) {
            beast.color = sheep.getColor();
            beast.sheared = beast.readSheared = sheep.isSheared();
        }
        if (beast.type == EntityType.CHICKEN) beast.eggStamp = runStamp(RancherActivity.lastEggTick(animal));
        if (beast.type == EntityType.COW) beast.shedStamp = runStamp(RancherActivity.lastShedTick(animal));
        return beast;
    }

    /** A game-time stamp on the run clock, never after its start (a test's moved-back sleep). */
    private long runStamp(@Nullable Long gameTime) {
        return gameTime == null ? NONE : Math.min(0L, gameTime - startGameTime);
    }

    // =========================================================================
    // Days
    // =========================================================================

    public List<Pen> pens() { return pens; }

    public boolean isEmpty() { return pens.isEmpty(); }

    public int notLoaded() { return notLoaded; }

    /** Run tick {@code day} starts at; its work hours come first. */
    public long dayStart(AwayDays.Day day) {
        return day.start() - startVillageTime;
    }

    /** A Good Breeder works today. */
    public void goodBreederAtWork() {
        goodBreederToday = true;
    }

    void startDay(AwayDays.Day day) {
        goodBreederToday = false;
        if (day.index() != 0) return;
        long t = dayStart(day);
        for (Pen pen : pens) {
            Map<EntityType<?>, Beast> waiting = new HashMap<>();
            for (Beast beast : List.copyOf(pen.beasts)) {
                if (!beast.inLove || beast.baby || beast.led) continue;
                Beast mate = waiting.remove(beast.type);
                if (mate == null) waiting.put(beast.type, beast);
                else breed(pen, mate, beast, t);
            }
        }
    }

    /** The day's end: shorn sheep that found grass have their wool, babies grow, the grown ones breed from tomorrow. */
    void endDay(AwayDays.Day day) {
        long from = dayStart(day);
        long to = from + day.length();
        double pace = goodBreederToday ? 1.0 + RancherBalance.GROW_BOOST : 1.0;
        for (Pen pen : pens) {
            for (Beast beast : pen.beasts) {
                if (beast.sheared) woolly(pen, beast, to);
                if (!beast.baby) continue;
                long grownFrom = beast.bornAt == NONE ? from : Math.max(from, beast.bornAt);
                long age = beast.age + Math.round(Math.max(0L, to - grownFrom) * pace);
                if (age < 0) {
                    beast.age = (int) age;
                    continue;
                }
                beast.baby = false;
                beast.age = 0;
                beast.restUntil = to;
                grownUp++;
            }
        }
    }

    /** Whether the sheep has its wool at {@code t}: a shorn one that found grass by then grew it back. */
    public boolean woolly(Pen pen, Beast sheep, long t) {
        if (!sheep.sheared) return true;
        while (sheep.woollyAt <= t) {
            if (pen.eat(sheep.woollyAt)) {
                sheep.sheared = false;
                sheep.woollyAt = NEVER;
                grassEaten++;
                return true;
            }
            sheep.woollyAt = Math.max(sheep.woollyAt + 1, pen.regrowAt(sheep.woollyAt));
        }
        return false;
    }

    public void shear(Pen pen, Beast sheep, long t) {
        sheep.sheared = true;
        sheep.woollyAt = pen.regrowAt(t);
    }

    public void collectEgg(Beast chicken, long t) {
        chicken.eggStamp = t;
        chicken.eggChanged = true;
    }

    public void combHide(Beast cow, long t) {
        cow.shedStamp = t;
        cow.shedChanged = true;
    }

    /** An adult that may be fed for breeding at {@code t}: grown, rested, on no lead. */
    public boolean readyToBreed(Beast beast, long t) {
        return !beast.baby && !beast.led && beast.restUntil <= t;
    }

    /** Still in love at {@code t}: fed just before everyone left, its love not yet worn off (live never culls it). */
    public boolean inLove(Beast beast, long t) {
        return beast.inLove && t < LOVE_TICKS;
    }

    /** The animal's health: as read, or a born one's full health. */
    public float health(Beast beast) {
        return beast.entity != null ? beast.entity.getHealth() : fullHealth(beast.type);
    }

    @SuppressWarnings("unchecked")
    private static float fullHealth(EntityType<?> type) {
        if (!DefaultAttributes.hasSupplier(type)) return 10f;
        return (float) DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type)
                .getValue(Attributes.MAX_HEALTH);
    }

    /**
     * A butcher's kill: the animal leaves the records at once (the next butcher and the
     * ranchers' room see it gone); one read from the world is removed at WRITE, a born one
     * is simply never placed.
     */
    public void cull(Pen pen, Beast beast) {
        pen.beasts.remove(beast);
        if (beast.entity != null) pen.culled.add(beast.entity);
        culled++;
    }

    /**
     * Run tick {@code kind} was last bred in the pen: this run's, else the live turn
     * ({@code RanchBreedTurns}) on the run's clock; {@code Long.MIN_VALUE} for neither.
     */
    public long lastBred(Pen pen, EntityType<?> kind) {
        Long bred = pen.lastBred.get(kind);
        if (bred != null) return bred;
        long live = RanchBreedTurns.lastPicked(pen.ranch, kind);
        return live == Long.MIN_VALUE ? live : live - startGameTime;
    }

    /** A pair fed at {@code t}: the baby is born then, the parents rest. */
    public Beast breed(Pen pen, Beast a, Beast b, long t) {
        pen.lastBred.put(a.type, t);
        a.restUntil = b.restUntil = t + BREED_REST_TICKS;
        a.bred = b.bred = true;
        a.inLove = b.inLove = false;
        Beast baby = new Beast(a.type, a, b);
        baby.baby = true;
        baby.readBaby = true;
        baby.age = AgeableMob.BABY_START_AGE;
        baby.bornAt = t;
        baby.color = a.color;
        pen.beasts.add(baby);
        births++;
        return baby;
    }

    // =========================================================================
    // WRITE
    // =========================================================================

    /** The pens' end state onto the world list: animals changed, babies born, grass eaten. */
    void writeTo(AwayWorld world) {
        for (Pen pen : pens) {
            for (Beast beast : pen.beasts) if (beast.sheared) woolly(pen, beast, endTicks);
            for (Beast beast : pen.beasts) {
                if (beast.entity == null) world.add(new Birth(pen, beast));
                else if (beast.changed(endTicks)) world.add(new Change(beast));
            }
            for (Patch patch : pen.patches) {
                if (patch.shortEaten) world.setBlock(patch.feet, patch.shortGrass, Blocks.AIR.defaultBlockState());
                if (patch.grass != null && patch.grassBackAt > endTicks) {
                    world.setBlock(patch.feet.below(), patch.grass, Blocks.DIRT.defaultBlockState());
                }
            }
            // After the births: a baby is placed beside its parent before a culled parent goes.
            for (Animal animal : pen.culled) world.add(new Cull(animal));
        }
    }

    /** A run tick on the game clock: the end of the counted time is now. */
    private long gameTime(long runTick) {
        return level.getGameTime() - endTicks + runTick;
    }

    /** The stamps a beast took while away, onto its animal. */
    private void stamp(Beast beast, Animal animal) {
        if (beast.eggChanged) RancherActivity.markEggCollected(animal, gameTime(beast.eggStamp));
        if (beast.shedChanged) RancherActivity.markShed(animal, gameTime(beast.shedStamp));
    }

    /** "If the animal is still here, give it its away state." */
    private final class Change implements AwayWorld.Change {
        private final Beast beast;

        Change(Beast beast) {
            this.beast = beast;
        }

        @Override
        public boolean stillHolds(ServerLevel level) {
            Animal animal = beast.entity;
            return animal != null && animal.isAlive() && !animal.isRemoved() && level.isLoaded(animal.blockPosition());
        }

        @Override
        public void apply(ServerLevel level) {
            Animal animal = beast.entity;
            int age = beast.finalAge(endTicks);
            if (age != beast.readAge || beast.baby != beast.readBaby) animal.setAge(age);
            if (beast.bred) animal.resetLove();
            // A player who sheared it since keeps it shorn.
            if (animal instanceof Sheep sheep && beast.sheared != beast.readSheared
                    && sheep.isSheared() == beast.readSheared) {
                sheep.setSheared(beast.sheared);
            }
            stamp(beast, animal);
        }

        @Override
        public String describe() {
            return EntityType.getKey(beast.type) + " " + (beast.entity == null ? "?" : beast.entity.getUUID());
        }
    }

    /** "If the pen still stands, the baby born while away is in it." */
    private final class Birth implements AwayWorld.Change {
        private final Pen pen;
        private final Beast beast;

        Birth(Pen pen, Beast beast) {
            this.pen = pen;
            this.beast = beast;
        }

        /** Beside a parent still here, else on the pen's first tile. */
        @Nullable
        private Vec3 spot() {
            for (Beast parent : new Beast[] { beast.parentA, beast.parentB }) {
                Animal animal = parent == null ? null : parent.entity;
                if (animal != null && animal.isAlive() && !animal.isRemoved()) return animal.position();
            }
            Iterator<BlockPos> tiles = pen.ranch.getNavMeshBlocks().iterator();
            return tiles.hasNext() ? Vec3.atBottomCenterOf(tiles.next()) : null;
        }

        @Override
        public boolean stillHolds(ServerLevel level) {
            Vec3 spot = spot();
            return pen.ranch.isValid() && spot != null && level.isLoaded(BlockPos.containing(spot));
        }

        @Override
        public void apply(ServerLevel level) {
            Entity made = beast.type.create(level);
            if (!(made instanceof Animal animal)) return;
            Vec3 spot = spot();
            animal.moveTo(spot.x, spot.y, spot.z, level.random.nextFloat() * 360f, 0f);
            animal.setAge(beast.finalAge(endTicks));
            if (animal instanceof Sheep sheep) {
                if (beast.color != null) sheep.setColor(beast.color);
                sheep.setSheared(beast.sheared);
            }
            stamp(beast, animal);
            if (!level.addFreshEntity(animal)) return;
            beast.entity = animal;
            pen.ranch.markMember(animal.getUUID(), level.getGameTime());
            babiesWritten++;
        }

        @Override
        public String describe() {
            return "new " + EntityType.getKey(beast.type) + " in the pen at " + pen.ranch.getDoorPos().toShortString();
        }
    }

    /**
     * "If the animal is still here and still nobody's pet or ride, on no lead, it was culled
     * while away." Its meat is already in the books: a player who named it during the catch-up
     * keeps it, and the meat too (my call; a few ticks).
     */
    private final class Cull implements AwayWorld.Change {
        private final Animal animal;

        Cull(Animal animal) {
            this.animal = animal;
        }

        @Override
        public boolean stillHolds(ServerLevel level) {
            return animal.isAlive() && !animal.isRemoved() && level.isLoaded(animal.blockPosition())
                    && !animal.hasCustomName() && !RanchStrays.isSaddled(animal)
                    && !animal.isLeashed() && !LedAnimals.isLed(animal);
        }

        @Override
        public void apply(ServerLevel level) {
            // Removed, not killed: no loot on the floor, the drops were banked while away.
            animal.remove(Entity.RemovalReason.KILLED);
            cullsWritten++;
        }

        @Override
        public String describe() {
            return "cull " + EntityType.getKey(animal.getType()) + " " + animal.getUUID();
        }
    }

    // =========================================================================
    // Log and preview
    // =========================================================================

    /** "1 pen (0 not loaded): 4 cow (2 baby), 6 sheep (3 shorn); grass 30 of 48 tile(s); 2 born, 1 grown up, 5 grass eaten" */
    String describe() {
        if (pens.isEmpty()) return notLoaded > 0 ? "no pen loaded (" + notLoaded + " not loaded)" : "no pen";
        Map<String, int[]> kinds = new TreeMap<>();
        int patches = 0;
        int tiles = 0;
        for (Pen pen : pens) {
            patches += pen.patches.size();
            tiles += pen.tiles;
            for (Beast beast : pen.beasts) {
                int[] count = kinds.computeIfAbsent(EntityType.getKey(beast.type).getPath(), k -> new int[3]);
                count[0]++;
                if (beast.baby) count[1]++;
                if (beast.sheared) count[2]++;
            }
        }
        StringBuilder text = new StringBuilder();
        text.append(pens.size()).append(" pen(s) (").append(notLoaded).append(" not loaded): ");
        if (kinds.isEmpty()) text.append("no animals");
        boolean first = true;
        for (Map.Entry<String, int[]> kind : kinds.entrySet()) {
            if (!first) text.append(", ");
            first = false;
            int[] count = kind.getValue();
            text.append(count[0]).append(' ').append(kind.getKey());
            if (count[1] > 0 || count[2] > 0) {
                text.append(" (").append(count[1]).append(" baby");
                if (count[2] > 0) text.append(", ").append(count[2]).append(" shorn");
                text.append(')');
            }
        }
        text.append("; grass on ").append(patches).append(" of ").append(tiles).append(" tile(s); ")
                .append(births).append(" born, ").append(grownUp).append(" grown up, ")
                .append(grassEaten).append(" grass eaten");
        if (babiesWritten > 0) text.append(", ").append(babiesWritten).append(" baby/babies placed");
        if (culled > 0) text.append(", ").append(culled).append(" culled");
        if (cullsWritten > 0) text.append(", ").append(cullsWritten).append(" removed");
        return text.toString();
    }
}
