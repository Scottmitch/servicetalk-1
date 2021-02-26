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
package io.servicetalk.serializer.utils;

import io.servicetalk.buffer.api.Buffer;
import io.servicetalk.buffer.api.BufferAllocator;
import io.servicetalk.serializer.api.SerializerDeserializer;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Serialize/deserialize {@link String}s with {@link java.nio.charset.StandardCharsets#US_ASCII} encoding.
 */
public final class StringAsciiSerializer implements SerializerDeserializer<String> {
    public static final SerializerDeserializer<String> INSTANCE = new StringAsciiSerializer();

    private StringAsciiSerializer() {
    }

    @Override
    public String deserialize(final Buffer serializedData, final BufferAllocator allocator) {
        String result = serializedData.toString(US_ASCII);
        serializedData.skipBytes(serializedData.readableBytes());
        return result;
    }

    @Override
    public Buffer serialize(String toSerialize, BufferAllocator allocator) {
        Buffer buffer = allocator.newBuffer(toSerialize.length());
        serialize(toSerialize, allocator, buffer);
        return buffer;
    }

    @Override
    public void serialize(final String toSerialize, final BufferAllocator allocator, final Buffer buffer) {
        buffer.writeAscii(toSerialize);
    }
}