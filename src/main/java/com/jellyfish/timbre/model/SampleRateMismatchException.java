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

/** Thrown when a track's sample rate differs from the project's fixed sample rate. */
public final class SampleRateMismatchException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param projectRate the project's sample rate in Hz
     * @param trackRate   the rejected track's sample rate in Hz
     */
    public SampleRateMismatchException(int projectRate, int trackRate) {
        super("Track sample rate " + trackRate + " Hz differs from project sample rate "
                + projectRate + " Hz");
    }
}
