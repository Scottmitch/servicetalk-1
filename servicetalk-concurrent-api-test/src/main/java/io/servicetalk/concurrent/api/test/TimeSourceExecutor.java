/*
 * Copyright © 2020 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.concurrent.api.test;

import io.servicetalk.concurrent.Cancellable;
import io.servicetalk.concurrent.Executor;

import java.time.Duration;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.api.test.DefaultModifiableTimeSource.MIN_TIME;
import static java.util.Objects.requireNonNull;

final class TimeSourceExecutor implements Executor, ModifiableTimeSource, NormalizedTimeSource, AutoCloseable {
    private final DefaultModifiableTimeSource timeSource;
    private final Queue<ScheduleEvent> events;
    private final ReentrantLock lock;
    private final Condition condition;
    private boolean closed;

    TimeSourceExecutor() {
        timeSource = new DefaultModifiableTimeSource();
        lock = new ReentrantLock();
        condition = lock.newCondition();
        events = new PriorityQueue<>(Comparator.<ScheduleEvent>comparingLong(value -> value.expireTime)
                .thenComparingLong(value -> value.id));
    }

    @Override
    public Cancellable execute(Runnable task) throws RejectedExecutionException {
        return scheduleBase(task, MIN_TIME);
    }

    @Override
    public Cancellable schedule(Runnable task, long delay, TimeUnit unit) throws RejectedExecutionException {
        return scheduleBase(task, timeStampFromNow(delay, unit));
    }

    private Cancellable scheduleBase(Runnable task, long expireTime) {
        final Cancellable c;
        lock.lock();
        try {
            if (closed) {
                throw new RejectedExecutionException();
            }
            ScheduleEvent event = new ScheduleEvent(task, expireTime);
            events.add(event);
            c = () -> {
                lock.lock();
                try {
                    events.remove(event);
                } finally {
                    lock.unlock();
                }
            };
            condition.signalAll();
        } finally {
            lock.unlock();
        }
        return c;
    }

    @Override
    public void incrementCurrentTime(long duration, TimeUnit unit) {
        timeSource.incrementCurrentTime(duration, unit);
        signalTimeChange();
    }

    @Override
    public void incrementCurrentTime(Duration duration) {
        timeSource.incrementCurrentTime(duration);
        signalTimeChange();
    }

    private void signalTimeChange() {
        lock.lock();
        try {
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long currentTime() {
        return timeSource.currentTime();
    }

    void runEventLoop() throws InterruptedException {
        runEventLoop(null);
    }

    boolean runEventLoop(@Nullable Duration timeout) throws InterruptedException {
        final long startTime = currentTime();
        lock.lock();
        try {
            for (;;) {
                if (closed) {
                    return true;
                }
                if (timeout != null && isExpired(startTime, timeout)) {
                    return false;
                }
                ScheduleEvent event;
                while (((event = events.peek()) == null) || isExpired(event.expireTime)) {
                    condition.await();
                    if (closed) {
                        return true;
                    }
                    if (timeout != null && isExpired(startTime, timeout)) {
                        return false;
                    }
                }
                ScheduleEvent pollEvent = events.poll();
                assert pollEvent == event;
                event.runnable.run();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private static final class ScheduleEvent {
        private static final AtomicLong nextId = new AtomicLong(Long.MIN_VALUE);
        final long id;
        final Runnable runnable;
        final long expireTime;

        ScheduleEvent(Runnable runnable, long expireTime) {
            this.runnable = requireNonNull(runnable);
            id = nextId.incrementAndGet();
            this.expireTime = expireTime;
        }
    }
}
