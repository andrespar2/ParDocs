package com.pardocs.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.RotateRight
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pardocs.app.engine.PdfEngine
import com.pardocs.app.tools.ToolExecutor
import com.pardocs.app.tools.ToolSpec
import com.pardocs.app.tools.ToolContext
import com.pardocs.app.tools.uniqueOut
import com.pardocs.app.ui.components.EmptyStateCard
import com.pardocs.app.ui.components.PrimaryButton
import com.pardocs.app.ui.components.ResultPanel
import com.pardocs.app.ui.components.SecondaryButton
import com.pardocs.app.ui.theme.TextFaint
import com.pardocs.app.vm.ToolFlowViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class PageHolder(val originalIndex: Int) {
    var rotation by mutableStateOf(0)
    var deleted by mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizeScreen(spec: ToolSpec, onBack: () -> Unit) {
    val vm: ToolFlowViewModel = viewModel(key = "tool_organize")
    val state by vm.state.collectAsState()

    val pages = remember { mutableStateListOf<PageHolder>() }
    var thumbnails by remember { mutableStateOf<List<ImageBitmap?>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) vm.import(uris.take(1)) }

    val current = state.inputs.firstOrNull()

    LaunchedEffect(current) {
        if (current == null) return@LaunchedEffect
        loading = true
        loadError = null
        val file = current.file
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val holders = (0 until PdfEngine.pageCount(file)).map { PageHolder(it) }
                val thumbs = renderThumbnails(file, holders.size)
                holders to thumbs
            }
        }
        loading = false
        result.onSuccess { (holders, thumbs) ->
            pages.clear()
            pages.addAll(holders)
            thumbnails = thumbs
        }.onFailure { error ->
            pages.clear()
            thumbnails = emptyList()
            loadError = error.message
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

        if (current == null) {
            Column(Modifier.padding(16.dp)) {
                if (loadError != null) {
                    Text(loadError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                EmptyStateCard(
                    title = "Elige un PDF",
                    subtitle = "Podrás reordenar, rotar y eliminar páginas con vista previa.",
                )
                Spacer(Modifier.height(14.dp))
                SecondaryButton(
                    text = "Elegir PDF",
                    onClick = { picker.launch(arrayOf("application/pdf")) },
                    icon = Icons.Outlined.PictureAsPdf,
                )
            }
            return@Column
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(current.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        "${pages.size} pág. · ${pages.count { !it.deleted }} visibles",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextFaint,
                    )
                }
                SecondaryButton(text = "Cambiar", onClick = { picker.launch(arrayOf("application/pdf")) })
            }
            Spacer(Modifier.height(10.dp))

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(pages) { position, holder ->
                    PageRow(
                        position = position,
                        total = pages.size,
                        holder = holder,
                        thumbnail = thumbnails.getOrNull(holder.originalIndex),
                        onMoveUp = {
                            if (position > 0) {
                                val from = pages.removeAt(position)
                                pages.add(position - 1, from)
                            }
                        },
                        onMoveDown = {
                            if (position < pages.size - 1) {
                                val from = pages.removeAt(position)
                                pages.add(position + 1, from)
                            }
                        },
                        onRotate = { holder.rotation = (holder.rotation + 90) % 360 },
                        onDelete = { holder.deleted = true },
                        onRestore = { holder.deleted = false },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "Aplicar cambios (${pages.count { !it.deleted }} páginas)",
                onClick = {
                    val opResult: ToolExecutor = ToolExecutor { ctx: ToolContext ->
                        val file = ctx.inputs.first()
                        val output = uniqueOut("organizado", "pdf", ctx)
                        val ops = pages.filter { !it.deleted }.map { holder ->
                            com.pardocs.app.engine.OrganizeOp(holder.originalIndex, holder.rotation)
                        }
                        PdfEngine.organize(file, output, ops, ctx.onProgress)
                    }
                    vm.run(spec, opResult)
                },
                enabled = pages.isNotEmpty() && pages.any { !it.deleted } && !state.running,
                loading = state.running,
            )
            if (state.running) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(state.progressLabel, style = MaterialTheme.typography.labelMedium, color = TextFaint)
            }
        }

        if (state.done && state.outputs.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(8.dp))
                ResultPanel(
                    outputs = state.outputs,
                    stats = state.stats,
                    warnings = state.warnings,
                    onRestart = {
                        vm.reset()
                        pages.clear()
                        thumbnails = emptyList()
                        vm.clearInputs()
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PageRow(
    position: Int,
    total: Int,
    holder: PageHolder,
    thumbnail: ImageBitmap?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = "Página ${position + 1}",
                modifier = Modifier
                    .size(width = 44.dp, height = 60.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                Modifier
                    .size(width = 44.dp, height = 60.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text("…", color = TextFaint)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Página ${position + 1}", style = MaterialTheme.typography.bodyMedium)
            val rotated = holder.rotation
            Text(
                if (rotated > 0) "Rotada ${rotated}°" else "Original",
                style = MaterialTheme.typography.labelMedium,
                color = TextFaint,
            )
        }
        if (holder.deleted) {
            IconButton(onClick = onRestore) {
                Icon(Icons.Outlined.Restore, "Restaurar", tint = MaterialTheme.colorScheme.secondary)
            }
        } else {
            IconButton(onClick = onMoveUp, enabled = position > 0) {
                Icon(Icons.Outlined.ArrowUpward, "Subir", tint = if (position > 0) MaterialTheme.colorScheme.onSurface else TextFaint)
            }
            IconButton(onClick = onMoveDown, enabled = position < total - 1) {
                Icon(Icons.Outlined.ArrowDownward, "Bajar", tint = if (position < total - 1) MaterialTheme.colorScheme.onSurface else TextFaint)
            }
            IconButton(onClick = onRotate) {
                Icon(Icons.Outlined.RotateRight, "Rotar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, "Eliminar", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun renderThumbnails(file: File, count: Int): List<ImageBitmap?> {
    if (count == 0) return emptyList()
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    return try {
        val renderer = PdfRenderer(pfd)
        try {
            val maxPages = minOf(count, 300)
            val result = ArrayList<ImageBitmap?>(count)
            for (index in 0 until maxPages) {
                val page = renderer.openPage(index)
                val scale = 44f / page.width
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(44, height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                result.add(bitmap.asImageBitmap())
            }
            while (result.size < count) result.add(null)
            result
        } finally {
            renderer.close()
        }
    } finally {
        pfd.close()
    }
}