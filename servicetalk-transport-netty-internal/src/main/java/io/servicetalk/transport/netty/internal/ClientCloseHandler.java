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

import io.servicetalk.concurrent.CompletableSource;
import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.Single;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.handler.ssl.SslHandler;

import javax.annotation.Nullable;

import static io.servicetalk.concurrent.api.Processors.newCompletableProcessor;
import static io.servicetalk.concurrent.api.SourceAdapters.fromSource;
import static io.servicetalk.transport.netty.internal.CloseHandler.CloseEvent.CHANNEL_CLOSED_INBOUND;
import static io.servicetalk.transport.netty.internal.CloseHandler.CloseEvent.CHANNEL_CLOSED_OUTBOUND;
import static io.servicetalk.transport.netty.internal.CloseHandler.CloseEvent.GRACEFUL_USER_CLOSING;
import static io.servicetalk.transport.netty.internal.CloseHandler.CloseEvent.PROTOCOL_CLOSING_INBOUND;
import static io.servicetalk.transport.netty.internal.CloseHandler.CloseEvent.PROTOCOL_CLOSING_OUTBOUND;
import static io.servicetalk.transport.netty.internal.CloseHandlerUtils.isAllSet;
import static io.servicetalk.transport.netty.internal.CloseHandlerUtils.isAnySet;
import static io.servicetalk.transport.netty.internal.CloseHandlerUtils.set;
import static io.servicetalk.transport.netty.internal.CloseHandlerUtils.unset;
import static java.util.Objects.requireNonNull;

final class ClientCloseHandler extends CloseHandler {
    private static final byte READ = 1;
    private static final byte WRITE = 1 << 1;
    private static final byte IN_CLOSING = 1 << 2;
    private static final byte OUT_CLOSING = 1 << 3;
    private static final byte IN_CLOSED = 1 << 4;
    private static final byte OUT_CLOSED = 1 << 5;
    private static final byte CLOSED = 1 << 6;
    private static final byte GRACEFUL_CLOSE = (byte) (1 << 7);
    private static final byte IN_OUT_CLOSED = IN_CLOSED | OUT_CLOSED;
    private static final byte ALL_CLOSED = IN_CLOSED | OUT_CLOSED | CLOSED;
    private static final byte READ_WRITE = READ | WRITE;
    private static final byte CLIENT_IN_WRITE = WRITE | IN_CLOSED;
    private static final byte GRACEFUL_IN_CLOSED = GRACEFUL_CLOSE | IN_CLOSED;
    private static final byte GRACEFUL_OUT_CLOSED = GRACEFUL_CLOSE | OUT_CLOSED;
    private byte state;
    private int pending;
    private final Channel channel;
    @Nullable
    private Throwable stopNewRequestsReason;
    private final CompletableSource.Processor stopRequestsProcessor;

    ClientCloseHandler(Channel channel) {
        this.channel = requireNonNull(channel);
        stopRequestsProcessor = newCompletableProcessor();
    }

    @Override
    public void protocolPayloadBeginInbound() {
        state = set(state, READ);
    }

    // client
    //   if protocolClosingOutbound -> finish all outstanding reads, no more new writes/requests allowed
    //   if protocolClosingInbound -> finish current read, outstanding writes/request beyond current are aborted
    // server
    //   if protocolClosingOutbound -> finish current read/request, outstanding reads/requests are aborted
    //   if protocolClosingInbound -> no more new reads/requests, outstanding writes should complete
    @Override
    public void protocolPayloadEndInbound() {
        assert pending > 0;
        state = unset(state, READ);
        if (--pending == 0 && isAnySet(state, GRACEFUL_CLOSE)) {
            state = set(state, IN_CLOSED);
        }
        if (isAnySet(state, IN_CLOSING) && !isAnySet(state, IN_CLOSED)) {
            // if client reads protocol inbound closing, abort all pending writes
            if (pending == 0) {
                closeChannel(); // If client is closing we give up on writes
            } else {
                channel.pipeline().fireUserEventTriggered(AbortWritesEvent.INSTANCE);
            }
        } else {
            inboundEventCheckClose();
        }
    }

    @Override
    public void protocolPayloadBeginOutbound() {
        ++pending;
        state = set(state, WRITE);
    }

    @Override
    public void protocolPayloadEndOutbound(final ChannelPromise promise) {
        if (isAnySet(state, OUT_CLOSING)) {
            state = set(state, OUT_CLOSED);
        }
        channel.pipeline().fireUserEventTriggered(OutboundDataEndEvent.INSTANCE);
        promise.addListener(f -> {
            state = unset(state, WRITE);
            outboundEventCheckClose();
        });
    }

    @Override
    public void protocolClosingInbound() {
        state = set(state, IN_CLOSING);
        storeCloseRequestAndEmit(PROTOCOL_CLOSING_INBOUND);
    }

    @Override
    public void protocolClosingOutbound() {
        state = set(state, OUT_CLOSING);
        storeCloseRequestAndEmit(PROTOCOL_CLOSING_OUTBOUND);
    }

    @Override
    Completable stopAccepting() {
        return fromSource(stopRequestsProcessor);
    }

    @Override
    public Single<CloseEvent> onClosing() {
        return fromSource(onClosing);
    }

    @Override
    void channelClosedInbound() {
        storeCloseRequestAndEmit(CHANNEL_CLOSED_INBOUND);
        inboundEventCheckClose();
    }

    @Override
    void channelClosedOutbound() {
        transportOutboundClose(CHANNEL_CLOSED_OUTBOUND);
    }

    @Override
    void channelCloseNotify() {
        channelClosedInbound();
        closeChannelOutbound();
    }

    @Override
    void closeChannelInbound() {
        transportInboundClose(null);
    }

    @Override
    void closeChannelOutbound() {
        transportOutboundClose(null);
    }

    @Override
    void gracefulUserClosing() {
        state = set(state, GRACEFUL_CLOSE);
        storeCloseRequestAndEmit(GRACEFUL_USER_CLOSING);
        if (pending == 0 && !isAnySet(state, READ_WRITE)) {
            closeChannel();
        }
    }

    private void transportInboundClose(@Nullable CloseEvent evt) {
        if (!isAllSet(state, IN_CLOSED)) {
            state = set(state, IN_CLOSED);
            if (evt != null) {
                storeCloseRequestAndEmit(evt);
            }
            inboundEventCheckClose();
        }
    }

    private void transportOutboundClose(@Nullable CloseEvent evt) {
        if (!isAllSet(state, OUT_CLOSED)) {
            state = unset(set(state, OUT_CLOSED), WRITE);
            if (evt != null) {
                storeCloseRequestAndEmit(evt);
            }
            outboundEventCheckClose();
        }
    }

    private void inboundEventCheckClose() {
        if (pending == 0 && (isAllSet(state, OUT_CLOSED) ||
                (isAnySet(state, GRACEFUL_IN_CLOSED) && !isAllSet(state, WRITE)))) {
            closeChannel();
        } else if (isAllSet(state, CLIENT_IN_WRITE)) {
            // If a client inbound has closed while writing we abort writes because we can't be sure if writes will ever
            // complete or receive any additional feedback from the server.
            state = unset(state, WRITE);
            channel.pipeline().fireUserEventTriggered(AbortWritesEvent.INSTANCE);
        }
    }

    private void outboundEventCheckClose() {
        if (pending == 0 && (isAllSet(state, IN_CLOSED) ||
                (isAnySet(state, GRACEFUL_OUT_CLOSED) && !isAllSet(state, READ)))) {
            closeChannel();
        }
    }

    private void storeCloseRequestAndEmit(final CloseEvent event) {
        onClosing.onSuccess(event);
    }

    private void closeChannel() {
        if (!isAllSet(state, CLOSED)) {
            state = set(state, ALL_CLOSED);
            final SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
            if (sslHandler != null) {
                // send close_notify: https://tools.ietf.org/html/rfc5246#section-7.2.1
                sslHandler.closeOutbound().addListener(ChannelFutureListener.CLOSE);
            } else {
                channel.close();
            }
        }
    }

    @Override
    public String toString() {
        String chStr = channel.toString();
        StringBuilder sb = new StringBuilder(32 + chStr.length());
        sb.append(chStr).append(" ");
        if (isAnySet(state, READ)) {
            sb.append("READ,");
        }
        if (isAnySet(state, WRITE)) {
            sb.append("WRITE,");
        }
        if (isAnySet(state, IN_CLOSING)) {
            sb.append("IN_CLOSING,");
        }
        if (isAnySet(state, OUT_CLOSING)) {
            sb.append("OUT_CLOSING,");
        }
        if (isAnySet(state, IN_CLOSED)) {
            sb.append("IN_CLOSED,");
        }
        if (isAnySet(state, OUT_CLOSED)) {
            sb.append("OUT_CLOSED,");
        }
        if (isAnySet(state, GRACEFUL_CLOSE)) {
            sb.append("GRACEFUL_CLOSE,");
        }
        if (isAnySet(state, CLOSED)) {
            sb.append("CLOSED,");
        }
        if (sb.length() == 0) {
            return "";
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
