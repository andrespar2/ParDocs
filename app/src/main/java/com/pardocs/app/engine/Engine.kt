package com.pardocs.app.engine

import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Progress callback used by every engine. `fraction` is in `0f..1f`. */
typealias ProgressListener = (fraction: Float, label: String) -> Unit

val NoProgress: ProgressListener = { _, _ -> }

/** Signals a known, presentable problem (shown verbatim to the user). */
class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Signals "this cannot be done on-device" — never a silent failure. */
class UnsupportedFormatException(message: String) : Exception(message)

/** Result of an engine run: the files produced plus anything worth telling the user. */
data class EngineOutcome(
    val outputs: List<File>,
    val warnings: List<String> = emptyList(),
    val stats: Map<String, String> = emptyMap(),
)

/** Cooperative cancellation: called inside long loops. */
internal suspend fun checkCancelled() {
    coroutineContext.ensureActive()
}

internal fun File.sizeLabel(): String = com.pardocs.app.core.Storage.formatSize(length())
