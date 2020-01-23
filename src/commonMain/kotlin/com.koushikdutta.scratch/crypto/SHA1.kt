package com.koushikdutta.scratch.crypto;

import kotlin.math.min

private inline fun Int.ext8(offset: Int) = (this ushr offset) and 0xFF

private fun Int.rotateRight(amount: Int): Int = (this ushr amount) or (this shl (32 - amount))
private fun Int.rotateLeft(bits: Int): Int = ((this shl bits) or (this ushr (32 - bits)))

private fun arraycopy(src: ByteArray, srcPos: Int, dst: ByteArray, dstPos: Int, count: Int) = src.copyInto(dst, dstPos, srcPos, srcPos + count)
private fun arraycopy(src: IntArray, srcPos: Int, dst: IntArray, dstPos: Int, count: Int) = src.copyInto(dst, dstPos, srcPos, srcPos + count)

private fun ByteArray.readU8(o: Int): Int = this[o].toInt() and 0xFF
private fun ByteArray.readS32_be(o: Int): Int =
        (readU8(o + 3) shl 0) or (readU8(o + 2) shl 8) or (readU8(o + 1) shl 16) or (readU8(o + 0) shl 24)

open class HashFactory(val create: () -> Hash) {
    fun digest(data: ByteArray) = create().also { it.update(data, 0, data.size) }.digest()
}

abstract class Hash(val chunkSize: Int, val digestSize: Int) {
    private val chunk = ByteArray(chunkSize)
    private var writtenInChunk = 0
    private var totalWritten = 0L

    fun reset(): Hash {
        coreReset()
        return this
    }

    fun update(data: ByteArray, offset: Int, count: Int): Hash {
        var curr = offset
        var left = count
        while (left > 0) {
            val remainingInChunk = chunkSize - writtenInChunk
            val toRead = min(remainingInChunk, left)
            arraycopy(data, curr, chunk, writtenInChunk, toRead)
            left -= toRead
            curr += toRead
            writtenInChunk += toRead
            if (writtenInChunk >= chunkSize) {
                writtenInChunk -= chunkSize
                coreUpdate(chunk)
            }
        }
        totalWritten += count
        return this
    }

    fun digestOut(out: ByteArray) {
        val pad = corePadding(totalWritten)
        var padPos = 0
        while (padPos < pad.size) {
            val padSize = chunkSize - writtenInChunk
            arraycopy(pad, padPos, chunk, writtenInChunk, padSize)
            coreUpdate(chunk)
            writtenInChunk = 0
            padPos += padSize
        }

        coreDigest(out)
        coreReset()
    }

    protected abstract fun coreReset()
    protected abstract fun corePadding(totalWritten: Long): ByteArray
    protected abstract fun coreUpdate(chunk: ByteArray)
    protected abstract fun coreDigest(out: ByteArray)

    fun update(data: ByteArray) = update(data, 0, data.size)
    fun digest(): ByteArray = ByteArray(digestSize).also { digestOut(it) }
}

fun ByteArray.hash(algo: HashFactory): ByteArray = algo.digest(this)

abstract class SHA(chunkSize: Int, digestSize: Int) : Hash(chunkSize, digestSize) {
    override fun corePadding(totalWritten: Long): ByteArray {
        val tail = totalWritten % 64
        val padding = (if (64 - tail >= 9) 64 - tail else 128 - tail)
        val pad = ByteArray(padding.toInt()).apply { this[0] = 0x80.toByte() }
        val bits = (totalWritten * 8)
        for (i in 0 until 8) pad[pad.size - 1 - i] = ((bits ushr (8 * i)) and 0xFF).toByte()
        return pad
    }
}

class SHA1 : SHA(chunkSize = 64, digestSize = 20) {
    companion object : HashFactory({ SHA1() }) {
        private val H = intArrayOf(
            0x67452301L.toInt(),
            0xEFCDAB89L.toInt(),
            0x98BADCFEL.toInt(),
            0x10325476L.toInt(),
            0xC3D2E1F0L.toInt()
        )

        private const val K0020: Int = 0x5A827999L.toInt()
        private const val K2040: Int = 0x6ED9EBA1L.toInt()
        private const val K4060: Int = 0x8F1BBCDCL.toInt()
        private const val K6080: Int = 0xCA62C1D6L.toInt()
    }

    private val w = IntArray(80)
    private val h = IntArray(5)

    override fun coreReset(): Unit = run { arraycopy(H, 0, h, 0, 5) }

    init {
        coreReset()
    }

    override fun coreUpdate(chunk: ByteArray) {
        for (j in 0 until 16) w[j] = chunk.readS32_be(j * 4)
        for (j in 16 until 80) w[j] = (w[j - 3] xor w[j - 8] xor w[j - 14] xor w[j - 16]).rotateLeft(1)

        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]

        for (j in 0 until 80) {
            val temp = a.rotateLeft(5) + e + w[j] + when (j / 20) {
                0 -> ((b and c) or ((b.inv()) and d)) + K0020
                1 -> (b xor c xor d) + K2040
                2 -> ((b and c) xor (b and d) xor (c and d)) + K4060
                else -> (b xor c xor d) + K6080
            }

            e = d
            d = c
            c = b.rotateLeft(30)
            b = a
            a = temp
        }

        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
    }

    override fun coreDigest(out: ByteArray) {
        for (n in out.indices) out[n] = (h[n / 4] ushr (24 - 8 * (n % 4))).toByte()
    }
}

fun ByteArray.sha1() = hash(SHA1)