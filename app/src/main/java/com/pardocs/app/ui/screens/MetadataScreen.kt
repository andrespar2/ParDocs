package com.pardocs.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pardocs.app.core.Storage
import com.pardocs.app.core.WorkFile
import com.pardocs.app.engine.ExifEngine
import com.pardocs.app.tools.ToolExecutor
import com.pardocs.app.tools.ToolSpec
import com.pardocs.app.ui.components.EmptyStateCard
import com.pardocs.app.ui.components.ParDocsCard
import com.pardocs.app.ui.components.PrimaryButton
import com.pardocs.app.ui.components.ResultPanel
import com.pardocs.app.ui.components.SecondaryButton
import com.pardocs.app.ui.components.SectionLabel
import com.pardocs.app.ui.components.TagChip
import com.pardocs.app.ui.components.ToolIconBox
import com.pardocs.app.ui.theme.TextFaint
import com.pardocs.app.vm.ToolFlowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataScreen(spec: ToolSpec, onBack: () -> Unit) {
    val vm: ToolFlowViewModel = viewModel(key = "tool_metadata")
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.import(uris)
    }

    var selectedIndex by remember { mutableStateOf(-1) }
    var tags by remember { mutableStateOf<List<ExifEngine.TagInfo>>(emptyList()) }
    var readError by remember { mutableStateOf<String?>(null) }

    var removeGps by remember { mutableStateOf(true) }
    var removeCamera by remember { mutableStateOf(true) }
    var removeDate by remember { mutableStateOf(true) }
    var removeAuthor by remember { mutableStateOf(true) }
    var removeAll by remember { mutableStateOf(false) }

    LaunchedEffect(state.inputs, selectedIndex) {
        val index = if (selectedIndex in state.inputs.indices) selectedIndex
        else if (state.inputs.isNotEmpty()) 0 else -1
        selectedIndex = index
        if (index < 0) {
            tags = emptyList()
            readError = null
            return@LaunchedEffect
        }
        val file = state.inputs[index].file
        readError = null
        val result = withContext(Dispatchers.IO) {
            runCatching { ExifEngine.read(file) }
        }
        result.onSuccess { tags = it }.onFailure {
            tags = emptyList()
            readError = it.message
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(spec.title) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Volver") }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "Consulta y elimina datos personales incrustados en tus imágenes: GPS, cámara, fechas y más. La limpieza no altera la calidad de la imagen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            SectionLabel("Imágenes")
            Spacer(Modifier.height(8.dp))
            if (state.inputs.isEmpty()) {
                EmptyStateCard(
                    title = "Ninguna imagen seleccionada",
                    subtitle = "Añade una o varias imágenes para inspeccionar y limpiar sus metadatos.",
                )
                Spacer(Modifier.height(10.dp))
            } else {
                state.inputs.forEachIndexed { index, file ->
                    FileSelectRow(
                        file = file,
                        selected = index == selectedIndex,
                        strippable = ExifEngine.canStrip(file.file),
                        onClick = { selectedIndex = index },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            SecondaryButton(
                text = "Añadir imágenes",
                onClick = { picker.launch(arrayOf("image/*")) },
                enabled = !state.running,
                icon = Icons.Outlined.Add,
            )

            if (state.inputs.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                SectionLabel("Opciones de limpieza")
                Spacer(Modifier.height(4.dp))
                StripCheckbox("Ubicación (GPS)", removeGps && !removeAll) {
                    removeGps = it
                }
                StripCheckbox("Cámara y disparo", removeCamera && !removeAll) {
                    removeCamera = it
                }
                StripCheckbox("Fecha y hora", removeDate && !removeAll) {
                    removeDate = it
                }
                StripCheckbox("Autor, software y descripción", removeAuthor && !removeAll) {
                    removeAuthor = it
                }
                StripCheckbox("Eliminar todo (incluye datos técnicos)", removeAll) { removeAll = it }
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    text = "Limpiar ${state.inputs.size} archivo(s)",
                    onClick = {
                        val options = ExifEngine.StripOptions(
                            removeAll = removeAll,
                            removeGps = removeGps,
                            removeCamera = removeCamera,
                            removeDate = removeDate,
                            removeAuthor = removeAuthor,
                        )
                        val executor = ToolExecutor { ctx ->
                            ExifEngine.strip(ctx.inputs, ctx.outDir, options, ctx.onProgress)
                        }
                        vm.run(spec, executor)
                    },
                    enabled = state.inputs.isNotEmpty() && !state.running,
                    loading = state.running,
                )
                if (state.running) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(state.progressLabel, style = MaterialTheme.typography.labelMedium, color = TextFaint)
                }
            }

            if (selectedIndex >= 0 && selectedIndex < state.inputs.size) {
                Spacer(Modifier.height(20.dp))
                SectionLabel("Metadatos detectados")
                Spacer(Modifier.height(8.dp))
                readError?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (tags.isEmpty() && readError == null) {
                    Text("Sin metadatos EXIF detectados en esta imagen.", style = MaterialTheme.typography.bodyMedium, color = TextFaint)
                } else {
                    val grouped = tags.groupBy { it.category }
                    ParDocsCard {
                        grouped.forEach { (category, entries) ->
                            Text(category, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            entries.forEach { tag ->
                                Row(Modifier.padding(top = 6.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(tag.label, style = MaterialTheme.typography.bodyMedium, color = TextFaint)
                                        Text(tag.value, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }

            if (state.done && state.outputs.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                ResultPanel(
                    outputs = state.outputs,
                    stats = state.stats,
                    warnings = state.warnings,
                    onRestart = {
                        vm.reset()
                        vm.clearInputs()
                        tags = emptyList()
                        selectedIndex = -1
                    },
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun StripCheckbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FileSelectRow(
    file: WorkFile,
    selected: Boolean,
    strippable: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(file.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                "${file.extension.uppercase()} · ${Storage.formatSize(file.sizeBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = TextFaint,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (!strippable) {
            TagChip("no editable")
        }
    }
}