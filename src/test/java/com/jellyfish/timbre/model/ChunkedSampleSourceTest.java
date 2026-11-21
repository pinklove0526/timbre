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
package com.jellyfish.timbre.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ChunkedSampleSource} against an independent oracle: plain arrays written by the test itself. The
 * oracle sample of frame f, channel c is {@code (f * 7 + c * 13 + 1) / 1_000_000f} (distinct per channel and
 * frame, so offsets and channel mix-ups show).
 */
@Timeout(30)
class ChunkedSampleSourceTest {

    private static final int RATE = 44100;

    private static float oracle(long f, int c) {
        return (f * 7 + c * 13 + 1) / 1_000_000f;
    }

    /** Appends {@code total} oracle frames in random block sizes from 0 to {@code maxBlock}. */
    private static ChunkedSampleSource.Builder fill(int channels, int chunk, int total, int maxBlock, long seed) {
        ChunkedSampleSource.Builder b = new ChunkedSampleSource.Builder(RATE, channels, chunk,
                ChunkedSampleSource.ChunkFactory.DEFAULT);
        Random rnd = new Random(seed);
        float[][] block = new float[channels][maxBlock];
        int done = 0;
        while (done < total) {
            int n = Math.min(total - done, rnd.nextInt(maxBlock + 1));
            for (int c = 0; c < channels; c++) {
                for (int i = 0; i < n; i++) {
                    block[c][i] = oracle(done + i, c);
                }
            }
            b.append(block, n);
            done += n;
            assertEquals(done, b.frameCount());
        }
        return b;
    }

    private static void assertRange(SampleSource s, long start, int frames) {
        int ch = s.channelCount();
        float[][] dest = new float[ch][frames + 3];
        for (float[] d : dest) {
            java.util.Arrays.fill(d, -9f);
        }
        s.read(start, frames, dest, 2);
        for (int c = 0; c < ch; c++) {
            assertEquals(-9f, dest[c][0], "untouched before the offset");
            assertEquals(-9f, dest[c][1], "untouched before the offset");
            for (int i = 0; i < frames; i++) {
                assertEquals(oracle(start + i, c), dest[c][2 + i],
                        "frame " + (start + i) + " channel " + c + " (read " + start + "+" + frames + ")");
            }
            assertEquals(-9f, dest[c][2 + frames], "untouched after the range");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 64, 65536, 200_000})
    void randomBlocksAndRandomReadsMatchTheOracle(int chunk) {
        int total = 150_001;
        ChunkedSampleSource s = fill(2, chunk, total, 3000, chunk).build();
        assertEquals(total, s.frameCount());
        assertEquals(2, s.channelCount());
        assertEquals(RATE, s.sampleRate());
        assertEquals(chunk, s.chunkFrames());
        Random rnd = new Random(42 + chunk);
        for (int k = 0; k < 200; k++) {
            int len = 1 + rnd.nextInt(5000);
            long start = rnd.nextInt(total - len + 1);
            assertRange(s, start, len);
        }
        assertRange(s, 0, 1);
        assertRange(s, 0, 4000);
        assertRange(s, total - 1, 1);
        assertRange(s, total - 4000, 4000);
        assertRange(s, 0, total);
    }

    @Test
    void readsCrossingSeveralChunkBoundariesAndExactBoundaries() {
        ChunkedSampleSource s = fill(3, 7, 100, 9, 5).build();
        assertRange(s, 5, 30);   // crosses 4 boundaries (7, 14, 21, 28, 35)
        assertRange(s, 6, 2);    // last frame of chunk 0 and first of chunk 1
        assertRange(s, 7, 7);    // exactly chunk 1
        assertRange(s, 13, 1);   // last frame of chunk 1
        assertRange(s, 14, 1);   // first frame of chunk 2
        assertRange(s, 98, 2);   // the partial last chunk (98, 99)
        assertRange(s, 0, 100);
    }

    @Test
    void atAndBeyondTheEndFollowsTheSampleSourceContract() {
        ChunkedSampleSource s = fill(1, 7, 20, 5, 1).build();
        float[][] dest = new float[1][10];
        s.read(20, 0, dest, 0); // 0 frames at the end is allowed
        assertThrows(IndexOutOfBoundsException.class, () -> s.read(20, 1, dest, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> s.read(19, 2, dest, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> s.read(-1, 1, dest, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> s.read(21, 0, dest, 0));
        assertThrows(IllegalArgumentException.class, () -> s.read(0, 5, dest, 6));
        assertThrows(IllegalArgumentException.class, () -> s.read(0, 1, new float[0][], 0));
    }

    @Test
    void emptySourceHasNoFramesAndOnlyAllowsAnEmptyReadAtZero() {
        ChunkedSampleSource s = new ChunkedSampleSource.Builder(48000, 2).build();
        assertEquals(0, s.frameCount());
        assertEquals(48000, s.sampleRate());
        assertEquals(ChunkedSampleSource.DEFAULT_CHUNK_FRAMES, s.chunkFrames());
        float[][] dest = new float[2][1];
        s.read(0, 0, dest, 0);
        assertThrows(IndexOutOfBoundsException.class, () -> s.read(0, 1, dest, 0));
    }

    @Test
    void builderIsUnusableAfterBuild() {
        ChunkedSampleSource.Builder b = fill(1, 4, 10, 3, 2);
        ChunkedSampleSource s = b.build();
        assertEquals(10, s.frameCount());
        float[][] block = new float[1][2];
        assertThrows(IllegalStateException.class, () -> b.append(block, 1));
        assertThrows(IllegalStateException.class, b::build);
        assertThrows(IllegalStateException.class, b::frameCount);
        assertRange(s, 0, 10); // the built source is unaffected
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkedSampleSource.Builder(0, 1, 4, ChunkedSampleSource.ChunkFactory.DEFAULT));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkedSampleSource.Builder(RATE, 0, 4, ChunkedSampleSource.ChunkFactory.DEFAULT));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkedSampleSource.Builder(RATE, 1, 0, ChunkedSampleSource.ChunkFactory.DEFAULT));
        assertThrows(NullPointerException.class, () -> new ChunkedSampleSource.Builder(RATE, 1, 4, null));
        ChunkedSampleSource.Builder b = new ChunkedSampleSource.Builder(RATE, 2, 4,
                ChunkedSampleSource.ChunkFactory.DEFAULT);
        assertThrows(IllegalArgumentException.class, () -> b.append(new float[1][8], 2));
        assertThrows(IllegalArgumentException.class, () -> b.append(new float[2][1], 2));
        assertThrows(IllegalArgumentException.class, () -> b.append(new float[2][4], -1));
        assertEquals(0, b.frameCount());
        ChunkedSampleSource.Builder bad = new ChunkedSampleSource.Builder(RATE, 2, 4, (f, c) -> new float[1][f]);
        assertThrows(IllegalStateException.class, () -> bad.append(new float[2][4], 1));
        assertEquals(0, bad.frameCount());
    }

    @Test
    void outOfMemoryFromTheFactoryPropagatesAndKeepsWhatWasStored() {
        int[] calls = {0};
        ChunkedSampleSource.ChunkFactory failsOnThird = (frames, channels) -> {
            calls[0]++;
            if (calls[0] >= 3) {
                throw new OutOfMemoryError("test: no third chunk");
            }
            return new float[channels][frames];
        };
        ChunkedSampleSource.Builder b = new ChunkedSampleSource.Builder(RATE, 2, 10, failsOnThird);
        float[][] block = new float[2][15];
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < 15; i++) {
                block[c][i] = oracle(i, c);
            }
        }
        b.append(block, 15); // chunks 1 and 2 (frames 0..14)
        float[][] next = new float[2][8];
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < 8; i++) {
                next[c][i] = oracle(15 + i, c);
            }
        }
        OutOfMemoryError e = assertThrows(OutOfMemoryError.class, () -> b.append(next, 8)); // needs chunk 3
        assertEquals("test: no third chunk", e.getMessage());
        assertEquals(15, b.frameCount(), "nothing of the failing block is stored");
        ChunkedSampleSource s = b.build();
        assertEquals(15, s.frameCount());
        assertRange(s, 0, 15);
        float[][] dest = new float[2][1];
        assertThrows(IndexOutOfBoundsException.class, () -> s.read(15, 1, dest, 0));
    }

    @Test
    void outOfMemoryPartWayThroughAMultiChunkAppendRollsBackTheWholeBlock() {
        int[] calls = {0};
        ChunkedSampleSource.ChunkFactory failsOnFourth = (frames, channels) -> {
            calls[0]++;
            if (calls[0] == 4) {
                throw new OutOfMemoryError("fourth");
            }
            return new float[channels][frames];
        };
        ChunkedSampleSource.Builder b = new ChunkedSampleSource.Builder(RATE, 1, 4, failsOnFourth);
        float[][] block = new float[1][20];
        for (int i = 0; i < 20; i++) {
            block[0][i] = oracle(i, 0);
        }
        b.append(block, 3); // chunk 1
        assertThrows(OutOfMemoryError.class, () -> b.append(block, 13)); // chunks 2, 3, then 4 fails
        assertEquals(3, b.frameCount());
        // the factory works again: the next append continues at frame 3 with fresh chunks
        float[][] rest = new float[1][5];
        for (int i = 0; i < 5; i++) {
            rest[0][i] = oracle(3 + i, 0);
        }
        b.append(rest, 5);
        ChunkedSampleSource s = b.build();
        assertEquals(8, s.frameCount());
        assertRange(s, 0, 8);
    }

    @Test
    void concurrentReadsOnABuiltSourceAreConsistent() throws Exception {
        int total = 300_000;
        ChunkedSampleSource s = fill(2, 1000, total, 4096, 9).build();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                long seed = t;
                results.add(pool.submit(() -> {
                    Random rnd = new Random(seed);
                    float[][] dest = new float[2][2048];
                    int checked = 0;
                    for (int k = 0; k < 500; k++) {
                        int len = 1 + rnd.nextInt(2048);
                        long start = rnd.nextInt(total - len + 1);
                        s.read(start, len, dest, 0);
                        for (int c = 0; c < 2; c++) {
                            for (int i = 0; i < len; i += 97) {
                                if (dest[c][i] != oracle(start + i, c)) {
                                    throw new AssertionError("frame " + (start + i) + " channel " + c);
                                }
                                checked++;
                            }
                        }
                    }
                    return checked;
                }));
            }
            for (Future<Integer> f : results) {
                try {
                    assertTrue(f.get(20, TimeUnit.SECONDS) > 0);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof AssertionError) {
                        throw (AssertionError) e.getCause(); // report a wrong sample as a failure, not an error
                    }
                    throw e;
                }
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void appendCopiesTheSamplesSoLaterChangesToTheBlockDoNotLeak() {
        ChunkedSampleSource.Builder b = new ChunkedSampleSource.Builder(RATE, 1, 4,
                ChunkedSampleSource.ChunkFactory.DEFAULT);
        float[][] block = {{0.25f, -0.5f, 0.75f}};
        b.append(block, 3);
        block[0][0] = 9f;
        ChunkedSampleSource s = b.build();
        float[][] dest = new float[1][3];
        s.read(0, 3, dest, 0);
        assertArrayEquals(new float[] {0.25f, -0.5f, 0.75f}, dest[0]);
    }
}
