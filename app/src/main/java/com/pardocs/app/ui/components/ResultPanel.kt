package com.pardocs.app.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pardocs.app.core.SafFiles
import com.pardocs.app.core.Storage
import com.pardocs.app.ui.theme.TextFaint
import java.io.File
import kotlinx.coroutines.launch

/**
 * The shared "operation finished" panel: list of produced files with the real
 * actions (share / open / save somewhere else), plus warnings and statistics.
 */
@Composable
fun ResultPanel(
    outputs: List<File>,
    stats: Map<String, String>,
    warnings: List<String>,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(8.dp))
            Text("Listo — ${outputs.size} archivo(s)", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        if (stats.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
            ) {
                stats.forEach { (key, value) ->
                    TagChip("$key: $value")
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (warnings.isNotEmpty()) {
            ParDocsCard {
                warnings.forEach { warning ->
                    Row(
                        modifier = Modifier.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Outlined.Warning, null, tint = Color(0xFFFFC24D), modifier = Modifier.size(16.dp).padding(top = 1.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(warning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        outputs.forEach { file ->
            val saveLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument(SafFiles.mimeFor(file)),
            ) { target ->
                if (target != null) {
                    scope.launch {
                        val ok = SafFiles.exportTo(context, target, file)
                        Toast.makeText(
                            context,
                            if (ok) "Guardado en tu carpeta" else "No se pudo guardar el archivo",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            ParDocsCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${file.extension.uppercase()} · ${Storage.formatSize(file.length())}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextFaint,
                        )
                    }
                    IconButton(onClick = { SafFiles.openFile(context, file) }) {
                        Icon(Icons.Outlined.OpenInNew, "Abrir", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { saveLauncher.launch(file.name) }) {
                        Icon(Icons.Outlined.SaveAlt, "Guardar en…", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { SafFiles.shareFiles(context, listOf(file)) }) {
                        Icon(Icons.Outlined.Share, "Compartir", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (outputs.size > 1) {
            SecondaryButton(text = "Compartir todos", onClick = { SafFiles.shareFiles(context, outputs) })
            Spacer(Modifier.height(10.dp))
        }
        SecondaryButton(text = "Realizar otra operación", onClick = onRestart)
    }
}