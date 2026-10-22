package app.opengan.core.gan

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption used by GAN Gen2/Gen3/Gen4 cubes: AES-128-CBC applied to the
 * first 16 bytes and to the last 16 bytes of the message, each with a fresh IV.
 * Key and IV are salted with the cube's MAC address.
 *
 * Port of gan-web-bluetooth's GanGen2CubeEncrypter (MIT License).
 */
class GanEncrypter(key: ByteArray, iv: ByteArray, salt: ByteArray) {
    private val key: ByteArray
    private val iv: ByteArray

    init {
        require(key.size == 16) { "Key must be 16 bytes" }
        require(iv.size == 16) { "IV must be 16 bytes" }
        require(salt.size == 6) { "Salt must be 6 bytes" }
        this.key = key.copyOf()
        this.iv = iv.copyOf()
        for (i in 0 until 6) {
            // Upstream uses modulo 0xFF (not 0x100); the cubes expect exactly this.
            this.key[i] = (((key[i].toInt() and 0xFF) + (salt[i].toInt() and 0xFF)) % 0xFF).toByte()
            this.iv[i] = (((iv[i].toInt() and 0xFF) + (salt[i].toInt() and 0xFF)) % 0xFF).toByte()
        }
    }

    constructor(key: GanDefinitions.Key, salt: ByteArray) : this(key.key, key.iv, salt)

    fun encrypt(data: ByteArray): ByteArray {
        require(data.size >= 16) { "Data must be at least 16 bytes" }
        val res = data.copyOf()
        chunk(res, 0, Cipher.ENCRYPT_MODE)
        if (res.size > 16) chunk(res, res.size - 16, Cipher.ENCRYPT_MODE)
        return res
    }

    fun decrypt(data: ByteArray): ByteArray {
        require(data.size >= 16) { "Data must be at least 16 bytes" }
        val res = data.copyOf()
        if (res.size > 16) chunk(res, res.size - 16, Cipher.DECRYPT_MODE)
        chunk(res, 0, Cipher.DECRYPT_MODE)
        return res
    }

    private fun chunk(buf: ByteArray, offset: Int, mode: Int) {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val out = cipher.doFinal(buf, offset, 16)
        out.copyInto(buf, offset)
    }
}
