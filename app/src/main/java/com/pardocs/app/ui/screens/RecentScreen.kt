package com.pardocs.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pardocs.app.core.SafFiles
import com.pardocs.app.core.Storage
import com.pardocs.app.ui.components.EmptyStateCard
import com.pardocs.app.ui.components.ParDocsCard
import com.pardocs.app.ui.components.ToolIconBox
import com.pardocs.app.ui.theme.TextFaint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<File>>(emptyList()) }

    fun refresh() {
        files = Storage.outputDir(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Recientes") },
            actions = {
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Outlined.Autorenew, "Actualizar")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        if (files.isEmpty()) {
            Column(Modifier.padding(16.dp)) {
                EmptyStateCard(
                    title = "Aún no hay resultados",
                    subtitle = "Los archivos que generes aparecerán aquí.",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(files, key = { it.absolutePath }) { file ->
                    val saveLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument(SafFiles.mimeFor(file)),
                    ) { target ->
                        if (target != null) {
                            scope.launch(Dispatchers.IO) {
                                val ok = SafFiles.exportTo(context, target, file)
                                launch(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        if (ok) "Guardado" else "No se pudo guardar",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    }
                    ParDocsCard(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ToolIconBox(Icons.Outlined.History, MaterialTheme.colorScheme.primary, size = 38)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(
                                    "${Storage.formatSize(file.length())} · " +
                                        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(file.lastModified())),
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
                            IconButton(onClick = {
                                file.delete()
                                refresh()
                            }) {
                                Icon(Icons.Outlined.Delete, "Eliminar", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}