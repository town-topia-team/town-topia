package com.alex.test.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /helditem} - live tuning for where a held item sits on a villager arm.
 *
 * <p>Two kinds of target. A <em>rig</em> moves every item that rig draws; a <em>tool</em>
 * moves only that class of item, on top of the rig placement, and is the one to reach
 * for when a sword looks wrong but the bread does not.
 *
 * <pre>
 *   /helditem                                     show every placement and adjustment
 *   /helditem &lt;target&gt;                            show one
 *   /helditem &lt;target&gt; offset &lt;x&gt; &lt;y&gt; &lt;z&gt;         set the offset, in model units (1/16 block)
 *   /helditem &lt;target&gt; offset add &lt;x&gt; &lt;y&gt; &lt;z&gt;     nudge it
 *   /helditem &lt;target&gt; rotation &lt;x&gt; &lt;y&gt; &lt;z&gt;       set rotation in degrees (applied X, Y, Z)
 *   /helditem &lt;target&gt; rotation add &lt;x&gt; &lt;y&gt; &lt;z&gt;   nudge it
 *   /helditem &lt;target&gt; scale &lt;s&gt;
 *   /helditem &lt;target&gt; reset
 *   /helditem &lt;rig&gt; bone &lt;name&gt;                   re-anchor to a different bone (rigs only)
 * </pre>
 *
 * <p>Targets are {@code base_villager} and {@code dynamic_villagers} for the rigs, and
 * {@code sword}, {@code axe} and {@code hoe} for the tools.
 *
 * <p>Axes, for the offsets: <b>x</b> runs across the arm, positive away from the body;
 * <b>y</b> runs up the arm from the fingertips, so negative reaches past the fist;
 * <b>z</b> runs forward, out in front of the villager.
 *
 * <p>Client-side only, so it takes effect on the next frame with no round trip and works
 * in any world. The values live in {@link HeldItemTransform} and are not persisted - copy
 * them into that class once a placement looks right.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class HeldItemTransformCommands {

    /**
     * Hand bones worth anchoring to, across both rigs. These are the limb upper bones:
     * that is the frame {@link BentLimb} builds its sweep in, and the only frame in which
     * a grip can be pinned to the drawn hand.
     */
    private static final String[] ANCHOR_SUGGESTIONS = {"right_arm", "left_arm", "bone2", "bone"};

    private HeldItemTransformCommands() {}

    /** Applies three floats to a target, either absolutely or relatively. */
    @FunctionalInterface
    private interface Vec3Action {
        void apply(Tunable target, float x, float y, float z);
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("helditem")
                .executes(ctx -> {
                    HeldItemTransform.profiles().forEach(p -> report(ctx.getSource(), p));
                    HeldItemTransform.tools().forEach(t -> report(ctx.getSource(), t));
                    return HeldItemTransform.profiles().size() + HeldItemTransform.tools().size();
                });

        for (HeldItemTransform placement : HeldItemTransform.profiles()) {
            root.then(node(placement).then(boneNode(placement)));
        }
        for (HeldItemTransform.Adjust tool : HeldItemTransform.tools()) {
            root.then(node(tool));
        }

        // A guard only has its shield up for moments mid-fight; this holds every
        // guard and captain in the shield pose, shield drawn, while it is tuned.
        root.then(Commands.literal("shieldpose")
                .then(Commands.literal("on").executes(ctx -> shieldPose(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> shieldPose(ctx.getSource(), false))));

        event.getDispatcher().register(root);
    }

    /** The knobs every target has. */
    private static LiteralArgumentBuilder<CommandSourceStack> node(Tunable target) {
        return Commands.literal(target.name())
                .executes(ctx -> report(ctx.getSource(), target))
                .then(vec3Node(target, "offset", Tunable::setOffset, Tunable::addOffset))
                .then(vec3Node(target, "rotation", Tunable::setRotation, Tunable::addRotation))
                .then(Commands.literal("scale")
                        .then(Commands.argument("scale", FloatArgumentType.floatArg(0.01f, 10f))
                                .executes(ctx -> {
                                    target.setScale(FloatArgumentType.getFloat(ctx, "scale"));
                                    return report(ctx.getSource(), target);
                                })))
                .then(Commands.literal("reset")
                        .executes(ctx -> {
                            target.reset();
                            return report(ctx.getSource(), target);
                        }));
    }

    /** Only a rig has a bone; a tool adjustment rides on whatever the rig chose. */
    private static LiteralArgumentBuilder<CommandSourceStack> boneNode(HeldItemTransform placement) {
        return Commands.literal("bone")
                .then(Commands.argument("bone", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider
                                .suggest(ANCHOR_SUGGESTIONS, builder))
                        .executes(ctx -> {
                            placement.setBone(StringArgumentType.getString(ctx, "bone"));
                            return report(ctx.getSource(), placement);
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> vec3Node(
            Tunable target, String name, Vec3Action set, Vec3Action add) {
        return Commands.literal(name)
                .then(Commands.literal("add").then(vec3Args(target, add)))
                .then(vec3Args(target, set));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> vec3Args(Tunable target, Vec3Action action) {
        return Commands.argument("x", FloatArgumentType.floatArg())
                .then(Commands.argument("y", FloatArgumentType.floatArg())
                        .then(Commands.argument("z", FloatArgumentType.floatArg())
                                .executes(ctx -> {
                                    action.apply(target,
                                            FloatArgumentType.getFloat(ctx, "x"),
                                            FloatArgumentType.getFloat(ctx, "y"),
                                            FloatArgumentType.getFloat(ctx, "z"));
                                    return report(ctx.getSource(), target);
                                })));
    }

    private static int shieldPose(CommandSourceStack source, boolean on) {
        com.alex.test.entity.NpcEntity.clientShieldPosePreview = on;
        source.sendSuccess(() -> Component.literal(on
                        ? "Shield pose ON: every guard and captain holds its shield up. Tune with /helditem shield ..."
                        : "Shield pose OFF.")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int report(CommandSourceStack source, Tunable target) {
        source.sendSuccess(() -> Component.literal(target.describe())
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }
}
