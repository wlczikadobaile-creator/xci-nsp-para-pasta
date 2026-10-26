package com.nsxpasta.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Acesso aleatório somente leitura a um bloco de bytes (arquivo, partição, seção decifrada...). */
interface Storage {
    val size: Long

    fun read(pos: Long, dst: ByteArray, off: Int, len: Int)

    fun readBytes(pos: Long, len: Int): ByteArray = ByteArray(len).also { read(pos, it, 0, len) }

    fun slice(offset: Long, length: Long): Storage = SubStorage(this, offset, length)
}

internal fun checkRange(pos: Long, len: Int, size: Long) {
    if (pos < 0 || len < 0 || pos + len > size) {
        throw IOException("Leitura fora dos limites (pos=$pos len=$len tamanho=$size) - arquivo truncado ou corrompido?")
    }
}

class ChannelStorage(private val channel: FileChannel) : Storage {
    override val size: Long = channel.size()

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
        checkRange(pos, len, size)
        val bb = ByteBuffer.wrap(dst, off, len)
        var p = pos
        while (bb.hasRemaining()) {
            val n = channel.read(bb, p)
            if (n < 0) throw IOException("Fim inesperado do arquivo em $p")
            p += n
        }
    }
}

class ByteArrayStorage(private val data: ByteArray) : Storage {
    override val size: Long = data.size.toLong()

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
        checkRange(pos, len, size)
        System.arraycopy(data, pos.toInt(), dst, off, len)
    }
}

class SubStorage(private val parent: Storage, private val base: Long, override val size: Long) : Storage {
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
        checkRange(pos, len, size)
        parent.read(base + pos, dst, off, len)
    }
}

// Leitura little-endian, como no Switch.
fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF
fun ByteArray.u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
fun ByteArray.u32(o: Int): Long = (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)
fun ByteArray.u64(o: Int): Long = u32(o) or (u32(o + 4) shl 32)

fun ByteArray.ascii(o: Int, len: Int): String = String(this, o, len, Charsets.US_ASCII)

/** Lê uma string terminada em zero (tabelas de nomes PFS0/HFS0). */
fun ByteArray.cString(o: Int): String {
    var end = o
    while (end < size && this[end] != 0.toByte()) end++
    return String(this, o, end - o, Charsets.UTF_8)
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    val s = trim()
    require(s.length % 2 == 0) { "hex com tamanho ímpar" }
    return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.putBE64(o: Int, v: Long) {
    for (i in 0 until 8) this[o + i] = (v ushr (56 - 8 * i)).toByte()
}
