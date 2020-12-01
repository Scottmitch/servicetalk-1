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
package io.servicetalk.concurrent.test;

import io.servicetalk.concurrent.api.Executor;
import io.servicetalk.concurrent.api.Executors;
import io.servicetalk.concurrent.internal.DeliberateException;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import static io.servicetalk.concurrent.api.Completable.completed;
import static io.servicetalk.concurrent.api.Completable.failed;
import static io.servicetalk.concurrent.api.Completable.never;
import static io.servicetalk.concurrent.api.test.StepVerifiers.create;
import static io.servicetalk.concurrent.internal.DeliberateException.DELIBERATE_EXCEPTION;
import static java.time.Duration.ofDays;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofNanos;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CompletableStepVerifierTest {
    @Test
    public void expectCancellable() {
        create(completed())
                .expectCancellable(Assert::assertNotNull)
                .expectComplete()
                .verify();
    }

    @Test(expected = AssertionError.class)
    public void expectCancellableTimeout() {
        verifyException(() -> create(completed())
                .expectCancellable(cancellable -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                })
                .expectComplete()
                .verify(ofNanos(10)));
    }

    @Test
    public void onComplete() {
        assertNotNull(create(completed())
                .expectComplete()
                .verify());
    }

    @Test
    public void onCompleteDuplicateVerify() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        StepVerifier verifier = create(completed())
                .expectCancellable(cancellable -> {
                    assertNotNull(cancellable);
                    latch.countDown();
                })
                .expectComplete();
        verifier.verify();
        verifier.verify();
        assertTrue(latch.await(10, SECONDS));
    }

    @Test
    public void onCompleteLargeTimeout() {
        assertNotNull(create(completed())
                .expectComplete()
                .verify(ofDays(1)));
    }

    @Test(expected = AssertionError.class)
    public void onCompleteTimeout() {
        verifyException(() -> create(never())
                .expectComplete()
                .verify(ofNanos(10)));
    }

    @Test(expected = AssertionError.class)
    public void onCompleteTimeoutExecutor() throws ExecutionException, InterruptedException {
        Executor executor = Executors.newFixedSizeExecutor(1);
        try {
            verifyException(() -> create(never())
                    .expectComplete()
                    .verify(ofNanos(10), executor));
        } finally {
            executor.closeAsync().toFuture().get();
        }
    }

    @Test
    public void onErrorClass() {
        create(failed(DELIBERATE_EXCEPTION))
                .expectError(DeliberateException.class)
                .verify();
    }

    @Test
    public void onErrorPredicate() {
        create(failed(DELIBERATE_EXCEPTION))
                .expectError(error -> error instanceof DeliberateException)
                .verify();
    }

    @Test
    public void onErrorConsumer() {
        create(failed(DELIBERATE_EXCEPTION))
                .expectError(error -> {
                    assertThat(error, is(DELIBERATE_EXCEPTION));
                })
                .verify();
    }

    @Test(expected = AssertionError.class)
    public void expectOnErrorWhenOnComplete() {
        verifyException(() -> create(completed())
                .expectError(DeliberateException.class)
                .verify());
    }

    @Test(expected = AssertionError.class)
    public void expectOnSuccessWhenOnError() {
        verifyException(() -> create(failed(DELIBERATE_EXCEPTION))
                    .expectComplete()
                    .verify());
    }

    @Test
    public void expectNoTerminal() {
        create(never())
                .expectNoTerminal(ofMillis(100))
                .thenCancel()
                .verify();
    }

    @Test(expected = AssertionError.class)
    public void expectNoTerminalFailOnSuccess() {
        verifyException(() -> create(completed())
                .expectNoTerminal(ofDays(1))
                .expectComplete()
                .verify());
    }

    @Test(expected = AssertionError.class)
    public void expectNoTerminalFailOnError() {
        verifyException(() -> create(failed(DELIBERATE_EXCEPTION))
                .expectNoTerminal(ofDays(1))
                .expectError(DeliberateException.class)
                .verify());
    }

    @Test
    public void thenCancel() {
        create(completed())
                .thenCancel()
                .verify();
    }

    private static void verifyException(Supplier<Duration> verifier) {
        PublisherStepVerifierTest.verifyException(verifier, CompletableStepVerifierTest.class.getName());
    }
}
