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
package com.jellyfish.timbre.app;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/** JavaFX application: an empty main window (frame for the future Audacity-style layout). */
public final class TimbreApp extends Application {

    private static final double WIDTH = 1100;
    private static final double HEIGHT = 700;
    private static final double MIN_WIDTH = 640;
    private static final double MIN_HEIGHT = 400;

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        stage.setScene(new Scene(root, WIDTH, HEIGHT));
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setTitle(AppInfo.get().name());
        stage.show();
    }
}
