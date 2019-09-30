package com.koushikdutta.scratch.buffers;

import java.nio.ByteBuffer;

public interface Buffers extends ReadableBuffers, WritableBuffers {
    Buffers addFirst(ByteBuffer b);
}
