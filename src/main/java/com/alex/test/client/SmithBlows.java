package com.alex.test.client;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client: the clink and a burst of sparks on the tick the smith's hammer lands on the bar while it draws one
 * out (smith_anvil_draw), timed from this client's own clip as the lumberjack's thunks are
 * (NpcEntity.tickChopBites). The server used to play the clinks on its own clock, a few ticks early (round 4
 * of docs/plans/2026-10-24-blacksmith-anvil-redo.md). The blows and where the hammer meets the bar come from
 * the sketch (tools/anvil, {@code __anvil.report("smith_anvil_draw")}): a re-bake that moves them changes both.
 * Each job starts the clip afresh (BlacksmithActivity: a new work sequence), so the count starts with it.
 * Off screen GeckoLib does not start the new clip, so the clinks keep the old one's beat until he is seen.
 */
public final class SmithBlows {

    /** The blows, in clip ticks: 0.60, 1.40, 2.20, 3.40, 4.20, 5.00 s. */
    private static final int[] HITS = {12, 28, 44, 68, 84, 100};
    /** Where the hammer's lowest corner meets the bar at each blow, px from his feet: right, up, ahead. */
    private static final float[][] AT = {
            {4.04f, 16.93f, 16.54f}, {4.26f, 16.92f, 17.10f}, {6.40f, 17.00f, 17.20f},
            {4.04f, 17.28f, 16.55f}, {4.26f, 17.25f, 17.13f}, {6.41f, 17.33f, 17.27f}};
    /** The server's old clink: ANVIL_PLACE, quiet and high. */
    private static final float VOLUME = 0.4f, PITCH = 1.35f;
    private static final int SPARKS = 10;
    /** As far as Minecraft draws particles from (LevelRenderer: 32 blocks). */
    private static final double SPARK_RANGE_SQR = 32 * 32;
    private static final ResourceLocation DOT = ResourceLocation.withDefaultNamespace("glow");

    /** Per smith drawing out a bar: blows heard, and the clip start they were counted from. */
    private static final Map<NpcEntity, int[]> HEARD = new WeakHashMap<>();

    private SmithBlows() {}

    /** Every client tick of every villager (NpcEntity.tick). */
    public static void tick(NpcEntity smith) {
        if (smith.getAnimation() != VillagerAnimationState.SMITH_ANVIL_DRAW || !smith.isAlive()) {
            if (!HEARD.isEmpty()) HEARD.remove(smith);
            return;
        }
        int start = smith.workClipStartTick();
        int landed = blowsBefore(smith.workClipTicks(0f) - VillagerAnimationState.WORK_BLEND_TICKS);
        int[] heard = HEARD.get(smith);
        // only the blow that has just landed: when he starts, a new clip starts or it jumps (seen again), catch up quietly
        boolean play = heard != null && heard[1] == start && landed == heard[0] + 1;
        if (heard == null) HEARD.put(smith, heard = new int[2]);
        heard[0] = landed;
        heard[1] = start;
        if (play) strike(smith, (landed - 1) % HITS.length);
    }

    /** Blows landed by this clip time (ticks after the blend in), counting every loop. */
    private static int blowsBefore(float clip) {
        if (clip < 0) return 0;
        int cycle = VillagerAnimationState.cycleTicks(VillagerAnimationState.SMITH_ANVIL_DRAW);
        int loops = (int) (clip / cycle);
        float inLoop = clip - loops * cycle;
        int n = loops * HITS.length;
        for (int hit : HITS) if (hit <= inLoop) n++;
        return n;
    }

    private static void strike(NpcEntity smith, int blow) {
        float[] at = AT[blow];
        double yaw = Math.toRadians(smith.yBodyRot);
        double right = at[0] / 16.0, ahead = at[2] / 16.0;
        double x = smith.getX() - Math.sin(yaw) * ahead - Math.cos(yaw) * right;
        double y = smith.getY() + at[1] / 16.0;
        double z = smith.getZ() + Math.cos(yaw) * ahead - Math.sin(yaw) * right;
        RandomSource random = smith.getRandom();
        smith.level().playLocalSound(x, y, z, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS,
                VOLUME, PITCH + (random.nextFloat() - 0.5f) * 0.1f, false);
        sparks(x, y, z, random);
    }

    /** A burst off the bar, as the sketch's: half yellow, half orange, fewer on Decreased, none on Minimal. */
    private static void sparks(double x, double y, double z, RandomSource random) {
        Minecraft mc = Minecraft.getInstance();
        ParticleStatus status = mc.options.particles().get();
        if (status == ParticleStatus.MINIMAL || mc.level == null
                || mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(x, y, z) > SPARK_RANGE_SQR) return;
        if (!(mc.getTextureManager().getTexture(TextureAtlas.LOCATION_PARTICLES) instanceof TextureAtlas atlas)) return;
        TextureAtlasSprite dot = atlas.getSprite(DOT);
        int count = status == ParticleStatus.DECREASED ? SPARKS / 3 : SPARKS;
        for (int i = 0; i < count; i++) {
            double turn = random.nextDouble() * Math.PI * 2, out = 0.03 + random.nextDouble() * 0.05;
            mc.particleEngine.add(new Spark(mc.level, x, y, z, Math.cos(turn) * out,
                    0.02 + random.nextDouble() * 0.07, Math.sin(turn) * out, dot, i % 2 == 0));
        }
    }

    /**
     * One spark: flies off the bar, falls (and stops on the anvil top), cools from yellow or orange toward red
     * and shrinks, gone within half a second. Always drawn at full light, like the bar.
     */
    private static final class Spark extends TextureSheetParticle {
        private final float green;

        Spark(ClientLevel level, double x, double y, double z, double dx, double dy, double dz,
              TextureAtlasSprite dot, boolean yellow) {
            super(level, x, y, z);
            setSprite(dot);
            xd = dx;
            yd = dy;
            zd = dz;
            gravity = 0.6f;
            friction = 0.94f;
            lifetime = 5 + random.nextInt(5);
            quadSize = 0.06f + random.nextFloat() * 0.03f;
            green = yellow ? 0.88f : 0.63f;
            setColor(1f, green, yellow ? 0.44f : 0.13f);
        }

        @Override
        public void tick() {
            super.tick();
            gCol = green * (1f - 0.5f * age / lifetime);
        }

        @Override
        public float getQuadSize(float partialTick) {
            return quadSize * (1f - 0.6f * Math.min(1f, (age + partialTick) / lifetime));
        }

        @Override
        protected int getLightColor(float partialTick) {
            return LightTexture.FULL_BRIGHT;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
        }
    }
}
