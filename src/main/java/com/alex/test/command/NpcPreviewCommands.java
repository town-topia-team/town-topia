package com.alex.test.command;

import com.alex.test.DynamicVillages;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.animation.ProfessionAnimationPreview;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.ConverseActivity;
import com.alex.test.village.social.ConversationScript;
import com.alex.test.village.social.ConversationScripts;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Handlers for the {@code /villager} subcommands that preview how villagers
 * look: mocap clips ({@code mocap}), animation states and GeckoLib's loaded
 * clips ({@code npc_anim}, {@code animdebug}), and thought/speech bubbles
 * ({@code thoughts}).
 *
 * <p>The command tree itself is built in {@link VillagerCommands#register}.
 * The preview villagers spawned here are throwaway entities with AI off, and
 * each preview has its own clear command.
 */
final class NpcPreviewCommands {

    private NpcPreviewCommands() {}

    static List<String> professionPreviewNames() {
        return Arrays.stream(Profession.values()).map(p -> p.name().toLowerCase(Locale.ROOT)).toList();
    }

    static CompletableFuture<Suggestions> suggestPreviewItems(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                java.util.stream.Stream.concat(java.util.stream.Stream.of("none"),
                        BuiltInRegistries.ITEM.keySet().stream().map(ResourceLocation::toString)), builder);
    }

    static int professionPreviewUsage(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                "/villager preview <profession> <item|none> <speed>\n"
                        + "Example: /villager preview farmer minecraft:iron_hoe 1.4\n"
                        + "Stands still and cycles through each work animation for three loops. "
                        + "Speed: 0.25 to 3; 1 is normal playback.\n"
                        + "/villager preview bass [play|jam] - a bard on the upright bass, its bass piece looping\n"
                        + "/villager preview violin [play|walk|dance] - a bard on the violin, its violin piece looping\n"
                        + "/villager preview flute [play|walk|dance] - a bard on the flute (silent)\n"
                        + "/villager preview guitar [play|walk|jam] - a bard on the guitar (silent)\n"
                        + "/villager preview bongos [play|jam] - a bard on a stool at the bongos, the band song's drums looping\n"
                        + "/villager preview minimerchant - the Mini Merchant model, standing still\n"
                        + "/villager preview midmerchant - the Mid Merchant model, standing still\n"
                        + "/villager preview clear - removes these previews within 32 blocks")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    static int spawnProfessionPreview(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        String professionName = StringArgumentType.getString(ctx, "profession");
        Profession profession = Arrays.stream(Profession.values())
                .filter(p -> p.name().equalsIgnoreCase(professionName)).findFirst().orElse(null);
        if (profession == null) {
            source.sendFailure(Component.literal("Unknown profession '" + professionName
                    + "'. Choose: " + String.join(", ", professionPreviewNames())));
            return 0;
        }
        ResourceLocation itemId = ResourceLocationArgument.getId(ctx, "item");
        ItemStack tool = ItemStack.EMPTY;
        if (!itemId.equals(ResourceLocation.withDefaultNamespace("none"))) {
            if (!BuiltInRegistries.ITEM.containsKey(itemId)) {
                source.sendFailure(Component.literal("Unknown item '" + itemId + "'."));
                return 0;
            }
            tool = new ItemStack(BuiltInRegistries.ITEM.get(itemId));
        }
        float speed = FloatArgumentType.getFloat(ctx, "speed");
        if (!Float.isFinite(speed)) {
            source.sendFailure(Component.literal("Playback speed must be a finite number from 0.25 to 3."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        Vec3 ahead = caller.position().add(Vec3.directionFromRotation(0, caller.getYRot()).scale(2.5));
        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        if (npc == null) {
            source.sendFailure(Component.literal("Could not create the NPC."));
            return 0;
        }
        face(npc, caller.getYRot() + 180f, ahead.x, caller.getY(), ahead.z);
        npc.setProfession(profession);
        npc.setItemSlot(EquipmentSlot.MAINHAND, tool);
        npc.setDropChance(EquipmentSlot.MAINHAND, 0f);
        npc.setPersistenceRequired();
        npc.setInvulnerable(true);
        npc.startProfessionAnimationPreview(speed);
        if (!level.addFreshEntity(npc)) {
            source.sendFailure(Component.literal("Could not spawn the preview NPC."));
            return 0;
        }
        String animations = ProfessionAnimationPreview.clips(profession).stream()
                .map(clip -> clip.state().name().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("Spawned " + profession.displayName()
                + " preview at " + speed + "x: " + animations
                + ". Clear it with /villager preview clear.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /**
     * {@code /villager preview bass [play|jam]} and {@code /villager preview violin
     * [play|walk|dance]}: a bard in front of you on that instrument and clip, its serenade
     * recording looping from it until {@code /villager preview clear}.
     */
    static int spawnInstrumentPreview(CommandContext<CommandSourceStack> ctx, VillagerAnimationState clip) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        Vec3 ahead = caller.position().add(Vec3.directionFromRotation(0, caller.getYRot()).scale(2.5));
        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        if (npc == null) {
            source.sendFailure(Component.literal("Could not create the NPC."));
            return 0;
        }
        face(npc, caller.getYRot() + 180f, ahead.x, caller.getY(), ahead.z);
        npc.setProfession(Profession.BARD);
        npc.setPersistenceRequired();
        npc.setInvulnerable(true);
        npc.startInstrumentPreview(clip);
        if (!level.addFreshEntity(npc)) {
            source.sendFailure(Component.literal("Could not spawn the preview NPC."));
            return 0;
        }
        String instrument = clip.isViolin() ? "violin" : clip.isFlute() ? "flute" : clip.isGuitar() ? "guitar"
                : clip.isBongos() ? "bongos" : "bass";
        String music = clip.isFlute() || clip.isGuitar() ? ", silent (the " + instrument + " has no recorded piece)."
                : clip.isBongos() ? ", the drum part of the band song looping in time."
                : ", its " + instrument + " piece looping.";
        source.sendSuccess(() -> Component.literal("Spawned a bard on the " + instrument + " ("
                + clip.name().toLowerCase(Locale.ROOT) + ")" + music
                + " Clear it with /villager preview clear.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    static int clearProfessionPreviews(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<NpcEntity> previews = source.getLevel().getEntitiesOfClass(NpcEntity.class,
                new AABB(BlockPos.containing(source.getPosition())).inflate(32),
                NpcEntity::isProfessionAnimationPreview);
        // An instrument preview's recording would play out its last seconds after the bard is gone.
        if (previews.stream().anyMatch(NpcEntity::hasPreviewMusic)) {
            for (var sound : List.of(com.alex.test.ModSounds.BARD_SHOWOFF_BASS, com.alex.test.ModSounds.BARD_SHOWOFF_VIOLIN,
                    com.alex.test.ModSounds.BARD_SHOWOFF_VIOLIN_2, com.alex.test.ModSounds.BARD_SHOWOFF_VIOLIN_1,
                    com.alex.test.ModSounds.BARD_SHOWOFF_VIOLIN_3, com.alex.test.ModSounds.BARD_SHOWOFF_VIOLIN_4,
                    com.alex.test.ModSounds.BARD_SUNNY_LANE, com.alex.test.ModSounds.BARD_SUNNY_LANE_BASS,
                    com.alex.test.ModSounds.BARD_GET_UP_AND_GO, com.alex.test.ModSounds.BARD_BAND_BONGOS)) {
                var stop = new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(
                        sound.getId(), net.minecraft.sounds.SoundSource.RECORDS);
                for (var player : source.getLevel().players()) {
                    if (player.distanceToSqr(source.getPosition()) <= 96 * 96) player.connection.send(stop);
                }
            }
        }
        previews.forEach(npc -> {
            npc.removePreviewStation();   // a blacksmith preview's anvil, table or furnace
            npc.discard();
        });
        List<com.alex.test.entity.MiniMerchantPreview> merchants = source.getLevel().getEntitiesOfClass(
                com.alex.test.entity.MiniMerchantPreview.class,
                new AABB(BlockPos.containing(source.getPosition())).inflate(32));
        merchants.forEach(Entity::discard);
        int removed = previews.size() + merchants.size();
        source.sendSuccess(() -> Component.literal("Removed " + removed
                + " profession animation preview(s)."), false);
        return removed;
    }

    static int spawnMiniMerchant(CommandContext<CommandSourceStack> ctx) {
        return spawnMerchantModel(ctx, DynamicVillages.MINI_MERCHANT_PREVIEW.get(), "Mini Merchant");
    }

    static int spawnMidMerchant(CommandContext<CommandSourceStack> ctx) {
        return spawnMerchantModel(ctx, DynamicVillages.MID_MERCHANT_PREVIEW.get(), "Mid Merchant");
    }

    /** Puts a merchant model 2.5 blocks in front of the player, facing them. */
    private static int spawnMerchantModel(CommandContext<CommandSourceStack> ctx,
            net.minecraft.world.entity.EntityType<com.alex.test.entity.MiniMerchantPreview> type, String name) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        Vec3 ahead = caller.position().add(Vec3.directionFromRotation(0, caller.getYRot()).scale(2.5));
        var merchant = type.create(level);
        if (merchant == null) {
            source.sendFailure(Component.literal("Could not create the " + name + "."));
            return 0;
        }
        float yaw = caller.getYRot() + 180f;
        merchant.moveTo(ahead.x, caller.getY(), ahead.z, yaw, 0f);
        merchant.setYBodyRot(yaw);
        merchant.setYHeadRot(yaw);
        merchant.yBodyRotO = yaw;
        merchant.yHeadRotO = yaw;
        merchant.yRotO = yaw;
        if (!level.addFreshEntity(merchant)) {
            source.sendFailure(Component.literal("Could not spawn the " + name + "."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Spawned the " + name + ". Clear it with /villager preview clear.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    // =========================================================================
    // Mocap preview
    // =========================================================================

    static int mocapUsage(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                "/villager mocap <" + String.join("|",
                        Arrays.copyOfRange(NpcEntity.MOCAP_CLIPS, 1, NpcEntity.MOCAP_CLIPS.length))
                        + ">  - spawns a villager in front of you looping that clip\n"
                        + "/villager mocap stop  - clears the preview on villagers within 32 blocks")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /**
     * Spawns a villager two blocks in front of the caller, facing them, looping one
     * mocap clip. The NPC is deliberately not bound to a {@code VillagerModel}, so no
     * task AI runs and nothing competes with the preview.
     */
    /**
     * Puts an NPC somewhere, pointing somewhere, and makes it actually point there.
     *
     * <p>{@code moveTo} sets {@code yRot}, and a {@link net.minecraft.world.entity.LivingEntity}
     * is not drawn facing {@code yRot} -- it is drawn facing {@code yBodyRot}, which
     * stays at its default until something moves the entity. With AI off nothing ever
     * does, so a preview villager rendered facing south whatever it was told, and the
     * prop placed in front of it landed behind it for every caller heading except one.
     */
    private static void face(NpcEntity npc, float yaw, double x, double y, double z) {
        npc.moveTo(x, y, z, yaw, 0f);
        npc.setYBodyRot(yaw);
        npc.setYHeadRot(yaw);
        npc.yBodyRotO = yaw;
        npc.yHeadRotO = yaw;
        npc.yRotO = yaw;
    }

    static int mocapSpawn(CommandContext<CommandSourceStack> ctx, String clipName) {
        CommandSourceStack source = ctx.getSource();
        int clip = NpcEntity.mocapClipByName(clipName);
        if (clip <= 0) {
            source.sendFailure(Component.literal("Unknown clip '" + clipName + "'. Try one of: "
                    + String.join(", ", Arrays.copyOfRange(
                            NpcEntity.MOCAP_CLIPS, 1, NpcEntity.MOCAP_CLIPS.length))));
            return 0;
        }
        if (!(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Server-side only."));
            return 0;
        }
        Entity caller = source.getEntity();
        if (caller == null) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }

        Vec3 eye = caller.position();
        Vec3 ahead = eye.add(Vec3.directionFromRotation(0, caller.getYRot()).scale(2.5));
        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        if (npc == null) {
            source.sendFailure(Component.literal("Could not create the NPC."));
            return 0;
        }
        face(npc, caller.getYRot() + 180f, ahead.x, eye.y, ahead.z);
        npc.setMocapClip(clip);
        npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                        NpcEntity.mocapTool(clip));
        npc.setCustomName(Component.literal("mocap: " + clipName));
        npc.setCustomNameVisible(true);
        npc.setNoAi(true);                       // stand still and just play the clip
        npc.addTag(NpcEntity.MOCAP_PREVIEW_TAG); // saved, unlike the clip
        level.addFreshEntity(npc);

        source.sendSuccess(() -> Component.literal(
                "Spawned a villager looping '" + clipName + "'. "
                        + "Clear it with /villager mocap stop.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /**
     * What to put in front of a villager so a clip has something to work on.
     *
     * <p>Matched on a substring of the clip name rather than listed per clip: the set
     * gains a chop or a till every time a take is recorded, and a table keyed by exact
     * name goes stale silently -- which has already cost this tree three separate
     * bugs. First match wins, so the more specific keys come first.
     */
    private record Prop(String match, Block ground, Block standing, Block above) {}

    private static final List<Prop> PROPS = List.of(
            // Two blocks tall: a villager swings at chest height, and a one-block stump
            // leaves the stroke landing in mid-air above it. It does put the prop between
            // the camera and the villager from some angles -- film from the other side.
            new Prop("chop",    Blocks.DIRT,     Blocks.OAK_LOG,        Blocks.OAK_LOG),
            new Prop("mine",    Blocks.STONE,    Blocks.STONE,          Blocks.STONE),
            // the mining clip is an overhand strike now, and carries no "mine" in its name
            new Prop("attack_down", Blocks.STONE, Blocks.STONE,         Blocks.STONE),
            new Prop("forge",   Blocks.STONE,    Blocks.ANVIL,          null),
            new Prop("fence",   Blocks.DIRT,     Blocks.OAK_FENCE,      null),
            // soil work: the crop stands on farmland, the villager works it from here
            new Prop("harvest", Blocks.FARMLAND, Blocks.WHEAT,          null),
            new Prop("plant",   Blocks.FARMLAND, Blocks.WHEAT,          null),
            new Prop("sow",     Blocks.FARMLAND, Blocks.WHEAT,          null),
            new Prop("till",    Blocks.FARMLAND, null,                  null),
            new Prop("dig",     Blocks.FARMLAND, null,                  null),
            new Prop("gather",  Blocks.GRASS_BLOCK, null,               null),
            new Prop("fish",    Blocks.WATER,    null,                  null),
            new Prop("craft",   Blocks.DIRT,     Blocks.CRAFTING_TABLE, null));

    /** Every block this command ever places, so cleanup can tell props from scenery. */
    private static final Set<Block> PROP_BLOCKS = PROPS.stream()
            .flatMap(p -> java.util.stream.Stream.of(p.ground(), p.standing(), p.above()))
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());

    private static Prop propFor(String clip) {
        for (Prop prop : PROPS) {
            if (clip.contains(prop.match())) return prop;
        }
        return null;
    }

    private static void placeProp(ServerLevel level, BlockPos front, String clip) {
        Prop prop = propFor(clip);
        if (prop == null) return;
        if (prop.ground() != null) level.setBlockAndUpdate(front.below(), prop.ground().defaultBlockState());
        if (prop.standing() != null) {
            BlockState state = prop.standing().defaultBlockState();
            if (prop.standing() == Blocks.WHEAT) {
                state = state.setValue(net.minecraft.world.level.block.CropBlock.AGE, 7);
            }
            level.setBlockAndUpdate(front, state);
        }
        if (prop.above() != null) level.setBlockAndUpdate(front.above(), prop.above().defaultBlockState());
    }

    /**
     * A row of villagers, one per clip, each standing on its mark and looping.
     *
     * <p>Root motion is off for these ({@code setMocapHoldInPlace}) -- it is right for
     * a single preview, where it stops the feet skating, and wrong for a row, where it
     * would walk forty villagers into each other.
     */
    static int mocapLineup(CommandContext<CommandSourceStack> ctx, int spacing) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null || !(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        clearPreviews(level, caller.position());

        Vec3 forward = Vec3.directionFromRotation(0, caller.getYRot());
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        Vec3 base = caller.position().add(forward.scale(6));
        int count = NpcEntity.MOCAP_CLIPS.length - 1;
        int spawned = 0;

        for (int i = 1; i <= count; i++) {
            String clipName = NpcEntity.MOCAP_CLIPS[i];
            Vec3 at = base.add(right.scale((i - 1 - (count - 1) / 2.0) * spacing));
            NpcEntity npc = DynamicVillages.NPC.get().create(level);
            if (npc == null) continue;
            face(npc, caller.getYRot() + 180f, at.x, base.y, at.z);
            npc.setMocapClip(i);
            npc.setMocapHoldInPlace(true);
            npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                            NpcEntity.mocapTool(i));
            npc.setCustomName(Component.literal(clipName));
            npc.setCustomNameVisible(true);
            npc.setNoAi(true);
            npc.addTag(NpcEntity.MOCAP_PREVIEW_TAG);
            level.addFreshEntity(npc);

            BlockPos feet = npc.blockPosition();
            placeProp(level, feet.offset((int) Math.round(-forward.x), 0, (int) Math.round(-forward.z)),
                      clipName);
            spawned++;
        }

        int total = spawned;
        source.sendSuccess(() -> Component.literal(
                "Lined up " + total + " villagers, " + spacing + " blocks apart, each looping one clip. "
                        + "Clear them with /villager mocap stop.")
                .withStyle(ChatFormatting.GREEN), false);
        return spawned;
    }

    /** Turns the preview off for nearby villagers, and removes the ones spawned by it. */
    /**
     * {@code /villager clericcast [dummy] [level]}: a brainless cleric throwing
     * healing orbs on a loop, at you or at a dummy patient across from it (see
     * {@link com.alex.test.village.villagers.cleric.behavior.ClericCastDemo}).
     */
    static int clericCast(CommandContext<CommandSourceStack> ctx, int level, boolean dummy) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null || !(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        Component reply = com.alex.test.village.villagers.cleric.behavior.ClericCastDemo
                .start(serverLevel, caller, level, dummy);
        source.sendSuccess(() -> reply.copy().withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /**
     * {@code /villager clericcast_raw [dummy] [level]}: the same demo cleric drawn
     * as plain rigid boxes, without its limbs welded and bent, to see the clip's
     * own pose. Stopped by the same {@code stop}.
     */
    static int clericCastRaw(CommandContext<CommandSourceStack> ctx, int level, boolean dummy) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null || !(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        Component reply = com.alex.test.village.villagers.cleric.behavior.ClericCastDemo
                .start(serverLevel, caller, level, dummy, true);
        source.sendSuccess(() -> reply.copy().withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    static int clericCastStop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("Server-side only."));
            return 0;
        }
        int stopped = com.alex.test.village.villagers.cleric.behavior.ClericCastDemo.stop(serverLevel);
        source.sendSuccess(() -> Component.literal("Stopped " + stopped + " demo cleric(s)."), false);
        return 1;
    }

    /**
     * {@code /villager magecast [spell] [level]}: a brainless mage casting at a
     * pack of practice dummies on a loop, every spell in turn or just one (see
     * {@link com.alex.test.village.villagers.mage.behavior.MageCastDemo}).
     */
    static int mageCast(CommandContext<CommandSourceStack> ctx, String spell, int level) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null || !(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        Component reply = com.alex.test.village.villagers.mage.behavior.MageCastDemo
                .start(serverLevel, caller, spell, level);
        source.sendSuccess(() -> reply.copy().withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    static int mageCastStop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("Server-side only."));
            return 0;
        }
        int stopped = com.alex.test.village.villagers.mage.behavior.MageCastDemo.stop(serverLevel);
        source.sendSuccess(() -> Component.literal("Stopped " + stopped + " demo mage(s)."), false);
        return 1;
    }

    /**
     * {@code /villager sharpen}: a brainless butcher sharpening at a grindstone
     * put down in front of you, on a loop (see
     * {@link com.alex.test.village.villagers.butcher.behavior.ButcherSharpenDemo}).
     */
    static int butcherSharpen(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null || !(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        Component reply = com.alex.test.village.villagers.butcher.behavior.ButcherSharpenDemo
                .start(serverLevel, caller);
        source.sendSuccess(() -> reply.copy().withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    static int butcherSharpenStop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("Server-side only."));
            return 0;
        }
        int stopped = com.alex.test.village.villagers.butcher.behavior.ButcherSharpenDemo.stop(serverLevel);
        source.sendSuccess(() -> Component.literal("Stopped " + stopped + " demo butcher(s)."), false);
        return 1;
    }

    /**
     * {@code /villager flyguys [walk|stand|seed|all]}: the outside animator's clips on villagers
     * with a chair, farmland and a walking lane (see {@link com.alex.test.animation.FlyguysDemo}).
     */
    static int flyguys(CommandContext<CommandSourceStack> ctx, String which) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null || !(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        Component reply = com.alex.test.animation.FlyguysDemo.start(serverLevel, caller, which);
        source.sendSuccess(() -> reply.copy().withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    static int flyguysStop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("Server-side only."));
            return 0;
        }
        int stopped = com.alex.test.animation.FlyguysDemo.stop(serverLevel);
        source.sendSuccess(() -> Component.literal("Stopped " + stopped
                + " flyguys demo villager(s) and put their blocks back."), false);
        return 1;
    }

    static int mocapStop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null || !(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        int cleared = clearPreviews(level, caller.position());
        source.sendSuccess(() -> Component.literal(
                "Cleared " + cleared + " mocap villager(s) and their props."), false);
        return 1;
    }

    /**
     * Removes preview villagers near a point, and the props put out for them.
     *
     * <p>Only blocks this command is capable of placing are taken back up, so a
     * lineup dropped next to somebody's farm cannot eat the farm. The radius has to
     * cover a whole lineup from wherever you happen to be standing when you clear it:
     * 57 clips at three blocks apart is a row 168 wide, so half of it is 84 from the
     * middle and further from either end.
     */
    private static int clearPreviews(ServerLevel level, Vec3 around) {
        BlockPos centre = BlockPos.containing(around);
        List<NpcEntity> nearby = level.getEntitiesOfClass(
                NpcEntity.class, new net.minecraft.world.phys.AABB(centre).inflate(220));
        int cleared = 0;
        for (NpcEntity npc : nearby) {
            // The clip is not saved: after a reload only the tag is left.
            if (npc.getMocapClip() <= 0 && !npc.getTags().contains(NpcEntity.MOCAP_PREVIEW_TAG)) continue;
            BlockPos feet = npc.blockPosition();
            // A flyguys demo villager stands on plain ground and its demo puts its own blocks back.
            boolean ownProps = !npc.getTags().contains(com.alex.test.animation.FlyguysDemo.TAG);
            for (BlockPos p : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 2, 2))) {
                if (ownProps && PROP_BLOCKS.contains(level.getBlockState(p).getBlock())) {
                    level.setBlockAndUpdate(p.immutable(), Blocks.AIR.defaultBlockState());
                }
            }
            npc.setMocapClip(0);
            npc.discard();                        // these were spawned only for the preview
            cleared++;
        }
        return cleared;
    }

    /**
     * Reports which animation names GeckoLib actually resolved.
     *
     * <p>Worth having because the failure is silent: {@code GeoModel.getAnimation}
     * returns null when a name is missing rather than throwing, and a file that fails
     * to parse is replaced wholesale by an <em>empty</em> animation set. Either way
     * nothing plays, nothing is logged, and the villager just stands in its rest pose.
     * This turns that into an answer.
     *
     * <p>Client-side data, so it only reports in single player, where the integrated
     * server shares the JVM with the client.
     */
    static int animDebug(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var cache = software.bernie.geckolib.cache.GeckoLibCache.getBakedAnimations();
        if (cache == null || cache.isEmpty()) {
            source.sendFailure(Component.literal(
                    "GeckoLib has no animations loaded here (dedicated server, or the "
                            + "client cache is not shared)."));
            return 0;
        }

        for (var entry : cache.entrySet()) {
            if (!entry.getKey().getNamespace().equals(DynamicVillages.MODID))
                continue;
            var loaded = entry.getValue().animations();
            source.sendSuccess(() -> Component.literal(
                    entry.getKey().getPath() + ": " + loaded.size() + " animations")
                    .withStyle(loaded.isEmpty() ? ChatFormatting.RED : ChatFormatting.GREEN), false);
            DynamicVillages.LOGGER.info("[animdebug] {} -> {} animations {}", entry.getKey(),
                    loaded.size(), loaded.size() < 40 ? loaded.keySet() : "");
        }

        // The rig everything renders on, and the clip names the controllers ask for.
        StringBuilder missing = new StringBuilder();
        var villagerAnims = cache.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "animations/base_villager.animation.json"));
        for (String name : new String[] {"walk", "chop", "act_chop", "act_till", "act_attack",
                                         "mixamo_walk", "mixamo_till", "mixamo_chop",
                                         "mocap_fence", "mocap_chop", "mocap_punch"}) {
            boolean ok = villagerAnims != null && villagerAnims.getAnimation(name) != null;
            DynamicVillages.LOGGER.info("[animdebug] {} : {}", name, ok ? "resolved" : "MISSING");
            if (!ok)
                missing.append(name).append(" ");
        }

        String result = missing.isEmpty()
                ? "All expected animations resolved."
                : "MISSING: " + missing;
        source.sendSuccess(() -> Component.literal(result)
                .withStyle(missing.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        return 1;
    }

    /** How long an endless clip (idle, walk, sitting, playing) is shown when no time is given. */
    private static final int NPC_ANIM_DEFAULT_TICKS = 200;
    private static final double NPC_ANIM_RANGE = 20.0;

    /** Every state by its lowercase name, for tab completion. */
    static List<String> npcAnimationNames() {
        return Arrays.stream(VillagerAnimationState.values())
                .map(s -> s.name().toLowerCase(Locale.ROOT)).toList();
    }

    static int npcAnimationUsage(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                "/villager npc_anim <name> [seconds]  - plays that state on the villager you look at"
                        + " (or the nearest within " + (int) NPC_ANIM_RANGE + " blocks), standing it still\n"
                        + "/villager npc_anim stop  - ends it early\n"
                        + "names: " + String.join(", ", npcAnimationNames()))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    static int stopNpcAnimation(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        NpcEntity target = npcAnimationTarget(source);
        if (target == null) {
            source.sendFailure(Component.literal("No NPC within " + (int) NPC_ANIM_RANGE + " blocks."));
            return 0;
        }
        if (target.isProfessionAnimationPreview()) {
            source.sendFailure(Component.literal("Remove this profession preview with /villager preview clear."));
            return 0;
        }
        boolean was = target.isPreviewingAnimation();
        target.previewAnimation(null, 0);
        source.sendSuccess(() -> Component.literal(was
                ? "NPC animation preview stopped on " + target.getStringUUID().substring(0, 8)
                : "That NPC was not previewing an animation."), false);
        return 1;
    }

    /**
     * Plays a state on a villager from its first frame and holds it: one-shot
     * clips for their own length (repeat the command to see one again), endless
     * ones for 10 s, or {@code seconds} when given. The villager stands still
     * meanwhile; the brain's own animation changes are ignored until it ends.
     * A number still works in place of the name, for scripts that pass ids.
     */
    static int triggerNpcAnimation(CommandContext<CommandSourceStack> ctx, int seconds) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        VillagerAnimationState state = parseAnimationState(name);
        if (state == null) {
            source.sendFailure(Component.literal("Unknown animation '" + name + "'. Names: "
                    + String.join(", ", npcAnimationNames())));
            return 0;
        }
        NpcEntity target = npcAnimationTarget(source);
        if (target == null) {
            source.sendFailure(Component.literal("No NPC within " + (int) NPC_ANIM_RANGE + " blocks."));
            return 0;
        }
        if (target.isProfessionAnimationPreview()) {
            source.sendFailure(Component.literal("This NPC is cycling profession animations. "
                    + "Remove it with /villager preview clear before using npc_anim."));
            return 0;
        }

        float clip = VillagerAnimationState.getDuration(state);
        int ticks = seconds > 0 ? seconds * 20
                : Float.isInfinite(clip) ? NPC_ANIM_DEFAULT_TICKS : (int) clip;
        target.previewAnimation(state, ticks);
        String seated = target.isChairSeated() ? " (seated)" : "";
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "NPC animation → %s%s on %s for %.1f s",
                state.name().toLowerCase(Locale.ROOT), seated,
                target.getStringUUID().substring(0, 8), ticks / 20f)), false);
        return 1;
    }

    @Nullable
    private static VillagerAnimationState parseAnimationState(String name) {
        for (VillagerAnimationState s : VillagerAnimationState.values()) {
            if (s.name().equalsIgnoreCase(name)) return s;
        }
        try {
            int id = Integer.parseInt(name);
            for (VillagerAnimationState s : VillagerAnimationState.values()) {
                if (s.getId() == id) return s;
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    /** The NPC the caller looks at, or else the nearest one in range. */
    @Nullable
    private static NpcEntity npcAnimationTarget(CommandSourceStack source) {
        Entity caller = source.getEntity();
        if (caller == null) return null;
        double range = NPC_ANIM_RANGE;
        Vec3 eye = caller.getEyePosition(), look = caller.getViewVector(1.0F);
        AABB box = caller.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        var hit = ProjectileUtil.getEntityHitResult(
                caller, eye, eye.add(look.scale(range)), box,
                e -> e instanceof NpcEntity, range * range);
        NpcEntity target = hit != null && hit.getEntity() instanceof NpcEntity looked ? looked : null;
        if (target == null) {
            // Fall back to the nearest one. Aiming is fine with a mouse, but this
            // command is also how the render harness drives a state over RCON, and
            // a console has no view vector to aim with -- the raycast simply never
            // hits, silently, which cost a round of "verified" screenshots that were
            // really just the idle clip.
            target = caller.level().getEntitiesOfClass(
                            NpcEntity.class, caller.getBoundingBox().inflate(range))
                    .stream()
                    .min(java.util.Comparator.comparingDouble(caller::distanceToSqr))
                    .orElse(null);
        }
        return target;
    }

    // =========================================================================
    // Thought bubbles
    // =========================================================================

    /** How long a commanded bubble stays up. Long enough to walk round and look. */
    private static final int DEBUG_BUBBLE_TICKS = 200;

    /**
     * Put one icon over the villager the player is looking at.
     *
     * <p>Shown as a speech bubble rather than a thought cloud: the icons are
     * being reviewed here, and the balloon is the tighter of the two frames.
     */
    static int thoughtsShow(CommandContext<CommandSourceStack> ctx, String iconId) {
        CommandSourceStack source = ctx.getSource();
        ThoughtIcon icon = ThoughtIcon.byId(iconId);
        if (icon == null) {
            source.sendFailure(Component.literal("Unknown icon '" + iconId + "'."));
            return 0;
        }
        NpcEntity npc = lookedAtNpc(source, 20.0);
        if (npc == null) return 0;

        npc.showBubble(icon, true, DEBUG_BUBBLE_TICKS);
        source.sendSuccess(() -> Component.literal(
                "Showing " + icon.id() + " (" + icon.valence() + ").")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /** Print the dirt and skin stage of the villager being looked at. */
    static int dirtShow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        NpcEntity npc = lookedAtNpc(source, 20.0);
        if (npc == null) return 0;
        source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "%s dirt %.1f, stage %d", npc.getProfession().displayName(),
                npc.getDirt(), npc.getDirtStage())), false);
        return 1;
    }

    /** Set the dirt of the villager being looked at, to check each skin stage by eye. */
    static int dirtSet(CommandContext<CommandSourceStack> ctx, float amount) {
        CommandSourceStack source = ctx.getSource();
        NpcEntity npc = lookedAtNpc(source, 20.0);
        if (npc == null) return 0;
        npc.setDirt(amount);
        source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "%s dirt set to %.1f, stage %d", npc.getProfession().displayName(),
                npc.getDirt(), npc.getDirtStage())).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /** Print what the villager being looked at remembers. */
    static int thoughtsMemory(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Server-side only."));
            return 0;
        }
        NpcEntity npc = lookedAtNpc(source, 20.0);
        if (npc == null) return 0;

        VillagerModel model = modelFor(npc, level);
        String memories = model.brain().memory().describe(level.getGameTime());
        source.sendSuccess(() -> Component.literal(
                npc.getProfession().displayName() + " remembers: " + memories), false);
        return 1;
    }

    /**
     * Script a conversation between the two villagers nearest the player and
     * print it, so the exchange can be read in chat while it plays out in
     * front of you.
     */
    static int thoughtsTalk(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null || !(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }

        List<NpcEntity> nearby = level.getEntitiesOfClass(
                NpcEntity.class, caller.getBoundingBox().inflate(24.0),
                npc -> npc.isAlive() && npc.getMocapClip() <= 0);
        nearby.sort((a, b) -> Double.compare(a.distanceToSqr(caller), b.distanceToSqr(caller)));
        if (nearby.size() < 2) {
            source.sendFailure(Component.literal("Need two villagers within 24 blocks."));
            return 0;
        }

        NpcEntity na = nearby.get(0), nb = nearby.get(1);
        VillagerModel a = modelFor(na, level);
        VillagerModel b = modelFor(nb, level);

        ConversationScript script = ConversationScripts.between(
                a.brain().memory(), na.getProfession(),
                b.brain().memory(), nb.getProfession(),
                level.getGameTime(), na.getRandom());

        int duration = script.durationTicks();
        a.brain().forceActivity(new ConverseActivity(nb, true,  duration, true, false, script));
        b.brain().forceActivity(new ConverseActivity(na, false, duration, true, false, script));

        String header = "A=" + na.getProfession().displayName()
                + "  B=" + nb.getProfession().displayName();
        source.sendSuccess(() -> Component.literal(header).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal(script.describe())
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /**
     * A row of stationary villagers, one per icon, for reviewing the whole set
     * in-game at the size it actually renders. Cleared with
     * {@code /villager thoughts clear}.
     */
    static int thoughtsLineup(CommandContext<CommandSourceStack> ctx, int spacing) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null || !(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        clearThoughtPreviews(level, caller.position());

        Vec3 forward = Vec3.directionFromRotation(0, caller.getYRot());
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        Vec3 base = caller.position().add(forward.scale(6));
        List<String> ids = ThoughtIcon.ids().toList();

        // Wrapped into rows of ten: sixty-four villagers two blocks apart is a
        // line long enough to leave the loaded chunks.
        int perRow = 10;
        int spawned = 0;
        for (int i = 0; i < ids.size(); i++) {
            ThoughtIcon icon = ThoughtIcon.byId(ids.get(i));
            int col = i % perRow, row = i / perRow;
            Vec3 at = base.add(right.scale((col - (perRow - 1) / 2.0) * spacing))
                          .add(forward.scale(row * spacing * 2.0));
            NpcEntity npc = DynamicVillages.NPC.get().create(level);
            if (npc == null) continue;
            face(npc, caller.getYRot() + 180f, at.x, base.y, at.z);
            npc.setNoAi(true);
            npc.setCustomName(Component.literal(icon.id()));
            npc.setCustomNameVisible(true);
            npc.addTag(NpcEntity.THOUGHT_PREVIEW_TAG);
            level.addFreshEntity(npc);
            // Alternating styles so both bubble frames are on screen at once.
            npc.showBubble(icon, i % 2 == 0, Integer.MAX_VALUE);
            spawned++;
        }

        int total = spawned;
        source.sendSuccess(() -> Component.literal(
                "Lined up " + total + " villagers, one per icon. "
                        + "Clear them with /villager thoughts clear.")
                .withStyle(ChatFormatting.GREEN), false);
        return spawned;
    }

    static int thoughtsClear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Entity caller = source.getEntity();
        if (caller == null || !(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        int cleared = clearThoughtPreviews(level, caller.position());
        source.sendSuccess(() -> Component.literal(
                "Cleared " + cleared + " preview villagers."), false);
        return cleared;
    }

    private static int clearThoughtPreviews(ServerLevel level, Vec3 around) {
        BlockPos centre = BlockPos.containing(around);
        int cleared = 0;
        for (NpcEntity npc : level.getEntitiesOfClass(
                NpcEntity.class, new net.minecraft.world.phys.AABB(centre).inflate(220))) {
            if (!npc.getTags().contains(NpcEntity.THOUGHT_PREVIEW_TAG)) continue;
            npc.discard();
            cleared++;
        }
        return cleared;
    }

    /**
     * The logical villager behind a puppet, falling back to the debug village
     * the way {@code /villager goto} does — so these commands work on a
     * villager spawned with {@code /villager spawn} in an empty test world,
     * which is where they are most often reached for.
     */
    private static VillagerModel modelFor(NpcEntity npc, ServerLevel level) {
        VillagerModel model = npc.resolveVillageModel(level);
        if (model != null) return model;
        model = VillageManager.INSTANCE.getOrCreateModel(npc.getNpcId());
        if (model.puppet() == null) model.bind(npc);
        return model;
    }

    /** The NPC the command's caller is looking at; sends the failure message itself. */
    @Nullable
    static NpcEntity lookedAtNpc(CommandSourceStack source, double range) {
        Entity caller = source.getEntity();
        if (caller == null) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return null;
        }
        Vec3 eye = caller.getEyePosition(), look = caller.getViewVector(1.0F);
        AABB box = caller.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        var hit = ProjectileUtil.getEntityHitResult(
                caller, eye, eye.add(look.scale(range)), box,
                e -> e instanceof NpcEntity, range * range);
        if (hit == null || !(hit.getEntity() instanceof NpcEntity npc)) {
            source.sendFailure(Component.literal("Look at an NPC within " + (int) range + " blocks."));
            return null;
        }
        return npc;
    }
}
