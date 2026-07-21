package io.github.weg2022.strguard

import java.nio.charset.StandardCharsets

internal class BoundedLogBuffer(private val capacity: Int) {
    private val bytes = ByteArray(capacity)
    private var start = 0
    private var size = 0

    init {
        require(capacity > 0) { "Log buffer capacity must be positive" }
    }

    @Synchronized
    fun append(source: ByteArray, offset: Int, length: Int) {
        if (length >= capacity) {
            source.copyInto(bytes, 0, offset + length - capacity, offset + length)
            start = 0
            size = capacity
            return
        }
        repeat(length) { index ->
            bytes[(start + size) % capacity] = source[offset + index]
            if (size < capacity) {
                size++
            } else {
                start = (start + 1) % capacity
            }
        }
    }

    @Synchronized
    fun text(): String {
        val ordered = ByteArray(size)
        repeat(size) { index -> ordered[index] = bytes[(start + index) % capacity] }
        return ordered.toString(StandardCharsets.UTF_8)
    }
}
