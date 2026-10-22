package app.opengan.core.gan

/** Reads arbitrary bit-aligned words from a protocol message, MSB first. */
internal class BitView(private val bytes: ByteArray) {

    private fun bit(i: Int): Int = (bytes[i / 8].toInt() shr (7 - i % 8)) and 1

    /** Words up to 8 bits are read MSB-first; 16/32-bit words are read byte-wise with the given endianness. */
    fun word(startBit: Int, bitLength: Int, littleEndian: Boolean = false): Long = when {
        bitLength <= 8 -> bits(startBit, bitLength).toLong()
        bitLength == 16 || bitLength == 32 -> {
            val n = bitLength / 8
            val b = IntArray(n) { bits(startBit + 8 * it, 8) }
            val ordered = if (littleEndian) b.reversed() else b.toList()
            ordered.fold(0L) { acc, v -> (acc shl 8) or v.toLong() }
        }
        else -> throw IllegalArgumentException("Unsupported bit word length $bitLength")
    }

    fun int(startBit: Int, bitLength: Int, littleEndian: Boolean = false): Int =
        word(startBit, bitLength, littleEndian).toInt()

    private fun bits(start: Int, len: Int): Int {
        var v = 0
        for (i in start until start + len) v = (v shl 1) or bit(i)
        return v
    }
}
