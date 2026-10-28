package app.stratasearch.data

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал сеанса: последние строки в памяти для экрана «Журнал» и полный файл
 * в Android/data/app.stratasearch/files/logs/ — его удобно отправить для разбора протокола.
 */
class SessionLog(context: Context) {
    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var writer: FileWriter? = null

    var file: File = newFile()
        private set

    private val recent = ArrayDeque<String>()
    val lines: List<String> get() = recent.toList()

    private fun newFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "session-$stamp.txt")
    }

    fun add(line: String) {
        val text = "${time.format(Date())}  $line"
        recent.addLast(text)
        while (recent.size > 400) recent.removeFirst()
        runCatching {
            val w = writer ?: FileWriter(file, true).also { writer = it }
            w.write(text)
            w.write("\n")
            w.flush()
        }
        trimOldFiles()
    }

    fun clear() {
        recent.clear()
        runCatching { writer?.close() }
        writer = null
        file = newFile()
    }

    private var trimmed = false
    private fun trimOldFiles() {
        if (trimmed) return
        trimmed = true
        dir.listFiles()?.sortedByDescending { it.name }?.drop(30)?.forEach { it.delete() }
    }
}
