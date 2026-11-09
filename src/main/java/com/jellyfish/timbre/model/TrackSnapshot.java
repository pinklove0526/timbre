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

import java.util.Arrays;

/**
 * An immutable rendering of a {@link Track} at one moment: clips are laid out on the
 * timeline and gaps read as silence.
 *
 * <p><b>Threading:</b> immutable and safe for concurrent reads; {@link #read} does not allocate.
 */
public final class TrackSnapshot implements SampleSource {

    private final int sampleRate;
    private final int channelCount;
    private final Clip[] clips;
    private final long frameCount;

    TrackSnapshot(int sampleRate, int channelCount, Clip[] clips) {
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        this.clips = clips;
        this.frameCount = clips.length == 0 ? 0 : clips[clips.length - 1].timelineEnd();
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int channelCount() {
        return channelCount;
    }

    /** @return the end of the last clip, or 0 if there are no clips */
    @Override
    public long frameCount() {
        return frameCount;
    }

    @Override
    public void read(long startFrame, int frames, float[][] dest, int destOffset) {
        ReadChecks.check(frameCount, channelCount, startFrame, frames, dest, destOffset);
        for (int c = 0; c < channelCount; c++) {
            Arrays.fill(dest[c], destOffset, destOffset + frames, 0f);
        }
        long end = startFrame + frames;
        // first clip whose end is after startFrame (clips are sorted and non-overlapping)
        int lo = 0;
        int hi = clips.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (clips[mid].timelineEnd() > startFrame) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        for (int i = lo; i < clips.length; i++) {
            Clip clip = clips[i];
            if (clip.timelineStart() >= end) {
                break;
            }
            long from = Math.max(startFrame, clip.timelineStart());
            long to = Math.min(end, clip.timelineEnd());
            clip.source().read(clip.sourceStart() + (from - clip.timelineStart()), (int) (to - from),
                    dest, destOffset + (int) (from - startFrame));
        }
    }
}
