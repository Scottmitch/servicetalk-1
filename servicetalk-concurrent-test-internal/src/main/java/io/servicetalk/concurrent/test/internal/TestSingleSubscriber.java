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
package io.servicetalk.concurrent.test.internal;

import io.servicetalk.concurrent.Cancellable;
import io.servicetalk.concurrent.PublisherSource;
import io.servicetalk.concurrent.SingleSource.Subscriber;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import static java.lang.System.nanoTime;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * A {@link Subscriber} that enqueues signals and provides blocking methods to consume them.
 * @param <T> The type of data in {@link #onSuccess(Object)}.
 */
public final class TestSingleSubscriber<T> implements Subscriber<T> {
    // 1 -> demand is implicit
    private final TestPublisherSubscriber<T> publisherSubscriber = new TestPublisherSubscriber<>(1);

    @Override
    public void onSubscribe(final Cancellable cancellable) {
        publisherSubscriber.onSubscribe(new PublisherSource.Subscription() {
            @Override
            public void request(final long n) {
            }

            @Override
            public void cancel() {
                cancellable.cancel();
            }
        });
    }

    @Override
    public void onSuccess(@Nullable final T result) {
        try {
            publisherSubscriber.onNext(result);
        } finally {
            publisherSubscriber.onComplete();
        }
    }

    @Override
    public void onError(final Throwable t) {
        publisherSubscriber.onError(t);
    }

    /**
     * Block until {@link #onSubscribe(Cancellable)}.
     *
     * @return The {@link Cancellable} from {@link #onSubscribe(Cancellable)}.
     */
    public Cancellable awaitSubscription() {
        return publisherSubscriber.awaitSubscription();
    }

    /**
     * Block until {@link Subscriber#onSubscribe(Cancellable)}.
     *
     * @return The {@link Cancellable} from {@link #onSubscribe(Cancellable)}.
     * @throws InterruptedException if the calling thread is interrupted while waiting for signals.
     */
    public Cancellable awaitSubscriptionInterruptible() throws InterruptedException {
        return publisherSubscriber.awaitSubscriptionInterruptible();
    }

    /**
     * Block until {@link Subscriber#onSubscribe(Cancellable)}.
     * @param timeout The amount of time to wait.
     * @param unit The units of {@code timeout}.
     * @return The {@link Cancellable} from {@link #onSubscribe(Cancellable)}.
     * @throws InterruptedException if the calling thread is interrupted while waiting for signals.
     * @throws TimeoutException if a timeout occurs while waiting.
     */
    public Cancellable awaitSubscriptionInterruptible(long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException {
        return publisherSubscriber.awaitSubscriptionInterruptible(timeout, unit);
    }

    /**
     * Block until a terminal signal is received, throws if {@link #onSuccess(Object)} and returns normally if
     * {@link #onError(Throwable)}.
     *
     * @return the exception received by {@link #onError(Throwable)}.
     */
    public Throwable awaitOnError() {
        return publisherSubscriber.awaitOnError();
    }

    /**
     * Block until a terminal signal is received, throws if {@link #onSuccess(Object)} and returns normally if
     * {@link #onError(Throwable)}.
     *
     * @return the exception received by {@link #onError(Throwable)}.
     * @throws InterruptedException if the calling thread is interrupted while waiting for signals.
     */
    public Throwable takeOnErrorInterruptible() throws InterruptedException {
        return publisherSubscriber.takeOnErrorInterruptible();
    }

    /**
     * Block until a terminal signal is received, throws if {@link #onSuccess(Object)} and returns normally if
     * {@link #onError(Throwable)}.
     * @param timeout The amount of time to wait.
     * @param unit The units of {@code timeout}.
     * @return the exception received by {@link #onError(Throwable)}.
     * @throws InterruptedException if the calling thread is interrupted while waiting for signals.
     * @throws TimeoutException if a timeout occurs while waiting.
     */
    public Throwable takeOnErrorInterruptible(long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException {
        return publisherSubscriber.takeOnErrorInterruptible(timeout, unit);
    }

    /**
     * Block until a terminal signal is received, throws if {@link #onError(Throwable)} and returns normally if
     * {@link #onSuccess(Object)}.
     *
     * @return The value delivered to {@link #onSuccess(Object)}.
     */
    @Nullable
    public T awaitOnSuccess() {
        final T next = publisherSubscriber.takeOnNext();
        publisherSubscriber.awaitOnComplete();
        return next;
    }

    /**
     * Block until a terminal signal is received, throws if {@link #onError(Throwable)} and returns normally if
     * {@link #onSuccess(Object)}.
     *
     * @return The value delivered to {@link #onSuccess(Object)}.
     * @throws InterruptedException if the calling thread is interrupted while waiting for signals.
     */
    @Nullable
    public T takeOnSuccessInterruptible() throws InterruptedException {
        final T next = publisherSubscriber.takeOnNextInterruptible();
        publisherSubscriber.takeOnCompleteInterruptible();
        return next;
    }

    /**
     * Block until a terminal signal is received, throws if {@link #onError(Throwable)} and returns normally if
     * {@link #onSuccess(Object)}.
     * @param timeout The amount of time to wait.
     * @param unit The units of {@code timeout}.
     * @return The value delivered to {@link #onSuccess(Object)}.
     * @throws InterruptedException if the calling thread is interrupted while waiting for signals.
     * @throws TimeoutException if a timeout occurs while waiting.
     */
    @Nullable
    public T takeOnSuccessInterruptible(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        final long startTime = nanoTime();
        final long timeoutNanos = NANOSECONDS.convert(timeout, unit);
        final T next = publisherSubscriber.takeOnNextInterruptible(timeout, unit);
        publisherSubscriber.takeOnCompleteInterruptible(timeoutNanos - (nanoTime() - startTime), NANOSECONDS);
        return next;
    }

    /**
     * Block for a terminal event.
     *
     * @param timeout The duration of time to wait.
     * @param unit The unit of time to apply to the duration.
     * @return {@code null} if a the timeout expires before a terminal event is received. A non-{@code null}
     * {@link Supplier} that returns {@code null} if {@link #onSuccess(Object)}, or the {@link Throwable} from
     * {@link #onError(Throwable)}.
     */
    @Nullable
    public Supplier<Throwable> pollTerminal(long timeout, TimeUnit unit) {
        return publisherSubscriber.pollTerminal(timeout, unit);
    }

    /**
     * Block for a terminal event.
     *
     * @param timeout The duration of time to wait.
     * @param unit The unit of time to apply to the duration.
     * @return {@code null} if a the timeout expires before a terminal event is received. A non-{@code null}
     * {@link Supplier} that returns {@code null} if {@link #onSuccess(Object)}, or the {@link Throwable} from
     * {@link #onError(Throwable)}.
     * @throws InterruptedException if the calling thread is interrupted while waiting for signals.
     */
    @Nullable
    public Supplier<Throwable> pollTerminalInterruptible(long timeout, TimeUnit unit) throws InterruptedException {
        return publisherSubscriber.pollTerminalInterruptible(timeout, unit);
    }
}
