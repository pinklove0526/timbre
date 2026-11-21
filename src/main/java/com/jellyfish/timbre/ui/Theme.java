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
package com.jellyfish.timbre.ui;

import javafx.scene.paint.Color;

/**
 * The colours and sizes of the editor display, in one place. The palette is Timbre's own: a light
 * background, light-blue peaks, darker-blue RMS, a grey centre line, an orange-red playhead and a
 * translucent blue selection.
 *
 * <p><b>Threading:</b> constants only; safe to use from any thread.
 */
public final class Theme {

    /** Lane background (#F7F8FA). */
    public static final Color LANE_BACKGROUND = Color.rgb(0xF7, 0xF8, 0xFA);
    /** The 1 px centre line of a lane (#C3C8D0). */
    public static final Color CENTER_LINE = Color.rgb(0xC3, 0xC8, 0xD0);
    /** The 1 px separator row below a lane (#8A929E). */
    public static final Color SEPARATOR = Color.rgb(0x8A, 0x92, 0x9E);
    /** Peak band (#8FB8F0). */
    public static final Color PEAK = Color.rgb(0x8F, 0xB8, 0xF0);
    /** RMS band (#2F62B5). */
    public static final Color RMS = Color.rgb(0x2F, 0x62, 0xB5);
    /** Sample polyline and dots when zoomed in to single samples (#1E3F7A). */
    public static final Color SAMPLE_LINE = Color.rgb(0x1E, 0x3F, 0x7A);
    /** Playhead and cursor line (#E4572E). */
    public static final Color PLAYHEAD = Color.rgb(0xE4, 0x57, 0x2E);
    /** Selection fill, translucent (#3A7BD5 at 30 % opacity). */
    public static final Color SELECTION_FILL = Color.rgb(0x3A, 0x7B, 0xD5, 0.30);
    /** Ruler background (#E9ECF1). */
    public static final Color RULER_BACKGROUND = Color.rgb(0xE9, 0xEC, 0xF1);
    /** Ruler ticks and labels (#38404B). */
    public static final Color RULER_INK = Color.rgb(0x38, 0x40, 0x4B);
    /** Track header background (#DDE2E9). */
    public static final Color TRACK_HEADER_BACKGROUND = Color.rgb(0xDD, 0xE2, 0xE9);
    /** Background of a selected track header, a darker blue-grey (#A9B8CC). */
    public static final Color TRACK_SELECTED_BACKGROUND = Color.rgb(0xA9, 0xB8, 0xCC);
    /** Clip border line in the lanes, a dark slate (#4A5563). */
    public static final Color CLIP_BORDER = Color.rgb(0x4A, 0x55, 0x63);

    /** Width of the track header in pixels. */
    public static final int HEADER_WIDTH = 120;
    /** Height of the ruler in pixels. */
    public static final int RULER_HEIGHT = 28;
    /** Length of a major ruler tick in pixels. */
    public static final int MAJOR_TICK_LENGTH = 14;
    /** Length of a minor ruler tick in pixels. */
    public static final int MINOR_TICK_LENGTH = 6;

    private Theme() {
    }
}
