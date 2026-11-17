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
package com.jellyfish.timbre.dsp;

import com.jellyfish.timbre.model.SampleSource;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Builds and remembers one {@link PeakPyramid} per source, off the calling thread.
 *
 * <p>Sources are keyed by identity with weak references: the cache never keeps a source alive, and the
 * entry of a collected source is dropped lazily. At most one build runs per source; all builds run one after
 * another on a single daemon thread named {@code timbre-peaks}, started on the first request.
 *
 * <p><b>Threading:</b> every method may be called from any thread. Callbacks given to
 * {@link #request(SampleSource, Runnable)} run on the {@code timbre-peaks} thread after the build, or, if the
 * pyramid is already built, on the calling thread before {@code request} returns. A callback must not block
 * for long (it delays later builds) and must not call {@link #close()}; an exception thrown by a callback
 * is swallowed. {@link #close()} may be called from any thread other than the build thread.
 */
public final class PeakPyramidCache implements AutoCloseable {

    private static final long JOIN_MILLIS = 5000;

    private final Object lock = new Object();
    private final Map<Key, Entry> entries = new HashMap<>();
    private final ReferenceQueue<SampleSource> collected = new ReferenceQueue<>();
    private final LinkedBlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private Thread thread; // guarded by lock
    private boolean closed; // guarded by lock
    private Throwable lastError; // guarded by lock

    /** Creates an empty cache. No thread is started until the first request. */
    public PeakPyramidCache() {
        // nothing to initialise
    }

    /**
     * Returns the pyramid of a source if it has been built.
     *
     * @param source the source
     * @return the pyramid, or empty if it is not built (yet) or the build failed
     * @throws NullPointerException if {@code source} is null
     */
    public Optional<PeakPyramid> getIfReady(SampleSource source) {
        Objects.requireNonNull(source, "source");
        synchronized (lock) {
            purgeLocked();
            Entry e = entries.get(new Key(source, null));
            return e == null ? Optional.empty() : Optional.ofNullable(e.pyramid);
        }
    }

    /**
     * Makes sure the pyramid of a source is built and calls back when it is ready. The build starts only if
     * none is built or running for this source. If the build fails the failure is recorded in
     * {@link #lastError()}, the callback is not invoked, and a later request starts a new attempt.
     *
     * @param source  the source
     * @param onReady called once when the pyramid is ready (see the class comment for the thread)
     * @throws NullPointerException  if an argument is null
     * @throws IllegalStateException if the cache is closed
     */
    public void request(SampleSource source, Runnable onReady) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(onReady, "onReady");
        boolean immediate = false;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("cache is closed");
            }
            purgeLocked();
            Key probe = new Key(source, null);
            Entry e = entries.get(probe);
            if (e == null) {
                e = new Entry();
                e.callbacks.add(onReady);
                Key key = new Key(source, collected);
                entries.put(key, e);
                startThreadLocked();
                queue.add(new Task(source, key, e));
            } else if (e.pyramid != null) {
                immediate = true;
            } else {
                e.callbacks.add(onReady);
            }
        }
        if (immediate) {
            runCallback(onReady);
        }
    }

    /** @return the most recent build failure, or empty if none has failed */
    public Optional<Throwable> lastError() {
        synchronized (lock) {
            return Optional.ofNullable(lastError);
        }
    }

    /**
     * Stops the build thread (an unfinished build is abandoned, queued builds are dropped without callbacks)
     * and refuses further requests. Idempotent.
     */
    @Override
    public void close() {
        Thread t;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            t = thread;
            queue.clear();
            entries.clear();
        }
        if (t != null) {
            t.interrupt();
            if (Thread.currentThread() != t) {
                try {
                    t.join(JOIN_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void startThreadLocked() {
        if (thread == null) {
            thread = new Thread(this::loop, "timbre-peaks");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void purgeLocked() {
        while (collected.poll() != null) {
            // a reference was cleared: drop every entry whose key is dead
            entries.keySet().removeIf(k -> k.get() == null);
        }
    }

    private void loop() {
        try {
            while (true) {
                Task task = queue.take();
                PeakPyramid built = null;
                Throwable failure = null;
                try {
                    built = PeakPyramid.build(task.source);
                } catch (Throwable t) { // NOPMD: recorded, never lost
                    failure = t;
                }
                task.source = null; // do not keep the source reachable through the queue
                List<Runnable> toRun;
                synchronized (lock) {
                    if (closed) {
                        return;
                    }
                    toRun = new ArrayList<>(task.entry.callbacks);
                    task.entry.callbacks.clear();
                    if (built != null) {
                        task.entry.pyramid = built;
                    } else {
                        lastError = failure;
                        entries.remove(task.key);
                        toRun.clear();
                    }
                }
                for (Runnable r : toRun) {
                    runCallback(r);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runCallback(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // a faulty callback must not stop the build thread
        }
    }

    /** Pyramid state of one source; guarded by the cache lock. */
    private static final class Entry {
        final List<Runnable> callbacks = new ArrayList<>();
        PeakPyramid pyramid;
    }

    private static final class Task {
        SampleSource source;
        final Key key;
        final Entry entry;

        Task(SampleSource source, Key key, Entry entry) {
            this.source = source;
            this.key = key;
            this.entry = entry;
        }
    }

    /** Identity-based weak key. A probe key (no queue) compares equal to a stored key of the same source. */
    private static final class Key extends WeakReference<SampleSource> {
        private final int hash;

        Key(SampleSource source, ReferenceQueue<SampleSource> queue) {
            super(source, queue);
            this.hash = System.identityHashCode(source);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other) || other.hash != hash) {
                return false;
            }
            SampleSource mine = get();
            return mine != null && mine == other.get();
        }
    }
}
