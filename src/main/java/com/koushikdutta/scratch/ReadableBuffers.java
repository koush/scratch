package com.koushikdutta.scratch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

public interface ReadableBuffers {
    ByteOrder order();
    void order(ByteOrder order);

    default boolean isEmpty() {
        return remaining() == 0;
    }
    default boolean hasRemaining() {
        return !isEmpty();
    }
    int remaining();

    /**
     * Skip the given number of bytes.
     * @param length
     * @return
     */
    ReadableBuffers skip(int length);

    /**
     * Empty the buffer.
     */
    void free();

    default byte[] getBytes(int length) {
        byte[] ret = new byte[length];
        get(ret);
        return ret;
    }
    default byte[] getBytes() {
        return getBytes(remaining());
    }
    byte[] peekBytes(int size);
    ByteBuffer[] getAll();

    void get(byte[] bytes);
    void get(byte[] bytes, int offset, int length);
    void get(WritableBuffers into, int length);
    void get(WritableBuffers into);

    /**
     * Fill the given buffer.
     * @param buffer
     */
    void get(ByteBuffer buffer);
    /**
     * Return all available data as a single buffer.
     * @return
     */
    ByteBuffer getByteBuffer();
    ByteBuffer getByteBuffer(int length);
    int getInt();
    char getByteChar();
    short getShort();
    byte get();
    long getLong();
    default String getString() {
        return getString(null);
    }
    default String getString(Charset charset) {
        String ret = peekString(charset);
        free();
        return ret;
    }

    default void spewString() {
        System.out.println(peekString());
    }

    char peekByteChar();
    short peekShort();
    byte peek();
    int peekInt();
    long peekLong();
    default String peekString() {
        return peekString(null);
    }
    String peekString(Charset charset);
}
