/*
 * Copyright © 2019 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.examples.grpc.routeguide.async.streaming;

import io.servicetalk.concurrent.api.Publisher;
import io.servicetalk.grpc.netty.GrpcClients;

import io.grpc.examples.routeguide.Point;
import io.grpc.examples.routeguide.RouteGuide;
import io.grpc.examples.routeguide.RouteGuide.ClientFactory;
import io.grpc.examples.routeguide.RouteNote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import static io.servicetalk.concurrent.api.Publisher.from;
import static java.util.function.Function.identity;

public final class RouteGuideStreamingClient2 {
    private static final Logger logger = LoggerFactory.getLogger(RouteGuideStreamingClient.class);

    public static void main(String[] args) throws Exception {
        try (RouteGuide.RouteGuideClient client = GrpcClients.forAddress("localhost", 8080)
                // .enableWireLogging("servicetalk-client-wire-logger")
                .build(new ClientFactory())) {
            // This example is demonstrating asynchronous execution, but needs to prevent the main thread from exiting
            // before the response has been processed. This isn't typical usage for a streaming API but is useful for
            // demonstration purposes.
            CountDownLatch responseProcessedLatch = new CountDownLatch(1);
            List<Integer> iterations = new ArrayList<>();
            for (int i = 0; i < 2048; i++) {
                iterations.add(i);
            }
            Publisher.fromIterable(iterations)
                    .flatMapMergeSingle(iter -> {
                        logger.error("Running iteration {}", iter);
                        return client.routeChat(from(RouteNote.newBuilder()
                                        .setLocation(Point.newBuilder().setLatitude(123456).setLongitude(-123456).build())
                                        .setMessage("note => " + iter)
                                        .build(),
                                RouteNote.newBuilder()
                                        .setLocation(Point.newBuilder().setLatitude(123456).setLongitude(-123456).build())
                                        .setMessage("Querying notes => " + iter)
                                        .build()))
                                //.whenOnNext(routeNote -> logger.error("Another note {} for iteration {}.", routeNote, iter))
                                .collect((Supplier<ArrayList<RouteNote>>) ArrayList::new,
                                        (routeNotes, routeNote) -> {
                                            routeNotes.add(routeNote);
                                            return routeNotes;
                                        })
                                .whenOnSuccess(routeNotes -> logger.error("Finished iteration {} with items.",
                                        iter));
                    }, 1024)
                    .flatMapConcatIterable(identity())
                    .whenFinally(responseProcessedLatch::countDown)
                    .ignoreElements()
                    .subscribe();
            responseProcessedLatch.await();
            logger.error("Wrapping up....");
        }
    }
}
