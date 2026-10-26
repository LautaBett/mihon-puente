package app.mihon.puente.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import app.mihon.puente.sync.NamedBackup
import app.mihon.puente.sync.PuenteConfig
import app.mihon.puente.sync.PuenteEngine
import app.mihon.puente.sync.readConfig
import app.mihon.puente.sync.writeConfig
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var folderLabel: TextView
    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var log: TextView
    private var tree: Uri? = null
    private var running = false
    private var loop: Thread? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        tree = uri
        folderLabel.text = uri.toString()
        getPreferences(MODE_PRIVATE).edit().putString(TREE_KEY, uri.toString()).apply()
        review()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = readConfig(configFile())
        tree = getPreferences(MODE_PRIVATE).getString(TREE_KEY, null)?.let(Uri::parse)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        folderLabel = TextView(this).apply {
            text = tree?.toString() ?: "Sin carpeta. Elegí autobackup de Mihon."
        }
        url = field(config.supabaseUrl, "URL de Supabase")
        key = field(config.publishableKey, "Clave publicable")
        email = field(config.email, "Email")
        password = field(config.password, "Contraseña")
        log = TextView(this)
        val pick = Button(this).apply {
            text = "Elegir carpeta de Mihon"
            setOnClickListener {
                pickFolder.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                    ),
                )
            }
        }
        val review = Button(this).apply {
            text = "Revisar ahora"
            setOnClickListener { review() }
        }
        val open = Button(this).apply {
            text = "Abrir en Mihon"
            setOnClickListener { openInMihon() }
        }
        listOf(pick, folderLabel, url, key, email, password, review, open, log).forEach { view ->
            view.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 16 }
            root.addView(view)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onStart() {
        super.onStart()
        loop = Thread {
            while (!Thread.currentThread().isInterrupted) {
                review()
                try {
                    Thread.sleep(15_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also { it.start() }
    }

    override fun onStop() {
        loop?.interrupt()
        loop = null
        super.onStop()
    }

    private fun review() {
        val uri = tree ?: return
        if (running) return
        running = true
        val config = currentConfig()
        writeConfig(configFile(), config)
        Thread {
            val tick = try {
                val engine = PuenteEngine(config, filesDir, platform = "android")
                engine.synchronize(readBackups(uri))
            } catch (error: Exception) {
                running = false
                runOnUiThread { append(error.message ?: "Falló la revisión.") }
                return@Thread
            }
            runOnUiThread {
                tick.lines.forEach(::append)
                val bytes = tick.importBytes
                val sha = tick.importSha
                val name = tick.importName
                if (bytes != null && name != null && sha != null) {
                    writeImport(uri, name, bytes)
                    PuenteEngine(config, filesDir, platform = "android").markOffered(sha)
                    append("Listo para abrir en Mihon.")
                }
                running = false
            }
        }.start()
    }

    private fun readBackups(uri: Uri): List<NamedBackup> {
        val root = DocumentFile.fromTreeUri(this, uri) ?: return emptyList()
        return root.listFiles()
            .filter { it.isFile && it.name?.endsWith(".tachibk") == true && it.name?.startsWith("puente-") != true }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                val bytes = contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: return@mapNotNull null
                NamedBackup(name, bytes, file.lastModified())
            }
    }

    private fun writeImport(uri: Uri, name: String, bytes: ByteArray) {
        val root = DocumentFile.fromTreeUri(this, uri) ?: return
        val salida = root.findFile("salida") ?: root.createDirectory("salida") ?: return
        salida.findFile(name)?.delete()
        val created = salida.createFile("application/octet-stream", name) ?: return
        contentResolver.openOutputStream(created.uri)?.use { it.write(bytes) }
        getPreferences(MODE_PRIVATE).edit().putString(IMPORT_KEY, created.uri.toString()).apply()
    }

    private fun openInMihon() {
        val stored = getPreferences(MODE_PRIVATE).getString(IMPORT_KEY, null) ?: run {
            append("Todavía no hay un archivo para importar.")
            return
        }
        val uri = Uri.parse(stored)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(view, "Abrir en Mihon"))
    }

    private fun currentConfig() = PuenteConfig(
        watchFolder = tree?.toString().orEmpty(),
        supabaseUrl = url.text.toString().trim(),
        publishableKey = key.text.toString().trim(),
        email = email.text.toString().trim(),
        password = password.text.toString(),
        deviceName = "Android",
    )

    private fun field(value: String, hint: String) = EditText(this).apply {
        setText(value)
        this.hint = hint
    }

    private fun configFile() = File(filesDir, "config.json")

    private fun append(line: String) {
        log.append(line + "\n")
    }

    companion object {
        private const val TREE_KEY = "tree_uri"
        private const val IMPORT_KEY = "import_uri"
    }
}
