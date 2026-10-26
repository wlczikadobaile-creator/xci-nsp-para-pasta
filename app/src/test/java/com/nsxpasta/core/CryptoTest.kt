package com.nsxpasta.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CryptoTest {
    private fun xtsVector(key: String, tweakLe: ByteArray, plain: String, cipher: String) {
        val k = key.hexToBytes()
        val p = plain.hexToBytes()
        val out = ByteArray(p.size)
        Crypto.xtsUnit(true, k, tweakLe, p, 0, p.size, out)
        assertEquals(cipher, out.toHex())
        val back = ByteArray(p.size)
        Crypto.xtsUnit(false, k, tweakLe, out, 0, out.size, back)
        assertArrayEquals(p, back)
    }

    @Test fun ieee1619Vector1() = xtsVector(
        "00".repeat(32), ByteArray(16), "00".repeat(32),
        "917cf69ebd68b2ec9b9fe9a3eadda692cd43d2f59598ed858c02c2652fbf922e",
    )

    @Test fun ieee1619Vector2() = xtsVector(
        "11".repeat(16) + "22".repeat(16), ByteArray(16).also { for (i in 0 until 5) it[i] = 0x33 },
        "44".repeat(32),
        "c454185e6a16936e39334038acef838bfb186fff7480adc4289382ecd6d394f0",
    )

    @Test fun nintendoTweakIsBigEndian() {
        assertEquals("00000000000000000000000000000102", Crypto.nintendoTweak(0x102).toHex())
    }
}
