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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.audio.support.FakeCaptureSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The fake capture device reproduces the Phase A0 findings it claims (findings 7 and 8). */
@Timeout(30)
class FakeCaptureSourceTest {

    private static FakeCaptureSource running(int bufferFrames) throws AudioDeviceException {
        FakeCaptureSource f = new FakeCaptureSource();
        f.open(44100, 2, bufferFrames);
        f.start();
        return f;
    }

    @Test
    void aFullBufferDropsFurtherBytesInsteadOfQueueingThem() throws Exception {
        FakeCaptureSource f = running(100);
        f.feedPattern(150);
        assertEquals(400, f.availableBytes());
        assertEquals(200, f.droppedFullBytes());
        byte[] b = new byte[4 * 200];
        assertEquals(400, f.read(b, 0, b.length), "a read returns what is there, up to the length");
        // frame 0 = (-32768, -32755) little-endian; frame 99 = (693 - 32768, 706 - 32768)
        assertEquals(0x00, b[0] & 0xFF);
        assertEquals(0x80, b[1] & 0xFF);
        int frame99left = (b[396] & 0xFF) | (b[397] << 8);
        assertEquals(-32075, frame99left);
        f.feedPattern(1);
        f.read(b, 0, 4);
        assertEquals(1050 - 32768, (b[0] & 0xFF) | (b[1] << 8), "frame 150 follows: the dropped frames are gone");
    }

    @Test
    void afterStopThePendingBytesAreReportedButUnreadable() throws Exception {
        FakeCaptureSource f = running(100);
        f.feedPattern(30);
        f.stop();
        assertEquals(120, f.availableBytes());
        assertEquals(0, f.read(new byte[400], 0, 400));
        f.feedPattern(10);
        assertEquals(40, f.droppedIdleBytes(), "a stopped device captures nothing");
        assertThrows(IllegalStateException.class, f::start, "single-use: no restart after stop");
        f.close();
        f.close();
        assertThrows(IllegalStateException.class, f::availableBytes);
        assertThrows(IllegalStateException.class, () -> f.read(new byte[4], 0, 4));
        assertThrows(IllegalStateException.class, () -> f.open(44100, 2, 100));
    }

    @Test
    void stopAndCloseWakeABlockedReadPromptlyWithZero() throws Exception {
        for (boolean useClose : new boolean[] {false, true}) {
            FakeCaptureSource f = running(100);
            CompletableFuture<Integer> read = CompletableFuture.supplyAsync(() -> f.read(new byte[400], 0, 400));
            assertTrue(awaitReadCalls(f, 1));
            long t0 = System.nanoTime();
            if (useClose) {
                f.close();
            } else {
                f.stop();
            }
            assertEquals(0, read.get(2, TimeUnit.SECONDS));
            assertTrue(System.nanoTime() - t0 < 1_000_000_000L);
        }
    }

    private static boolean awaitReadCalls(FakeCaptureSource f, int n) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (f.calls().stream().filter(c -> c == FakeCaptureSource.Call.READ).count() >= n) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    @Test
    void theGateHoldsAReadAfterItTookItsBytes() throws Exception {
        FakeCaptureSource f = running(100);
        f.holdReads();
        f.feedPattern(10);
        CompletableFuture<Integer> read = CompletableFuture.supplyAsync(() -> f.read(new byte[400], 0, 400));
        assertTrue(f.awaitHeld(5000));
        assertEquals(0, f.pendingBytes(), "the bytes were taken before the hold");
        f.feedPattern(150);
        assertEquals(400, f.pendingBytes());
        assertEquals(50 * 4, f.droppedFullBytes());
        f.releaseReads();
        assertEquals(40, read.get(2, TimeUnit.SECONDS));
    }

    @Test
    void switchesFailOpenAndFailReadsAfterNBytes() throws Exception {
        FakeCaptureSource broken = new FakeCaptureSource().failOpen("nope");
        assertEquals("nope", assertThrows(AudioDeviceException.class, () -> broken.open(44100, 1, 10)).getMessage());
        FakeCaptureSource f = new FakeCaptureSource().failAfterBytes(12, "gone");
        f.open(44100, 1, 100);
        f.start();
        f.feedPattern(20);
        byte[] b = new byte[40];
        assertEquals(12, f.read(b, 0, 40));
        assertEquals("gone", assertThrows(IllegalStateException.class, () -> f.read(b, 0, 40)).getMessage());
    }
}
