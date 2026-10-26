package com.nsxpasta

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import com.nsxpasta.core.Mode

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("config", MODE_PRIVATE) }

    private var inputUri: Uri? = null
    private var inputName = ""
    private var outUri: Uri? = null
    private var keysUri: Uri? = null

    private lateinit var txtInput: TextView
    private lateinit var txtOut: TextView
    private lateinit var txtKeys: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var modeExtract: RadioButton

    private val pickInput = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        inputUri = uri
        inputName = displayName(uri)
        refresh()
    }

    private val pickKeys = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        keysUri = uri
        prefs.edit().putString("keys", uri.toString()).apply()
        refresh()
    }

    private val pickOut = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        outUri = uri
        prefs.edit().putString("out", uri.toString()).apply()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<android.view.View>(R.id.root)
        val pad = root.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
            insets
        }

        txtInput = findViewById(R.id.txtInput)
        txtOut = findViewById(R.id.txtOut)
        txtKeys = findViewById(R.id.txtKeys)
        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)
        logScroll = findViewById(R.id.logScroll)
        progress = findViewById(R.id.progress)
        btnStart = findViewById(R.id.btnStart)
        modeExtract = findViewById(R.id.modeExtract)

        outUri = prefs.getString("out", null)?.let(Uri::parse)?.takeIf(::hasPermission)
        keysUri = prefs.getString("keys", null)?.let(Uri::parse)?.takeIf(::hasPermission)

        findViewById<Button>(R.id.btnInput).setOnClickListener { pickInput.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.btnKeys).setOnClickListener { pickKeys.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.btnOut).setOnClickListener { pickOut.launch(null) }
        findViewById<android.widget.RadioGroup>(R.id.modeGroup).setOnCheckedChangeListener { _, _ -> refresh() }
        btnStart.setOnClickListener { onStartClicked() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        ConversionJob.listener = ::refresh
        refresh()
    }

    override fun onPause() {
        super.onPause()
        ConversionJob.listener = null
    }

    private fun onStartClicked() {
        if (ConversionJob.running) {
            ConversionJob.cancel()
            return
        }
        val input = inputUri ?: return toast("Escolha o arquivo XCI/NSP")
        val out = outUri ?: return toast("Escolha a pasta de saída")
        val mode = if (modeExtract.isChecked) Mode.EXTRACT else Mode.UNPACK
        if (mode == Mode.EXTRACT && keysUri == null) return toast("Escolha o prod.keys")
        ConversionJob.start(this, input, inputName, out, mode, keysUri)
        refresh()
    }

    private fun refresh() {
        val job = ConversionJob
        txtInput.text = if (inputUri == null) "Nenhum arquivo" else inputName
        txtOut.text = outUri?.let { DocumentFile.fromTreeUri(this, it)?.name } ?: "Nenhuma pasta"
        txtKeys.text = keysUri?.let(::displayName) ?: "Nenhuma chave"
        btnStart.text = if (job.running) "Cancelar" else "Converter"
        progress.progress = if (job.total > 0) (job.done * 1000 / job.total).toInt() else 0
        txtStatus.text = job.status
        val text = synchronized(job.log) { job.log.toString() }
        if (txtLog.text.length != text.length) {
            txtLog.text = text
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        if (job.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun hasPermission(uri: Uri) = contentResolver.persistedUriPermissions.any { it.uri == uri }

    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "arquivo"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
