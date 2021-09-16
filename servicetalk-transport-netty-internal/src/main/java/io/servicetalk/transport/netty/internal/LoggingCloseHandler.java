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
package io.servicetalk.transport.netty.internal;

import io.servicetalk.concurrent.api.Single;

import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LoggingCloseHandler extends CloseHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingCloseHandler.class);
    private final CloseHandler delegate;

    LoggingCloseHandler(final CloseHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void protocolPayloadBeginInbound() {
        LOGGER.error("protocolPayloadBeginInbound {}", delegate);
        delegate.protocolPayloadBeginInbound();
    }

    @Override
    public void protocolPayloadEndInbound() {
        LOGGER.error("protocolPayloadEndInbound {}", delegate);
        delegate.protocolPayloadEndInbound();
    }

    @Override
    public void protocolPayloadBeginOutbound() {
        LOGGER.error("protocolPayloadBeginOutbound {}", delegate);
        delegate.protocolPayloadBeginOutbound();
    }

    @Override
    public void protocolPayloadEndOutbound(final ChannelPromise promise) {
        LOGGER.error("protocolPayloadEndOutbound {}", delegate);
        delegate.protocolPayloadEndOutbound(promise);
    }

    @Override
    public void protocolClosingInbound() {
        LOGGER.error("protocolClosingInbound {}", delegate);
        delegate.protocolClosingInbound();
    }

    @Override
    public void protocolClosingOutbound() {
        LOGGER.error("protocolClosingOutbound {}", delegate);
        delegate.protocolClosingOutbound();
    }

    @Override
    public Single<CloseEvent> onClosing() {
        return delegate.onClosing();
    }

    @Override
    void channelClosedInbound() {
        LOGGER.error("channelClosedInbound {}", delegate);
        delegate.channelClosedInbound();
    }

    @Override
    void channelClosedOutbound() {
        LOGGER.error("channelClosedOutbound {}", delegate);
        delegate.channelClosedOutbound();
    }

    @Override
    void channelCloseNotify() {
        LOGGER.error("channelCloseNotify {}", delegate);
        delegate.channelCloseNotify();
    }

    @Override
    void closeChannelInbound() {
        LOGGER.error("closeChannelInbound {}", delegate);
        delegate.closeChannelInbound();
    }

    @Override
    void closeChannelOutbound() {
        LOGGER.error("closeChannelOutbound {}", delegate);
        delegate.closeChannelOutbound();
    }

    @Override
    void gracefulUserClosing() {
        LOGGER.error("gracefulUserClosing {}", delegate);
        delegate.gracefulUserClosing();
    }
}
