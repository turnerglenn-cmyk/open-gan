package app.opengan.core.gan

/**
 * BLE identifiers and keys for GAN smart cubes.
 *
 * Values from gan-web-bluetooth by Andy Fedotov (MIT License),
 * https://github.com/afedotov/gan-web-bluetooth
 */
object GanDefinitions {
    const val GEN2_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dc4179"
    const val GEN2_COMMAND = "28be4a4a-cd67-11e9-a32f-2a2ae2dbcce4"
    const val GEN2_STATE = "28be4cb6-cd67-11e9-a32f-2a2ae2dbcce4"

    const val GEN3_SERVICE = "8653000a-43e6-47b7-9cb0-5fc21d4ae340"
    const val GEN3_COMMAND = "8653000c-43e6-47b7-9cb0-5fc21d4ae340"
    const val GEN3_STATE = "8653000b-43e6-47b7-9cb0-5fc21d4ae340"

    const val GEN4_SERVICE = "00000010-0000-fff7-fff6-fff5fff4fff0"
    const val GEN4_COMMAND = "0000fff5-0000-1000-8000-00805f9b34fb"
    const val GEN4_STATE = "0000fff6-0000-1000-8000-00805f9b34fb"

    /** Advertised name prefixes of supported cubes. */
    val CUBE_NAME_PREFIXES = listOf("GAN", "MG", "AiCube")

    /** Company identifier codes used in GAN manufacturer data: 0x0001, 0x0101, ... 0xFF01. */
    val CIC_LIST: List<Int> = List(256) { (it shl 8) or 0x01 }

    /** Key used by GAN Gen2, Gen3 and Gen4 cubes. */
    val KEY_GAN = Key(
        bytes(0x01, 0x02, 0x42, 0x28, 0x31, 0x91, 0x16, 0x07, 0x20, 0x05, 0x18, 0x54, 0x42, 0x11, 0x12, 0x53),
        bytes(0x11, 0x03, 0x32, 0x28, 0x21, 0x01, 0x76, 0x27, 0x20, 0x95, 0x78, 0x14, 0x32, 0x12, 0x02, 0x43),
    )

    /** Key used by MoYu AI 2023 (Gen2 protocol, name prefix "AiCube"). */
    val KEY_MOYU_AI = Key(
        bytes(0x05, 0x12, 0x02, 0x45, 0x02, 0x01, 0x29, 0x56, 0x12, 0x78, 0x12, 0x76, 0x81, 0x01, 0x08, 0x03),
        bytes(0x01, 0x44, 0x28, 0x06, 0x86, 0x21, 0x22, 0x28, 0x51, 0x05, 0x08, 0x31, 0x82, 0x02, 0x21, 0x06),
    )

    class Key(val key: ByteArray, val iv: ByteArray)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /**
     * Extract the cube MAC address from manufacturer specific data. The MAC is
     * stored reversed in the last 6 of the first 9 data bytes.
     */
    fun macFromManufacturerData(data: ByteArray): String? {
        if (data.size < 6) return null
        val view = data.copyOfRange(0, minOf(9, data.size))
        return (1..6).joinToString(":") { "%02X".format(view[view.size - it].toInt() and 0xFF) }
    }

    /** Salt for the encryption key: MAC address bytes in reverse order. */
    fun saltFromMac(mac: String): ByteArray {
        val parts = mac.split(Regex("[:\\-\\s]+")).map { it.toInt(16).toByte() }
        require(parts.size == 6) { "MAC must have 6 bytes: $mac" }
        return parts.reversed().toByteArray()
    }
}
