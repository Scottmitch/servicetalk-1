/*
 * Copyright © 2022 Apple Inc. and the ServiceTalk project authors
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

import io.servicetalk.concurrent.PublisherSource;
import io.servicetalk.concurrent.test.internal.TestPublisherSubscriber;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.api.SourceAdapters.toSource;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

class SplitterTest {

    @Test
    void basic() {
        Publisher<Integer> publisher = Publisher.range(0, 4);
        Publisher<Integer> fanOut = Publisher.defer(() -> {
            final ArrayList<PublisherSource.Subscriber<? super Integer>> subscribers = new ArrayList<>();
            return publisher
                    .multicast(1)
                    .liftSync(new Splitter<>(subscribers))
                    .shareContextOnSubscribe();
        });
        TestPublisherSubscriber<Integer> subscriber1 = new TestPublisherSubscriber<>();
        TestPublisherSubscriber<Integer> subscriber2 = new TestPublisherSubscriber<>();
        toSource(fanOut).subscribe(subscriber1);
        toSource(fanOut).subscribe(subscriber2);

        subscriber1.awaitSubscription().request(2);
        subscriber2.awaitSubscription().request(2);
        assertThat(subscriber1.takeOnNext(2), contains(0, 2));
        assertThat(subscriber2.takeOnNext(2), contains(1, 3));
        subscriber1.awaitOnComplete();
        subscriber2.awaitOnComplete();
    }

    private static final class Splitter<T> implements PublisherOperator<T, T> {
        private final List<PublisherSource.Subscriber<? super T>> subscribers;
        @Nullable
        private PublisherSource.Subscriber<? super T> emittingSub;
        private int emissions;
        private int emitIndex;

        private Splitter(final List<PublisherSource.Subscriber<? super T>> subscribers) {
            this.subscribers = subscribers;
        }

        @Override
        public PublisherSource.Subscriber<? super T> apply(
                final PublisherSource.Subscriber<? super T> subscriber) {
            SplitterSubscriber newSubscriber = new SplitterSubscriber(subscriber);
            synchronized (subscribers) {
                subscribers.add(newSubscriber);
            }
            return newSubscriber;
        }

        private final class SplitterSubscriber implements PublisherSource.Subscriber<T> {
            private final PublisherSource.Subscriber<? super T> delegate;
            @Nullable
            private PublisherSource.Subscription subscription;

            private SplitterSubscriber(final PublisherSource.Subscriber<? super T> delegate) {
                this.delegate = delegate;
            }

            @Override
            public void onSubscribe(final PublisherSource.Subscription s) {
                subscription = s;
                delegate.onSubscribe(new PublisherSource.Subscription() {
                    private boolean cancelled;
                    @Override
                    public void request(final long n) {
                        synchronized (subscribers) {
                            subscription.request(n);
                        }
                    }

                    @Override
                    public void cancel() {
                        if (cancelled) {
                            return;
                        }
                        cancelled = true;
                        synchronized (subscribers) {
                            try {
                                subscribers.remove(SplitterSubscriber.this);
                            } finally {
                                subscription.cancel();
                            }
                        }
                    }
                });
            }

            @Override
            public void onNext(final T integer) {
                assert subscription != null;
                final boolean emit;
                synchronized (subscribers) {
                    if (emittingSub == null) {
                        emittingSub = subscribers.get(emitIndex++ % subscribers.size());
                    }

                    emit = emittingSub == this;
                    if (!emit) {
                        subscription.request(1);
                    }
                    if (++emissions > subscribers.size()) {
                        emittingSub = null;
                        emissions = 0;
                    }
                }
                if (emit) {
                    delegate.onNext(integer);
                }
            }

            @Override
            public void onError(final Throwable t) {
                delegate.onError(t);
            }

            @Override
            public void onComplete() {
                delegate.onComplete();
            }
        }
    }
}
