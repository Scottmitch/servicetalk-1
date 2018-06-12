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
package io.servicetalk.transport.netty.internal;

import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.CompletableProcessor;
import io.servicetalk.concurrent.api.ListenableAsyncCloseable;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static java.util.Objects.requireNonNull;

/**
 * Implements {@link ListenableAsyncCloseable} using a netty {@link Channel}.
 */
public final class NettyChannelListenableAsyncCloseable implements ListenableAsyncCloseable {

    private static final AtomicIntegerFieldUpdater<NettyChannelListenableAsyncCloseable> closeTriggeredUpdater =
            AtomicIntegerFieldUpdater.newUpdater(NettyChannelListenableAsyncCloseable.class, "closeTriggered");
    protected final Channel channel;
    private final CompletableProcessor onClose = new CompletableProcessor();
    @SuppressWarnings("unused")
    private volatile int closeTriggered;

    /**
     * New instance.
     * @param channel to use.
     */
    public NettyChannelListenableAsyncCloseable(Channel channel) {
        this.channel = requireNonNull(channel);
        channel.closeFuture().addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                onClose.onComplete();
            } else if (future.cause() != null) {
                onClose.onError(future.cause());
            }
        });
    }

    @Override
    public Completable closeAsync() {
        return new Completable() {
            @Override
            protected void handleSubscribe(Subscriber subscriber) {
                onClose.subscribe(subscriber);
                if (closeTriggeredUpdater.compareAndSet(NettyChannelListenableAsyncCloseable.this, 0, 1)) {
                    channel.eventLoop().execute(channel::close);
                }
            }
        };
    }

    @Override
    public Completable onClose() {
        return onClose;
    }
}
