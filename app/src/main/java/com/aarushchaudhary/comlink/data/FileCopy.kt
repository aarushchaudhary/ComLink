package com.aarushchaudhary.comlink.data

import java.io.InputStream
import java.io.OutputStream

/** Streams a file in 64 KB chunks, so even a 10 MB video never sits in memory whole. No Android imports. */
object FileCopy {
    private const val BUFFER_SIZE = 64 * 1024

    /** Copies everything from [input] to [output] and returns the number of bytes copied. */
    fun copy(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            total += n
        }
        output.flush()
        return total
    }

    /** A name safe to suggest in the system "Save as" screen: no folder separators, never blank. */
    fun suggestedName(original: String?): String {
        val cleaned = original.orEmpty().replace('/', '_').replace('\\', '_').trim()
        return cleaned.ifEmpty { "comlink_file" }.take(120)
    }
}
