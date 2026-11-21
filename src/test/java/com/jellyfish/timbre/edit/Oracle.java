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
package com.jellyfish.timbre.edit;

import static org.junit.jupiter.api.Assertions.fail;

import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.MemorySampleSource;
import java.util.List;

/**
 * The independent reference for the edit tests: a track is just its rendered samples, {@code float[channel][frame]}
 * over {@code [0, N)} with zeros in gaps, built brute force from the clip fields with {@code SampleSource.read}
 * (no {@link ClipOps}, no {@code MixPlan}, no {@code TrackSnapshot}). Every edit has an array version here
 * written with {@code System.arraycopy}. Arrays are compared as if padded with zeros to the same length, because
 * a trailing gap has no samples.
 */
final class Oracle {

    private Oracle() {
    }

    /**
     * A source whose samples are unique per source and index and asymmetric: channel 0 is
     * {@code id * 1000 + i + 1}, channel 1 is {@code -(id * 1000 + i + 1) - 0.5}. Never 0, so a block of zeros
     * means "no clip there".
     */
    static MemorySampleSource source(int id, int channels, int frames, int rate) {
        float[][] d = new float[channels][frames];
        for (int i = 0; i < frames; i++) {
            float v = id * 1000 + i + 1;
            d[0][i] = v;
            if (channels > 1) {
                d[1][i] = -v - 0.5f;
            }
        }
        return MemorySampleSource.copyOf(rate, d);
    }

    /** Renders clips (in any order) into a timeline that ends at the largest clip end. */
    static float[][] render(List<Clip> clips, int channels) {
        long end = 0;
        for (Clip c : clips) {
            end = Math.max(end, c.timelineStart() + c.frameCount());
        }
        float[][] out = new float[channels][(int) end];
        for (Clip c : clips) {
            int n = (int) c.frameCount();
            float[][] buf = new float[c.source().channelCount()][n];
            c.source().read(c.sourceStart(), n, buf, 0);
            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(buf[ch], 0, out[ch], (int) c.timelineStart(), n);
            }
        }
        return out;
    }

    static int length(float[][] a) {
        return a[0].length;
    }

    /** Delete with ripple: remove {@code [from, min(to, N))}. */
    static float[][] removeRange(float[][] a, long from, long to) {
        int n = length(a);
        int f = (int) Math.min(from, n);
        int t = (int) Math.min(to, n);
        float[][] out = new float[a.length][n - (t - f)];
        for (int ch = 0; ch < a.length; ch++) {
            System.arraycopy(a[ch], 0, out[ch], 0, f);
            System.arraycopy(a[ch], t, out[ch], f, n - t);
        }
        return out;
    }

    /** Silence: zero {@code [from, min(to, N))}. */
    static float[][] zeroRange(float[][] a, long from, long to) {
        int n = length(a);
        float[][] out = new float[a.length][];
        for (int ch = 0; ch < a.length; ch++) {
            out[ch] = a[ch].clone();
            for (int i = (int) Math.min(from, n); i < Math.min(to, n); i++) {
                out[ch][i] = 0f;
            }
        }
        return out;
    }

    /** Trim: keep {@code [from, min(to, N))}, starting at 0. */
    static float[][] keepRange(float[][] a, long from, long to) {
        int n = length(a);
        int f = (int) Math.min(from, n);
        int t = (int) Math.min(to, n);
        float[][] out = new float[a.length][t - f];
        for (int ch = 0; ch < a.length; ch++) {
            System.arraycopy(a[ch], f, out[ch], 0, t - f);
        }
        return out;
    }

    /** Copy: the block {@code [from, to)}, zeros where the track has no samples. */
    static float[][] block(float[][] a, long from, long to) {
        int n = length(a);
        float[][] out = new float[a.length][(int) (to - from)];
        for (int ch = 0; ch < a.length; ch++) {
            int f = (int) Math.min(from, n);
            int t = (int) Math.min(to, n);
            System.arraycopy(a[ch], f, out[ch], 0, t - f);
        }
        return out;
    }

    /** Paste: insert a block at {@code at}; beyond the end, zeros fill the gap first. */
    static float[][] insertBlock(float[][] a, long at, float[][] block) {
        int n = length(a);
        int len = block[0].length;
        int p = (int) at;
        int head = Math.min(p, n);
        float[][] out = new float[a.length][Math.max(n, p) + len];
        for (int ch = 0; ch < a.length; ch++) {
            System.arraycopy(a[ch], 0, out[ch], 0, head);
            System.arraycopy(block[ch], 0, out[ch], p, len);
            if (p < n) {
                System.arraycopy(a[ch], p, out[ch], p + len, n - p);
            }
        }
        return out;
    }

    /** True if the two renderings are equal when padded with zeros to the same length. */
    static boolean same(float[][] a, float[][] b) {
        if (a.length != b.length) {
            return false;
        }
        int n = Math.max(length(a), length(b));
        for (int ch = 0; ch < a.length; ch++) {
            for (int i = 0; i < n; i++) {
                float x = i < a[ch].length ? a[ch][i] : 0f;
                float y = i < b[ch].length ? b[ch][i] : 0f;
                if (Float.compare(x, y) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** True if the block holds any non-zero sample, which with these sources means at least one clip. */
    static boolean hasAudio(float[][] a) {
        for (float[] ch : a) {
            for (float v : ch) {
                if (v != 0f) {
                    return true;
                }
            }
        }
        return false;
    }

    static void assertSame(float[][] expected, float[][] actual, String message) {
        if (same(expected, actual)) {
            return;
        }
        int n = Math.max(length(expected), length(actual));
        for (int ch = 0; ch < expected.length; ch++) {
            for (int i = 0; i < n; i++) {
                float x = i < expected[ch].length ? expected[ch][i] : 0f;
                float y = i < actual[ch].length ? actual[ch][i] : 0f;
                if (Float.compare(x, y) != 0) {
                    fail(message + ": first difference at channel " + ch + " frame " + i + ": expected " + x
                            + " but was " + y + " (lengths " + length(expected) + " and " + length(actual) + ")");
                }
            }
        }
        fail(message + ": channel counts differ");
    }

    /** Mono convenience: the rendered channel 0 as a list of floats. */
    static float[] mono(List<Clip> clips) {
        return render(clips, 1)[0];
    }
}
