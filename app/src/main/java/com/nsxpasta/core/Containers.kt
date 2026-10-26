package com.nsxpasta.core

import java.io.IOException

class ContainerEntry(val name: String, val offset: Long, val size: Long)

/**
 * PFS0 (NSP, ExeFS) e HFS0 (partições de XCI) têm o mesmo formato, só muda o tamanho da entrada:
 * PFS0 = 0x18 bytes, HFS0 = 0x40 bytes (inclui hash).
 */
open class PartitionFs(private val storage: Storage, magic: String, entrySize: Int) {
    val entries: List<ContainerEntry>
    private val dataStart: Long

    init {
        val hdr = storage.readBytes(0, 0x10)
        val m = hdr.ascii(0, 4)
        if (m != magic) throw IOException("Esperado $magic, encontrado \"$m\"")
        val count = hdr.u32(4)
        val strSize = hdr.u32(8)
        if (count > 100_000 || strSize > 16 * 1024 * 1024) throw IOException("Cabeçalho $magic inválido")
        val tableSize = (count * entrySize).toInt()
        val table = storage.readBytes(0x10, tableSize + strSize.toInt())
        dataStart = 0x10L + tableSize + strSize
        entries = (0 until count.toInt()).map { i ->
            val e = i * entrySize
            ContainerEntry(
                name = table.cString(tableSize + table.u32(e + 0x10).toInt()),
                offset = table.u64(e),
                size = table.u64(e + 8),
            )
        }
    }

    fun open(e: ContainerEntry): Storage = storage.slice(dataStart + e.offset, e.size)
}

class Pfs0(storage: Storage) : PartitionFs(storage, "PFS0", 0x18)

class Hfs0(storage: Storage) : PartitionFs(storage, "HFS0", 0x40)

object Xci {
    /** Retorna a partição "secure", onde ficam as NCAs do jogo. */
    fun securePartition(s: Storage): Hfs0 {
        // Alguns dumps completos têm a "key area" de 0x1000 bytes antes do cabeçalho.
        val base = when {
            s.size >= 0x200 && s.readBytes(0x100, 4).ascii(0, 4) == "HEAD" -> 0L
            s.size >= 0x1200 && s.readBytes(0x1100, 4).ascii(0, 4) == "HEAD" -> 0x1000L
            else -> throw IOException("Não é um XCI válido (cabeçalho HEAD não encontrado)")
        }
        val hdr = s.readBytes(base + 0x100, 0x100)
        val rootOffset = hdr.u64(0x30)
        val rootSize = hdr.u64(0x38)
        val root = Hfs0(s.slice(base + rootOffset, minOf(rootSize, s.size - base - rootOffset)))
        val secure = root.entries.firstOrNull { it.name == "secure" }
            ?: throw IOException("Partição secure não encontrada no XCI")
        return Hfs0(root.open(secure))
    }

    fun isXci(s: Storage): Boolean =
        (s.size >= 0x200 && s.readBytes(0x100, 4).ascii(0, 4) == "HEAD") ||
            (s.size >= 0x1200 && s.readBytes(0x1100, 4).ascii(0, 4) == "HEAD")
}

class Ticket(val rightsId: ByteArray, val encryptedTitleKey: ByteArray, val titleKeyType: Int)

object Tickets {
    fun parse(data: ByteArray): Ticket {
        val (sigSize, pad) = when (data.u32(0)) {
            0x10000L, 0x10003L -> 0x200 to 0x3C
            0x10001L, 0x10004L -> 0x100 to 0x3C
            0x10002L, 0x10005L -> 0x3C to 0x40
            else -> throw IOException("Tipo de assinatura do ticket desconhecido")
        }
        val d = 4 + sigSize + pad
        return Ticket(
            rightsId = data.copyOfRange(d + 0x160, d + 0x170),
            encryptedTitleKey = data.copyOfRange(d + 0x40, d + 0x50),
            titleKeyType = data.u8(d + 0x141),
        )
    }
}
