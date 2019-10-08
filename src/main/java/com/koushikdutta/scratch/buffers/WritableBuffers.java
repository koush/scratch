package com.koushikdutta.scratch.buffers;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

public interface WritableBuffers {
    ByteOrder order();
    void order(ByteOrder order);

    WritableBuffers add(ReadableBuffers b);
    WritableBuffers add(ByteBuffer b);
    default WritableBuffers addAll(ByteBuffer... bb) {
        for (ByteBuffer b: bb) {
            add(b);
        }
        return this;
    }
    default WritableBuffers add(byte[] bytes) {
        return add(bytes, 0, bytes.length);
    }
    default WritableBuffers add(byte[] bytes, int offset, int length) {
        return add(ByteBuffer.wrap(bytes, offset, length));
    }

    WritableBuffers put(byte b);
    WritableBuffers putBytes(byte[] bytes);
    WritableBuffers putShort(short s);
    WritableBuffers putInt(int i);
    WritableBuffers putLong(long l);
    WritableBuffers putByteChar(char c);
    default WritableBuffers putString(String s) {
        return putString(s, null);
    }
    WritableBuffers putString(String s, Charset charset);
}
