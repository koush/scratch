package com.koushikdutta.scratch;

import java.nio.ByteBuffer;

public interface Buffers extends ReadableBuffers, WritableBuffers {
    Buffers addFirst(ByteBuffer b);
}
