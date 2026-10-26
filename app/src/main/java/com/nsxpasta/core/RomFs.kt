package com.nsxpasta.core

import java.io.IOException

class RomFs(private val storage: Storage) {
    private val dirMeta: ByteArray
    private val fileMeta: ByteArray
    private val dataOffset: Long

    init {
        val h = storage.readBytes(0, 0x50)
        if (h.u64(0) != 0x50L) throw IOException("Cabeçalho RomFS inválido (chave errada?)")
        val dirMetaSize = h.u64(0x20)
        val fileMetaSize = h.u64(0x40)
        if (dirMetaSize > 256L shl 20 || fileMetaSize > 256L shl 20) throw IOException("Tabelas RomFS grandes demais")
        dirMeta = storage.readBytes(h.u64(0x18), dirMetaSize.toInt())
        fileMeta = storage.readBytes(h.u64(0x38), fileMetaSize.toInt())
        dataOffset = h.u64(0x48)
    }

    interface Visitor {
        fun enterDir(name: String)
        fun leaveDir()
        fun file(name: String, data: Storage)
    }

    fun walk(v: Visitor) = walkDir(0, v, 0)

    private fun walkDir(dirOff: Int, v: Visitor, depth: Int) {
        if (depth > 64) throw IOException("RomFS com diretórios aninhados demais")
        var f = dirMeta.u32(dirOff + 0x0C)
        while (f != EMPTY) {
            val o = f.toInt()
            val name = String(fileMeta, o + 0x20, fileMeta.u32(o + 0x1C).toInt(), Charsets.UTF_8)
            v.file(name, storage.slice(dataOffset + fileMeta.u64(o + 0x08), fileMeta.u64(o + 0x10)))
            f = fileMeta.u32(o + 0x04)
        }
        var d = dirMeta.u32(dirOff + 0x08)
        while (d != EMPTY) {
            val o = d.toInt()
            val name = String(dirMeta, o + 0x18, dirMeta.u32(o + 0x14).toInt(), Charsets.UTF_8)
            v.enterDir(name)
            walkDir(o, v, depth + 1)
            v.leaveDir()
            d = dirMeta.u32(o + 0x04)
        }
    }

    companion object {
        const val EMPTY = 0xFFFFFFFFL
    }
}
