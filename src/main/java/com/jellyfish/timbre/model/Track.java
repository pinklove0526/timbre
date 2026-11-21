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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A named, ordered set of non-overlapping {@link Clip}s. Gaps between clips are silence.
 *
 * <p><b>Threading:</b> mutable and not thread-safe: mutate it ({@link #addClip}, {@link #replaceClips}) from
 * one thread only (the JavaFX application thread in the app). Use {@link #snapshot()} to hand the audio to
 * other threads: a snapshot is immutable and a later edit never changes it.
 */
public final class Track {

    private final String name;
    private final int sampleRate;
    private final int channelCount;
    private final List<Clip> clips = new ArrayList<>();

    /**
     * Creates an empty track.
     *
     * @param name         track name
     * @param sampleRate   sample rate in Hz, greater than zero
     * @param channelCount channels, at least one
     * @throws IllegalArgumentException if a number is out of range
     */
    public Track(String name, int sampleRate, int channelCount) {
        this.name = Objects.requireNonNull(name, "name");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0: " + sampleRate);
        }
        if (channelCount < 1) {
            throw new IllegalArgumentException("channelCount must be >= 1: " + channelCount);
        }
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
    }

    /**
     * Creates a track with one clip covering the whole source at timeline frame 0.
     *
     * @param name   track name
     * @param source the audio, which must not be empty
     * @return the new track
     * @throws IllegalArgumentException if the source is empty
     */
    public static Track of(String name, SampleSource source) {
        Objects.requireNonNull(source, "source");
        if (source.frameCount() == 0) {
            throw new IllegalArgumentException("cannot create a track from an empty source");
        }
        Track track = new Track(name, source.sampleRate(), source.channelCount());
        track.addClip(new Clip(source, 0, source.frameCount(), 0));
        return track;
    }

    /** @return the track name */
    public String name() {
        return name;
    }

    /** @return the sample rate in Hz */
    public int sampleRate() {
        return sampleRate;
    }

    /** @return the channel count */
    public int channelCount() {
        return channelCount;
    }

    /**
     * Adds a clip, keeping the clips sorted by timeline start.
     *
     * @param clip the clip to add
     * @throws IllegalArgumentException if the clip's source differs in sample rate or channel
     *                                  count, or the clip overlaps an existing clip
     */
    public void addClip(Clip clip) {
        Objects.requireNonNull(clip, "clip");
        checkCompatible(clip);
        int index = insertionIndex(clip.timelineStart());
        if (index > 0 && clips.get(index - 1).timelineEnd() > clip.timelineStart()) {
            throw new IllegalArgumentException("clip overlaps the previous clip");
        }
        if (index < clips.size() && clips.get(index).timelineStart() < clip.timelineEnd()) {
            throw new IllegalArgumentException("clip overlaps the next clip");
        }
        clips.add(index, clip);
    }

    private void checkCompatible(Clip clip) {
        SampleSource src = clip.source();
        if (src.sampleRate() != sampleRate) {
            throw new IllegalArgumentException("clip sample rate " + src.sampleRate()
                    + " differs from track sample rate " + sampleRate);
        }
        if (src.channelCount() != channelCount) {
            throw new IllegalArgumentException("clip has " + src.channelCount()
                    + " channels, track has " + channelCount);
        }
    }

    /**
     * Replaces all clips at once. The new list must be well-formed: sorted by timeline start, non-overlapping
     * (a clip may start exactly where the previous one ends), and every clip's source must have the track's
     * sample rate and channel count, exactly as {@link #addClip} checks. Frame counts above 0 and timeline
     * starts of at least 0 are guaranteed by {@link Clip} itself. If any check fails nothing changes.
     *
     * @param newClips the new clips (copied); may be empty
     * @throws NullPointerException     if the list or an element is null
     * @throws IllegalArgumentException if the list is not well-formed or a clip is incompatible; the track is
     *                                  left unchanged
     */
    public void replaceClips(List<Clip> newClips) {
        Objects.requireNonNull(newClips, "newClips");
        List<Clip> copy = new ArrayList<>(newClips.size());
        Clip previous = null;
        for (Clip c : newClips) {
            Objects.requireNonNull(c, "clip");
            checkCompatible(c);
            if (previous != null) {
                if (c.timelineStart() < previous.timelineStart()) {
                    throw new IllegalArgumentException("clips are not sorted by timeline start at index "
                            + copy.size());
                }
                if (c.timelineStart() < previous.timelineEnd()) {
                    throw new IllegalArgumentException("clip at index " + copy.size()
                            + " overlaps the previous clip");
                }
            }
            copy.add(c);
            previous = c;
        }
        clips.clear();
        clips.addAll(copy);
    }

    private int insertionIndex(long timelineStart) {
        int lo = 0;
        int hi = clips.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (clips.get(mid).timelineStart() < timelineStart) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** @return an unmodifiable copy of the clips, sorted by timeline start */
    public List<Clip> clips() {
        return Collections.unmodifiableList(new ArrayList<>(clips));
    }

    /** @return an immutable snapshot of the track's current audio */
    public TrackSnapshot snapshot() {
        return new TrackSnapshot(sampleRate, channelCount, clips.toArray(new Clip[0]));
    }
}
