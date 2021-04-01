/*
 * Copyright © 2021 Apple Inc. and the ServiceTalk project authors
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

import io.servicetalk.concurrent.internal.SignalOffloader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import static java.util.Objects.requireNonNull;

final class ScanWithLifetimePublisher<T, R> extends AbstractNoHandleSubscribePublisher<R> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScanWithLifetimePublisher.class);

    private final Publisher<T> original;
    private final Supplier<? extends ScanWithLifetimeMapper<? super T, ? extends R>> mapperSupplier;

    ScanWithLifetimePublisher(Publisher<T> original,
                              Supplier<? extends ScanWithLifetimeMapper<? super T, ? extends R>> mapperSupplier,
                              Executor executor) {
        super(executor, true);
        this.mapperSupplier = requireNonNull(mapperSupplier);
        this.original = original;
    }

    @Override
    void handleSubscribe(final Subscriber<? super R> subscriber, final SignalOffloader signalOffloader,
                         final AsyncContextMap contextMap, final AsyncContextProvider contextProvider) {
        original.delegateSubscribe(new ScanWithLifetimeSubscriber<>(subscriber, mapperSupplier.get(), signalOffloader,
                contextMap, contextProvider), signalOffloader, contextMap, contextProvider);
    }

    /**
     * Wraps the {@link io.servicetalk.concurrent.api.ScanWithPublisher.ScanWithSubscriber} to provide mutual exclusion
     * to the {@link ScanWithLifetimeMapper#afterFinally()} call and guarantee a 'no-use-after-free' contract.
     */
    private static final class ScanWithLifetimeSubscriber<T, R> extends ScanWithPublisher.ScanWithSubscriber<T, R> {
        private static final int STATE_UNLOCKED = 0;
        private static final int STATE_BUSY = 1;
        private static final int STATE_FINALIZE_PENDING = 2;
        private static final int STATE_FINALIZED = 3;
        /**
         * Special state to handle the case where a reentry to a terminal (eg. onComplete) signal needs to defer
         * finalization to the root frame onNext.
         * This state helps differentiate between cancel and reentrant completion.
         */
        private static final int STATE_FINALIZE_AFTER_REENTRY = 4;
        /**
         * Special state to handle the case where a reentry to a terminal (eg. onComplete) signal needs to defer
         * a pending finalization to the root frame onNext.
         */
        private static final int STATE_FINALIZE_PENDING_AFTER_REENTRY = 5;

        @SuppressWarnings("rawtypes")
        private static final AtomicIntegerFieldUpdater<ScanWithLifetimeSubscriber> stateUpdater =
                AtomicIntegerFieldUpdater.newUpdater(ScanWithLifetimeSubscriber.class, "state");

        private volatile int state = STATE_UNLOCKED;

        private final ScanWithLifetimeMapper<? super T, ? extends R> mapper;

        ScanWithLifetimeSubscriber(final Subscriber<? super R> subscriber,
                           final ScanWithLifetimeMapper<? super T, ? extends R> mapper,
                           final SignalOffloader signalOffloader, final AsyncContextMap contextMap,
                           final AsyncContextProvider contextProvider) {
            super(subscriber, mapper, signalOffloader, contextMap, contextProvider);
            this.mapper = mapper;
        }

        @Override
        protected void onCancel() {
            // This is not a serial invocation.
            // Finalize only when CAS succeeds and previous state was UNLOCKED or FINALIZE_PENDING, otherwise:
            // - If BUSY the signal will do the finalization
            // - If FINALIZED or FINALIZED_REENTRY nothing needs to be done
            final int prevState = stateUpdater.getAndSet(this, STATE_FINALIZED);
            if (prevState == STATE_UNLOCKED || prevState == STATE_FINALIZE_PENDING) {
                finalize0();
            }
        }

        @Override
        public void onNext(@Nullable final T t) {
            final int prevState = state;
            if (prevState == STATE_FINALIZED) {
                return;
            }
            final boolean reentry = prevState == STATE_BUSY;

            // No need to loop since the signals are guaranteed to be signalled serially,
            // thus a CAS fail means cancellation won, so we can safely ignore it.
            if (!stateUpdater.compareAndSet(ScanWithLifetimeSubscriber.this, prevState, STATE_BUSY)) {
                return;
            }

            try {
                super.onNext(t);
            } finally {
                // Re-entry -> don't unlock
                if (!reentry) {
                    if (state == STATE_FINALIZE_PENDING_AFTER_REENTRY) {
                        // If a re-entrant terminal didn't complete due to missing demand, we take over and set
                        // state to STATE_FINALIZE_PENDING.
                        if (!stateUpdater.compareAndSet(this, STATE_FINALIZE_PENDING_AFTER_REENTRY,
                                STATE_FINALIZE_PENDING)) {
                            // state changed while BUSY eg. cancellation or reentrant terminal -> take over finalization
                            state = STATE_FINALIZED;
                            finalize0();
                        }
                    } else {
                        if (!stateUpdater.compareAndSet(this, STATE_BUSY, prevState)) {
                            // state changed while BUSY eg. cancellation or reentrant terminal -> take over finalization
                            state = STATE_FINALIZED;
                            finalize0();
                        }
                    }
                }
            }
        }

        @Override
        public void onError(final Throwable t) {
            final int prevState = state;
            if (prevState == STATE_FINALIZED) {
                return;
            }
            final boolean reentry = prevState == STATE_BUSY;

            // No need to loop since the signals are guaranteed to be signalled serially,
            // thus a CAS fail means cancellation won, so we can safely ignore it.
            if (!stateUpdater.compareAndSet(ScanWithLifetimeSubscriber.this, prevState, STATE_BUSY)) {
                return;
            }

            boolean completed = true;
            try {
                completed = super.onError0(t);
            } finally {
                releaseFromTerminal(reentry, completed);
            }
        }

        @Override
        public void onComplete() {
            final int prevState = state;
            if (prevState == STATE_FINALIZED) {
                return;
            }
            final boolean reentry = prevState == STATE_BUSY;

            // No need to loop since the signals are guaranteed to be signalled serially,
            // thus a CAS fail means cancellation won, so we can safely ignore it.
            if (!stateUpdater.compareAndSet(ScanWithLifetimeSubscriber.this, prevState, STATE_BUSY)) {
                return;
            }

            boolean completed = true;
            try {
                completed = super.onComplete0();
            } finally {
                releaseFromTerminal(reentry, completed);
            }
        }

        @Override
        protected void deliverOnCompleteFromSubscription(final Subscriber<? super R> subscriber) {
            int currState = state;
            if (currState == STATE_FINALIZED) {
                return;
            }

            if (!stateUpdater.compareAndSet(ScanWithLifetimeSubscriber.this, currState, STATE_BUSY)) {
                return;
            }

            super.deliverOnCompleteFromSubscription(subscriber);

            // Done, transit to FINALIZED.
            // No need to CAS here, we already have exclusion, and any cancellations will hand-over finalization to us.
            state = STATE_FINALIZED;
            finalize0();
        }

        @Override
        protected void deliverOnErrorFromSubscription(final Throwable t, final Subscriber<? super R> subscriber) {
            int currState = state;
            if (currState == STATE_FINALIZED) {
                return;
            }

            if (!stateUpdater.compareAndSet(ScanWithLifetimeSubscriber.this, currState, STATE_BUSY)) {
                return;
            }

            super.deliverOnErrorFromSubscription(t, subscriber);

            // Done, transit to FINALIZED.
            // No need to CAS here, we already have exclusion, and any cancellations will hand-over finalization to us.
            state = STATE_FINALIZED;
            finalize0();
        }

        private void releaseFromTerminal(final boolean reentry, final boolean completed) {
            if (!completed) {
                // Demand wasn't sufficient to deliver -> transit to FINALIZE_PENDING till more demand comes.
                if (reentry) {
                    // If re-entry the defer transit to FINALIZE_PENDING to the root frame.
                    // No need to CAS, reentry root (ie. onNext) will do that.
                    state = STATE_FINALIZE_PENDING_AFTER_REENTRY;
                } else if (!stateUpdater.compareAndSet(this, STATE_BUSY, STATE_FINALIZE_PENDING)) {
                    // state changed while we were in progress -> take over finalization
                    finalize0();
                }
            } else {
                // Done, transit to FINALIZED if not reentry else to STATE_FINALIZE_AFTER_REENTRY.
                if (reentry) {
                    // No need to CAS, reentry root (ie. onNext) will do that.
                    state = STATE_FINALIZE_AFTER_REENTRY;
                } else {
                    // No need to CAS here, we already have exclusion, and any cancellations will hand-over
                    // finalization to us anyhow.
                    state = STATE_FINALIZED;
                    finalize0();
                }
            }
        }

        private void finalize0() {
            try {
                mapper.afterFinally();
            } catch (Throwable cause) {
                LOGGER.error("Unexpected error occurred during finalization.", cause);
            }
        }
    }
}
