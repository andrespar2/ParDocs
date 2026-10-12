package com.pardocs.app.core

/**
 * Parses page expressions such as `1-3, 5, 8-` (1-based, inclusive).
 * Used by split / extract / PDF related tools.
 */
object PageRange {

    sealed interface Result {
        data class Ok(val pages: List<Int>) : Result
        data class Error(val message: String) : Result
    }

    /** @return zero-based page indices, in the order requested, de-duplicated. */
    fun parse(expression: String, pageCount: Int): Result {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return Result.Ok((0 until pageCount).toList())

        val pages = LinkedHashSet<Int>()
        for (rawPart in trimmed.split(',', ';')) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue
            if (!part.contains('-')) {
                val page = part.toIntOrNull()
                    ?: return Result.Error("«$part» no es un número de página válido.")
                if (page < 1 || page > pageCount) {
                    return Result.Error("La página $page está fuera de rango (el documento tiene $pageCount).")
                }
                pages += page - 1
                continue
            }
            val bounds = part.split('-')
            if (bounds.size != 2) return Result.Error("Rango «$part» mal formado. Usa por ejemplo 1-3.")
            val start = bounds[0].trim().toIntOrNull()
                ?: return Result.Error("«${bounds[0]}» no es una página válida.")
            val end = if (bounds[1].isBlank()) pageCount else bounds[1].trim().toIntOrNull()
                ?: return Result.Error("«${bounds[1]}» no es una página válida.")
            if (start < 1 || end > pageCount || start > end) {
                return Result.Error("El rango «$part» no es válido para un documento de $pageCount páginas.")
            }
            for (page in start..end) pages += page - 1
        }
        if (pages.isEmpty()) return Result.Error("No se seleccionó ninguna página.")
        return Result.Ok(pages.toList())
    }
}
