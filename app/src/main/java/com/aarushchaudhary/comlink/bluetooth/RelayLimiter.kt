package com.aarushchaudhary.comlink.bluetooth

/**
 * Caps how many envelopes we relay for each neighbour: at most [maxPerWindow] per [windowMs]
 * (fixed windows). One nearby phone can then no longer make the whole mesh repeat a flood.
 *
 * 20 per 10 s is 2 envelopes a second, each up to 100 KiB, which is more than BLE carries, so
 * normal relaying never hits it. Only relaying is limited; messages for us are not.
 * Thread-safe, no Android imports.
 */
class RelayLimiter(
    private val maxPerWindow: Int = 20,
    private val windowMs: Long = 10_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Window(var start: Long, var count: Int)

    private val windows = HashMap<String, Window>()

    /** True if an envelope from [neighbour] may be relayed now (and counts it). */
    @Synchronized
    fun allow(neighbour: String): Boolean {
        val now = clock()
        if (windows.size > 256) {
            // Forget neighbours whose window ended, so the map can't grow without bound
            windows.values.removeAll { now - it.start >= windowMs }
        }
        val window = windows.getOrPut(neighbour) { Window(now, 0) }
        if (now - window.start >= windowMs) {
            window.start = now
            window.count = 0
        }
        if (window.count >= maxPerWindow) return false
        window.count++
        return true
    }
}
