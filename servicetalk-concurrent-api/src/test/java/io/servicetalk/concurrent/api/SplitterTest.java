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
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.api.SourceAdapters.toSource;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

class SplitterTest {

    @Test
    void basic() {
        final ArrayList<Splitter<Integer>.SplitterSubscriber> subscribers = new ArrayList<>();
        final AtomicInteger itemIndex = new AtomicInteger();
        Publisher<Integer> publisher = Publisher.range(0, 4);
        Publisher<Integer> fanOut = publisher
                    .map(t -> new Item<>(itemIndex.getAndIncrement(), t))
                    .multicast(1)
                    .liftSync(new Splitter<Integer>(subscribers));
        TestPublisherSubscriber<Integer> subscriber1 = new TestPublisherSubscriber<>();
        TestPublisherSubscriber<Integer> subscriber2 = new TestPublisherSubscriber<>();
        toSource(fanOut).subscribe(subscriber1);
        toSource(fanOut).subscribe(subscriber2);

        subscriber1.awaitSubscription().request(3);
        subscriber2.awaitSubscription().request(2);
        assertThat(subscriber1.takeOnNext(2), contains(0, 2));
        assertThat(subscriber2.takeOnNext(2), contains(1, 3));
        subscriber1.awaitOnComplete();
        subscriber2.awaitOnComplete();
    }

    private static final class Item<T> {
        final int index;
        final T item;

        private Item(final int index, final T item) {
            this.index = index;
            this.item = item;
        }

        @Override
        public String toString() {
            return index + ", " + item;
        }
    }

    private static final class Splitter<T> implements PublisherOperator<Item<T>, T> {
        private final List<SplitterSubscriber> subscribers;

        private Splitter(final List<SplitterSubscriber> subscribers) {
            this.subscribers = subscribers;
        }

        @Override
        public PublisherSource.Subscriber<? super Item<T>> apply(
                final PublisherSource.Subscriber<? super T> subscriber) {
            final SplitterSubscriber newSubscriber;
            synchronized (subscribers) {
                newSubscriber = new SplitterSubscriber(subscriber, subscribers.size());
                subscribers.add(newSubscriber);
            }
            return newSubscriber;
        }

        private final class SplitterSubscriber implements PublisherSource.Subscriber<Item<T>> {
            private final PublisherSource.Subscriber<? super T> delegate;
            private int index;
            @Nullable
            private PublisherSource.Subscription subscription;

            private SplitterSubscriber(final PublisherSource.Subscriber<? super T> delegate, int index) {
                this.delegate = delegate;
                this.index = index;
            }

            @Override
            public void onSubscribe(final PublisherSource.Subscription s) {
                subscription = s;
                delegate.onSubscribe(new PublisherSource.Subscription() {
                    private boolean cancelled;
                    @Override
                    public void request(final long n) {
                        synchronized (subscribers) { // protects subscription, subscribers, index
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
                                final int removeIndex = subscribers.indexOf(SplitterSubscriber.this);
                                assert removeIndex >= 0;
                                for (int i = removeIndex + 1; i < subscribers.size(); ++i) {
                                    --subscribers.get(i).index;
                                }
                                subscribers.remove(removeIndex);
                            } finally {
                                subscription.cancel();
                            }
                        }
                    }
                });
            }

            @Override
            public void onNext(final Item<T> item) {
                assert subscription != null;
                final boolean emit;
                synchronized (subscribers) {
                    emit = (item.index % subscribers.size()) == index;
                    if (!emit) {
                        subscription.request(1);
                    }
                }
                if (emit) {
                    delegate.onNext(item.item);
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
