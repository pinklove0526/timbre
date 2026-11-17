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
package com.jellyfish.timbre.ui.viewmodel;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jellyfish.timbre.dsp.PeakPyramid;
import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.MemorySampleSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Opt-in timing report ({@code -Dtimbre.bench=true}): builds a pyramid for a generated 10-minute stereo
 * 44.1 kHz source and times {@link LaneSampler} for 1500 columns and 2 lanes at three zoom levels. The only
 * assertion is the 9-block invariant; the numbers go to the console and to {@code build-logs/m3a/bench.txt}
 * when that directory exists.
 */
@Timeout(600)
class ViewModelBenchmarkTest {

    private static final int RATE = 44100;
    private static final int FRAMES = 600 * RATE;
    private static final int WIDTH = 1500;

    private final StringBuilder report = new StringBuilder();

    private void log(String line) {
        System.out.println("BENCH " + line);
        report.append(line).append('\n');
    }

    @Test
    void timings() throws IOException {
        assumeTrue("true".equals(System.getProperty("timbre.bench")), "benchmark is opt-in: -Dtimbre.bench=true");
        Random r = new Random(1);
        float[][] data = new float[2][FRAMES];
        for (int c = 0; c < 2; c++) {
            double phase = 0;
            for (int i = 0; i < FRAMES; i++) {
                phase += 2 * Math.PI * (110 + 90 * Math.sin(i * 1e-6)) / RATE;
                data[c][i] = (float) (0.5 * Math.sin(phase) * (0.6 + 0.4 * Math.sin(i * 3e-5))
                        + 0.05 * (r.nextDouble() - 0.5));
            }
        }
        MemorySampleSource source = MemorySampleSource.adopt(RATE, data);
        log("source: 10 min stereo " + RATE + " Hz = " + FRAMES + " frames");

        long[] build = new long[3];
        PeakPyramid pyramid = null;
        for (int i = 0; i < build.length; i++) {
            long t0 = System.nanoTime();
            pyramid = PeakPyramid.build(source);
            build[i] = System.nanoTime() - t0;
        }
        log(String.format("PeakPyramid.build: first %.0f ms, then %.0f ms, %.0f ms (levels=%d, level-0 blocks=%d)",
                build[0] / 1e6, build[1] / 1e6, build[2] / 1e6, pyramid.levelCount(), pyramid.blockCount(0)));

        PeakPyramid ready = pyramid;
        List<Clip> clips = List.of(new Clip(source, 0, FRAMES, 0));
        LaneSampler sampler = new LaneSampler();
        double[] zooms = {17640, 300, 0.5};
        String[] names = {"PEAKS 17640 frames/px", "DIRECT 300 frames/px", "SAMPLE_LINES 0.5 frames/px"};
        for (int z = 0; z < zooms.length; z++) {
            TimeAxis axis = new TimeAxis(RATE, zooms[z], zooms[z] >= 1000 ? 0 : 5_000_000);
            int worstBlocks = 0;
            int runs = 30;
            double[] ms = new double[runs];
            LaneColumns[] lanes = {new LaneColumns(), new LaneColumns()};
            for (int i = 0; i < runs; i++) {
                long t0 = System.nanoTime();
                for (int lane = 0; lane < 2; lane++) {
                    sampler.sample(clips, lane, s -> ready, axis, WIDTH, lanes[lane]);
                    worstBlocks = Math.max(worstBlocks, lanes[lane].maxBlocksPerColumn());
                }
                ms[i] = (System.nanoTime() - t0) / 1e6;
            }
            double[] sorted = ms.clone();
            Arrays.sort(sorted);
            log(String.format("LaneSampler %s, %d columns x 2 lanes: first %.2f ms, median %.2f ms, best %.2f ms "
                    + "(mode %s, most blocks per column %d)", names[z], WIDTH, ms[0], sorted[runs / 2], sorted[0],
                    lanes[0].mode(), worstBlocks));
            if (lanes[0].mode() == LaneColumns.Mode.PEAKS) {
                assertTrue(worstBlocks <= 9, "more than 9 blocks per column: " + worstBlocks);
            }
        }
        Path dir = Path.of("build-logs/m3a");
        if (Files.isDirectory(dir)) {
            Files.writeString(dir.resolve("bench.txt"), report.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }
}
