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

import com.jellyfish.timbre.ui.viewmodel.RulerTicks;
import java.util.List;
import java.util.Objects;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.Font;

/**
 * The time ruler: a canvas of {@code laneWidth x 28} pixels that draws {@link RulerTicks} for the model's
 * axis. A tick is the 1 px column {@code min(floor(x), width - 1)}, 14 px (major) or 6 px (minor) tall from
 * the bottom edge; major ticks have their label to the right of the tick.
 *
 * <p><b>Threading:</b> JavaFX application thread only.
 */
public final class RulerView extends Canvas {

    private final EditorModel model;
    private List<RulerTicks.Tick> ticks = List.of();

    /**
     * Creates the ruler.
     *
     * @param model the editor model
     */
    public RulerView(EditorModel model) {
        super(model.laneWidth(), Theme.RULER_HEIGHT);
        this.model = Objects.requireNonNull(model, "model");
        setId("ruler");
    }

    /** @return the ticks drawn by the last repaint, with their labels (the labels are not asserted by pixel) */
    public List<RulerTicks.Tick> ticks() {
        return ticks;
    }

    /** Resizes the canvas to the model's lane width and redraws the ruler. */
    public void repaint() {
        int width = model.laneWidth();
        if (getWidth() != width) {
            setWidth(width);
        }
        double h = getHeight();
        GraphicsContext g = getGraphicsContext2D();
        g.setFill(Theme.RULER_BACKGROUND);
        g.fillRect(0, 0, width, h);
        ticks = RulerTicks.generate(model.axis(), width);
        g.setFill(Theme.RULER_INK);
        g.setFont(Font.font(11));
        for (RulerTicks.Tick t : ticks) {
            int column = (int) Math.min(Math.floor(t.x()), width - 1);
            int length = t.major() ? Theme.MAJOR_TICK_LENGTH : Theme.MINOR_TICK_LENGTH;
            g.fillRect(column, h - length, 1, length);
            if (t.major()) {
                g.fillText(t.label(), column + 3, 11);
            }
        }
    }
}
