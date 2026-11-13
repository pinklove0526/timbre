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

import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.SampleSource;
import com.jellyfish.timbre.model.Track;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What to play: an immutable list of sources at one sample rate. An empty plan is allowed and has
 * length 0.
 *
 * <p><b>Threading:</b> immutable; safe to share between threads. The sources must themselves be
 * thread-safe, as every {@link SampleSource} is.
 */
public final class MixPlan {

    private static final MixPlan EMPTY = new MixPlan(List.of(), 0, 0L);

    private final List<SampleSource> sources;
    private final int sampleRate;
    private final long length;

    private MixPlan(List<SampleSource> sources, int sampleRate, long length) {
        this.sources = sources;
        this.sampleRate = sampleRate;
        this.length = length;
    }

    /** @return the empty plan (no sources, length 0, sample rate 0) */
    public static MixPlan empty() {
        return EMPTY;
    }

    /**
     * Builds a plan from the current snapshot of every track of a project.
     *
     * @param project the project
     * @return the plan (empty if the project has no tracks)
     */
    public static MixPlan of(Project project) {
        Objects.requireNonNull(project, "project");
        List<SampleSource> list = new ArrayList<>();
        for (Track t : project.tracks()) {
            list.add(t.snapshot());
        }
        return of(list);
    }

    /**
     * Builds a plan from sources.
     *
     * @param sources the sources, copied
     * @return the plan
     * @throws IllegalArgumentException if the sources do not all have the same sample rate (the message
     *                                  names the rates) or one has no channels
     */
    public static MixPlan of(List<? extends SampleSource> sources) {
        Objects.requireNonNull(sources, "sources");
        if (sources.isEmpty()) {
            return EMPTY;
        }
        List<SampleSource> copy = new ArrayList<>(sources.size());
        int rate = 0;
        long len = 0;
        for (SampleSource s : sources) {
            Objects.requireNonNull(s, "source");
            if (s.channelCount() < 1) {
                throw new IllegalArgumentException("a source has no channels");
            }
            if (copy.isEmpty()) {
                rate = s.sampleRate();
            } else if (s.sampleRate() != rate) {
                throw new IllegalArgumentException("sample rates differ: " + rate + " Hz and " + s.sampleRate()
                        + " Hz");
            }
            len = Math.max(len, s.frameCount());
            copy.add(s);
        }
        return new MixPlan(List.copyOf(copy), rate, len);
    }

    /** @return the sources, unmodifiable */
    public List<SampleSource> sources() {
        return sources;
    }

    /** @return the common sample rate, or 0 for an empty plan */
    public int sampleRate() {
        return sampleRate;
    }

    /** @return the length in frames: the longest source, 0 for an empty plan */
    public long length() {
        return length;
    }

    /** @return true if there are no sources */
    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /** @return the largest channel count among the sources, 0 for an empty plan */
    int maxChannels() {
        int m = 0;
        for (SampleSource s : sources) {
            m = Math.max(m, s.channelCount());
        }
        return m;
    }
}
