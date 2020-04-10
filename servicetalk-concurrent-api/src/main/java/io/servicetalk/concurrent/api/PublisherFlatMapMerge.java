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
package io.servicetalk.concurrent.api;

import io.servicetalk.concurrent.internal.ConcurrentSubscription;
import io.servicetalk.concurrent.internal.EmptySubscription;
import io.servicetalk.concurrent.internal.FlowControlUtils;
import io.servicetalk.concurrent.internal.QueueFullException;
import io.servicetalk.concurrent.internal.TerminalNotification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.api.SubscriberApiUtils.NULL_TOKEN;
import static io.servicetalk.concurrent.internal.ConcurrentUtils.acquirePendingLock;
import static io.servicetalk.concurrent.internal.ConcurrentUtils.releasePendingLock;
import static io.servicetalk.concurrent.internal.SubscriberUtils.checkDuplicateSubscription;
import static io.servicetalk.concurrent.internal.SubscriberUtils.isRequestNValid;
import static io.servicetalk.concurrent.internal.SubscriberUtils.trySetTerminal;
import static io.servicetalk.concurrent.internal.TerminalNotification.complete;
import static io.servicetalk.utils.internal.PlatformDependent.newUnboundedMpscQueue;
import static io.servicetalk.utils.internal.PlatformDependent.throwException;
import static java.lang.Math.min;
import static java.util.Collections.newSetFromMap;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.atomic.AtomicReferenceFieldUpdater.newUpdater;

final class PublisherFlatMapMerge<T, R> extends AbstractAsynchronousPublisherOperator<T, R> {
    private static final Logger LOGGER = LoggerFactory.getLogger(PublisherFlatMapMerge.class);
    private final Function<? super T, ? extends Publisher<? extends R>> mapper;
    private final int maxConcurrency;
    private final int maxMappedDemand;
    private final boolean delayError;

    PublisherFlatMapMerge(Publisher<T> original, Function<? super T, ? extends Publisher<? extends R>> mapper,
                          boolean delayError, int maxConcurrency, int maxMappedDemand, Executor executor) {
        super(original, executor);
        this.mapper = requireNonNull(mapper);
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency: " + maxConcurrency + " (expected >0)");
        }
        if (maxMappedDemand <= 0) {
            throw new IllegalArgumentException("maxMappedDemand: " + maxMappedDemand + " (expected >0)");
        }
        this.maxMappedDemand = maxMappedDemand;
        this.maxConcurrency = maxConcurrency;
        this.delayError = delayError;
    }

    @Override
    public Subscriber<? super T> apply(final Subscriber<? super R> subscriber) {
        return new FlatMapSubscriber<>(this, subscriber);
    }

    private static final class FlatMapSubscriber<T, R> implements Subscriber<T>, Subscription {
        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<FlatMapSubscriber, CompositeException> delayedErrorUpdater =
                newUpdater(FlatMapSubscriber.class, CompositeException.class, "delayedError");
        @SuppressWarnings("rawtypes")
        private static final AtomicIntegerFieldUpdater<FlatMapSubscriber> emittingLockUpdater =
                AtomicIntegerFieldUpdater.newUpdater(FlatMapSubscriber.class, "emittingLock");
        @SuppressWarnings("rawtypes")
        private static final AtomicIntegerFieldUpdater<FlatMapSubscriber> tryRequestMoreLockUpdater =
                AtomicIntegerFieldUpdater.newUpdater(FlatMapSubscriber.class, "tryRequestMoreLock");
        @SuppressWarnings("rawtypes")
        private static final AtomicLongFieldUpdater<FlatMapSubscriber> pendingDemandUpdater =
                AtomicLongFieldUpdater.newUpdater(FlatMapSubscriber.class, "pendingDemand");
        @SuppressWarnings("rawtypes")
        private static final AtomicIntegerFieldUpdater<FlatMapSubscriber> activeMappedSourcesUpdater =
                AtomicIntegerFieldUpdater.newUpdater(FlatMapSubscriber.class, "activeMappedSources");
        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<FlatMapSubscriber, TerminalNotification>
                terminalNotificationUpdater = newUpdater(FlatMapSubscriber.class, TerminalNotification.class,
                "terminalNotification");

        @Nullable
        private volatile CompositeException delayedError;
        @SuppressWarnings("unused")
        private volatile int tryRequestMoreLock;
        @SuppressWarnings("unused")
        private volatile int emittingLock;
        private volatile long pendingDemand;
        private volatile int activeMappedSources;
        @SuppressWarnings("unused")
        @Nullable
        private volatile TerminalNotification terminalNotification;

        /**
         * This variable is only accessed within the "emitting lock" so we rely upon this to provide visibility to
         * other threads.
         */
        private boolean targetTerminated;
        @Nullable
        private Subscription subscription;

        private final Subscriber<? super R> target;
        private final Queue<Object> pending;
        private final PublisherFlatMapMerge<T, R> source;
        private final Set<FlatMapPublisherSubscriber<T, R>> subscribers;

        FlatMapSubscriber(PublisherFlatMapMerge<T, R> source, Subscriber<? super R> target) {
            this.source = source;
            this.target = target;
            // Start with a small capacity as maxConcurrency can be large.
            pending = newUnboundedMpscQueue(min(2, source.maxConcurrency));
            subscribers = newSetFromMap(new ConcurrentHashMap<>());
        }

        @Override
        public void cancel() {
            doCancel(true);
        }

        @Override
        public void request(final long n) {
            assert subscription != null;
            if (!isRequestNValid(n)) {
                subscription.request(n);
            } else {
                pendingDemandUpdater.accumulateAndGet(this, n, FlowControlUtils::addWithOverflowProtection);
                tryRequestMore();
            }
        }

        @Override
        public void onSubscribe(final Subscription s) {
            if (!checkDuplicateSubscription(subscription, s)) {
                return;
            }

            // We assume that FlatMapSubscriber#cancel() will never be called before this method, and therefore we
            // don't have to worry about being cancelled before the onSubscribe method is called.
            subscription = ConcurrentSubscription.wrap(s);
            target.onSubscribe(this);

            // Currently we always request maxConcurrency elements from upstream.
            subscription.request(source.maxConcurrency);
        }

        @Override
        public void onNext(@Nullable final T t) {
            final Publisher<? extends R> publisher = requireNonNull(source.mapper.apply(t));
            FlatMapPublisherSubscriber<T, R> subscriber = new FlatMapPublisherSubscriber<>(this);
            if (subscribers.add(subscriber)) {
                for (;;) {
                    final int prevActiveMappedSources = activeMappedSources;
                    if (prevActiveMappedSources < 0) {
                        // We have been cancelled, so in this case we don't want to Subscribe and instead
                        // just want to discard the Publisher.
                        subscribers.remove(subscriber);
                        break;
                    } else if (activeMappedSourcesUpdater.compareAndSet(this, prevActiveMappedSources,
                            prevActiveMappedSources + 1)) {
                        publisher.subscribeInternal(subscriber);
                        tryRequestMore(subscriber);
                        break;
                    }
                }
            }
        }

        @Override
        public void onError(final Throwable t) {
            if (!onError0(t, false, false)) {
                LOGGER.debug("Already terminated/cancelled, ignoring error notification.", t);
            }
        }

        @Override
        public void onComplete() {
            // Setting terminal must be done before terminateActiveMappedSources to ensure visibility of the terminal.
            final boolean setTerminal = trySetTerminal(complete(), false, terminalNotificationUpdater, this);
            final boolean allSourcesTerminated = terminateActiveMappedSources();
            if (setTerminal && allSourcesTerminated) {
                enqueueAndDrain(complete());
            }
        }

        private boolean onError0(Throwable throwable, boolean overrideComplete,
                                 boolean cancelSubscriberIfNecessary) {
            final TerminalNotification notification = TerminalNotification.error(throwable);
            if (trySetTerminal(notification, overrideComplete, terminalNotificationUpdater, this)) {
                try {
                    doCancel(cancelSubscriberIfNecessary);
                } finally {
                    enqueueAndDrain(notification);
                }
                return true;
            }
            return false;
        }

        private void doCancel(boolean cancelSubscription) {
            Throwable delayedCause = null;
            // Prevent future onNext operations from adding to subscribers which otherwise may result in
            // not cancelling mapped Subscriptions.
            activeMappedSources = Integer.MIN_VALUE;
            try {
                if (cancelSubscription) {
                    assert subscription != null;
                    subscription.cancel();
                }
            } finally {
                for (FlatMapPublisherSubscriber<T, R> subscriber : subscribers) {
                    try {
                        subscriber.cancelFromUpstream();
                    } catch (Throwable c) {
                        if (delayedCause == null) {
                            delayedCause = c;
                        }
                    }
                }
                subscribers.clear();
            }
            if (delayedCause != null) {
                throwException(delayedCause);
            }
        }

        private void enqueueAndDrain(Object item) {
            if (!pending.offer(item)) {
                enqueueAndDrainFail(item);
            }
            drainPending();
        }

        private void enqueueAndDrainFail(Object item) {
            QueueFullException exception = new QueueFullException("pending");
            if (item instanceof TerminalNotification) {
                LOGGER.error("Queue should be unbounded, but an offer failed!", exception);
                throw exception;
            } else {
                onError0(exception, true, true);
            }
        }

        private boolean acquireEmittingLock() {
            return acquirePendingLock(emittingLockUpdater, this);
        }

        private boolean releaseEmittingLock() {
            return releasePendingLock(emittingLockUpdater, this);
        }

        private void drainPending() {
            long emittedCount = 0;
            for (;;) {
                if (!acquireEmittingLock()) {
                    break;
                }
                Object t;
                while ((t = pending.poll()) != null) {
                    if (sendToTarget(t)) {
                        ++emittedCount;
                    }
                }
                if (releaseEmittingLock()) {
                    break;
                }
            }

            if (emittedCount != 0) {
                tryRequestMore();
            }
        }

        private boolean sendToTarget(Object item) {
            if (targetTerminated) {
                // No notifications past terminal/cancelled
                return false;
            }
            if (item instanceof TerminalNotification) {
                targetTerminated = true;
                // Load the terminal notification in case an error happened after an onComplete and we override the
                // terminal value.
                TerminalNotification terminalNotification = this.terminalNotification;
                assert terminalNotification != null;
                CompositeException de = this.delayedError;
                if (de != null) {
                    de.addAllPendingSuppressed();
                    if (terminalNotification.cause() == de) {
                        terminalNotification.terminate(target);
                    } else {
                        terminalNotification.terminate(target, de);
                    }
                } else {
                    terminalNotification.terminate(target);
                }
                return false;
            }
            @SuppressWarnings("unchecked")
            final R rItem = item == NULL_TOKEN ? null : (R) item;
            target.onNext(rItem);
            return true;
        }

        private boolean acquireTryRequestMoreLock() {
            return acquirePendingLock(tryRequestMoreLockUpdater, this);
        }

        private boolean releaseTryRequestMoreLock() {
            return releasePendingLock(tryRequestMoreLockUpdater, this);
        }

        private void tryRequestMore(FlatMapPublisherSubscriber<T, R> subscriber) {
            if (acquireTryRequestMoreLock()) {
                try {
                    final long availableRequestN = pendingDemandUpdater.getAndSet(this, 0);
                    if (availableRequestN != 0) {
                        final int consumedRequestN = subscriber.requestFromUpstream(
                                (int) min(source.maxMappedDemand, availableRequestN));
                        assert availableRequestN >= consumedRequestN;
                        if (consumedRequestN != availableRequestN) {
                            giveBackRequestN(availableRequestN - consumedRequestN);
                        }
                    }
                } finally {
                    if (!releaseTryRequestMoreLock()) {
                        tryRequestMore();
                    }
                }
            }
        }

        private void tryRequestMore() {
            Throwable delayedCause = null;
            for (;;) {
                if (!acquireTryRequestMoreLock()) {
                    break;
                }

                try {
                    final long availableRequestN = pendingDemandUpdater.getAndSet(this, 0);
                    if (availableRequestN != 0) {
                        long remainingRequestN = availableRequestN;
                        Iterator<FlatMapPublisherSubscriber<T, R>> itr = subscribers.iterator();
                        while (itr.hasNext() && remainingRequestN > 0) {
                            remainingRequestN -= itr.next().requestFromUpstream(
                                    (int) Math.min(source.maxMappedDemand, remainingRequestN));
                        }

                        assert availableRequestN >= remainingRequestN && remainingRequestN >= 0;
                        if (remainingRequestN > 0) {
                            giveBackRequestN(remainingRequestN);
                        }
                    }
                } catch (Throwable cause) {
                    if (delayedCause == null) {
                        delayedCause = cause;
                    }
                }
                if (releaseTryRequestMoreLock()) {
                    break;
                }
            }
            if (delayedCause != null) {
                throwException(delayedCause);
            }
        }

        private void giveBackRequestN(final long delta) {
            pendingDemandUpdater.addAndGet(this, delta);
        }

        private boolean terminateActiveMappedSources() {
            for (;;) {
                final int prevActiveMappedSources = activeMappedSources;
                // Should always be >= 0, but just in case there is a bug in user code that results in multiple terminal
                // events we avoid corrupting our internal state.
                if (prevActiveMappedSources < 0 || activeMappedSourcesUpdater.compareAndSet(this,
                        prevActiveMappedSources, -prevActiveMappedSources)) {
                    return prevActiveMappedSources == 0;
                }
            }
        }

        private boolean decrementActiveMappedSources() {
            for (;;) {
                final int prevActivePublishers = activeMappedSources;
                assert prevActivePublishers != 0;
                if (prevActivePublishers > 0) {
                    if (activeMappedSourcesUpdater.compareAndSet(this, prevActivePublishers,
                            prevActivePublishers - 1)) {
                        return false;
                    }
                } else if (activeMappedSourcesUpdater.compareAndSet(this, prevActivePublishers,
                        prevActivePublishers + 1)) {
                    return prevActivePublishers == -1;
                }
            }
        }

        private boolean removeSubscriber(final FlatMapPublisherSubscriber<T, R> subscriber,
                                         final long requestNGiveBack) {
            if (subscribers.remove(subscriber)) {
                if (decrementActiveMappedSources()) {
                    return true;
                }

                assert subscription != null;
                subscription.request(1);

                if (requestNGiveBack > 0) {
                    giveBackRequestN(requestNGiveBack);
                    tryRequestMore();
                }
            }
            return false;
        }

        public static final class FlatMapPublisherSubscriber<T, R> implements Subscriber<R> {
            private static final Subscription CANCELLED = new EmptySubscription();
            private static final Subscription CANCEL_PENDING = new EmptySubscription();
            private static final Subscription REQUEST_PENDING = new EmptySubscription();
            private static final Subscription PROCESSING_REQUEST = new EmptySubscription();
            private static final Subscription PROCESSING_ONNEXT = new EmptySubscription();
            @SuppressWarnings("rawtypes")
            private static final AtomicReferenceFieldUpdater<FlatMapPublisherSubscriber, Subscription>
                    subscriptionUpdater = newUpdater(FlatMapPublisherSubscriber.class, Subscription.class,
                                                        "subscription");
            @SuppressWarnings("rawtypes")
            private static final AtomicLongFieldUpdater<FlatMapPublisherSubscriber> outstandingDemandUpdater =
                    AtomicLongFieldUpdater.newUpdater(FlatMapPublisherSubscriber.class, "outstandingDemand");
            @SuppressWarnings("rawtypes")
            private static final AtomicLongFieldUpdater<FlatMapPublisherSubscriber> pendingDemandUpdater =
                    AtomicLongFieldUpdater.newUpdater(FlatMapPublisherSubscriber.class, "pendingDemand");

            private final FlatMapSubscriber<T, R> parent;

            @SuppressWarnings("unused")
            @Nullable
            private volatile Subscription subscription;
            private volatile long pendingDemand;
            private volatile long outstandingDemand;

            FlatMapPublisherSubscriber(FlatMapSubscriber<T, R> parent) {
                this.parent = parent;
            }

            void cancelFromUpstream() {
                for (;;) {
                    final Subscription prevSubscription = subscription;
                    if (prevSubscription == null) {
                        if (subscriptionUpdater.compareAndSet(this, null, CANCELLED)) {
                            break;
                        }
                    } else if (prevSubscription == CANCELLED || prevSubscription == CANCEL_PENDING) {
                        break;
                    } else if (prevSubscription == PROCESSING_ONNEXT || prevSubscription == PROCESSING_REQUEST) {
                        if (subscriptionUpdater.compareAndSet(this, prevSubscription, CANCEL_PENDING)) {
                            break;
                        }
                    } else if (subscriptionUpdater.compareAndSet(this, prevSubscription, CANCELLED)) {
                        cancelAndGiveBack(prevSubscription);
                        break;
                    }
                }
            }

            int requestFromUpstream(final int n) {
                for (;;) {
                    final long prevOutstandingDemand = outstandingDemand;
                    if (prevOutstandingDemand < 0) {
                        return 0; // we have already been cancelled, or terminated
                    }
                    final int quotaToUse = (int) min(Long.MAX_VALUE - prevOutstandingDemand,
                            min(parent.source.maxMappedDemand - prevOutstandingDemand, n));
                    if (quotaToUse == 0) {
                        if (outstandingDemandUpdater.compareAndSet(this, prevOutstandingDemand,
                                prevOutstandingDemand)) {
                            return 0;
                        }
                    } else if (outstandingDemandUpdater.compareAndSet(this, prevOutstandingDemand,
                            prevOutstandingDemand + quotaToUse)) {
                        pendingDemandUpdater.addAndGet(this, quotaToUse);
                        for (;;) {
                            final Subscription prevSubscription = subscription;
                            if (prevSubscription == null) {
                                if (subscriptionUpdater.compareAndSet(this, null, REQUEST_PENDING)) {
                                    break;
                                }
                            } else if (prevSubscription == CANCELLED || prevSubscription == CANCEL_PENDING) {
                                break;
                            } else if (prevSubscription == REQUEST_PENDING || prevSubscription == PROCESSING_ONNEXT ||
                                       prevSubscription == PROCESSING_REQUEST) {
                                if (subscriptionUpdater.compareAndSet(this, prevSubscription, REQUEST_PENDING)) {
                                    break;
                                }
                            } else if (subscriptionUpdater.compareAndSet(this, prevSubscription, PROCESSING_REQUEST)) {
                                doRequestMore(prevSubscription);
                                break;
                            }
                        }
                        return quotaToUse;
                    }
                }
            }

            private void doRequestMore(Subscription prevSubscription) {
                long availableRequestN = pendingDemandUpdater.getAndSet(this, 0);
                for (;;) {
                    if (availableRequestN > 0) {
                        try {
                            prevSubscription.request(availableRequestN);
                        } catch (Throwable cause) {
                            subscription = CANCELLED;
                            cancelAndGiveBack(prevSubscription);
                            throw cause;
                        }
                    }

                    final Subscription afterLockSubscription = subscription;
                    if (afterLockSubscription == PROCESSING_ONNEXT || afterLockSubscription == CANCELLED) {
                        // onNext is allowed to interrupt, and will handle any pending cancel/request events
                        // after it completes (otherwise we may drop signals).
                        break;
                    } else if (afterLockSubscription == CANCEL_PENDING) {
                        subscription = CANCELLED;
                        cancelAndGiveBack(prevSubscription);
                        break;
                    } else if (afterLockSubscription == REQUEST_PENDING) {
                        if (subscriptionUpdater.compareAndSet(this, REQUEST_PENDING, PROCESSING_REQUEST)) {
                            availableRequestN = pendingDemandUpdater.getAndSet(this, 0);
                            // continue through next iteration to try to unlock
                        }
                    } else if (afterLockSubscription == PROCESSING_REQUEST &&
                            subscriptionUpdater.compareAndSet(this, PROCESSING_REQUEST, prevSubscription)) {
                        break;
                    }
                }
            }

            private void cancelAndGiveBack(Subscription subscription) {
                try {
                    subscription.cancel();
                } finally {
                    giveBackUnusedRequestN();
                }
            }

            private boolean giveBackUnusedRequestN() {
                // we need to give back the outstanding amount that has been requested, but not emitted.
                // resetting sourceRequestedUpdater must happen unconditionally first, as this stops
                // calculateSourceRequested from attempting to request any more.
                final long prevOutstandingDemand = outstandingDemandUpdater.getAndSet(this, -1);
                return prevOutstandingDemand >= 0 && parent.removeSubscriber(this, prevOutstandingDemand);
            }

            @Override
            public void onSubscribe(final Subscription s) {
                for (;;) {
                    final Subscription prevSubscription = subscription;
                    assert prevSubscription != CANCEL_PENDING && prevSubscription != PROCESSING_ONNEXT &&
                            prevSubscription != PROCESSING_REQUEST;
                    if (prevSubscription == null) {
                        if (subscriptionUpdater.compareAndSet(this, null, s)) {
                            break;
                        }
                    } else if (prevSubscription == REQUEST_PENDING) {
                        if (subscriptionUpdater.compareAndSet(this, REQUEST_PENDING, PROCESSING_REQUEST)) {
                            doRequestMore(s);
                            break;
                        }
                    } else {
                        s.cancel(); // already cancelled or duplicate onSubscribe
                        break;
                    }
                }
            }

            @Override
            public void onNext(@Nullable final R r) {
                boolean acquiredLock = false;
                for (;;) {
                    Subscription prevSubscription = subscription;
                    assert prevSubscription != null;
                    if (prevSubscription == CANCELLED || prevSubscription == CANCEL_PENDING) {
                        // we have already given our undelivered requestN quota up, or will after we unroll process
                        // onNext. we are not allowed to deliver more data or else we may violate upstream's requestN.
                        break;
                    } else if (prevSubscription == PROCESSING_ONNEXT ||
                              (acquiredLock = subscriptionUpdater.compareAndSet(this,
                                      prevSubscription, PROCESSING_ONNEXT))) {
                        final long newOutstandingDemand = outstandingDemandUpdater.decrementAndGet(this);
                        try {
                            parent.enqueueAndDrain(r == null ? NULL_TOKEN : r);
                        } finally {
                            if (acquiredLock) {
                                for (;;) {
                                    Subscription afterLockSubscription = subscription;
                                    assert afterLockSubscription != PROCESSING_REQUEST;
                                    if (afterLockSubscription == CANCEL_PENDING) {
                                        subscription = CANCELLED; // this is a terminal state.
                                        cancelAndGiveBack(prevSubscription);
                                        break;
                                    } else if (afterLockSubscription == REQUEST_PENDING) {
                                        if (subscriptionUpdater.compareAndSet(this, REQUEST_PENDING,
                                                PROCESSING_REQUEST)) {
                                            doRequestMore(prevSubscription);
                                            break;
                                        }
                                    } else if (afterLockSubscription == CANCELLED) {
                                        break;
                                    } else if (subscriptionUpdater.compareAndSet(this, PROCESSING_ONNEXT,
                                            prevSubscription)) {
                                        // If we have exhausted our quota, try to request more
                                        if (newOutstandingDemand == 0) {
                                            parent.tryRequestMore(this);
                                        }
                                        break; // we unlocked, so bail out.
                                    }
                                }
                            }
                        }
                        break;
                    }
                }
            }

            @Override
            public void onError(final Throwable t) {
                // the Subscription is considered cancelled after a terminal signal.
                subscription = CANCELLED;
                if (!parent.source.delayError) {
                    parent.onError0(t, true, true);
                } else {
                    CompositeException de = parent.delayedError;
                    if (de == null) {
                        de = new CompositeException(t);
                        if (!delayedErrorUpdater.compareAndSet(parent, null, de)) {
                            de = parent.delayedError;
                            assert de != null;
                            de.add(t);
                        }
                    } else {
                        de.add(t);
                    }
                    if (giveBackUnusedRequestN()) {
                        trySetTerminal(TerminalNotification.error(de), true, terminalNotificationUpdater, parent);

                        // Since we have already added error to delayedError, we use complete() TerminalNotification
                        // as a dummy signal to start draining and termination.
                        parent.enqueueAndDrain(complete());
                    }
                }
            }

            @Override
            public void onComplete() {
                // the Subscription is considered cancelled after a terminal signal.
                subscription = CANCELLED;
                if (giveBackUnusedRequestN()) {
                    parent.enqueueAndDrain(complete());
                }
            }
        }
    }
}
