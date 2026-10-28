package app.stratasearch.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stratasearch.data.MainViewModel
import app.stratasearch.data.UiState
import java.io.File

@Composable
fun LogScreen(st: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row {
                    Stat("Отчёты меток", st.reportPackets.toString(), Modifier.weight(1f))
                    Stat("Служебные", (st.gateway?.packets ?: 0).toString(), Modifier.weight(1f))
                    Stat("Отброшено байт", st.droppedBytes.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { shareLog(context, vm.currentLogFile()) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Отправить журнал")
                }
                Text(
                    "Файл: ${vm.currentLogFile().name}. В журнал пишутся все принятые пакеты — " +
                        "по нему можно разобрать новые типы данных.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(st.logLines.asReversed()) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

private fun shareLog(context: Context, file: File) {
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Журнал приёмника ${file.name}")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Отправить журнал"))
}
