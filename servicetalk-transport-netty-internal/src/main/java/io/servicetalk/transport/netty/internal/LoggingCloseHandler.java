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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

final class LoggingCloseHandler extends CloseHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingCloseHandler.class);
    private final CloseHandler delegate;

    LoggingCloseHandler(final CloseHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void protocolPayloadBeginInbound(ChannelHandlerContext ctx) {
        LOGGER.error("{} protocolPayloadBeginInbound {}", ctx.channel(), delegate);
        delegate.protocolPayloadBeginInbound(ctx);
    }

    @Override
    public void protocolPayloadEndInbound(ChannelHandlerContext ctx) {
        LOGGER.error("{} protocolPayloadEndInbound {}", ctx.channel(), delegate);
        delegate.protocolPayloadEndInbound(ctx);
    }

    @Override
    public void protocolPayloadBeginOutbound(ChannelHandlerContext ctx) {
        LOGGER.error("{} protocolPayloadBeginOutbound {}", ctx.channel(), delegate);
        delegate.protocolPayloadBeginOutbound(ctx);
    }

    @Override
    public void protocolPayloadEndOutbound(ChannelHandlerContext ctx, final ChannelPromise promise) {
        LOGGER.error("{} protocolPayloadEndOutbound {}", ctx.channel(), delegate);
        delegate.protocolPayloadEndOutbound(ctx, promise);
    }

    @Override
    public void protocolClosingInbound(ChannelHandlerContext ctx) {
        LOGGER.error("{} protocolClosingInbound {}", ctx.channel(), delegate);
        delegate.protocolClosingInbound(ctx);
    }

    @Override
    public void protocolClosingOutbound(ChannelHandlerContext ctx) {
        LOGGER.error("{} protocolClosingOutbound {}", ctx.channel(), delegate);
        delegate.protocolClosingOutbound(ctx);
    }

    @Override
    void registerEventHandler(final Channel channel, final Consumer<CloseEvent> eventHandler) {
        LOGGER.error("{} registerEventHandler {}", channel, delegate);
        delegate.registerEventHandler(channel, eventHandler);
    }

    @Override
    void channelClosedInbound(ChannelHandlerContext ctx) {
        LOGGER.error("{} channelClosedInbound {}", ctx.channel(), delegate);
        delegate.channelClosedInbound(ctx);
    }

    @Override
    void channelClosedOutbound(ChannelHandlerContext ctx) {
        LOGGER.error("{} channelClosedOutbound {}", ctx.channel(), delegate);
        delegate.channelClosedOutbound(ctx);
    }

    @Override
    void channelCloseNotify(ChannelHandlerContext ctx) {
        LOGGER.error("{} channelCloseNotify {}", ctx.channel(), delegate);
        delegate.channelCloseNotify(ctx);
    }

    @Override
    void closeChannelInbound(Channel channel) {
        LOGGER.error("{} closeChannelInbound {}", channel, delegate);
        delegate.closeChannelInbound(channel);
    }

    @Override
    void closeChannelOutbound(Channel channel) {
        LOGGER.error("{} closeChannelOutbound {}", channel, delegate);
        delegate.closeChannelOutbound(channel);
    }

    @Override
    void gracefulUserClosing(Channel channel) {
        LOGGER.error("{} gracefulUserClosing {}", channel, delegate);
        delegate.gracefulUserClosing(channel);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + delegate + ")";
    }
}
