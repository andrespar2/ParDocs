package com.pardocs.app.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pardocs.app.core.SafFiles
import com.pardocs.app.core.Storage
import com.pardocs.app.core.WorkFile
import com.pardocs.app.engine.EngineException
import com.pardocs.app.engine.UnsupportedFormatException
import com.pardocs.app.tools.ToolSpec
import com.pardocs.app.tools.ToolContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ToolFlowState(
    val inputs: List<WorkFile> = emptyList(),
    val importing: Boolean = false,
    val importLabel: String = "",
    val options: Map<String, Any> = emptyMap(),
    val running: Boolean = false,
    val progress: Float = 0f,
    val progressLabel: String = "",
    val outputs: List<File> = emptyList(),
    val done: Boolean = false,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    val stats: Map<String, String> = emptyMap(),
)

/** Drives one tool execution from file selection to result sharing. */
class ToolFlowViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ToolFlowState())
    val state: StateFlow<ToolFlowState> = _state.asStateFlow()

    fun seedOptions(spec: ToolSpec) {
        val current = _state.value.options
        val missing = spec.options.filter { it.id !in current }.associate { it.id to it.default }
        if (missing.isNotEmpty()) _state.update { it.copy(options = current + missing) }
    }

    fun import(uris: List<Uri>) {
        viewModelScope.launch {
            _state.update { it.copy(importing = true, importLabel = "Preparando archivos…") }
            val imported = SafFiles.importAll(getApplication(), uris) { done, total ->
                _state.update { it.copy(importLabel = "Importando $done de $total") }
            }
            _state.update {
                it.copy(importing = false, inputs = it.inputs + imported)
            }
        }
    }

    fun setOption(id: String, value: Any) {
        _state.update { current ->
            current.copy(options = current.options + (id to value))
        }
    }

    fun removeInput(index: Int) {
        _state.update { it.copy(inputs = it.inputs.filterIndexed { i, _ -> i != index }) }
    }

    fun moveInput(from: Int, to: Int) {
        val items = _state.value.inputs.toMutableList()
        if (from !in items.indices || to !in items.indices) return
        val item = items.removeAt(from)
        items.add(to, item)
        _state.update { it.copy(inputs = items) }
    }

    fun clearInputs() {
        _state.update { it.copy(inputs = emptyList()) }
        Storage.clearWork(getApplication())
    }

    fun run(spec: ToolSpec, executor: com.pardocs.app.tools.ToolExecutor = spec.executor) {
        if (_state.value.running || _state.value.inputs.isEmpty()) return
        viewModelScope.launch {
            _state.update {
                it.copy(running = true, error = null, outputs = emptyList(), warnings = emptyList(), stats = emptyMap(), done = false, progress = 0f, progressLabel = "Preparando…")
            }
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val outDir = Storage.outputDir(getApplication())
                    val context = ToolContext(getApplication(), _state.value.inputs, _state.value.options, outDir) { fraction, label ->
                        _state.update { it.copy(progress = fraction, progressLabel = label) }
                    }
                    executor.execute(context)
                }
            }
            result.onSuccess { outcome ->
                _state.update {
                    it.copy(
                        running = false,
                        done = true,
                        outputs = outcome.outputs,
                        warnings = outcome.warnings,
                        stats = outcome.stats,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val message = when (error) {
                    is UnsupportedFormatException, is EngineException -> error.message ?: "No se pudo completar la operación."
                    else -> error.message ?: "No se pudo completar la operación."
                }
                _state.update { it.copy(running = false, error = message) }
            }
        }
    }

    fun reset() {
        _state.update {
            it.copy(outputs = emptyList(), done = false, error = null, warnings = emptyList(), stats = emptyMap(), progress = 0f, progressLabel = "")
        }
    }
}