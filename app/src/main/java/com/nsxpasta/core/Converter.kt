package com.nsxpasta.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Destino da extração. No Android é implementado com SAF (DocumentFile); nos testes, com java.io.File. */
interface OutputDir {
    fun dir(name: String): OutputDir
    fun file(name: String): OutputStream
}

class FileOutputDir(private val root: File) : OutputDir {
    init { root.mkdirs() }
    override fun dir(name: String) = FileOutputDir(File(root, name))
    override fun file(name: String): OutputStream = FileOutputStream(File(root, name))
}

enum class Mode {
    /** Só abre o container: NSP/XCI vira uma pasta com as .nca/.tik/.cert (não precisa de chaves). */
    UNPACK,
    /** Decifra as NCAs: gera exefs/ + romfs/ (+ control/), formato de jogo extraído. Precisa do prod.keys. */
    EXTRACT,
}

class CancelledException : IOException("Cancelado pelo usuário")

class Converter(
    private val log: (String) -> Unit,
    private val progress: (done: Long, total: Long) -> Unit,
    private val isCancelled: () -> Boolean = { false },
) {
    private var done = 0L
    private var total = 0L
    private val buffer = ByteArray(4 shl 20)

    /** Retorna o número de erros (0 = tudo certo). */
    fun run(input: Storage, inputName: String, out: OutputDir, mode: Mode, keys: KeySet?): Int {
        val (kind, container) = openContainer(input)
        log("Formato: $kind - ${container.entries.size} arquivo(s) dentro")
        val gameDir = out.dir(sanitize(inputName.substringBeforeLast('.').ifBlank { "jogo" }))
        return when (mode) {
            Mode.UNPACK -> unpack(container, gameDir)
            Mode.EXTRACT -> extract(container, gameDir, keys ?: throw IOException("Selecione o prod.keys para este modo"))
        }
    }

    private fun openContainer(input: Storage): Pair<String, PartitionFs> {
        if (input.size >= 4 && input.readBytes(0, 4).ascii(0, 4) == "PFS0") return "NSP" to Pfs0(input)
        if (Xci.isXci(input)) return "XCI" to Xci.securePartition(input)
        throw IOException("Arquivo não reconhecido: não é NSP (PFS0) nem XCI. NSZ/XCZ comprimidos não são suportados.")
    }

    private fun unpack(c: PartitionFs, dir: OutputDir): Int {
        total = c.entries.sumOf { it.size }
        for (e in c.entries) {
            log("Copiando ${e.name} (${human(e.size)})")
            dir.file(sanitize(e.name)).use { copy(c.open(e), it) }
        }
        progress(total, total)
        return 0
    }

    private fun extract(c: PartitionFs, dir: OutputDir, keys: KeySet): Int {
        val titleKeys = HashMap<String, ByteArray>()
        for (e in c.entries.filter { it.name.endsWith(".tik", ignoreCase = true) }) {
            runCatching { Tickets.parse(c.open(e).readBytes(0, e.size.toInt())) }
                .onSuccess { t ->
                    if (t.titleKeyType != 0) log("Aviso: ${e.name} é um ticket personalizado (não suportado)")
                    else titleKeys[t.rightsId.toHex()] = t.encryptedTitleKey
                }
                .onFailure { log("Aviso: ticket ${e.name} ignorado: ${it.message}") }
        }

        val ncas = c.entries.filter { it.name.endsWith(".nca", ignoreCase = true) && !it.name.endsWith(".cnmt.nca", true) }
        if (ncas.isEmpty()) throw IOException("Nenhuma NCA encontrada (NCZ comprimido não é suportado)")

        // Primeiro abre os cabeçalhos para saber o que extrair e calcular o tamanho total.
        class Job(val entry: ContainerEntry, val nca: Nca)
        val jobs = ArrayList<Job>()
        var errors = 0
        for (e in ncas) {
            try {
                val nca = Nca(c.open(e), keys, titleKeys)
                log("${e.name}: ${nca.contentTypeName}, title id %016x, keygen ${nca.keyGeneration}".format(nca.programId))
                if (nca.contentType in setOf(0, 2, 4, 5)) jobs += Job(e, nca)
            } catch (ex: IOException) {
                errors++
                log("ERRO em ${e.name}: ${ex.message}")
            }
        }
        total = jobs.sumOf { it.entry.size }

        var programCount = 0
        for (job in jobs) {
            checkCancel()
            val nca = job.nca
            val target = when (nca.contentType) {
                0 -> if (programCount++ == 0) dir else dir.dir("%016x".format(nca.programId))
                2 -> dir.dir("control")
                else -> dir.dir("data_%016x".format(nca.programId))
            }
            val startDone = done
            for (sec in nca.sections) {
                checkCancel()
                val name = when {
                    nca.contentType == 0 && sec.index == 0 -> "exefs"
                    nca.contentType == 0 && sec.index == 1 -> "romfs"
                    nca.contentType == 0 && sec.index == 2 -> continue // logo, não é necessário
                    nca.contentType == 2 -> null // control: joga o RomFS direto em control/
                    else -> "romfs"
                }
                try {
                    val data = nca.openSectionData(sec)
                    val secDir = if (name == null) target else target.dir(name)
                    if (sec.partitionType == 1) {
                        log("  extraindo ${name ?: "control"} (PFS0)")
                        val pfs = Pfs0(data)
                        for (f in pfs.entries) secDir.file(sanitize(f.name)).use { copy(pfs.open(f), it) }
                    } else {
                        log("  extraindo ${name ?: "control"} (RomFS)")
                        extractRomFs(RomFs(data), secDir)
                    }
                } catch (ex: CancelledException) {
                    throw ex
                } catch (ex: IOException) {
                    errors++
                    log("  ERRO na seção ${sec.index}: ${ex.message}")
                }
            }
            // A barra de progresso conta pelo tamanho da NCA; acerta o valor ao terminar cada uma.
            done = startDone + job.entry.size
            progress(done, total)
        }
        if (jobs.none { it.nca.contentType == 0 }) log("Aviso: nenhuma NCA Program encontrada (é DLC ou atualização?)")
        progress(total, total)
        return errors
    }

    private fun extractRomFs(romFs: RomFs, root: OutputDir) {
        val stack = ArrayDeque<OutputDir>().apply { addLast(root) }
        romFs.walk(object : RomFs.Visitor {
            override fun enterDir(name: String) { stack.addLast(stack.last().dir(sanitize(name))) }
            override fun leaveDir() { stack.removeLast() }
            override fun file(name: String, data: Storage) {
                stack.last().file(sanitize(name)).use { copy(data, it) }
            }
        })
    }

    private fun copy(src: Storage, out: OutputStream) {
        var pos = 0L
        while (pos < src.size) {
            checkCancel()
            val n = minOf(buffer.size.toLong(), src.size - pos).toInt()
            src.read(pos, buffer, 0, n)
            out.write(buffer, 0, n)
            pos += n
            done += n
            progress(minOf(done, total), total)
        }
    }

    private fun checkCancel() {
        if (isCancelled()) throw CancelledException()
    }

    companion object {
        fun sanitize(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().ifEmpty { "_" }

        fun human(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
            else -> "%d KB".format(bytes / 1024)
        }
    }
}
