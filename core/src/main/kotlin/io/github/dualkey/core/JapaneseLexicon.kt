package io.github.dualkey.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.PriorityQueue

data class LexiconWord(val text: String, val left: Int, val right: Int, val cost: Int)
data class LexiconMatch(val end: Int, val word: LexiconWord, val unknown: Boolean = false)

/** Read-only DKL1 index. Absolute buffer reads make the mapped lexicon shareable. */
class JapaneseLexicon private constructor(private val data: ByteBuffer) {
    val readingCount = data.getInt(8)
    val entryCount = data.getInt(12)
    private val dimension = data.getInt(16)
    private val maxLength = data.getInt(20)
    private val keys = data.getInt(24)
    private val entries = data.getInt(28)
    private val strings = data.getInt(32)
    private val matrix = data.getInt(40)
    private val tree = data.getInt(44)
    private val treeBase = data.getInt(48)

    init {
        require(data.getInt(0) == 0x314c4b44 && data.getInt(4) == 1) { "Unsupported Japanese dictionary" }
        require(readingCount in 1..2_000_000 && entryCount in 1..4_000_000 && dimension in 1..8192)
        require(maxLength in 1..256 && treeBase >= readingCount && treeBase and (treeBase - 1) == 0)
        require(keys == 64 && entries.toLong() == keys + readingCount.toLong() * 12)
        require(strings.toLong() == entries + entryCount.toLong() * 12)
        require(matrix.toLong() == strings + data.getInt(36).toLong())
        require(tree.toLong() == matrix + dimension.toLong() * dimension * 2)
        require(data.getInt(52).toLong() == tree + treeBase.toLong() * 8 && data.getInt(52) == data.limit())
    }

    private fun short(offset: Int) = data.getShort(offset).toInt() and 0xffff
    private fun decode(offset: Int, length: Int): String {
        val bytes = ByteArray(length)
        data.duplicate().apply { position(strings + offset); get(bytes) }
        return String(bytes, Charsets.UTF_8)
    }
    fun reading(group: Int): String = decode(data.getInt(keys + group * 12), short(keys + group * 12 + 4))
    fun words(group: Int, limit: Int = 256): List<LexiconWord> {
        val offset = keys + group * 12
        val first = data.getInt(offset + 8)
        return (first until first + minOf(short(offset + 6), limit)).map { index ->
            val p = entries + index * 12
            LexiconWord(decode(data.getInt(p), short(p + 4)), short(p + 6), short(p + 8), data.getShort(p + 10).toInt())
        }
    }
    fun transition(right: Int, left: Int): Int = short(matrix + (right * dimension + left) * 2)

    // Compare just the prefix: all completions compare equal to the query bytes.
    private fun comparePrefix(group: Int, query: ByteArray): Int {
        val p = keys + group * 12
        val length = short(p + 4)
        val offset = strings + data.getInt(p)
        for (i in 0 until minOf(length, query.size)) {
            val difference = (data.get(offset + i).toInt() and 255) - (query[i].toInt() and 255)
            if (difference != 0) return difference
        }
        return if (length < query.size) -1 else 0
    }
    private fun range(query: ByteArray, from: Int = 0, until: Int = readingCount): IntRange {
        var low = from; var high = until
        while (low < high) {
            val mid = (low + high) ushr 1
            if (comparePrefix(mid, query) < 0) low = mid + 1 else high = mid
        }
        val start = low; high = until
        while (low < high) {
            val mid = (low + high) ushr 1
            if (comparePrefix(mid, query) <= 0) low = mid + 1 else high = mid
        }
        return start until low
    }
    fun exact(reading: String): List<LexiconWord> {
        val bytes = reading.toByteArray(Charsets.UTF_8)
        val matches = range(bytes)
        return if (!matches.isEmpty() && short(keys + matches.first * 12 + 4) == bytes.size) words(matches.first) else emptyList()
    }
    fun prefixes(input: String, start: Int): List<LexiconMatch> {
        val result = ArrayList<LexiconMatch>()
        var matches = 0 until readingCount
        var end = start
        while (end < input.length && end - start < maxLength) {
            end += Character.charCount(Character.codePointAt(input, end))
            val bytes = input.substring(start, end).toByteArray(Charsets.UTF_8)
            matches = range(bytes, matches.first, matches.last + 1)
            if (matches.isEmpty()) break
            if (short(keys + matches.first * 12 + 4) == bytes.size) {
                words(matches.first).forEach { result.add(LexiconMatch(end, it)) }
            }
        }
        return result
    }

    /** Retrieve likely completions in O(limit * log(dictionary size)), including short prefixes. */
    fun predictiveGroups(prefix: String, limit: Int): List<Int> {
        if (prefix.isEmpty()) return emptyList()
        val matches = range(prefix.toByteArray(Charsets.UTF_8))
        if (matches.isEmpty()) return emptyList()
        val queue = PriorityQueue<Int>(compareBy<Int> { data.getInt(tree + it * 4) }.thenBy { it })
        var low = treeBase + matches.first
        var high = treeBase + matches.last + 1
        while (low < high) {
            if (low and 1 == 1) queue.add(low++)
            if (high and 1 == 1) queue.add(--high)
            low = low ushr 1; high = high ushr 1
        }
        val result = ArrayList<Int>()
        while (queue.isNotEmpty() && result.size < limit) {
            val node = queue.remove()
            if (node >= treeBase) result.add(node - treeBase)
            else { queue.add(node * 2); queue.add(node * 2 + 1) }
        }
        return result
    }

    companion object {
        fun open(file: File): JapaneseLexicon = RandomAccessFile(file, "r").use { access ->
            require(access.length() in 64..150_000_000)
            JapaneseLexicon(access.channel.map(FileChannel.MapMode.READ_ONLY, 0, access.length()).order(ByteOrder.LITTLE_ENDIAN))
        }
    }
}
