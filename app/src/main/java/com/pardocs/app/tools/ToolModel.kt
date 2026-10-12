package com.pardocs.app.tools

import android.content.Context
import androidx.compose.ui.graphics.vector.ImageVector
import com.pardocs.app.core.Storage
import com.pardocs.app.core.WorkFile
import com.pardocs.app.engine.EngineOutcome
import com.pardocs.app.engine.ImageEngine
import com.pardocs.app.engine.OfficeEngine
import com.pardocs.app.engine.PdfEngine
import com.pardocs.app.engine.PdfRender
import com.pardocs.app.engine.ProgressListener
import java.io.File

enum class ToolCategory(val label: String) {
    PDF("PDF"),
    IMAGE("Imágenes"),
    DOCUMENT("Documentos"),
    OTHER("Otros"),
}

enum class OptionKind { TOGGLE, SLIDER, CHOICE, TEXT }

data class ToolOption(
    val id: String,
    val label: String,
    val kind: OptionKind,
    val default: Any,
    val help: String = "",
    val choices: List<Pair<String, String>> = emptyList(),
    val min: Int = 0,
    val max: Int = 100,
    val step: Int = 1,
    val unit: String = "",
    val secret: Boolean = false,
    val visibleWhen: (Map<String, Any>) -> Boolean = { true },
)

class ToolContext(
    val context: Context,
    val inputs: List<WorkFile>,
    val options: Map<String, Any>,
    val outDir: File,
    val onProgress: ProgressListener,
)

fun interface ToolExecutor {
    suspend fun execute(context: ToolContext): EngineOutcome
}

data class ToolSpec(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val category: ToolCategory,
    val mimeTypes: List<String>,
    val minFiles: Int = 1,
    val maxFiles: Int = Int.MAX_VALUE,
    val options: List<ToolOption> = emptyList(),
    val outputBase: String = "resultado",
    /** When set, the tool renders a bespoke screen instead of the generic flow. */
    val customScreen: String? = null,
    val executor: ToolExecutor,
)

// Convenient typed option accessors.
fun ToolContext.str(id: String, default: String) = (options[id] as? String) ?: default
fun ToolContext.bool(id: String, default: Boolean) = (options[id] as? Boolean) ?: default
fun ToolContext.int(id: String, default: Int) = (options[id] as? Int) ?: default
fun ToolContext.float(id: String, default: Float) = (options[id] as? Float) ?: default

fun uniqueOut(base: String, ext: String, context: ToolContext): File =
    Storage.uniqueFile(context.outDir, base, ext)

object PdfToolMime {
    val pdf = listOf("application/pdf")
    val image = listOf("image/*")
    val documents = listOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/msword",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
        "text/plain",
        "text/csv",
        "text/html",
        "application/rtf",
        "text/markdown",
        "application/json",
        "text/xml",
        "application/xml",
        "application/octet-stream",
    )
}