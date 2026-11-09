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
package com.jellyfish.timbre.audio.support;

import com.jellyfish.timbre.model.SampleSource;

/** A configurable {@link SampleSource} for tests; it never materialises its frames. */
public final class FakeSource implements SampleSource {

    /** Supplies the samples of a {@link FakeSource}. */
    @FunctionalInterface
    public interface Reader {
        /** Fills {@code dest}; may throw to simulate failures. */
        void read(long start, int frames, float[][] dest, int offset);
    }

    private final int rate;
    private final int channels;
    private final long frames;
    private final Reader reader;

    /** Creates a fake source. */
    public FakeSource(int rate, int channels, long frames, Reader reader) {
        this.rate = rate;
        this.channels = channels;
        this.frames = frames;
        this.reader = reader;
    }

    /** @return a source of silence of the given size */
    public static FakeSource silence(int rate, int channels, long frames) {
        return new FakeSource(rate, channels, frames, (s, n, d, o) -> {
            for (int c = 0; c < d.length; c++) {
                java.util.Arrays.fill(d[c], o, o + n, 0f);
            }
        });
    }

    @Override
    public int sampleRate() {
        return rate;
    }

    @Override
    public int channelCount() {
        return channels;
    }

    @Override
    public long frameCount() {
        return frames;
    }

    @Override
    public void read(long startFrame, int count, float[][] dest, int destOffset) {
        reader.read(startFrame, count, dest, destOffset);
    }
}
