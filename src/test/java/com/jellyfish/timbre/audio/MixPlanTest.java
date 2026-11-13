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

import com.jellyfish.timbre.model.MemorySampleSource;
import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.Track;
import java.util.List;
import org.junit.jupiter.api.Test;

class MixPlanTest {

    private static MemorySampleSource src(int rate, int frames) {
        return MemorySampleSource.copyOf(rate, new float[][] {new float[frames]});
    }

    @Test
    void ofProjectTakesSnapshotsAndTheLongestLength() {
        Project p = new Project();
        p.addTrack(Track.of("a", src(8000, 10)));
        p.addTrack(Track.of("b", src(8000, 25)));
        MixPlan plan = MixPlan.of(p);
        assertEquals(2, plan.sources().size());
        assertEquals(25, plan.length());
        assertEquals(8000, plan.sampleRate());
        assertTrue(!plan.isEmpty());
    }

    @Test
    void emptyProjectGivesTheEmptyPlan() {
        MixPlan plan = MixPlan.of(new Project());
        assertTrue(plan.isEmpty());
        assertEquals(0, plan.length());
        assertEquals(0, plan.sampleRate());
    }

    @Test
    void sourcesAreCopiedAndUnmodifiable() {
        java.util.ArrayList<MemorySampleSource> list = new java.util.ArrayList<>(List.of(src(8000, 3)));
        MixPlan plan = MixPlan.of(list);
        list.clear();
        assertEquals(1, plan.sources().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.sources().clear());
    }
}
