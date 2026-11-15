package com.alex.test.ui;

import com.alex.test.Tr;
import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.guard.settings.GuardMobPolicy;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Server menu plus opening snapshot for one Guard settings screen. */
public final class GuardSettingsMenu extends AbstractContainerMenu implements NpcUiMenu {

    public record SwordSetting(ResourceLocation id, String name, boolean enabled) {}
    public record MobSetting(ResourceLocation id, String name, boolean enabled) {}
    public record BehaviorSetting(ResourceLocation id, String name, boolean enabled) {}

    public static final ResourceLocation PROTECT_PLAYER_ID =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "protect_player");
    public static final ResourceLocation FOLLOW_PLAYER_ID =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "follow_player");
    public static final ResourceLocation ATTACK_HOSTILE_MOBS_ID =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "attack_hostile_mobs");
    public static final ResourceLocation PATROL_AT_NIGHT_ONLY_ID =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "patrol_at_night_only");
    public static final ResourceLocation USE_GUARD_POSTS_ID =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "use_guard_posts");

    private final int villagerId;
    @Nullable private final NpcEntity villager;
    private final List<SwordSetting> swords;
    private final List<MobSetting> mobs;
    private final List<BehaviorSetting> behaviors;
    private int guardPostTimeSeconds;

    public GuardSettingsMenu(int id, Inventory inventory, NpcEntity villager) {
        super(DynamicVillages.GUARD_SETTINGS_MENU.get(), id);
        this.villagerId = villager.getId();
        this.villager = villager;
        GuardSettings settings = GuardSettings.of(villager);
        this.swords = swordSnapshot(settings);
        this.mobs = mobSnapshot(settings);
        this.behaviors = behaviorSnapshot(settings);
        this.guardPostTimeSeconds = settings.guardPostStaySeconds();
    }

    public GuardSettingsMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        super(DynamicVillages.GUARD_SETTINGS_MENU.get(), id);
        this.villagerId = buffer.readVarInt();
        this.villager = null;
        int count = buffer.readVarInt();
        this.swords = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            swords.add(new SwordSetting(
                    buffer.readResourceLocation(),
                    buffer.readUtf(128),
                    buffer.readBoolean()));
        }
        int mobCount = buffer.readVarInt();
        this.mobs = new ArrayList<>(mobCount);
        for (int i = 0; i < mobCount; i++) {
            mobs.add(new MobSetting(
                    buffer.readResourceLocation(),
                    buffer.readUtf(128),
                    buffer.readBoolean()));
        }
        int behaviorCount = buffer.readVarInt();
        this.behaviors = new ArrayList<>(behaviorCount);
        for (int i = 0; i < behaviorCount; i++) {
            behaviors.add(new BehaviorSetting(
                    buffer.readResourceLocation(),
                    buffer.readUtf(128),
                    buffer.readBoolean()));
        }
        this.guardPostTimeSeconds = buffer.readVarInt();
    }

    public static void writeOpeningData(RegistryFriendlyByteBuf buffer, NpcEntity villager) {
        GuardSettings settings = GuardSettings.of(villager);
        List<SwordSetting> swords = swordSnapshot(settings);
        List<MobSetting> mobs = mobSnapshot(settings);
        List<BehaviorSetting> behaviors = behaviorSnapshot(settings);
        buffer.writeVarInt(villager.getId());
        buffer.writeVarInt(swords.size());
        for (SwordSetting sword : swords) {
            buffer.writeResourceLocation(sword.id());
            buffer.writeUtf(sword.name(), 128);
            buffer.writeBoolean(sword.enabled());
        }
        buffer.writeVarInt(mobs.size());
        for (MobSetting mob : mobs) {
            buffer.writeResourceLocation(mob.id());
            buffer.writeUtf(mob.name(), 128);
            buffer.writeBoolean(mob.enabled());
        }
        buffer.writeVarInt(behaviors.size());
        for (BehaviorSetting behavior : behaviors) {
            buffer.writeResourceLocation(behavior.id());
            buffer.writeUtf(behavior.name(), 128);
            buffer.writeBoolean(behavior.enabled());
        }
        buffer.writeVarInt(settings.guardPostStaySeconds());
    }

    private static List<SwordSetting> swordSnapshot(GuardSettings settings) {
        List<SwordSetting> list = new ArrayList<>();
        for (GuardSettings.SwordTier tier : GuardSettings.SwordTier.values()) {
            list.add(new SwordSetting(
                    tier.settingId,
                    SettingsLabels.titleCase(tier.displayName),
                    settings.isSwordTierAllowed(tier)));
        }
        return list;
    }

    private static List<MobSetting> mobSnapshot(GuardSettings settings) {
        List<MobSetting> list = new ArrayList<>();
        for (GuardMobPolicy.MobOption option : GuardMobPolicy.options()) {
            list.add(new MobSetting(
                    option.id(),
                    option.displayName(),
                    settings.isMobAllowed(option.id())));
        }
        return list;
    }

    private static List<BehaviorSetting> behaviorSnapshot(GuardSettings settings) {
        List<BehaviorSetting> list = new ArrayList<>();
        list.add(new BehaviorSetting(PROTECT_PLAYER_ID, Tr.s("setting.dynamicvillages.protect_player", "Defending You"), settings.protectPlayer()));
        list.add(new BehaviorSetting(FOLLOW_PLAYER_ID, Tr.s("setting.dynamicvillages.follow_player", "Following You"), settings.followPlayer()));
        list.add(new BehaviorSetting(ATTACK_HOSTILE_MOBS_ID, Tr.s("setting.dynamicvillages.attack_hostile_mobs", "Fighting"), settings.attackHostileMobs()));
        list.add(new BehaviorSetting(PATROL_AT_NIGHT_ONLY_ID, Tr.s("setting.dynamicvillages.patrol_at_night_only", "Working Nights"), settings.patrolAtNightOnly()));
        list.add(new BehaviorSetting(USE_GUARD_POSTS_ID, Tr.s("setting.dynamicvillages.use_guard_posts", "Guard Posts"), settings.useGuardPosts()));
        return list;
    }

    public int villagerId() { return villagerId; }
    public List<SwordSetting> swords() { return swords; }
    public List<MobSetting> mobs() { return mobs; }
    public List<BehaviorSetting> behaviors() { return behaviors; }
    public int guardPostTimeSeconds() { return guardPostTimeSeconds; }
    public boolean useGuardPostsClient() {
        return behaviorEnabled(USE_GUARD_POSTS_ID, true);
    }

    public boolean protectPlayerClient() {
        return behaviorEnabled(PROTECT_PLAYER_ID, false);
    }

    public boolean followPlayerClient() {
        return behaviorEnabled(FOLLOW_PLAYER_ID, false);
    }

    private boolean behaviorEnabled(ResourceLocation id, boolean fallback) {
        for (BehaviorSetting behavior : behaviors) {
            if (id.equals(behavior.id())) return behavior.enabled();
        }
        return fallback;
    }

    public void setSwordClient(ResourceLocation id, boolean enabled) {
        for (int i = 0; i < swords.size(); i++) {
            SwordSetting sword = swords.get(i);
            if (sword.id().equals(id)) {
                swords.set(i, new SwordSetting(id, sword.name(), enabled));
                return;
            }
        }
    }

    public void setMobClient(ResourceLocation id, boolean enabled) {
        for (int i = 0; i < mobs.size(); i++) {
            MobSetting mob = mobs.get(i);
            if (mob.id().equals(id)) {
                mobs.set(i, new MobSetting(id, mob.name(), enabled));
                return;
            }
        }
    }

    public void setBehaviorClient(ResourceLocation id, boolean enabled) {
        for (int i = 0; i < behaviors.size(); i++) {
            BehaviorSetting behavior = behaviors.get(i);
            if (behavior.id().equals(id)) {
                behaviors.set(i, new BehaviorSetting(id, behavior.name(), enabled));
                if (PROTECT_PLAYER_ID.equals(id) && !enabled) {
                    setBehaviorClient(FOLLOW_PLAYER_ID, false);
                }
                return;
            }
        }
    }

    public void setGuardPostTimeSecondsClient(int seconds) {
        guardPostTimeSeconds = GuardSettings.clampGuardPostStaySeconds(seconds);
    }

    @Override
    public boolean stillValid(Player player) {
        return villager == null
                || NpcUiMenu.villagerStillThere(player, villager);
    }

    @Override
    public boolean isForNpc(NpcEntity npc) {
        return villager == npc;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
