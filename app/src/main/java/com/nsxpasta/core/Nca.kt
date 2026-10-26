package com.nsxpasta.core

import java.io.IOException

class NcaSection(
    val index: Int,
    /** Offset absoluto da seção dentro da NCA. */
    val offset: Long,
    val size: Long,
    /** 0 = RomFS, 1 = PartitionFS (PFS0). */
    val partitionType: Int,
    /** 2 = HierarchicalSha256, 3 = HierarchicalIntegrity (IVFC). */
    val hashType: Int,
    /** 1 = nenhuma, 2 = XTS, 3 = CTR, 4 = CTR-EX (BKTR/atualização), 5 = CTR (skip layer hash), 6 = CTR-EX. */
    val encryptionType: Int,
    val fsHeader: ByteArray,
) {
    val upperCounter: Long get() = fsHeader.u64(0x140)

    /** Região onde está o PFS0/RomFS de verdade, relativa ao início da seção (pula as tabelas de hash). */
    fun dataRegion(): Pair<Long, Long> {
        val sb = 0x08
        return when (hashType) {
            2 -> { // HierarchicalSha256: a última camada é o PFS0
                val layers = fsHeader.u32(sb + 0x24).toInt().coerceIn(1, 5)
                val r = sb + 0x28 + (layers - 1) * 0x10
                fsHeader.u64(r) to fsHeader.u64(r + 8)
            }
            3 -> { // IVFC: o nível 6 (índice 5) é o RomFS
                if (fsHeader.ascii(sb, 4) != "IVFC") throw IOException("Superbloco IVFC inválido")
                val levels = fsHeader.u32(sb + 0x0C).toInt()
                val lvl = (levels - 2).coerceIn(0, 5)
                val r = sb + 0x10 + lvl * 0x18
                fsHeader.u64(r) to fsHeader.u64(r + 8)
            }
            else -> 0L to size
        }
    }

    val isCompressed: Boolean get() = fsHeader.u64(0x178 + 8) != 0L
}

class Nca(private val raw: Storage, private val keys: KeySet, private val titleKeys: Map<String, ByteArray>) {
    val header: ByteArray

    init {
        val headerKey = keys.headerKey ?: throw IOException("header_key não encontrada no prod.keys")
        if (raw.size < 0xC00) throw IOException("NCA pequena demais")
        val enc = raw.readBytes(0, 0xC00)
        var dec = Crypto.xtsDecrypt(headerKey, enc, 0, 0x200)
        when (dec.ascii(0x200, 4)) {
            "NCA3" -> Unit
            "NCA2" -> {
                // NCA2: cada cabeçalho de seção é cifrado sozinho como setor 0.
                dec = Crypto.xtsDecrypt(headerKey, enc.copyOfRange(0, 0x400), 0, 0x200) +
                    (0 until 4).map { i ->
                        Crypto.xtsDecrypt(headerKey, enc.copyOfRange(0x400 + i * 0x200, 0x600 + i * 0x200), 0, 0x200)
                    }.reduce { a, b -> a + b }
            }
            "NCA0" -> throw IOException("NCA0 (formato pré-lançamento) não é suportado")
            else -> throw IOException("Não foi possível decifrar o cabeçalho NCA (header_key errada ou arquivo corrompido)")
        }
        header = dec
    }

    val contentType: Int get() = header.u8(0x205)
    val keyGeneration: Int get() = maxOf(header.u8(0x206), header.u8(0x220))
    val masterKeyRevision: Int get() = if (keyGeneration == 0) 0 else keyGeneration - 1
    private val keyAreaIndex: Int get() = header.u8(0x207)
    val programId: Long get() = header.u64(0x210)
    val rightsId: ByteArray get() = header.copyOfRange(0x230, 0x240)
    val hasRightsId: Boolean get() = rightsId.any { it != 0.toByte() }

    val contentTypeName: String
        get() = when (contentType) {
            0 -> "Program"; 1 -> "Meta"; 2 -> "Control"; 3 -> "Manual"; 4 -> "Data"; 5 -> "PublicData"
            else -> "Tipo $contentType"
        }

    val sections: List<NcaSection> = (0 until 4).mapNotNull { i ->
        val start = header.u32(0x240 + i * 0x10) * 0x200
        val end = header.u32(0x244 + i * 0x10) * 0x200
        if (end <= start) return@mapNotNull null
        val fs = header.copyOfRange(0x400 + i * 0x200, 0x600 + i * 0x200)
        NcaSection(i, start, end - start, fs.u8(2), fs.u8(3), fs.u8(4), fs)
    }

    private val ctrKey: ByteArray by lazy {
        if (hasRightsId) {
            val enc = titleKeys[rightsId.toHex()]
                ?: throw IOException("Ticket (.tik) com a title key não encontrado para rights id ${rightsId.toHex()}")
            val kek = keys.titleKek(masterKeyRevision)
                ?: throw IOException("titlekek_%02x não encontrada no prod.keys (firmware mais nova?)".format(masterKeyRevision))
            Crypto.ecbDecrypt(kek, enc)
        } else {
            val kak = keys.keyAreaKey(keyAreaIndex, masterKeyRevision)
                ?: throw IOException("key_area_key_%02x não encontrada no prod.keys (firmware mais nova?)".format(masterKeyRevision))
            Crypto.ecbDecrypt(kak, header.copyOfRange(0x300, 0x340)).copyOfRange(0x20, 0x30)
        }
    }

    /** Abre a seção já decifrada. */
    fun openSection(sec: NcaSection): Storage {
        if (sec.offset + sec.size > raw.size) throw IOException("Seção ${sec.index} passa do fim da NCA (arquivo truncado?)")
        return when (sec.encryptionType) {
            1 -> raw.slice(sec.offset, sec.size)
            3, 5 -> CtrStorage(raw, sec.offset, sec.size, ctrKey, sec.upperCounter)
            4, 6 -> throw IOException("Seção BKTR (atualização/patch) precisa do jogo base - não suportado")
            2 -> throw IOException("Seção com AES-XTS não suportada")
            else -> throw IOException("Tipo de criptografia ${sec.encryptionType} desconhecido")
        }
    }

    /** Seção já decifrada e cortada só na parte de dados (PFS0 ou RomFS). */
    fun openSectionData(sec: NcaSection): Storage {
        if (sec.isCompressed) throw IOException("Seção comprimida não suportada")
        val (off, len) = sec.dataRegion()
        return openSection(sec).slice(off, len)
    }
}
