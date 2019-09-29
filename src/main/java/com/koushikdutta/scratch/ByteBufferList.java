package com.koushikdutta.scratch;


import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.PriorityQueue;

public class ByteBufferList implements Buffers {
    private static final Object LOCK = new Object();
    public static final ByteBuffer EMPTY_BYTEBUFFER = ByteBuffer.allocate(0);
    static int MAX_ITEM_SIZE = 1024 * 256;
    private static PriorityQueue<ByteBuffer> reclaimed = new PriorityQueue<>(8, (byteBuffer, byteBuffer2) -> {
        // keep the smaller ones at the head, so they get tossed out quicker
        return Integer.compare(byteBuffer.capacity(), byteBuffer2.capacity());
    });
    private static int currentSize = 0;
    private static int maxItem = 0;
    private static int MAX_SIZE = 1024 * 1024;
    private static Charset UTF_8 = Charset.forName("UTF-8");
    private ArrayDeque<ByteBuffer> mBuffers = new ArrayDeque<>();
    private ByteOrder order = ByteOrder.BIG_ENDIAN;
    private int remaining = 0;

    public ByteBufferList() {
    }

    public ByteBufferList(ByteBuffer... b) {
        addAll(b);
    }


    public ByteBufferList(byte[] buf) {
        super();
        ByteBuffer b = ByteBuffer.wrap(buf);
        add(b);
    }

    private static PriorityQueue<ByteBuffer> getReclaimed() {
        return reclaimed;
    }

    public static void setMaxPoolSize(int size) {
        MAX_SIZE = size;
    }

    public static void setMaxItemSize(int size) {
        MAX_ITEM_SIZE = size;
    }

    private static boolean reclaimedContains(ByteBuffer b) {
        for (ByteBuffer other : reclaimed) {
            if (other == b)
                return true;
        }
        return false;
    }

    public static void reclaim(ByteBuffer b) {
        if (b == null || b.isDirect())
            return;
        if (b.arrayOffset() != 0 || b.array().length != b.capacity())
            return;
        if (b.capacity() < 8192)
            return;
        if (b.capacity() > MAX_ITEM_SIZE)
            return;

        PriorityQueue<ByteBuffer> r = getReclaimed();
        if (r == null)
            return;

        synchronized (LOCK) {
            while (currentSize > MAX_SIZE && r.size() > 0 && r.peek().capacity() < b.capacity()) {
//                System.out.println("removing for better: " + b.capacity());
                ByteBuffer head = r.remove();
                currentSize -= head.capacity();
            }

            if (currentSize > MAX_SIZE) {
//                System.out.println("too full: " + b.capacity());
                return;
            }

            assert !reclaimedContains(b);

            b.position(0);
            b.limit(b.capacity());
            currentSize += b.capacity();

            r.add(b);
            assert r.size() != 0 ^ currentSize == 0;

            maxItem = Math.max(maxItem, b.capacity());
        }
    }

    public static ByteBuffer obtain(int size) {
        if (size <= maxItem) {
            PriorityQueue<ByteBuffer> r = getReclaimed();
            if (r != null) {
                synchronized (LOCK) {
                    while (r.size() > 0) {
                        ByteBuffer ret = r.remove();
                        if (r.size() == 0)
                            maxItem = 0;
                        currentSize -= ret.capacity();
                        assert r.size() != 0 ^ currentSize == 0;
                        if (ret.capacity() >= size) {
//                            System.out.println("using " + ret.capacity());
                            return ret;
                        }
//                        System.out.println("dumping " + ret.capacity());
                    }
                }
            }
        }

//        System.out.println("alloc for " + size);
        ByteBuffer ret = ByteBuffer.allocate(Math.max(8192, size));
        return ret;
    }

    public static ByteBuffer deepCopy(ByteBuffer copyOf) {
        if (copyOf == null)
            return null;
        return (ByteBuffer) obtain(copyOf.remaining()).put(copyOf.duplicate()).flip();
    }

    public static void writeOutputStream(OutputStream out, ByteBuffer b) throws IOException {
        byte[] bytes;
        int offset;
        int length;
        if (b.isDirect()) {
            bytes = new byte[b.remaining()];
            offset = 0;
            length = b.remaining();
            b.get(bytes);
        } else {
            bytes = b.array();
            offset = b.arrayOffset() + b.position();
            length = b.remaining();
        }
        out.write(bytes, offset, length);
    }

    public ByteOrder order() {
        return order;
    }

    public void order(ByteOrder order) {
        this.order = order;
    }

    public ByteBuffer[] getAll() {
        ByteBuffer[] ret = new ByteBuffer[mBuffers.size()];
        ret = mBuffers.toArray(ret);
        mBuffers.clear();
        remaining = 0;
        return ret;
    }

    public int remaining() {
        return remaining;
    }

    public short peekShort() {
        return read(2).getShort(mBuffers.peekFirst().position());
    }

    public byte peek() {
        return read(1).get(mBuffers.peekFirst().position());
    }

    public char peekByteChar() {
        return (char)peek();
    }

    public int peekInt() {
        return read(4).getInt(mBuffers.peekFirst().position());
    }

    public long peekLong() {
        return read(8).getLong(mBuffers.peekFirst().position());
    }

    public byte[] peekBytes(int size) {
        byte[] ret = new byte[size];
        read(size).get(ret, mBuffers.peekFirst().position(), ret.length);
        return ret;
    }

    public ByteBufferList skip(int length) {
        get(null, 0, length);
        return this;
    }

    public int getInt() {
        int ret = read(4).getInt();
        remaining -= 4;
        return ret;
    }

    public char getByteChar() {
        char ret = (char) read(1).get();
        remaining--;
        return ret;
    }

    public short getShort() {
        short ret = read(2).getShort();
        remaining -= 2;
        return ret;
    }

    public byte get() {
        byte ret = read(1).get();
        remaining--;
        return ret;
    }

    public long getLong() {
        long ret = read(8).getLong();
        remaining -= 8;
        return ret;
    }

    public void get(byte[] bytes) {
        get(bytes, 0, bytes.length);
    }

    public void get(ByteBuffer buffer) {
        if (remaining < buffer.remaining())
            throw new IllegalArgumentException("length");

        int reading = buffer.remaining();

        while (buffer.hasRemaining()) {
            ByteBuffer b = mBuffers.peekFirst();
            if (buffer.remaining() < b.remaining()) {
                int oldLimit = b.limit();
                b.limit(b.position() + buffer.remaining());
                buffer.put(b);
                b.limit(oldLimit);
            }
            else {
                buffer.put(b);
                trim();
            }
        }

        remaining -= reading;
    }

    public void get(byte[] bytes, int offset, int length) {
        if (remaining < length)
            throw new IllegalArgumentException("length");

        int need = length;
        while (need > 0) {
            ByteBuffer b = mBuffers.peek();
            int read = Math.min(b.remaining(), need);
            if (bytes != null) {
                b.get(bytes, offset, read);
            } else {
                //when bytes is null, just skip data.
                b.position(b.position() + read);
            }
            need -= read;
            offset += read;
            if (b.remaining() == 0) {
                ByteBuffer removed = mBuffers.remove();
                assert b == removed;
                reclaim(b);
            }
        }

        remaining -= length;
    }

    public void get(WritableBuffers into, int length) {
        if (remaining < length)
            throw new IllegalArgumentException("length");
        int offset = 0;

        while (offset < length) {
            ByteBuffer b = mBuffers.remove();
            int remaining = b.remaining();

            if (remaining == 0) {
                reclaim(b);
                continue;
            }

            if (offset + remaining > length) {
                int need = length - offset;
                // this is shared between both
                ByteBuffer subset = obtain(need);
                subset.limit(need);
                b.get(subset.array(), 0, need);
                into.add(subset);
                mBuffers.addFirst(b);
                assert subset.capacity() >= need;
                assert subset.position() == 0;
                break;
            } else {
                // this belongs to the new list
                into.add(b);
            }

            offset += remaining;
        }

        remaining -= length;
    }

    public void get(WritableBuffers into) {
        get(into, remaining);
    }

    public ByteBufferList get(int length) {
        ByteBufferList ret = new ByteBufferList();
        get(ret, length);
        ret.order(order);
        return ret;
    }

    public ByteBuffer getByteBuffer() {
        if (remaining == 0)
            return EMPTY_BYTEBUFFER;
        read(remaining);
        return remove();
    }

    @Override
    public ByteBuffer getByteBuffer(int length) {
        ByteBuffer ret = obtain(length);
        ret.limit(length);
        get(ret);
        ret.flip();
        return ret;
    }

    private ByteBuffer read(int count) {
        if (remaining < count)
            throw new IllegalArgumentException("count : " + remaining + "/" + count);

        ByteBuffer first = mBuffers.peek();
        while (first != null && !first.hasRemaining()) {
            reclaim(mBuffers.remove());
            first = mBuffers.peek();
        }

        if (first == null) {
            return EMPTY_BYTEBUFFER;
        }

        if (first.remaining() >= count) {
            return first.order(order);
        }

        ByteBuffer ret = obtain(count);
        ret.limit(count);
        byte[] bytes = ret.array();
        int offset = 0;
        ByteBuffer bb = null;
        while (offset < count) {
            bb = mBuffers.remove();
            int toRead = Math.min(count - offset, bb.remaining());
            bb.get(bytes, offset, toRead);
            offset += toRead;
            if (bb.remaining() == 0) {
                reclaim(bb);
                bb = null;
            }
        }
        // if there was still data left in the last buffer we popped
        // toss it back into the head
        if (bb != null && bb.remaining() > 0)
            mBuffers.addFirst(bb);
        mBuffers.addFirst(ret);
        return ret.order(order);
    }

    private void trim() {
        // this clears out buffers that are empty in the beginning of the list
        read(0);
    }

    public ByteBufferList add(ReadableBuffers b) {
        b.get(this);
        return this;
    }

    public ByteBufferList add(ByteBuffer b) {
        if (b.remaining() <= 0) {
//            System.out.println("reclaiming remaining: " + b.remaining());
            reclaim(b);
            return this;
        }
        addRemaining(b.remaining());
        // see if we can fit the entirety of the buffer into the end
        // of the current last buffer
        if (mBuffers.size() > 0) {
            ByteBuffer last = mBuffers.getLast();
            if (last.capacity() - last.limit() >= b.remaining()) {
                last.mark();
                last.position(last.limit());
                last.limit(last.capacity());
                last.put(b);
                last.limit(last.position());
                last.reset();
                reclaim(b);
                trim();
                return this;
            }
        }
        mBuffers.add(b);
        trim();
        return this;
    }

    public ByteBufferList addFirst(ByteBuffer b) {
        if (b.remaining() <= 0) {
            reclaim(b);
            return this;
        }
        addRemaining(b.remaining());
        // see if we can fit the entirety of the buffer into the beginning
        // of the current first buffer
        if (mBuffers.size() > 0) {
            ByteBuffer first = mBuffers.getFirst();
            if (first.position() >= b.remaining()) {
                first.position(first.position() - b.remaining());
                first.mark();
                first.put(b);
                first.reset();
                reclaim(b);
                return this;
            }
        }
        mBuffers.addFirst(b);
        return this;
    }

    private void addRemaining(int remaining) {
        if (this.remaining >= 0)
            this.remaining += remaining;
    }

    public void free() {
        while (mBuffers.size() > 0) {
            reclaim(mBuffers.remove());
        }
        assert mBuffers.size() == 0;
        remaining = 0;
    }

    private ByteBuffer remove() {
        ByteBuffer ret = mBuffers.remove();
        remaining -= ret.remaining();
        return ret;
    }

    public int size() {
        return mBuffers.size();
    }

    public void spewString() {
        System.out.println(peekString());
    }

    // allocate or extend an existing buffer.
    // return the buffer with the mark set so position can be restored after writing.
    private ByteBuffer grow(int length) {
        if (!mBuffers.isEmpty()) {
            ByteBuffer b = mBuffers.peekLast();
            if (b.limit() + length < b.capacity()) {
                b.mark();
                b.position(b.limit());
                b.limit(b.limit() + length);
                remaining += length;
                return b.order(order);
            }
        }

        ByteBuffer ret = obtain(length);
        ret.mark();
        ret.limit(length);
        add(ret);

        return ret.order(order);
    }

    public ByteBufferList put(byte b) {
        grow(1).put(b).reset();
        return this;
    }

    public ByteBufferList putShort(short s) {
        grow(2).putShort(s).reset();
        return this;
    }

    public ByteBufferList putInt(int i) {
        grow(4).putInt(i).reset();
        return this;
    }

    public ByteBufferList putLong(long l) {
        grow(8).putLong(l).reset();
        return this;
    }

    public WritableBuffers putByteChar(char c) {
        return put((byte) c);
    }

    @Override
    public WritableBuffers putString(String s, Charset charset) {
        if (charset == null)
            charset = UTF_8;
        return add(charset.encode(s));
    }

    // not doing toString as this is really nasty in the debugger...
    public String peekString(Charset charset) {
        if (charset == null)
            charset = UTF_8;
        StringBuilder builder = new StringBuilder();
        for (ByteBuffer bb : mBuffers) {
            byte[] bytes;
            int offset;
            int length;
            if (bb.isDirect()) {
                bytes = new byte[bb.remaining()];
                offset = 0;
                length = bb.remaining();
                bb.get(bytes);
            } else {
                bytes = bb.array();
                offset = bb.arrayOffset() + bb.position();
                length = bb.remaining();
            }
            builder.append(new String(bytes, offset, length, charset));
        }
        return builder.toString();
    }
}
