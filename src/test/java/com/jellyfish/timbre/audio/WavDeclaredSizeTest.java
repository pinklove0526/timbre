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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jellyfish.timbre.audio.support.RiffBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A data chunk that declares an enormous size but holds only a few bytes must neither
 * allocate for the declared size nor lose the frames that are present, through both
 * public entry points: {@link WavReader#read(Path)} and {@link WavReader#read(java.io.InputStream)}.
 */
class WavDeclaredSizeTest {

    /** Far below what a declared-size allocation would need (0x7FFFFFFF bytes), far above the reader's real use. */
    private static final long ALLOCATION_BUDGET_BYTES = 4L << 20;

    @TempDir
    Path dir;

    private static byte[] hugeDeclaringFile(long declared) {
        // 5 stereo 16-bit frames plus one stray byte
        byte[] payload = new byte[5 * 4 + 1];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i + 1);
        }
        return RiffBuilder.wave().fmt(1, 2, 8000, 16).dataDeclaring(declared, payload).build();
    }

    private static com.sun.management.ThreadMXBean counters() {
        java.lang.management.ThreadMXBean b = ManagementFactory.getThreadMXBean();
        assumeTrue(b instanceof com.sun.management.ThreadMXBean, "no allocation counters on this JVM");
        com.sun.management.ThreadMXBean sun = (com.sun.management.ThreadMXBean) b;
        assumeTrue(sun.isThreadAllocatedMemorySupported(), "thread allocation counters unsupported");
        sun.setThreadAllocatedMemoryEnabled(true);
        return sun;
    }

    @FunctionalInterface
    private interface Call {
        WavFile run() throws IOException;
    }

    private static void assertBoundedAndComplete(String what, Call call) throws IOException {
        com.sun.management.ThreadMXBean bean = counters();
        long tid = Thread.currentThread().getId();
        call.run(); // warm-up: class loading and first-use allocations are not what is being measured
        long before = bean.getThreadAllocatedBytes(tid);
        WavFile f = call.run();
        long allocated = bean.getThreadAllocatedBytes(tid) - before;
        System.out.println("DECLARED-SIZE " + what + ": allocated " + allocated + " bytes");
        assertEquals(5, f.source().frameCount(), what + " frames present");
        assertEquals(2, f.source().channelCount(), what + " channels");
        assertTrue(allocated < ALLOCATION_BUDGET_BYTES,
                what + " allocated " + allocated + " bytes (budget " + ALLOCATION_BUDGET_BYTES + ")");
    }

    @ParameterizedTest
    @ValueSource(longs = {0x7FFFFFFFL, 0xFFFFFFFFL})
    void readPathWithHugeDeclaredSizeAllocatesLittleAndKeepsPresentFrames(long declared) throws IOException {
        Path p = dir.resolve("huge-" + declared + ".wav");
        Files.write(p, hugeDeclaringFile(declared));
        assertBoundedAndComplete("read(Path) declared=" + declared, () -> WavReader.read(p));
    }

    @ParameterizedTest
    @ValueSource(longs = {0x7FFFFFFFL, 0xFFFFFFFFL})
    void readStreamWithHugeDeclaredSizeAllocatesLittleAndKeepsPresentFrames(long declared) throws IOException {
        byte[] file = hugeDeclaringFile(declared);
        assertBoundedAndComplete("read(InputStream) declared=" + declared,
                () -> WavReader.read(new ByteArrayInputStream(file)));
    }
}
