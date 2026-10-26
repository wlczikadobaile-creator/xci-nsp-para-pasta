package com.nsxpasta.core

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    fun ecbDecrypt(key: ByteArray, data: ByteArray): ByteArray = ecb(Cipher.DECRYPT_MODE, key).doFinal(data)

    fun ecbEncrypt(key: ByteArray, data: ByteArray): ByteArray = ecb(Cipher.ENCRYPT_MODE, key).doFinal(data)

    private fun ecb(mode: Int, key: ByteArray): Cipher =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(mode, SecretKeySpec(key, "AES")) }

    /** Tweak no estilo Nintendo: número do setor em big-endian nos 16 bytes (igual ao get_tweak do hactool). */
    fun nintendoTweak(sector: Long): ByteArray = ByteArray(16).also { it.putBE64(8, sector) }

    fun xtsDecrypt(key: ByteArray, data: ByteArray, firstSector: Long, sectorSize: Int): ByteArray =
        xts(false, key, data, firstSector, sectorSize)

    fun xtsEncrypt(key: ByteArray, data: ByteArray, firstSector: Long, sectorSize: Int): ByteArray =
        xts(true, key, data, firstSector, sectorSize)

    private fun xts(encrypt: Boolean, key: ByteArray, data: ByteArray, firstSector: Long, sectorSize: Int): ByteArray {
        require(key.size == 32) { "chave XTS precisa ter 32 bytes" }
        require(data.size % sectorSize == 0)
        val out = ByteArray(data.size)
        var sector = firstSector
        var off = 0
        while (off < data.size) {
            xtsUnit(encrypt, key, nintendoTweak(sector), data, off, sectorSize, out)
            off += sectorSize
            sector++
        }
        return out
    }

    /** Processa uma unidade de dados XTS (AES-128) com o tweak já montado. */
    fun xtsUnit(encrypt: Boolean, key: ByteArray, tweak: ByteArray, src: ByteArray, off: Int, len: Int, dst: ByteArray) {
        require(len % 16 == 0)
        val dataCipher = ecb(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, key.copyOfRange(0, 16))
        val t = ecb(Cipher.ENCRYPT_MODE, key.copyOfRange(16, 32)).doFinal(tweak)
        val block = ByteArray(16)
        var p = off
        while (p < off + len) {
            for (i in 0 until 16) block[i] = (src[p + i].toInt() xor t[i].toInt()).toByte()
            val r = dataCipher.doFinal(block)
            for (i in 0 until 16) dst[p + i] = (r[i].toInt() xor t[i].toInt()).toByte()
            mulAlpha(t)
            p += 16
        }
    }

    private fun mulAlpha(t: ByteArray) {
        var carry = 0
        for (i in 0 until 16) {
            val b = t[i].toInt() and 0xFF
            t[i] = ((b shl 1) or carry).toByte()
            carry = b ushr 7
        }
        if (carry != 0) t[0] = (t[0].toInt() xor 0x87).toByte()
    }
}

/**
 * Seção AES-CTR de uma NCA. O contador é: 8 bytes do "section_ctr" (big-endian) + offset absoluto na NCA / 16.
 * Como CTR é simétrico, a mesma classe também cifra (usada nos testes).
 */
class CtrStorage(
    private val nca: Storage,
    private val sectionOffset: Long,
    override val size: Long,
    key: ByteArray,
    private val upperCounter: Long,
) : Storage {
    private val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    private val keySpec = SecretKeySpec(key, "AES")
    private var tmp = ByteArray(0)

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
        checkRange(pos, len, size)
        if (len == 0) return
        val abs = sectionOffset + pos
        val aligned = abs and 15L.inv()
        val skip = (abs - aligned).toInt()
        val total = skip + len
        if (tmp.size < total) tmp = ByteArray(total)
        nca.read(aligned, tmp, 0, total)
        val iv = ByteArray(16)
        iv.putBE64(0, upperCounter)
        iv.putBE64(8, aligned ushr 4)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
        cipher.doFinal(tmp, 0, total, tmp, 0)
        System.arraycopy(tmp, skip, dst, off, len)
    }
}
