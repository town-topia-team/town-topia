package com.alex.test.client;

import com.alex.test.animation.FastPose;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Extras with a texture of their own (guard armor, the captain's cape, the bard's
 * instruments) drawn after the villager's body instead of in the middle of it.
 *
 * <p>A render layer's {@code renderForBone} runs while GeckoLib is part way through
 * the body. Asking the buffer source there for another texture sends everything
 * drawn so far to the graphics card, and GeckoLib, asking for the body's texture
 * again before the next bone, sends the extra too. An armored guard paid about
 * fourteen of those trips a frame, a third of all the frame time the villagers
 * took (spark, 2026-10-15). So each layer now notes where its part goes while
 * the body is drawn ({@link #keep}) and draws all of it from {@code render}, which
 * GeckoLib calls once the body is done, a texture at a time.
 *
 * <p>{@code /villagerfps batch on|off} switches back to drawing in the middle of
 * the body, to compare. {@code /villagerfps names <blocks>} sets how far away a
 * villager still shows its name ({@link #nameDistance}); {@code /villagerfps limbs
 * <smooth> <medium> <simple>} how tall on screen, in pixels, a limb must be for each
 * level of curve ({@link BentLimb#smoothPx}); {@code /villagerfps detail <face> <props>
 * <items>} how tall on screen a villager must be to keep its eyes and nose, its hats and
 * sleeves, and its held tool ({@link DetailCull}); {@code /villagerfps armor <trims> <stiff>}
 * when armor drops its trims and goes stiff, {@code armor off} full armor everywhere,
 * {@code armor on} the defaults back; {@code /villagerfps budget <full> <mid>} how many of
 * the villagers on screen, biggest first, keep full detail, and down to which rank the next
 * ones keep moving (not stiff) armor ({@link DetailCull#budgetFull}; {@code budget off} / {@code on}).
 * {@code /villagerfps} on its own reports all of it and what the renderer did last frame
 * ({@link VillagerFrameStats}). {@code /villagerfps log on [seconds] | off} writes where the
 * frame time goes to the game log every few seconds ({@link ClientCostLog}); {@code /villagerfps
 * log} shows the last block in chat. {@code /villagerfps anim fast|safe|gecko|auto|check|fingerprint} picks or
 * checks the villagers' posing ({@link FastPose}); {@code /villagerfps hide on|off} draws no villager at all,
 * to read the frame's cost without them. Client side, not saved.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class DrawBatching {

    /** Draw extras after the body ({@code true}) or where their bone is met, as before. */
    public static volatile boolean afterBody = true;

    /**
     * How far away, in blocks, a villager still shows its name over its head.
     * GeckoLib's own reach is 64; most of that is spent on tags too small to read,
     * and every tag costs the villager a draw of its own. Read by
     * {@link NpcGeoRenderer#getNameRenderCutoffDistance}.
     */
    public static volatile double nameDistance = 24;

    private DrawBatching() {}

    /** Where a part is to be drawn: a copy of the frame {@code poseStack} holds now. */
    static PoseStack.Pose keep(PoseStack poseStack) {
        return poseStack.last().copy();
    }

    /**
     * A pose stack standing exactly where {@code kept} was. GeckoLib's cubes and
     * {@link BentLimb} read the pose and normal matrices straight off the stack, so
     * copying both reproduces the frame they were met in.
     */
    static PoseStack at(PoseStack.Pose kept) {
        PoseStack stack = new PoseStack();
        moveTo(stack, kept);
        return stack;
    }

    /** As {@link #at}, reusing {@code stack}; its pushes must all have been popped. */
    static void moveTo(PoseStack stack, PoseStack.Pose kept) {
        stack.last().pose().set(kept.pose());
        stack.last().normal().set(kept.normal());
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("villagerfps")
                .executes(ctx -> report(ctx.getSource()))
                .then(Commands.literal("batch")
                        .then(Commands.literal("on").executes(ctx -> {
                            afterBody = true;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            afterBody = false;
                            return report(ctx.getSource());
                        })))
                .then(Commands.literal("names")
                        .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0, 64)).executes(ctx -> {
                            nameDistance = DoubleArgumentType.getDouble(ctx, "blocks");
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("count")
                                .then(Commands.argument("villagers", IntegerArgumentType.integer(0, 4096)).executes(ctx -> {
                                    // The nearest this many show a name; 0 = no limit (NameTagLimit).
                                    NameTagLimit.limit = IntegerArgumentType.getInteger(ctx, "villagers");
                                    return report(ctx.getSource());
                                }))))
                .then(Commands.literal("limbs")
                        .then(Commands.argument("smooth", FloatArgumentType.floatArg(0, 4096))
                                .then(Commands.argument("medium", FloatArgumentType.floatArg(0, 4096))
                                        .then(Commands.argument("simple", FloatArgumentType.floatArg(0, 4096)).executes(ctx -> {
                                            // Pixels of limb height on screen; each tier needs at most what the finer one does.
                                            float smooth = FloatArgumentType.getFloat(ctx, "smooth");
                                            float medium = Math.min(smooth, FloatArgumentType.getFloat(ctx, "medium"));
                                            float simple = Math.min(medium, FloatArgumentType.getFloat(ctx, "simple"));
                                            BentLimb.smoothPx = smooth;
                                            BentLimb.mediumPx = medium;
                                            BentLimb.simplePx = simple;
                                            return report(ctx.getSource());
                                        })))))
                .then(Commands.literal("detail")
                        .then(Commands.argument("face", FloatArgumentType.floatArg(0, 4096))
                                .then(Commands.argument("props", FloatArgumentType.floatArg(0, 4096))
                                        .then(Commands.argument("items", FloatArgumentType.floatArg(0, 4096)).executes(ctx -> {
                                            // Villager height on screen in pixels; 0 keeps that group always.
                                            DetailCull.facePx = FloatArgumentType.getFloat(ctx, "face");
                                            DetailCull.propsPx = FloatArgumentType.getFloat(ctx, "props");
                                            DetailCull.itemsPx = FloatArgumentType.getFloat(ctx, "items");
                                            return report(ctx.getSource());
                                        })))))
                .then(Commands.literal("armor")
                        .executes(ctx -> report(ctx.getSource()))
                        .then(Commands.literal("on").executes(ctx -> {
                            DetailCull.armorTrimPx = DetailCull.ARMOR_TRIM_PX;
                            DetailCull.armorStiffPx = DetailCull.ARMOR_STIFF_PX;
                            DetailCull.armorBoxPx = DetailCull.ARMOR_BOX_PX;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            // Full armor at every distance, to compare against.
                            DetailCull.armorTrimPx = 0f;
                            DetailCull.armorStiffPx = 0f;
                            DetailCull.armorBoxPx = 0f;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("box")
                                .then(Commands.argument("px", FloatArgumentType.floatArg(0, 4096)).executes(ctx -> {
                                    // Villager height in pixels below which limb armor is one plain box that still bends.
                                    DetailCull.armorBoxPx = FloatArgumentType.getFloat(ctx, "px");
                                    return report(ctx.getSource());
                                })))
                        .then(Commands.argument("trims", FloatArgumentType.floatArg(0, 64))
                                .then(Commands.argument("stiff", FloatArgumentType.floatArg(0, 4096)).executes(ctx -> {
                                    // Screen pixels per model pixel for trims; villager height in pixels for stiff.
                                    DetailCull.armorTrimPx = FloatArgumentType.getFloat(ctx, "trims");
                                    DetailCull.armorStiffPx = FloatArgumentType.getFloat(ctx, "stiff");
                                    return report(ctx.getSource());
                                })))
                        .then(Commands.literal("far")
                                .then(Commands.literal("on").executes(ctx -> {
                                    DetailCull.armorFarModel = true;
                                    return report(ctx.getSource());
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    // Stiff armor keeps every panel, to compare against.
                                    DetailCull.armorFarModel = false;
                                    return report(ctx.getSource());
                                }))))
                .then(Commands.literal("budget")
                        .executes(ctx -> report(ctx.getSource()))
                        .then(Commands.literal("on").executes(ctx -> {
                            DetailCull.budgetFull = DetailCull.BUDGET_FULL;
                            DetailCull.budgetMid = DetailCull.BUDGET_MID;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            // Size on screen alone decides, as before the budget.
                            DetailCull.budgetFull = 0;
                            DetailCull.budgetMid = 0;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.argument("full", IntegerArgumentType.integer(0, 4096))
                                .then(Commands.argument("mid", IntegerArgumentType.integer(0, 4096)).executes(ctx -> {
                                    // Ranks on screen: full detail up to <full>, trims off and 6 strips per limb half up to <mid>.
                                    DetailCull.budgetFull = IntegerArgumentType.getInteger(ctx, "full");
                                    DetailCull.budgetMid = IntegerArgumentType.getInteger(ctx, "mid");
                                    return report(ctx.getSource());
                                }))))
                .then(Commands.literal("crowd")
                        .then(Commands.literal("on").executes(ctx -> {
                            CrowdBatching.enabled = true;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            // Back to Minecraft's own batching, to compare against.
                            CrowdBatching.enabled = false;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("all")
                                .then(Commands.literal("on").executes(ctx -> {
                                    CrowdBatching.allMobs = true;
                                    return report(ctx.getSource());
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    // Villagers only, as before plan P2.
                                    CrowdBatching.allMobs = false;
                                    return report(ctx.getSource());
                                }))))
                .then(Commands.literal("writer")
                        .then(Commands.literal("new").executes(ctx -> {
                            CubeWriter.fast = true;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("old").executes(ctx -> {
                            // GeckoLib's own cube drawing and bone walk, to compare.
                            CubeWriter.fast = false;
                            return report(ctx.getSource());
                        })))
                .then(Commands.literal("flat")
                        .then(Commands.literal("on").executes(ctx -> {
                            // Plan W1: faces with no width and bones squashed to nothing left out.
                            CubeWriter.skipFlat = true;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            CubeWriter.skipFlat = false;
                            return report(ctx.getSource());
                        })))
                .then(Commands.literal("limbmath")
                        .then(Commands.literal("new").executes(ctx -> {
                            // Plan W3: the same limb shape with less maths.
                            BentLimb.fastMaths = true;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("old").executes(ctx -> {
                            BentLimb.fastMaths = false;
                            return report(ctx.getSource());
                        })))
                .then(Commands.literal("poserate")
                        .then(Commands.literal("off").executes(ctx -> {
                            PoseRate.cap = 0;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.argument("perSecond", IntegerArgumentType.integer(20, 1000)).executes(ctx -> {
                            // Plan W8: on a fast screen a villager is posed at least this often, not every frame.
                            PoseRate.cap = IntegerArgumentType.getInteger(ctx, "perSecond");
                            return report(ctx.getSource());
                        })))
                .then(Commands.literal("empty")
                        .then(Commands.literal("on").executes(ctx -> {
                            // Plan W4: faces the skin paints nothing on are left out.
                            SkinFaces.skipEmpty = true;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            SkinFaces.skipEmpty = false;
                            return report(ctx.getSource());
                        })))
                .then(Commands.literal("faces")
                        .then(Commands.literal("all").executes(ctx -> {
                            // Plan W5: closed boxes' back faces left out on every villager.
                            SkinFaces.backFaces = SkinFaces.BackFaces.ALL;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("standin").executes(ctx -> {
                            SkinFaces.backFaces = SkinFaces.BackFaces.STANDIN;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            SkinFaces.backFaces = SkinFaces.BackFaces.OFF;
                            return report(ctx.getSource());
                        })))
                .then(Commands.literal("standin")
                        .then(Commands.literal("on").executes(ctx -> {
                            StandIn.enabled = true;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            // Far crowd villagers drawn like the rest, to compare.
                            StandIn.enabled = false;
                            StandIn.show = false;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("show").executes(ctx -> {
                            // The villager looked at gets a stand-in copy beside it; again to stop.
                            StandIn.show = !StandIn.show;
                            ctx.getSource().sendSuccess(() -> Component.literal(StandIn.show
                                    ? "Stand-in copy on: look at a villager (up to 16 blocks). The copy to its right is how "
                                            + "it looks as a crowd stand-in: plain helmet and chest boxes, a flat tool, back faces "
                                            + "left out. Run /villagerfps standin show again to stop."
                                    : "Stand-in copy off."), false);
                            return 1;
                        })))
                .then(Commands.literal("items")
                        .then(Commands.literal("ready").executes(ctx -> {
                            ReadyItem.enabled = true;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("minecraft").executes(ctx -> {
                            // Minecraft builds every held item afresh each frame, to compare.
                            ReadyItem.enabled = false;
                            return report(ctx.getSource());
                        })))
                .then(Commands.literal("anim")
                        .executes(ctx -> animReport(ctx.getSource()))
                        .then(Commands.literal("fast").executes(ctx -> {
                            // Our copies, whatever the fingerprint said.
                            FastPose.choose(FastPose.Mode.FULL);
                            return animReport(ctx.getSource());
                        }))
                        .then(Commands.literal("safe").executes(ctx -> {
                            // GeckoLib's own posing handed only the bones the clips move, to compare.
                            FastPose.choose(FastPose.Mode.SAFE);
                            return animReport(ctx.getSource());
                        }))
                        .then(Commands.literal("gecko").executes(ctx -> {
                            // GeckoLib's own posing, to compare.
                            FastPose.choose(FastPose.Mode.GECKO);
                            return animReport(ctx.getSource());
                        }))
                        .then(Commands.literal("auto").executes(ctx -> {
                            // The game picks again from the fingerprint and the self-check.
                            FastPose.choose(null);
                            return animReport(ctx.getSource());
                        }))
                        .then(Commands.literal("check")
                                .executes(ctx -> animCheck(ctx.getSource(), 10))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600))
                                        .executes(ctx -> animCheck(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "seconds")))))
                        .then(Commands.literal("fingerprint")
                                .executes(ctx -> {
                                    for (String line : FastPose.fingerprintReport())
                                        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                                    return 1;
                                })
                                .then(Commands.literal("write").executes(ctx -> {
                                    // The loaded GeckoLib's codes to a file in the game folder (for re-basing the copy).
                                    String done = FastPose.writeFingerprint(Minecraft.getInstance().gameDirectory.toPath());
                                    ctx.getSource().sendSuccess(() -> Component.literal(done), false);
                                    return 1;
                                }))))
                .then(Commands.literal("hide")
                        .then(Commands.literal("on").executes(ctx -> {
                            // The ceiling: no villager drawn at all (plan step 0e).
                            NpcGeoRenderer.hidden = true;
                            return report(ctx.getSource());
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            NpcGeoRenderer.hidden = false;
                            return report(ctx.getSource());
                        })))
                .then(Commands.literal("log")
                        .executes(ctx -> logReport(ctx.getSource()))
                        .then(Commands.literal("on")
                                .executes(ctx -> {
                                    ClientCostLog.start(5);
                                    return logReport(ctx.getSource());
                                })
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600)).executes(ctx -> {
                                    ClientCostLog.start(IntegerArgumentType.getInteger(ctx, "seconds"));
                                    return logReport(ctx.getSource());
                                })))
                        .then(Commands.literal("off").executes(ctx -> {
                            ClientCostLog.stop();
                            return logReport(ctx.getSource());
                        })));
        event.getDispatcher().register(root);
    }

    /** Whether the {@code [ClientCost]} log is on, and its last block of lines. */
    private static int logReport(CommandSourceStack source) {
        if (!ClientCostLog.on) {
            source.sendSuccess(() -> Component.literal("Cost log off. /villagerfps log on [seconds] writes "
                    + "[ClientCost] lines to logs/latest.log"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Cost log on: [ClientCost] lines in logs/latest.log every "
                + ClientCostLog.seconds() + " s" + (com.alex.test.performance.ServerCostLog.on()
                        ? ", and [ServerCost] lines from the built-in server" : "")), false);
        for (String line : ClientCostLog.lastLines())
            source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    /** {@code /villagerfps anim}: which posing runs, and the last same-pose check. */
    private static int animReport(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(FastPose.report()), false);
        source.sendSuccess(() -> Component.literal(FastPose.checkReport()), false);
        return 1;
    }

    /**
     * {@code /villagerfps anim check [seconds]}: every villager drawn works each short cut out
     * GeckoLib's way too and counts differences (should stay 0). Report again with
     * {@code /villagerfps anim} once it has run.
     */
    private static int animCheck(CommandSourceStack source, int seconds) {
        if (FastPose.mode() != FastPose.Mode.FULL) {
            source.sendSuccess(() -> Component.literal("Full fast posing is not running, nothing to check ("
                    + FastPose.report() + ")"), false);
            return 1;
        }
        FastPose.startCheck(seconds);
        source.sendSuccess(() -> Component.literal("Checking fast posing against GeckoLib's for " + seconds
                + " s on every villager drawn. Look at a few villagers walking and working, then run "
                + "/villagerfps anim to see the counts (0 different = the same pose)."), false);
        return 1;
    }

    private static int report(CommandSourceStack source) {
        if (NpcGeoRenderer.hidden)
            source.sendSuccess(() -> Component.literal("VILLAGERS HIDDEN (/villagerfps hide off): not drawn at all, "
                    + "to read the frame's cost without them"), false);
        source.sendSuccess(() -> Component.literal("Armor, cape and instruments: "
                + (afterBody ? "drawn after the body, one texture at a time (faster)"
                        : "drawn in the middle of the body (old way)")), false);
        source.sendSuccess(() -> Component.literal("Names shown within " + (int) nameDistance
                + " blocks (the villager under the crosshair always)" + (NameTagLimit.limit > 0
                        ? ", on the nearest " + NameTagLimit.limit + " at most (/villagerfps names count <n>, 0 = all)"
                        : ", no count limit")), false);
        source.sendSuccess(() -> Component.literal("Limbs by height on screen (strips across the bend for each half, twice"
                + " that a limb): 12 from "
                + (int) BentLimb.smoothPx + " px, 6 from " + (int) BentLimb.mediumPx
                + ", 3 from " + (int) BentLimb.simplePx + ", blocky below"), false);
        source.sendSuccess(() -> Component.literal("Left out when a villager is shorter on screen than: eyes and nose "
                + (int) DetailCull.facePx + " px, hats and sleeves " + (int) DetailCull.propsPx
                + ", held tools " + (int) DetailCull.itemsPx + " (capes and instruments go with the hats)"), false);
        source.sendSuccess(() -> Component.literal(DetailCull.armorTrimPx <= 0f && DetailCull.armorStiffPx <= 0f
                ? "Armor: full detail at every distance (/villagerfps armor on brings the size steps back)"
                : "Armor: trims and rivets left out once a model pixel is under " + DetailCull.armorTrimPx
                        + " px on screen (villager under " + (int) DetailCull.armorTrimHeightPx()
                        + " px tall); arm and leg pieces stiff when the villager is under "
                        + (int) DetailCull.armorStiffPx + " px tall"), false);
        source.sendSuccess(() -> Component.literal("Stiff armor drawn as " + (DetailCull.armorFarModel
                ? "one plain box per arm and leg piece (/villagerfps armor far off keeps every panel)"
                : "every panel (/villagerfps armor far on: one box per piece)")), false);
        source.sendSuccess(() -> Component.literal(DetailCull.armorFarModel && DetailCull.armorBoxPx > 0f
                ? "Arm and leg armor is one plain box that still bends below " + (int) DetailCull.armorBoxPx
                        + " px tall, and for the crowd budget's middle ranks (/villagerfps armor box <px>, 0 = off)"
                : "Bending arm and leg armor keeps every panel (/villagerfps armor box <px> to use plain boxes)"), false);
        source.sendSuccess(() -> Component.literal(DetailCull.budgetReport()), false);
        source.sendSuccess(() -> Component.literal("Crowd batching (one batch per texture for the whole crowd): "
                + CrowdBatching.state() + " (/villagerfps crowd on|off, crowd all on|off)"), false);
        source.sendSuccess(() -> Component.literal(CubeWriter.fast
                ? "Cubes and bone walk: our own, no new objects per cube or bone, hidden hats not walked (/villagerfps writer old for GeckoLib's)"
                : "Cubes and bone walk: GeckoLib's own (/villagerfps writer new for ours)"), false);
        source.sendSuccess(() -> Component.literal(CubeWriter.skipFlat
                ? "Faces with no width and bones squashed to nothing: left out" + (CubeWriter.fast ? "" : " (needs writer new)")
                        + " (/villagerfps flat off to draw them)"
                : "Faces with no width and bones squashed to nothing: drawn (/villagerfps flat on to leave them out)"), false);
        source.sendSuccess(() -> Component.literal(BentLimb.fastMaths
                ? "Bent limb maths: new, boxes measured once, the curve worked out only in the bend, one turn per band"
                        + " height for all four sides (/villagerfps limbmath old to compare)"
                : "Bent limb maths: old, every box measured and the whole curve worked out each frame"
                        + " (/villagerfps limbmath new)"), false);
        source.sendSuccess(() -> Component.literal(SkinFaces.skipEmpty
                ? "Faces the skin paints nothing on: left out" + (CubeWriter.fast ? "" : " (needs writer new)")
                        + " (/villagerfps empty off to draw them)"
                : "Faces the skin paints nothing on: drawn (/villagerfps empty on to leave them out)"), false);
        source.sendSuccess(() -> Component.literal(switch (SkinFaces.backFaces) {
            case ALL -> "Back faces: left out on every villager's closed boxes and gently bent closed limbs, and on"
                    + " far stand-ins" + (CubeWriter.fast ? "" : " (needs writer new)")
                    + " (/villagerfps faces standin for stand-ins only, faces off for none)";
            case STANDIN -> "Back faces: left out on far stand-ins only (/villagerfps faces all, faces off)";
            case OFF -> "Back faces: all drawn, stand-ins too (/villagerfps faces all, faces standin)";
        }), false);
        source.sendSuccess(() -> Component.literal("Skin pictures: " + SkinFaces.state()), false);
        source.sendSuccess(() -> Component.literal(StandIn.report()), false);
        source.sendSuccess(() -> Component.literal(PoseRate.report()), false);
        source.sendSuccess(() -> Component.literal(ReadyItem.report()), false);
        source.sendSuccess(() -> Component.literal(FastPose.report()), false);
        for (String line : VillagerFrameStats.report())
            source.sendSuccess(() -> Component.literal(line), false);
        // The game's own frame counter, so a before/after reads from the chat log.
        source.sendSuccess(() -> Component.literal("FPS now: " + Minecraft.getInstance().getFps()), false);
        return 1;
    }
}
