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

import com.jellyfish.timbre.model.Clip;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure edit operations on the clip list of ONE track. Every operation takes a well-formed list (sorted by
 * timeline start, non-overlapping) and returns a new well-formed list; the input is never modified. No
 * samples are copied: results only contain clips of the same sources, cut, moved or both. Zero-length
 * pieces are never produced.
 *
 * <p>Ranges are half-open {@code [from, to)} in timeline frames. A negative {@code from} is treated as 0;
 * a range with {@code to <= from} (after that) changes nothing. Where a clip is cut, the left piece keeps
 * its {@code sourceStart} and the right piece gets its {@code sourceStart} advanced by the frames cut off.
 *
 * <p><b>Threading:</b> stateless; safe to call from any thread.
 */
public final class ClipOps {

    private ClipOps() {
    }

    /**
     * Copies the part of the timeline in {@code [from, to)}.
     *
     * @param clips the track's clips
     * @param from  first frame
     * @param to    end frame (exclusive)
     * @return a fragment of length {@code to - from} (0 for an empty range) holding the pieces of the clips
     *         that intersect the range, re-based so the range starts at 0; gaps are preserved
     */
    public static Fragment copy(List<Clip> clips, long from, long to) {
        Objects.requireNonNull(clips, "clips");
        long f = Math.max(0, from);
        if (to <= f) {
            return new Fragment(0, List.of());
        }
        return new Fragment(to - f, piecesIn(clips, f, to, -f));
    }

    /**
     * Removes {@code [from, to)} and closes the gap: everything after the range moves left by
     * {@code to - from}.
     *
     * @param clips the track's clips
     * @param from  first frame
     * @param to    end frame (exclusive)
     * @return the new clip list
     */
    public static List<Clip> delete(List<Clip> clips, long from, long to) {
        return remove(clips, from, to, true);
    }

    /**
     * Removes {@code [from, to)} and leaves a gap (silence); nothing moves.
     *
     * @param clips the track's clips
     * @param from  first frame
     * @param to    end frame (exclusive)
     * @return the new clip list
     */
    public static List<Clip> silence(List<Clip> clips, long from, long to) {
        return remove(clips, from, to, false);
    }

    private static List<Clip> remove(List<Clip> clips, long from, long to, boolean ripple) {
        Objects.requireNonNull(clips, "clips");
        long f = Math.max(0, from);
        if (to <= f) {
            return List.copyOf(clips);
        }
        long shift = ripple ? to - f : 0;
        List<Clip> out = new ArrayList<>(clips.size() + 1);
        for (Clip c : clips) {
            long cs = c.timelineStart();
            long ce = c.timelineEnd();
            if (ce <= f) {
                out.add(c);
            } else if (cs >= to) {
                out.add(shift == 0 ? c : c.withTimelineStart(cs - shift));
            } else {
                if (cs < f) {
                    out.add(new Clip(c.source(), c.sourceStart(), f - cs, cs));
                }
                if (ce > to) {
                    out.add(new Clip(c.source(), c.sourceStart() + (to - cs), ce - to, to - shift));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Keeps only {@code [from, to)} and moves it to start at frame 0.
     *
     * @param clips the track's clips
     * @param from  first frame
     * @param to    end frame (exclusive)
     * @return the new clip list (unchanged for an empty range)
     */
    public static List<Clip> trim(List<Clip> clips, long from, long to) {
        Objects.requireNonNull(clips, "clips");
        long f = Math.max(0, from);
        if (to <= f) {
            return List.copyOf(clips);
        }
        return piecesIn(clips, f, to, -f);
    }

    /**
     * Inserts a fragment at a frame: a clip that straddles {@code at} is split, every clip that starts at or
     * after {@code at} moves right by {@code fragment.length()}, and the fragment's clips are added starting
     * at {@code at}. Inserting beyond the end of the content leaves a gap before the inserted clips.
     *
     * @param clips    the track's clips
     * @param at       the insert position, at least 0
     * @param fragment what to insert
     * @return the new clip list
     * @throws IllegalArgumentException if {@code at} is negative
     */
    public static List<Clip> insert(List<Clip> clips, long at, Fragment fragment) {
        Objects.requireNonNull(clips, "clips");
        Objects.requireNonNull(fragment, "fragment");
        if (at < 0) {
            throw new IllegalArgumentException("at must be >= 0: " + at);
        }
        long len = fragment.length();
        if (len == 0) {
            return List.copyOf(clips);
        }
        List<Clip> out = new ArrayList<>(clips.size() + fragment.clips().size() + 1);
        for (Clip c : clips) {
            long cs = c.timelineStart();
            long ce = c.timelineEnd();
            if (ce <= at) {
                out.add(c);
            } else if (cs >= at) {
                out.add(c.withTimelineStart(cs + len));
            } else {
                out.add(new Clip(c.source(), c.sourceStart(), at - cs, cs));
                out.add(new Clip(c.source(), c.sourceStart() + (at - cs), ce - at, at + len));
            }
        }
        for (Clip c : fragment.clips()) {
            out.add(c.withTimelineStart(c.timelineStart() + at));
        }
        out.sort(Comparator.comparingLong(Clip::timelineStart));
        return List.copyOf(out);
    }

    /**
     * Splits the clip that strictly contains {@code at} ({@code timelineStart < at < timelineEnd}) into two
     * touching clips; at a clip edge, in a gap or beyond the content nothing changes.
     *
     * @param clips the track's clips
     * @param at    the split position
     * @return the new clip list
     */
    public static List<Clip> split(List<Clip> clips, long at) {
        Objects.requireNonNull(clips, "clips");
        List<Clip> out = new ArrayList<>(clips.size() + 1);
        for (Clip c : clips) {
            if (c.timelineStart() < at && at < c.timelineEnd()) {
                Clip.Split s = c.split(at);
                out.add(s.left());
                out.add(s.right());
            } else {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Checks the clip list invariant.
     *
     * @param clips the clips
     * @return true if the clips are sorted by timeline start and no two overlap (touching is allowed)
     */
    public static boolean isWellFormed(List<Clip> clips) {
        Objects.requireNonNull(clips, "clips");
        Clip previous = null;
        for (Clip c : clips) {
            if (c == null || c.frameCount() <= 0) {
                return false;
            }
            if (previous != null && c.timelineStart() < previous.timelineEnd()) {
                return false;
            }
            previous = c;
        }
        return true;
    }

    /** The pieces of the clips inside {@code [from, to)}, moved by {@code offset}. */
    private static List<Clip> piecesIn(List<Clip> clips, long from, long to, long offset) {
        List<Clip> out = new ArrayList<>();
        for (Clip c : clips) {
            long s = Math.max(from, c.timelineStart());
            long e = Math.min(to, c.timelineEnd());
            if (s >= e) {
                continue;
            }
            out.add(new Clip(c.source(), c.sourceStart() + (s - c.timelineStart()), e - s, s + offset));
        }
        return List.copyOf(out);
    }
}
