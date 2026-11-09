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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/** Application name and version, loaded from the filtered {@code timbre.properties} resource. No JavaFX. */
public final class AppInfo {

    static final String RESOURCE = "/com/jellyfish/timbre/app/timbre.properties";

    private static volatile AppInfo instance;

    private final String name;
    private final String version;

    private AppInfo(String name, String version) {
        this.name = name;
        this.version = version;
    }

    /**
     * Returns the shared instance, loading it on first use.
     *
     * @return the application info
     * @throws IllegalStateException if the resource is missing or invalid
     */
    public static AppInfo get() {
        AppInfo local = instance;
        if (local == null) {
            synchronized (AppInfo.class) {
                local = instance;
                if (local == null) {
                    local = load(RESOURCE);
                    instance = local;
                }
            }
        }
        return local;
    }

    static AppInfo load(String resource) {
        Objects.requireNonNull(resource, "resource");
        Properties props = new Properties();
        try (InputStream in = AppInfo.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + resource);
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read resource: " + resource, e);
        }
        return new AppInfo(require(props, "app.name"), require(props, "app.version"));
    }

    private static String require(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Blank or missing property: " + key);
        }
        if (value.contains("${")) {
            throw new IllegalStateException("Unfiltered property " + key + "=" + value + " (resource filtering did not run)");
        }
        return value.trim();
    }

    /** @return the application name */
    public String name() {
        return name;
    }

    /** @return the application version */
    public String version() {
        return version;
    }

    /** @return name and version, e.g. {@code Timbre 0.1.0-SNAPSHOT} */
    public String displayName() {
        return name + " " + version;
    }
}
