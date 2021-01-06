/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.concurrent.internal;

import io.servicetalk.concurrent.PublisherSource;
import io.servicetalk.concurrent.PublisherSource.Subscriber;
import io.servicetalk.concurrent.PublisherSource.Subscription;

import static io.servicetalk.concurrent.internal.SubscriberUtils.isRequestNValid;
import static io.servicetalk.concurrent.internal.SubscriberUtils.newExceptionForInvalidRequestN;

/**
 * A {@link Subscription} implementation, which does not do anything.
 */
public final class EmptySubscription implements Subscription {
    public static final EmptySubscription EMPTY_SUBSCRIPTION = new EmptySubscription();
    public static final EmptySubscription EMPTY_SUBSCRIPTION_NO_THROW = new EmptySubscription(false);

    private final boolean throwOnInvalidDemand;

    /**
     * Create a new instance.
     */
    public EmptySubscription() {
        this(true);
    }

    /**
     * Create a new instance.
     * @param throwOnInvalidDemand {@code true} to throw on invalid {@link Subscription#request(long)} demand.
     * {@code false} will not validate demand.
     */
    public EmptySubscription(boolean throwOnInvalidDemand) {
        this.throwOnInvalidDemand = throwOnInvalidDemand;
    }

    @Override
    public void request(long n) {
        if (throwOnInvalidDemand && !isRequestNValid(n)) {
            throw newExceptionForInvalidRequestN(n);
        }
    }

    @Override
    public void cancel() {
        // No op
    }

    /**
     * Create an empty {@link Subscription} that will propagate an error to a {@link PublisherSource.Subscriber} upon
     * invalid demand.
     * @param subscriber The subscriber to propagate
     * @param <T> The type of {@link PublisherSource.Subscriber}.
     * @return An empty {@link Subscription} that will propagate an error to a {@link PublisherSource.Subscriber} upon
     * invalid demand.
     */
    public static <T> Subscription newEmptySubscription(Subscriber<T> subscriber) {
        return new Subscription() {
            private boolean terminated;
            @Override
            public void request(final long n) {
                if (!terminated && !isRequestNValid(n)) {
                    terminated = true;
                    subscriber.onError(newExceptionForInvalidRequestN(n));
                }
            }

            @Override
            public void cancel() {
            }
        };
    }
}
