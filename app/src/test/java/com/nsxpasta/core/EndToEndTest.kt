package com.nsxpasta.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/** Monta NSP/XCI sintéticos e cifrados como os reais e confere se a conversão devolve os arquivos originais. */
class EndToEndTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rnd = Random(1234)
    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    private val headerKey = bytes(32)
    private val keyAreaKey = bytes(16)
    private val titleKek = bytes(16)
    private val ctrKey = bytes(16)
    private val keys = KeySet.parse(
        """
        header_key = ${headerKey.toHex()}
        key_area_key_application_00 = ${keyAreaKey.toHex()}
        titlekek_00 = ${titleKek.toHex()}
        """.trimIndent(),
    )

    private val mainBin = bytes(5000)
    private val npdm = bytes(700)
    private val aTxt = "olá switch".toByteArray()
    private val bBin = bytes(100_000)

    private fun ByteArray.le32(o: Int, v: Long) { for (i in 0 until 4) this[o + i] = (v ushr (8 * i)).toByte() }
    private fun ByteArray.le64(o: Int, v: Long) { for (i in 0 until 8) this[o + i] = (v ushr (8 * i)).toByte() }
    private fun align(n: Int, a: Int) = (n + a - 1) / a * a

    private fun partitionFs(magic: String, entrySize: Int, files: List<Pair<String, ByteArray>>): ByteArray {
        val names = files.map { it.first.toByteArray() + 0 }
        val strSize = align(names.sumOf { it.size }, 0x20)
        val headerSize = 0x10 + entrySize * files.size + strSize
        val out = ByteArray(headerSize + files.sumOf { it.second.size })
        System.arraycopy(magic.toByteArray(), 0, out, 0, 4)
        out.le32(4, files.size.toLong())
        out.le32(8, strSize.toLong())
        var dataOff = 0L
        var strOff = 0
        files.forEachIndexed { i, (_, data) ->
            val e = 0x10 + i * entrySize
            out.le64(e, dataOff)
            out.le64(e + 8, data.size.toLong())
            out.le32(e + 0x10, strOff.toLong())
            System.arraycopy(names[i], 0, out, 0x10 + entrySize * files.size + strOff, names[i].size)
            System.arraycopy(data, 0, out, headerSize + dataOff.toInt(), data.size)
            strOff += names[i].size
            dataOff += data.size
        }
        return out
    }

    /** RomFS: / contém a.txt e sub/, sub/ contém b.bin. */
    private fun romFs(): ByteArray {
        val empty = 0xFFFFFFFFL
        val dirMeta = ByteArray(0x18 + 0x18 + 4)
        val fileMeta = ByteArray(0x28 + 0x28)
        // raiz
        dirMeta.le32(0, 0); dirMeta.le32(4, empty); dirMeta.le32(8, 0x18); dirMeta.le32(0xC, 0)
        dirMeta.le32(0x10, empty); dirMeta.le32(0x14, 0)
        // sub
        dirMeta.le32(0x18, 0); dirMeta.le32(0x1C, empty); dirMeta.le32(0x20, empty); dirMeta.le32(0x24, 0x28)
        dirMeta.le32(0x28, empty); dirMeta.le32(0x2C, 3); System.arraycopy("sub".toByteArray(), 0, dirMeta, 0x30, 3)
        val bOff = align(aTxt.size, 16).toLong()
        // a.txt
        fileMeta.le32(0, 0); fileMeta.le32(4, empty); fileMeta.le64(8, 0); fileMeta.le64(0x10, aTxt.size.toLong())
        fileMeta.le32(0x18, empty); fileMeta.le32(0x1C, 5); System.arraycopy("a.txt".toByteArray(), 0, fileMeta, 0x20, 5)
        // b.bin
        fileMeta.le32(0x28, 0x18); fileMeta.le32(0x2C, empty); fileMeta.le64(0x30, bOff); fileMeta.le64(0x38, bBin.size.toLong())
        fileMeta.le32(0x40, empty); fileMeta.le32(0x44, 5); System.arraycopy("b.bin".toByteArray(), 0, fileMeta, 0x48, 5)

        val dirMetaOff = 0x50 + 4
        val fileMetaOff = dirMetaOff + dirMeta.size + 4
        val dataOff = align(fileMetaOff + fileMeta.size, 16)
        val out = ByteArray(dataOff + bOff.toInt() + bBin.size)
        out.le64(0, 0x50)
        out.le64(0x08, 0x50); out.le64(0x10, 4)
        out.le64(0x18, dirMetaOff.toLong()); out.le64(0x20, dirMeta.size.toLong())
        out.le64(0x28, (dirMetaOff + dirMeta.size).toLong()); out.le64(0x30, 4)
        out.le64(0x38, fileMetaOff.toLong()); out.le64(0x40, fileMeta.size.toLong())
        out.le64(0x48, dataOff.toLong())
        System.arraycopy(dirMeta, 0, out, dirMetaOff, dirMeta.size)
        System.arraycopy(fileMeta, 0, out, fileMetaOff, fileMeta.size)
        System.arraycopy(aTxt, 0, out, dataOff, aTxt.size)
        System.arraycopy(bBin, 0, out, dataOff + bOff.toInt(), bBin.size)
        return out
    }

    private fun nca(contentType: Int, sections: List<ByteArray>, rightsId: ByteArray?): ByteArray {
        // cada seção = 0x200 de "tabela de hash" + dados, alinhado a 0x200
        val sizes = sections.map { 0x200 + align(it.size, 0x200) }
        val total = 0xC00 + sizes.sum()
        val nca = ByteArray(total)
        val h = nca // o cabeçalho fica nos primeiros 0xC00 bytes
        System.arraycopy("NCA3".toByteArray(), 0, h, 0x200, 4)
        h[0x205] = contentType.toByte()
        h.le64(0x208, total.toLong())
        h.le64(0x210, 0x0100000000010000L)
        if (rightsId != null) System.arraycopy(rightsId, 0, h, 0x230, 16)
        val keyArea = ByteArray(0x40).also { System.arraycopy(ctrKey, 0, it, 0x20, 16) }
        System.arraycopy(Crypto.ecbEncrypt(keyAreaKey, keyArea), 0, h, 0x300, 0x40)

        var off = 0xC00
        sections.forEachIndexed { i, data ->
            h.le32(0x240 + i * 0x10, (off / 0x200).toLong())
            h.le32(0x244 + i * 0x10, ((off + sizes[i]) / 0x200).toLong())
            val fs = 0x400 + i * 0x200
            val isPfs = data.copyOfRange(0, 4).contentEquals("PFS0".toByteArray())
            h[fs] = 2
            h[fs + 2] = if (isPfs) 1 else 0
            h[fs + 3] = if (isPfs) 2 else 3
            h[fs + 4] = 3
            if (isPfs) {
                h.le32(fs + 0x28, 0x1000); h.le32(fs + 0x2C, 2)
                h.le64(fs + 0x30, 0); h.le64(fs + 0x38, 0x200)
                h.le64(fs + 0x40, 0x200); h.le64(fs + 0x48, data.size.toLong())
            } else {
                System.arraycopy("IVFC".toByteArray(), 0, h, fs + 8, 4)
                h.le32(fs + 0x0C, 0x20000); h.le32(fs + 0x10, 0x20); h.le32(fs + 0x14, 7)
                h.le64(fs + 0x90, 0x200); h.le64(fs + 0x98, data.size.toLong())
            }
            h.le64(fs + 0x140, 0x0000000700000000L + i)
            System.arraycopy(data, 0, nca, off + 0x200, data.size)
            off += sizes[i]
        }
        // cifra as seções (CTR é simétrico) e depois o cabeçalho
        val plain = ByteArrayStorage(nca.copyOf())
        off = 0xC00
        sections.indices.forEach { i ->
            val enc = CtrStorage(plain, off.toLong(), sizes[i].toLong(), ctrKey, 0x0000000700000000L + i)
                .readBytes(0, sizes[i])
            System.arraycopy(enc, 0, nca, off, sizes[i])
            off += sizes[i]
        }
        System.arraycopy(Crypto.xtsEncrypt(headerKey, nca.copyOfRange(0, 0xC00), 0, 0x200), 0, nca, 0, 0xC00)
        return nca
    }

    private fun programNca(rightsId: ByteArray?) = nca(
        0, listOf(partitionFs("PFS0", 0x18, listOf("main" to mainBin, "main.npdm" to npdm)), romFs()), rightsId,
    )

    private fun controlNca() = nca(2, listOf(romFs()), null)

    private fun ticket(rightsId: ByteArray): ByteArray {
        val t = ByteArray(0x2C0)
        t.le32(0, 0x10004)
        val d = 0x140
        System.arraycopy(Crypto.ecbEncrypt(titleKek, ctrKey), 0, t, d + 0x40, 16)
        System.arraycopy(rightsId, 0, t, d + 0x160, 16)
        return t
    }

    private fun convert(container: ByteArray, name: String, mode: Mode): Pair<File, Int> {
        val out = tmp.newFolder()
        val errors = Converter(log = { println(it) }, progress = { _, _ -> })
            .run(ByteArrayStorage(container), name, FileOutputDir(out), mode, keys)
        return File(out, name.substringBeforeLast('.')) to errors
    }

    private fun assertGame(dir: File) {
        assertArrayEquals(mainBin, File(dir, "exefs/main").readBytes())
        assertArrayEquals(npdm, File(dir, "exefs/main.npdm").readBytes())
        assertArrayEquals(aTxt, File(dir, "romfs/a.txt").readBytes())
        assertArrayEquals(bBin, File(dir, "romfs/sub/b.bin").readBytes())
    }

    @Test fun nspWithTicket() {
        val rightsId = bytes(16)
        val nsp = partitionFs(
            "PFS0", 0x18,
            listOf("jogo.nca" to programNca(rightsId), "c.nca" to controlNca(), "${rightsId.toHex()}.tik" to ticket(rightsId)),
        )
        val (dir, errors) = convert(nsp, "Meu Jogo.nsp", Mode.EXTRACT)
        assertEquals(0, errors)
        assertGame(dir)
        assertArrayEquals(aTxt, File(dir, "control/a.txt").readBytes())
    }

    @Test fun xciStandardCrypto() {
        val secure = partitionFs("HFS0", 0x40, listOf("p.nca" to programNca(null)))
        val root = partitionFs("HFS0", 0x40, listOf("update" to partitionFs("HFS0", 0x40, emptyList()), "secure" to secure))
        val xci = ByteArray(0x200 + root.size)
        System.arraycopy("HEAD".toByteArray(), 0, xci, 0x100, 4)
        xci.le64(0x130, 0x200)
        xci.le64(0x138, root.size.toLong())
        System.arraycopy(root, 0, xci, 0x200, root.size)

        val (dir, errors) = convert(xci, "cartucho.xci", Mode.EXTRACT)
        assertEquals(0, errors)
        assertGame(dir)

        val (raw, _) = convert(xci, "cartucho.xci", Mode.UNPACK)
        assertArrayEquals(programNca(null), File(raw, "p.nca").readBytes())
    }
}
