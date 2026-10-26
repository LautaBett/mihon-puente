package app.mihon.puente.desktop

import app.mihon.puente.backup.backupTakenAtMillis
import app.mihon.puente.backup.decodeTachibk
import app.mihon.puente.backup.encodeTachibk
import app.mihon.puente.merge.MergeState
import app.mihon.puente.merge.merge
import app.mihon.puente.sync.NamedBackup
import app.mihon.puente.sync.PuenteConfig
import app.mihon.puente.sync.PuenteEngine
import app.mihon.puente.sync.readConfig
import app.mihon.puente.sync.writeConfig
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.GridLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

fun main(args: Array<String>) {
    if (args.firstOrNull() == "merge" && args.size == 4) {
        mergeFiles(args[1], args[2], args[3])
        return
    }
    SwingUtilities.invokeLater { DesktopWindow().show() }
}

private fun mergeFiles(canonicalPath: String, incomingPath: String, outputPath: String) {
    val incoming = File(incomingPath)
    val merged = merge(
        MergeState(decodeTachibk(File(canonicalPath).readBytes())),
        decodeTachibk(incoming.readBytes()),
        backupTakenAtMillis(incoming.name, incoming.lastModified()),
    )
    File(outputPath).writeBytes(encodeTachibk(merged.state.backup))
    println(
        "Series nuevas: ${merged.mangaAdded}. Actualizadas: ${merged.mangaUpdated}. " +
            "Capítulos nuevos: ${merged.chaptersAdded}. Con más progreso: ${merged.chaptersProgressed}.",
    )
}

private class DesktopWindow {
    private val home = File(System.getProperty("user.home"), ".mihon-puente")
    private val configFile = File(home, "config.json")
    private var config = readConfig(configFile)
    private val folder = JTextField(config.watchFolder, 28)
    private val url = JTextField(config.supabaseUrl, 28)
    private val key = JTextField(config.publishableKey, 28)
    private val email = JTextField(config.email, 28)
    private val password = JPasswordField(config.password, 28)
    private val device = JTextField(config.deviceName.ifBlank { "Mac" }, 28)
    private val log = JTextArea()
    private var running = false

    fun show() {
        log.isEditable = false
        log.lineWrap = true
        log.wrapStyleWord = true
        val frame = JFrame("Puente Mihon")
        frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        frame.layout = BorderLayout(8, 8)
        frame.add(form(), BorderLayout.NORTH)
        frame.add(JScrollPane(log), BorderLayout.CENTER)
        frame.add(buttons(frame), BorderLayout.SOUTH)
        (frame.contentPane as JPanel).border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        frame.setSize(760, 640)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        append("Carpeta de trabajo: ${home.absolutePath}")
        append("Elegí la carpeta donde Tachimanga deja los .tachibk.")
        Timer(15_000) { review() }.apply { isRepeats = true; start() }
        review()
    }

    private fun form(): JPanel {
        val panel = JPanel(GridLayout(0, 2, 8, 6))
        panel.add(JLabel("Carpeta de backups"))
        panel.add(folderRow())
        panel.add(JLabel("URL de Supabase"))
        panel.add(url)
        panel.add(JLabel("Clave publicable"))
        panel.add(key)
        panel.add(JLabel("Email"))
        panel.add(email)
        panel.add(JLabel("Contraseña"))
        panel.add(password)
        panel.add(JLabel("Nombre de este aparato"))
        panel.add(device)
        return panel
    }

    private fun folderRow(): JPanel {
        val row = JPanel(BorderLayout(6, 0))
        val choose = JButton("Elegir")
        choose.addActionListener {
            val chooser = JFileChooser(folder.text.ifBlank { System.getProperty("user.home") })
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                folder.text = chooser.selectedFile.absolutePath
                saveConfig()
                review()
            }
        }
        row.add(folder, BorderLayout.CENTER)
        row.add(choose, BorderLayout.EAST)
        return row
    }

    private fun buttons(frame: JFrame): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT))
        val review = JButton("Revisar ahora")
        review.addActionListener { review() }
        val open = JButton("Abrir carpeta de importación")
        open.addActionListener {
            val salida = File(folder.text, "salida")
            salida.mkdirs()
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(salida)
        }
        val save = JButton("Guardar")
        save.addActionListener {
            saveConfig()
            append("Configuración guardada.")
        }
        row.add(review)
        row.add(open)
        row.add(save)
        frame.rootPane.defaultButton = review
        return row
    }

    private fun review() {
        if (running) return
        val watch = folder.text.trim()
        if (watch.isBlank()) return
        saveConfig()
        running = true
        Thread {
            val tick = try {
                val engine = PuenteEngine(currentConfig(), home, platform = "mac")
                engine.synchronize(readBackups(File(watch)))
            } catch (error: Exception) {
                running = false
                SwingUtilities.invokeLater { append(error.message ?: "Falló la revisión.") }
                return@Thread
            }
            SwingUtilities.invokeLater {
                tick.lines.forEach(::append)
                val bytes = tick.importBytes
                val sha = tick.importSha
                if (bytes != null && tick.importName != null && sha != null) {
                    val salida = File(watch, "salida")
                    salida.mkdirs()
                    File(salida, tick.importName).writeBytes(bytes)
                    PuenteEngine(currentConfig(), home, platform = "mac").markOffered(sha)
                    append("Archivo listo en ${File(salida, tick.importName).absolutePath}")
                }
                running = false
            }
        }.start()
    }

    private fun readBackups(folder: File): List<NamedBackup> {
        if (!folder.isDirectory) return emptyList()
        return folder.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".tachibk") && !it.name.startsWith("puente-") }
            .map { NamedBackup(it.name, it.readBytes(), it.lastModified()) }
    }

    private fun currentConfig() = PuenteConfig(
        watchFolder = folder.text.trim(),
        supabaseUrl = url.text.trim(),
        publishableKey = key.text.trim(),
        email = email.text.trim(),
        password = String(password.password),
        deviceName = device.text.trim().ifBlank { "Mac" },
    )

    private fun saveConfig() {
        config = currentConfig()
        writeConfig(configFile, config)
        configFile.setReadable(false, false)
        configFile.setWritable(false, false)
        configFile.setReadable(true, true)
        configFile.setWritable(true, true)
    }

    private fun append(line: String) {
        log.append(line + "\n")
        log.caretPosition = log.document.length
    }
}
