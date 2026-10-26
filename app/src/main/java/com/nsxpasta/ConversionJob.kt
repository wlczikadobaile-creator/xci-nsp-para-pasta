package com.nsxpasta

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import com.nsxpasta.core.CancelledException
import com.nsxpasta.core.ChannelStorage
import com.nsxpasta.core.Converter
import com.nsxpasta.core.KeySet
import com.nsxpasta.core.Mode
import com.nsxpasta.core.OutputDir
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

/** Grava na pasta escolhida pelo usuário via Storage Access Framework. */
class SafOutputDir(private val context: Context, private val doc: DocumentFile) : OutputDir {
    override fun dir(name: String): OutputDir =
        SafOutputDir(context, doc.createDirectory(name) ?: throw IOException("Não foi possível criar a pasta $name"))

    override fun file(name: String): OutputStream {
        val f = doc.createFile("application/octet-stream", name) ?: throw IOException("Não foi possível criar $name")
        return context.contentResolver.openOutputStream(f.uri, "w") ?: throw IOException("Não foi possível abrir $name")
    }
}

/** Estado da conversão fora da Activity, para sobreviver se a tela for recriada. */
object ConversionJob {
    @Volatile var running = false
        private set
    @Volatile private var cancel = false
    var done = 0L; private set
    var total = 0L; private set
    var status = ""; private set
    val log = StringBuilder()

    var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastUiUpdate = 0L

    private fun notifyUi(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastUiUpdate < 150) return
        lastUiUpdate = now
        main.post { listener?.invoke() }
    }

    private fun appendLog(line: String) {
        synchronized(log) {
            log.append(line).append('\n')
            if (log.length > 200_000) log.delete(0, log.length - 150_000)
        }
        notifyUi(force = true)
    }

    fun cancel() { cancel = true }

    fun start(context: Context, input: Uri, inputName: String, outTree: Uri, mode: Mode, keysUri: Uri?) {
        if (running) return
        running = true
        cancel = false
        done = 0; total = 0
        status = "Iniciando..."
        synchronized(log) { log.setLength(0) }
        val app = context.applicationContext
        Thread({
            val startTime = SystemClock.elapsedRealtime()
            try {
                val keys = if (mode == Mode.EXTRACT) {
                    val uri = keysUri ?: throw IOException("Selecione o prod.keys")
                    val text = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IOException("Não foi possível ler o prod.keys")
                    KeySet.parse(text).also { appendLog("prod.keys: ${it.count} chaves carregadas") }
                } else null

                val outRoot = DocumentFile.fromTreeUri(app, outTree) ?: throw IOException("Pasta de saída inválida")
                val pfd = app.contentResolver.openFileDescriptor(input, "r") ?: throw IOException("Não foi possível abrir o arquivo")
                pfd.use {
                    FileInputStream(it.fileDescriptor).channel.use { ch ->
                        val converter = Converter(
                            log = ::appendLog,
                            progress = { d, t -> done = d; total = t; status = "${Converter.human(d)} / ${Converter.human(t)}"; notifyUi() },
                            isCancelled = { cancel },
                        )
                        appendLog("Arquivo: $inputName (${Converter.human(ch.size())})")
                        val errors = converter.run(ChannelStorage(ch), inputName, SafOutputDir(app, outRoot), mode, keys)
                        val secs = (SystemClock.elapsedRealtime() - startTime) / 1000
                        status = if (errors == 0) "Concluído em ${secs}s" else "Concluído com $errors erro(s) em ${secs}s"
                        appendLog(status)
                    }
                }
            } catch (e: CancelledException) {
                status = "Cancelado"
                appendLog("Cancelado. A pasta de saída pode ter ficado incompleta.")
            } catch (e: Throwable) {
                status = "Falhou"
                appendLog("ERRO: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                running = false
                notifyUi(force = true)
            }
        }, "conversao").start()
    }
}
