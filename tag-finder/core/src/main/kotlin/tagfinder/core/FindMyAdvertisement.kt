package tagfinder.core

/**
 * Apple "Find My" (offline finding) BLE advertisement, parsed from manufacturer-specific
 * data for company ID 0x004C. Apple packs type-length-value records; Find My is type 0x12.
 *
 * Layout as documented by public reverse-engineering work (OpenHaystack, AirGuard):
 *  - separated from owner: 25-byte payload = status, 22 bytes of the rotating public key,
 *    key bits byte, hint byte.
 *  - near the owner: 2-byte payload (status, hint); the key lives only in the BLE address.
 *
 * The status byte's top two bits are the battery level. The device-type bits are treated
 * as a hint only, because the exact encoding is not officially documented.
 */
data class FindMyAdvertisement(
    val status: Int,
    val separated: Boolean,
    val keyFragment: ByteArray,
) {
    enum class Battery { FULL, MEDIUM, LOW, CRITICAL }

    val battery: Battery get() = Battery.entries[(status shr 6) and 0x3]

    /** Bits 4–5 of the status byte; reverse-engineered value 1 is commonly reported for AirTags. */
    val typeHint: Int get() = (status shr 4) and 0x3

    val probablyAirTag: Boolean get() = typeHint == 1

    override fun equals(other: Any?): Boolean =
        other is FindMyAdvertisement && status == other.status && separated == other.separated &&
            keyFragment.contentEquals(other.keyFragment)

    override fun hashCode(): Int = 31 * (31 * status + separated.hashCode()) + keyFragment.contentHashCode()

    companion object {
        const val APPLE_COMPANY_ID = 0x004C
        const val TYPE_FIND_MY = 0x12

        /** [data] is the manufacturer data *after* the 2-byte company identifier. */
        fun parse(data: ByteArray?): FindMyAdvertisement? {
            if (data == null) return null
            var i = 0
            while (i + 1 < data.size) {
                val type = data[i].toInt() and 0xFF
                val len = data[i + 1].toInt() and 0xFF
                val start = i + 2
                val end = start + len
                if (end > data.size) return null
                if (type == TYPE_FIND_MY && len >= 1) {
                    val payload = data.copyOfRange(start, end)
                    val separated = len >= 25
                    val key = if (separated) payload.copyOfRange(1, 23) else ByteArray(0)
                    return FindMyAdvertisement(payload[0].toInt() and 0xFF, separated, key)
                }
                i = end
            }
            return null
        }
    }
}
