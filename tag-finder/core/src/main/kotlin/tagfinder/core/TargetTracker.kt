package tagfinder.core

import kotlin.math.abs

/**
 * Follows one Find My device even though it periodically changes its BLE address (and
 * public key) for privacy. Nothing in the packet links the old and new identity, so we use
 * a continuity heuristic: the old address goes silent, a never-before-seen address of the
 * same kind appears shortly after with a similar signal strength, and it is the only such
 * candidate. Ambiguous cases are not switched; the user can re-pick the device.
 */
class TargetTracker(
    initialAddress: String,
    private val handoverWindowMs: Long = 90_000,
    private val silenceBeforeHandoverMs: Long = 8_000,
    private val maxRssiJumpDb: Double = 12.0,
) {
    var address: String = initialAddress
        private set
    var handovers = 0
        private set

    private var lastSeenMs = Long.MIN_VALUE
    private var lastRssi = Double.NaN
    private var lastSeparated: Boolean? = null
    private val firstSeen = HashMap<String, Long>()
    private val candidates = HashMap<String, Pair<Long, Double>>()

    /** Returns true if this advertisement belongs to the tracked device. */
    fun accept(address: String, rssi: Double, ad: FindMyAdvertisement, timeMs: Long): Boolean {
        firstSeen.putIfAbsent(address, timeMs)
        if (address == this.address) {
            remember(rssi, ad, timeMs)
            candidates.clear()
            return true
        }
        if (lastSeenMs == Long.MIN_VALUE) return false
        val silentFor = timeMs - lastSeenMs
        val isNew = (firstSeen[address] ?: timeMs) > lastSeenMs
        val sameKind = lastSeparated == null || lastSeparated == ad.separated
        if (isNew && sameKind && silentFor in 0..handoverWindowMs &&
            abs(rssi - lastRssi) <= maxRssiJumpDb
        ) {
            candidates[address] = timeMs to rssi
        }
        candidates.entries.removeIf { timeMs - it.value.first > 10_000 }
        if (silentFor >= silenceBeforeHandoverMs && candidates.size == 1 && candidates.containsKey(address)) {
            this.address = address
            handovers++
            candidates.clear()
            remember(rssi, ad, timeMs)
            return true
        }
        return false
    }

    private fun remember(rssi: Double, ad: FindMyAdvertisement, timeMs: Long) {
        lastSeenMs = timeMs
        lastRssi = rssi
        lastSeparated = ad.separated
    }
}
