package com.koushikdutta.scratch.buffers;

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

    /**
     * Read the buffer. Returns false if nothing was read due to the buffer being empty.
     * @param into
     * @return
     */
    boolean get(WritableBuffers into);

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
    default String getString(int length, Charset charset) {
        return new String(this.getBytes(length), charset);
    }
    default String getString(Charset charset) {
        String ret = peekString(charset);
        free();
        return ret;
    }

    /**
     * Fill the given buffer with data, until the given sequence of bytes is found.
     * @param into
     * @param scan The byte sequence to find.
     * @return Returns true if the byte sequence was found, false if the buffer ends
     * before the sequence was found.
     * For example, if the this buffer ends on a partial byte sequence match, the partial sequence
     * will be left in the this buffer, and all data prior to that sequence will be filled into
     * the given buffer.
     */
    boolean getScan(WritableBuffers into, byte[] scan);

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
