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
package io.servicetalk.concurrent.api.completable;

import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.MockedSubscriberRule;

import org.junit.Rule;
import org.junit.Test;

import static io.servicetalk.concurrent.api.DeliberateException.DELIBERATE_EXCEPTION;

public class CompletableToPublisherTest {
    @Rule
    public MockedSubscriberRule<String> subscriberRule = new MockedSubscriberRule<>();

    @Test
    public void exceptionInTerminalCallsOnError() {
        subscriberRule.subscribe(Completable.completed().toPublisher(() -> {
            throw DELIBERATE_EXCEPTION;
        })).request(1).verifyFailure(DELIBERATE_EXCEPTION);
    }

    @Test
    public void nullSupplierInTerminalSucceeds() {
        subscriberRule.subscribe(Completable.completed().toPublisher(() -> null))
                .request(1).verifySuccess();
    }

    @Test
    public void nullInTerminalSucceeds() {
        subscriberRule.subscribe(Completable.completed().toPublisher((String) null))
                .request(1).verifySuccess();
    }

    @Test
    public void noTerminalSucceeds() {
        subscriberRule.subscribe(Completable.completed().toPublisher())
                .request(1).verifySuccess();
    }
}