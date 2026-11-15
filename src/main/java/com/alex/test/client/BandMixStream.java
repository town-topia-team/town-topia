package com.alex.test.client;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatConsumer;
import net.minecraft.client.sounds.FloatSampleSource;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;

/**
 * The band's four recordings decoded side by side and added together sample by sample,
 * each at its own gain: one stream, so the parts share one clock and stay exactly
 * together whatever the game's frame rate or tick lag. A part nobody plays has gain 0.
 * Gain changes glide over 20 ms so a part coming in or out does not click.
 *
 * <p>All four files are the same length, rate and channel count (48.0 s, 44,100 Hz,
 * mono), so sample n of each is the same moment of the song.
 */
final class BandMixStream implements FloatSampleSource {
    private static final int CHUNK = 2048;
    private final FloatSampleSource[] parts;
    private final FloatArrayList[] queued;
    private final int[] readAt;
    private final boolean[] ended;
    private final float[] current;
    /** Replaced whole from the game thread, read on the sound thread. */
    private volatile float[] target;
    private final float rampStep;

    BandMixStream(FloatSampleSource[] parts, float[] gains) {
        this.parts = parts;
        this.queued = new FloatArrayList[parts.length];
        this.readAt = new int[parts.length];
        this.ended = new boolean[parts.length];
        for (int i = 0; i < parts.length; i++) queued[i] = new FloatArrayList(CHUNK * 4);
        this.current = gains.clone();
        this.target = gains.clone();
        this.rampStep = 1f / Math.max(1f, getFormat().getSampleRate() * 0.02f);
    }

    void setGains(float[] gains) {
        target = gains.clone();
    }

    @Override
    public AudioFormat getFormat() {
        return parts[0].getFormat();
    }

    /** Fills every part's queue to at least {@code wanted} samples, or to its end. */
    private void fill(int wanted) throws IOException {
        for (int i = 0; i < parts.length; i++) {
            FloatArrayList q = queued[i];
            if (readAt[i] > 0 && readAt[i] >= q.size() / 2) {
                q.removeElements(0, readAt[i]);
                readAt[i] = 0;
            }
            FloatConsumer into = q::add;
            while (!ended[i] && q.size() - readAt[i] < wanted) {
                if (!parts[i].readChunk(into)) ended[i] = true;
            }
        }
    }

    /** How many samples can be mixed now: a full chunk while any part still plays, else what is left. */
    private int available(int wanted) {
        int most = 0;
        boolean anyLive = false;
        for (int i = 0; i < parts.length; i++) {
            int left = queued[i].size() - readAt[i];
            if (!ended[i]) anyLive = true;
            most = Math.max(most, left);
        }
        return anyLive ? wanted : Math.min(most, wanted);
    }

    @Override
    public boolean readChunk(FloatConsumer output) throws IOException {
        fill(CHUNK);
        int n = available(CHUNK);
        if (n <= 0) return false;
        float[] goal = target;
        for (int s = 0; s < n; s++) {
            float sum = 0f;
            for (int i = 0; i < parts.length; i++) {
                float g = current[i];
                float to = goal[i];
                if (g != to) {
                    g = g < to ? Math.min(to, g + rampStep) : Math.max(to, g - rampStep);
                    current[i] = g;
                }
                int at = readAt[i] + s;
                if (g != 0f && at < queued[i].size()) sum += queued[i].getFloat(at) * g;
            }
            output.accept(Math.max(-1f, Math.min(1f, sum)));
        }
        for (int i = 0; i < parts.length; i++) {
            readAt[i] = Math.min(queued[i].size(), readAt[i] + n);
        }
        return true;
    }

    /** Throws away exactly {@code samples} samples of every part, keeping them together. */
    void skip(long samples) throws IOException {
        while (samples > 0) {
            int step = (int) Math.min(samples, CHUNK);
            fill(step);
            int n = available(step);
            if (n <= 0) return;
            for (int i = 0; i < parts.length; i++) {
                readAt[i] = Math.min(queued[i].size(), readAt[i] + n);
            }
            samples -= n;
        }
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (FloatSampleSource part : parts) {
            try {
                part.close();
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }
}
