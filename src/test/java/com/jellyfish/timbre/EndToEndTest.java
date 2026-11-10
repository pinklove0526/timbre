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
package com.jellyfish.timbre;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jellyfish.timbre.audio.WavEncoding;
import com.jellyfish.timbre.audio.WavFile;
import com.jellyfish.timbre.audio.WavReader;
import com.jellyfish.timbre.audio.WavWriter;
import com.jellyfish.timbre.audio.support.TestWav;
import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.Track;
import com.jellyfish.timbre.model.TrackSnapshot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EndToEndTest {

    @TempDir
    Path dir;

    @Test
    void openSplitSaveAndReopenGivesIdenticalSamples() throws IOException {
        TestWav w = TestWav.build(1, 24, 2, 1001, 48000, 11);
        WavFile opened = WavReader.read(new ByteArrayInputStream(w.file()));

        Track whole = Track.of("take 1", opened.source());
        Project project = new Project();
        Clip only = whole.clips().get(0);
        Clip.Split halves = only.split(only.timelineStart() + only.frameCount() / 2);
        Track split = new Track("take 1", opened.source().sampleRate(), opened.source().channelCount());
        split.addClip(halves.left());
        split.addClip(halves.right());
        project.addTrack(split);
        assertEquals(48000, project.sampleRate().getAsInt());
        assertEquals(2, split.clips().size());

        TrackSnapshot snapshot = split.snapshot();
        Path out = dir.resolve("saved.wav");
        WavWriter.write(out, snapshot, opened.encoding());
        WavFile reread = WavReader.read(out);

        assertEquals(WavEncoding.PCM_24, reread.encoding());
        assertEquals(1001, reread.source().frameCount());
        float[][] got = new float[2][1001];
        reread.source().read(0, 1001, got, 0);
        assertArrayEquals(w.expected(), got);
    }
}
