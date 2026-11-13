/*
 * Copyright 2026 Jellyfish Ng
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jellyfish.timbre.audio;

import com.jellyfish.timbre.model.SampleSource;
import java.util.List;
import java.util.Objects;

/**
 * Sums the sources of a {@link MixPlan} into a stereo pair at unity gain: a mono source goes to both
 * outputs at full level, a source with two or more channels contributes channels 1 and 2 only. Frames
 * at or beyond a source's end are silence.
 *
 * <p><b>Threading:</b> not thread-safe (it owns scratch buffers); use from one thread at a time.
 * {@link #mix} performs no allocation.
 */
public final class StereoMixer {

    private final SampleSource[] sources;
    private final int blockFrames;
    private final float[][] scratch;

    /**
     * Creates a mixer and allocates all scratch buffers.
     *
     * @param plan        what to mix
     * @param blockFrames the largest block that will be mixed
     */
    public StereoMixer(MixPlan plan, int blockFrames) {
        Objects.requireNonNull(plan, "plan");
        if (blockFrames <= 0) {
            throw new IllegalArgumentException("blockFrames must be positive: " + blockFrames);
        }
        List<SampleSource> list = plan.sources();
        this.sources = list.toArray(new SampleSource[0]);
        this.blockFrames = blockFrames;
        this.scratch = new float[plan.maxChannels()][blockFrames];
    }

    /**
     * Mixes {@code frames} frames starting at {@code startFrame} into {@code left} and {@code right}
     * (positions 0 to {@code frames - 1}); the rest of the arrays is untouched.
     *
     * @param startFrame first project frame, at least 0
     * @param frames     number of frames, 0 to the block size given at construction
     * @param left       output, at least {@code frames} long
     * @param right      output, at least {@code frames} long
     * @throws IllegalArgumentException if an argument is out of range
     */
    public void mix(long startFrame, int frames, float[] left, float[] right) {
        if (startFrame < 0) {
            throw new IllegalArgumentException("startFrame must not be negative: " + startFrame);
        }
        if (frames < 0 || frames > blockFrames) {
            throw new IllegalArgumentException("frames must be in [0, " + blockFrames + "]: " + frames);
        }
        if (left.length < frames || right.length < frames) {
            throw new IllegalArgumentException("output arrays shorter than " + frames);
        }
        for (int i = 0; i < frames; i++) {
            left[i] = 0f;
            right[i] = 0f;
        }
        for (SampleSource s : sources) {
            long available = s.frameCount() - startFrame;
            if (available <= 0) {
                continue;
            }
            int n = (int) Math.min(frames, available);
            s.read(startFrame, n, scratch, 0);
            float[] l = scratch[0];
            float[] r = s.channelCount() >= 2 ? scratch[1] : scratch[0];
            for (int i = 0; i < n; i++) {
                left[i] += l[i];
                right[i] += r[i];
            }
        }
    }
}
