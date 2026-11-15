package com.alex.test.village.raid.warlord;

import com.alex.test.DynamicVillages;
import com.alex.test.ModSounds;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.raid.RaidKidnap;
import com.alex.test.village.raid.RaidSpawner;
import com.alex.test.village.shelter.RaidShout;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;

/**
 * The Illager Warlord: a giant illager that leads the Bad Omen raid's last
 * wave. It counts as an illager to Minecraft (iron golems hate it, raiders are
 * its friends) but never joins Minecraft's own raids, and it breaks no blocks.
 * Its numbers grow with the omen level ({@link WarlordMath}); the moves live in
 * their own goals. Spawned by the raid, or by {@code /vtest raid warlord}.
 */
public class RaidWarlord extends AbstractIllager implements GeoEntity {

    private static final EntityDataAccessor<Boolean> DATA_ENRAGED =
            SynchedEntityData.defineId(RaidWarlord.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_ACTION =
            SynchedEntityData.defineId(RaidWarlord.class, EntityDataSerializers.INT);

    /** Where its head may turn from its body, so the helmet never reaches the banner on its back. */
    public static final int MAX_HEAD_YAW = 25;
    /** It reaches farther than a mob its width: the axe is long (its swing starts this far out). */
    private static final double ATTACK_REACH = WarlordMath.SWING_START_REACH;
    /** How far past its box any mob reaches (Mob's own value, private there). */
    private static final double VANILLA_ATTACK_REACH = Math.sqrt(2.04F) - 0.6F;

    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);
    private final ServerBossEvent bossBar = new ServerBossEvent(
            Component.translatable("entity." + DynamicVillages.MODID + ".raid_warlord"),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
    private int omenLevel = 1;
    private long lastFightTick;
    /** Made on first use: goals are registered inside the Mob constructor, before fields are set. */
    @Nullable private WarlordUnstuck unstuck;
    /** Raider plan round 5 (switch raidbig): its walks by village map route; made on first use too. */
    @Nullable private com.alex.test.village.raid.WideMapWalk wideWalk;

    public RaidWarlord(EntityType<? extends RaidWarlord> type, Level level) {
        super(type, level);
        this.xpReward = 50;
        setCanPickUpLoot(false);
        // Plan bug 24, fix 1 (switch warlordturn): its walking turns gently, not 90 degrees a tick.
        this.moveControl = new WarlordMoveControl(this);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, WarlordMath.maxHealth(1))
                .add(Attributes.MOVEMENT_SPEED, WarlordMath.walkSpeed(false))
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.ATTACK_DAMAGE, WarlordMath.swingDamage(1))
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ENRAGED, false);
        builder.define(DATA_ACTION, WarlordAction.NONE.ordinal());
    }

    /** Its health and swing as at this omen level (1 to 5), healed to full. */
    public void setOmenLevel(int level) {
        omenLevel = com.alex.test.village.raid.RaidOmenMath.clampLevel(level);
        setBase(Attributes.MAX_HEALTH, WarlordMath.maxHealth(omenLevel));
        setBase(Attributes.ATTACK_DAMAGE, WarlordMath.swingDamage(omenLevel));
        setHealth(getMaxHealth());
    }

    public int omenLevel() {
        return omenLevel;
    }

    public boolean isEnraged() {
        return entityData.get(DATA_ENRAGED);
    }

    private void setEnraged() {
        entityData.set(DATA_ENRAGED, true);
        setBase(Attributes.MOVEMENT_SPEED, WarlordMath.walkSpeed(true));
    }

    /** Angry from now on: its eyes burn brighter, it moves and swings faster, and it roars. */
    public void enrage() {
        if (isEnraged()) return;
        setEnraged();
        roar();
        VillageManager village = raidVillage();
        if (village != null && level() instanceof ServerLevel server) village.raids().onWarlordEnraged(server, this);
    }

    WarlordUnstuck unstuck() {
        if (unstuck == null) unstuck = new WarlordUnstuck(this);
        return unstuck;
    }

    /** Its one map walk, shared by the raid's march, its chase and its detour (they never run together). */
    public com.alex.test.village.raid.WideMapWalk wideWalk() {
        if (wideWalk == null) wideWalk = new com.alex.test.village.raid.WideMapWalk(this, this::raidVillage);
        return wideWalk;
    }

    /** The village whose raid it came with; null for one summoned outside any raid. */
    @Nullable
    VillageManager raidVillage() {
        RaidSpawner.Tagged tag = RaidSpawner.tagOf(this);
        if (tag == null || !(level() instanceof ServerLevel server)) return null;
        return VillageManagerRegistry.get(server.getServer()).get(tag.villageId());
    }

    public WarlordAction action() {
        return WarlordAction.byIndex(entityData.get(DATA_ACTION));
    }

    public void setAction(WarlordAction action) {
        entityData.set(DATA_ACTION, action.ordinal());
    }

    /** When it last hit something or was hit (game time). */
    public long lastFightTick() {
        return lastFightTick;
    }

    private void setBase(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double value) {
        AttributeInstance instance = getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("OmenLevel", omenLevel);
        tag.putBoolean("Enraged", isEnraged());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // Health and the levelled attributes come back with the entity's own saved attributes.
        omenLevel = com.alex.test.village.raid.RaidOmenMath.clampLevel(tag.getInt("OmenLevel"));
        if (tag.getBoolean("Enraged")) setEnraged();
        if (hasCustomName()) bossBar.setName(getDisplayName());
    }

    // -------------------------------------------------------------------------
    // Brain
    // -------------------------------------------------------------------------

    @Override
    protected void registerGoals() {
        // Not super: no vanilla raider goals (banner hunting, raid pathing, the cheer).
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new WarlordSlamGoal(this));
        // Same rank as the slam, after it: a crowd of three gets slammed, two get the spin.
        goalSelector.addGoal(1, new WarlordSpinGoal(this));
        goalSelector.addGoal(2, new WarlordChargeGoal(this));
        // Ahead of the swing (3) and the raid's march (4): a goal only takes over from a
        // worse-ranked one, and the swing runs whenever it has a target, reachable or not.
        // Both give way by themselves when a foe is in reach.
        goalSelector.addGoal(2, new WarlordUnstuck.DetourGoal(this));
        goalSelector.addGoal(2, new WarlordPoundGoal(this));
        goalSelector.addGoal(3, new WarlordSwingGoal(this));
        goalSelector.addGoal(8, new RandomStrollGoal(this, 0.8));
        goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 15.0F));
        targetSelector.addGoal(1, new HurtByTargetGoal(this, Raider.class));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, NpcEntity.class, 10, true, false,
                target -> target instanceof NpcEntity npc && npc.getProfession().isDefender()));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
        targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Player.class, true));
        targetSelector.addGoal(5, new NearestAttackableTargetGoal<>(this, NpcEntity.class, 10, true, false,
                target -> target instanceof NpcEntity npc && !npc.getProfession().isDefender()
                        && !RaidKidnap.isCaptive(npc)
                        && npc.level().canSeeSky(BlockPos.containing(npc.getEyePosition()))));
    }

    /** Once a second: getting unstuck, and the glow when it has been out of a fight for long. */
    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (tickCount % 20 == 0 && level() instanceof ServerLevel server) unstuck().check(server);
    }

    /** In tick, not the AI step: the showcase's Warlord has no AI and still gets angry and shows its health. */
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide() || isDeadOrDying()) return;
        if (!isEnraged() && WarlordMath.enraged(getHealth(), getMaxHealth())) enrage();
        bossBar.setProgress(getHealth() / getMaxHealth());
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit) lastFightTick = level().getGameTime();
        return hit;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt) lastFightTick = level().getGameTime();
        return hurt;
    }

    /**
     * Someone its slam and charge hurt: a player in Survival or Adventure, a
     * villager (not one held captive) or an iron golem. Never a raider.
     */
    public boolean isFoe(LivingEntity entity) {
        if (entity == this || !entity.isAlive() || entity instanceof Raider) return false;
        if (entity instanceof Player player) return !player.isCreative() && !player.isSpectator();
        if (entity instanceof NpcEntity npc) return !RaidKidnap.isCaptive(npc);
        return entity instanceof IronGolem;
    }

    /** Hurts {@code foe} with one of its heavy moves (the slam, the spin, the charge); false if it was blocked. */
    public boolean strike(LivingEntity foe, double damage) {
        boolean hurt = foe.hurt(damageSources().mobAttack(this), (float) damage);
        if (hurt) lastFightTick = level().getGameTime();
        return hurt;
    }

    /**
     * Turns it, head and body, to {@code yaw} at once and keeps its look there: its
     * swing and spin land where its body faces.
     */
    void face(float yaw) {
        setYRot(yaw);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
        Vec3 ahead = position().add(forward(yaw).scale(4.0));
        getLookControl().setLookAt(ahead.x, getEyeY(), ahead.z);
    }

    /** The flat way a mob turned to {@code yaw} faces. */
    static Vec3 forward(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vec3(-Math.sin(r), 0, Math.cos(r));
    }

    /** The flat way to the right hand of a mob turned to {@code yaw}. */
    static Vec3 right(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vec3(-Math.cos(r), 0, -Math.sin(r));
    }

    /** The yaw that faces from {@code from} to {@code to}. */
    static float yawToward(Vec3 from, Vec3 to) {
        return (float) (Math.atan2(to.z - from.z, to.x - from.x) * (180.0 / Math.PI)) - 90.0F;
    }

    /** Flings {@code foe} along {@code direction} (flattened) and up, less for what resists knockback. */
    public static void fling(LivingEntity foe, Vec3 direction, double sideways, double up) {
        double scale = 1.0 - foe.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
        if (scale <= 0) return;
        Vec3 flat = new Vec3(direction.x, 0, direction.z);
        if (flat.lengthSqr() < 1.0e-6) flat = new Vec3(1, 0, 0);
        flat = flat.normalize().scale(sideways * scale);
        foe.setDeltaMovement(flat.x, up * scale, flat.z);
        foe.hurtMarked = true;
    }

    @Override
    protected AABB getAttackBoundingBox() {
        double extra = ATTACK_REACH - VANILLA_ATTACK_REACH;
        return super.getAttackBoundingBox().inflate(extra, 0.0, extra);
    }

    /** A blocked swing knocks the shield down for a few seconds, as an axe does. */
    @Override
    public boolean canDisableShield() {
        return true;
    }

    @Override
    public int getMaxHeadYRot() {
        return MAX_HEAD_YAW;
    }

    @Override
    public boolean canJoinRaid() {
        return false;
    }

    @Override
    public void applyRaidBuffs(ServerLevel level, int wave, boolean unused) {}

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    /** Like any monster it goes on Peaceful, except in the showcase, which plays on any difficulty. */
    @Override
    protected boolean shouldDespawnInPeaceful() {
        return !getTags().contains(WarlordShowcase.TAG);
    }

    // -------------------------------------------------------------------------
    // Shows: clips, sounds, boss bar, death
    // -------------------------------------------------------------------------

    /** Plays one of the one-shot clips (swing, slam, spin, roar, pound, leap, land) on every client watching. */
    public void playClip(String clip) {
        triggerAnim(WarlordAnimator.NAME, clip);
    }

    /** Ends a held clip (the leap) on every client watching, if it is still playing; it eases back to standing. */
    void stopClip(String clip) {
        stopTriggeredAnim(WarlordAnimator.NAME, clip);
    }

    /** The roar clip and sound; villagers out in the open nearby run for shelter. */
    public void roar() {
        playClip("roar");
        playSound(ModSounds.RAID_WARLORD_ROAR.get(), 3.0F, 1.0F);
        VillageManager village = raidVillage();
        if (village != null && level() instanceof ServerLevel server) {
            RaidShout.hearRoar(village, server, this, WarlordMath.ROAR_RADIUS);
        }
    }

    @Override
    public SoundEvent getCelebrateSound() {
        return ModSounds.RAID_WARLORD_ROAR.get();
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return ModSounds.RAID_WARLORD_IDLE.get();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.RAID_WARLORD_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.RAID_WARLORD_DEATH.get();
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(ModSounds.RAID_WARLORD_STEP.get(), 1.0F, 1.0F);
    }

    @Override
    protected float getSoundVolume() {
        return 2.0F;
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        bossBar.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        bossBar.removePlayer(player);
    }

    @Override
    public void setCustomName(@Nullable Component name) {
        super.setCustomName(name);
        bossBar.setName(getDisplayName());
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        playClip("death");
        bossBar.setProgress(0.0F);
    }

    /** The body stays for the whole death clip (it kneels, then falls on its face), then goes. */
    @Override
    protected void tickDeath() {
        ++deathTime;
        if (deathTime >= WarlordMath.DEATH_HOLD_TICKS && !level().isClientSide() && !isRemoved()) {
            level().broadcastEntityEvent(this, (byte) 60);
            remove(RemovalReason.KILLED);
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new WarlordAnimator(this));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animatableCache;
    }
}
