package com.pardocs.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pardocs.app.ui.components.EmptyStateCard
import com.pardocs.app.ui.components.FileRow
import com.pardocs.app.ui.components.PrimaryButton
import com.pardocs.app.ui.components.ResultPanel
import com.pardocs.app.ui.components.SecondaryButton
import com.pardocs.app.ui.components.SectionLabel
import com.pardocs.app.tools.OptionKind
import com.pardocs.app.tools.ToolOption
import com.pardocs.app.tools.ToolSpec
import com.pardocs.app.vm.ToolFlowViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScreen(spec: ToolSpec, onBack: () -> Unit) {
    val vm: ToolFlowViewModel = viewModel(key = "tool_${spec.id}")
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) vm.import(uris) }

    LaunchedEffect(spec) { vm.seedOptions(spec) }

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
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(spec.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            SectionLabel("Archivos")
            Spacer(Modifier.height(8.dp))
            if (state.inputs.isEmpty()) {
                EmptyStateCard(
                    title = "Ningún archivo seleccionado",
                    subtitle = "Elige ${if (spec.minFiles > 1) "al menos ${spec.minFiles}" else "un"} archivo de tu dispositivo.",
                )
                Spacer(Modifier.height(10.dp))
            } else {
                state.inputs.forEachIndexed { index, file ->
                    FileRow(
                        file = file,
                        index = index,
                        total = state.inputs.size,
                        canMove = spec.category.name != "DOCUMENT",
                        onMoveUp = { vm.moveInput(index, index - 1) },
                        onMoveDown = { vm.moveInput(index, index + 1) },
                        onRemove = { vm.removeInput(index) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            SecondaryButton(
                text = "Seleccionar archivos",
                onClick = { picker.launch(spec.mimeTypes.toTypedArray()) },
                enabled = !state.importing && !state.running,
                icon = Icons.Outlined.Add,
            )
            if (state.importing) {
                Spacer(Modifier.height(8.dp))
                Text(state.importLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }

            val visibleOptions = spec.options.filter { it.visibleWhen(state.options) }
            if (visibleOptions.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionLabel("Opciones")
                Spacer(Modifier.height(8.dp))
                visibleOptions.forEach { option ->
                    OptionRow(option, state.options[option.id] ?: option.default) { value ->
                        vm.setOption(option.id, value)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (state.error != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    state.error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = if (state.running) "Procesando…" else "Procesar",
                onClick = { vm.run(spec) },
                enabled = state.inputs.size in spec.minFiles..spec.maxFiles && state.inputs.isNotEmpty() && !state.running && !state.importing,
                loading = state.running,
            )
            if (state.running) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(state.progressLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (state.done && state.outputs.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                ResultPanel(
                    outputs = state.outputs,
                    stats = state.stats,
                    warnings = state.warnings,
                    onRestart = { vm.reset() },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OptionRow(option: ToolOption, value: Any, onChange: (Any) -> Unit) {
    when (option.kind) {
        OptionKind.TOGGLE -> {
            val checked = value as? Boolean ?: false
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    if (option.help.isNotEmpty()) {
                        Text(option.help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = checked, onCheckedChange = onChange)
            }
        }
        OptionKind.SLIDER -> {
            val number = (value as? Number)?.toFloat() ?: (option.default as Number).toFloat()
            val scaled = number.coerceIn(option.min.toFloat(), option.max.toFloat())
            val steps = ((option.max - option.min) / maxOf(option.step, 1)) - 1
            Column(Modifier.fillMaxWidth()) {
                Row {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        "${scaled.toInt()}${option.unit}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = scaled,
                    onValueChange = { onChange(it) },
                    valueRange = option.min.toFloat()..option.max.toFloat(),
                    steps = steps.coerceAtLeast(0),
                )
                if (option.help.isNotEmpty()) {
                    Text(option.help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        OptionKind.CHOICE -> {
            Column(Modifier.fillMaxWidth()) {
                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                option.choices.forEach { (choiceValue, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = value == choiceValue,
                                onClick = { onChange(choiceValue) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == choiceValue, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (option.help.isNotEmpty()) {
                    Text(option.help, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        OptionKind.TEXT -> {
            val text = value as? String ?: option.default as String
            OutlinedTextField(
                value = text,
                onValueChange = onChange,
                label = { Text(option.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (option.secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (option.secret) KeyboardType.Password else KeyboardType.Text),
                supportingText = if (option.help.isNotEmpty()) ({ Text(option.help) }) else null,
            )
        }
    }
}