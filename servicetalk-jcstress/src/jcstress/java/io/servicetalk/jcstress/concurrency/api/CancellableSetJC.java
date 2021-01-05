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
package io.servicetalk.jcstress.concurrency.api;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

import java.util.concurrent.atomic.AtomicInteger;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

@JCStressTest
@State
// "X -> Y" means X observes Y
// "X !-> Y" means X does not observe Y
@Outcome(id = "0, 0, 0", expect = FORBIDDEN, desc = "cancel() !-> add(), add() !-> cancel()")
@Outcome(id = "0, 0, 1", expect = ACCEPTABLE, desc = "cancel() !-> add(), add() -> cancel()")
@Outcome(id = "1, 0, 0", expect = ACCEPTABLE, desc = "cancel() -> add(), add() !-> cancel()")
@Outcome(id = "1, 0, 1", expect = ACCEPTABLE, desc = "cancel() -> add(), add() -> cancel()")
public class CancellableSetJC {
    private final AtomicInteger cancelled = new AtomicInteger();
    // CHM uses Unsafe.compareAndSetObject for empty bucket
    private volatile int mapBucket;

    @Actor
    public void cancel(III_Result r) {
        if (cancelled.compareAndSet(0, 1)) {
            r.r1 = mapBucket;
        }
    }

    @Actor
    public void add(III_Result r) {
        // CHM uses Unsafe.compareAndSetObject for empty bucket, memory semantics of volatile read and write.
        r.r2 = mapBucket;
        mapBucket = 1;

        r.r3 = cancelled.get();
    }
}
