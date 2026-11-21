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

import com.jellyfish.timbre.audio.support.FakeAudioSink;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Test support: a {@link PlaybackEngine} that plays through {@link FakeAudioSink} in manual-advance mode with
 * the engine clock tied to the fake device time (the clock constructor is package-private, hence this class
 * lives in the audio package). Nothing is ever played; every {@code play()} opens one new fake sink, so
 * {@link #sinkCount()} counts the runs.
 */
public final class FakeEngineRig {

    private final List<FakeAudioSink> sinks = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger finished = new AtomicInteger();
    private final PlaybackEngine engine;

    /** Creates the rig. */
    public FakeEngineRig() {
        engine = new PlaybackEngine(() -> {
            FakeAudioSink sink = new FakeAudioSink(FakeAudioSink.Pace.MANUAL, FakeAudioSink.Artifacts.NONE, 1.0,
                    EnumSet.of(FakeAudioSink.Quirk.DRAIN_RETURNS_WHEN_STOPPED));
            sinks.add(sink);
            return sink;
        }, () -> last().nanoTime());
        engine.addListener(new PlaybackListener() {
            @Override
            public void finished() {
                finished.incrementAndGet();
            }
        });
    }

    /** @return the engine under the rig */
    public PlaybackEngine engine() {
        return engine;
    }

    /** @return the number of fake sinks opened so far (one per started run) */
    public int sinkCount() {
        return sinks.size();
    }

    /** @return the sink of the latest run */
    public FakeAudioSink last() {
        synchronized (sinks) {
            return sinks.get(sinks.size() - 1);
        }
    }

    /** @return how many times {@code finished()} was reported (natural ends) */
    public int finishedCount() {
        return finished.get();
    }

    /**
     * Advances the latest fake device in steps until a condition holds.
     *
     * @param condition   the condition to reach
     * @param stepNanos   virtual device nanoseconds per step
     * @param timeoutMillis real-time limit
     * @return true if the condition was reached
     * @throws InterruptedException if interrupted
     */
    public boolean advanceUntil(BooleanSupplier condition, long stepNanos, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            last().advance(stepNanos);
            Thread.sleep(1); // lets the audio thread refill the fake device between steps
        }
        return true;
    }
}
