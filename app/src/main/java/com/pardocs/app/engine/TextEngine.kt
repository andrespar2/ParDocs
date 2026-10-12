package com.pardocs.app.engine

import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Plain text extraction for TXT, CSV, HTML and RTF, with honest limits: legacy
 * binary office formats are rejected with an explanation instead of a guess.
 */
object TextEngine {

    fun plainText(file: File): String {
        val raw = file.readBytes()
        return decodeText(raw)
    }

    fun csvText(file: File): String = plainText(file)

    fun htmlText(file: File): String {
        val raw = decodeText(file.readBytes())
        return stripHtml(raw)
    }

    fun rtfText(file: File): String = rtfToText(decodeText(file.readBytes()))

    /** Decodes bytes honouring BOMs, falling back to UTF-8 then ISO-8859-1. */
    fun decodeText(raw: ByteArray): String {
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            return String(raw, 3, raw.size - 3, StandardCharsets.UTF_8)
        }
        if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
            return String(raw, 2, raw.size - 2, StandardCharsets.UTF_16BE)
        }
        if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) {
            return String(raw, 2, raw.size - 2, StandardCharsets.UTF_16LE)
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString() }
            .getOrElse { String(raw, 0, raw.size, StandardCharsets.ISO_8859_1) }
    }

    /** Lightweight HTML-to-text: no DOM, just tags, scripts and entities. */
    fun stripHtml(html: String): String {
        var text = html
        // <head> carries metadata (title, style, links) rather than body text.
        text = text.replace(Regex("""(?is)<head\b[^>]*>.*?</head>"""), " ")
        text = text.replace(Regex("""(?is)<script\b[^>]*>.*?</script>"""), " ")
        text = text.replace(Regex("""(?is)<style\b[^>]*>.*?</style>"""), " ")
        text = text.replace(Regex("""(?i)<br\s*/?>"""), "\n")
        text = text.replace(Regex("""(?i)</(p|div|h[1-6]|li|tr|table|ul|ol|blockquote)>"""), "\n")
        text = text.replace(Regex("""<[^>]+>"""), "")
        text = decodeEntities(text)
        return text
            .replace(Regex("""[ \t]+\n"""), "\n")
            // A block end next to a <br> must not leave a blank line behind.
            .replace(Regex("""\n{2,}"""), "\n")
            .replace(Regex("""\n[ \t]+"""), "\n")
            .trim()
    }

    private fun decodeEntities(input: String): String {
        val patterns = mapOf(
            "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
            "&#39;" to "'", "&nbsp;" to " ", "&copy;" to "©", "&reg;" to "®",
            "&euro;" to "€", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…",
            "&deg;" to "°", "&plusmn;" to "±", "&times;" to "×", "&middot;" to "·",
            "&aacute;" to "á", "&eacute;" to "é", "&iacute;" to "í", "&oacute;" to "ó",
            "&uacute;" to "ú", "&ntilde;" to "ñ", "&uuml;" to "ü", "&Aacute;" to "Á",
            "&Eacute;" to "É", "&Iacute;" to "Í", "&Oacute;" to "Ó", "&Uacute;" to "Ú",
            "&Ntilde;" to "Ñ", "&Uuml;" to "Ü",
        )
        var text = input
        for ((entity, value) in patterns) text = text.replace(entity, value)
        text = text.replace(Regex("""&#x([0-9a-fA-F]+);""")) { match ->
            match.groupValues[1].toIntOrNull(16)?.let(::charFromCode) ?: match.value
        }
        text = text.replace(Regex("""&#(\d{1,7});""")) { match ->
            match.groupValues[1].toIntOrNull()?.let(::charFromCode) ?: match.value
        }
        return text
    }

    private fun charFromCode(code: Int): String = try {
        String(Character.toChars(code))
    } catch (_: Exception) {
        "\uFFFD"
    }

    /** Minimal RTF -> plain text. Handles destinations, escapes and unicode. */
    fun rtfToText(rtf: String): String {
        val output = StringBuilder()
        var index = 0
        var skipDepth = 0

        // 0x80..0x9F are Windows-1252 control escapes commonly found in RTF.
        val cp1252 = mapOf(
            0x80 to '€', 0x82 to '‚', 0x83 to 'ƒ', 0x84 to '„', 0x85 to '…',
            0x86 to '†', 0x87 to '‡', 0x88 to 'ˆ', 0x89 to '‰', 0x8A to 'Š',
            0x8B to '‹', 0x8C to 'Œ', 0x8E to 'Ž', 0x91 to '\'', 0x92 to '\'',
            0x93 to '“', 0x94 to '”', 0x95 to '•', 0x96 to '–', 0x97 to '—',
            0x98 to '˜', 0x99 to '™', 0x9A to 'š', 0x9B to '›', 0x9C to 'œ',
            0x9E to 'ž', 0x9F to 'Ÿ',
        )

        while (index < rtf.length) {
            when (val c = rtf[index]) {
                '{' -> {
                    val groupIsSkipped = skipDepth > 0 || (index + 1 < rtf.length && rtf[index + 1] == '\\' && index + 2 < rtf.length && rtf[index + 2] == '*')
                    if (groupIsSkipped) skipDepth++
                    index++
                }
                '}' -> {
                    if (skipDepth > 0) skipDepth--
                    index++
                }
                '\\' -> {
                    index++
                    if (index < rtf.length && rtf[index] == '\'') {
                        // Hex escape \'hh (bytes in 0x80..0x9F follow Windows-1252).
                        index++
                        if (index + 1 < rtf.length) {
                            val hex = rtf.substring(index, index + 2)
                            index += 2
                            val value = hex.toIntOrNull(16)
                            if (value != null && skipDepth == 0) {
                                val char = if (value in 0x80..0x9F) cp1252[value] ?: '\uFFFD' else value.toChar()
                                output.append(char)
                            }
                        }
                    } else {
                        val word = StringBuilder()
                        while (index < rtf.length && rtf[index].isLetter()) {
                            word.append(rtf[index])
                            index++
                        }
                        var signed = 1
                        var number = 0
                        var hasNumber = false
                        if (index < rtf.length && rtf[index] == '-') {
                            signed = -1
                            index++
                            hasNumber = true
                        }
                        while (index < rtf.length && rtf[index].isDigit()) {
                            number = number * 10 + (rtf[index] - '0')
                            index++
                            hasNumber = true
                        }
                        if (index < rtf.length && (rtf[index] == ' ' || rtf[index] == '\n' || rtf[index] == '\r')) index++
                        when (word.toString()) {
                            "par", "line", "row" -> if (skipDepth == 0) output.append('\n')
                            "tab" -> if (skipDepth == 0) output.append('\t')
                            "emdash" -> if (skipDepth == 0) output.append("—")
                            "endash" -> if (skipDepth == 0) output.append("–")
                            "lquote" -> if (skipDepth == 0) output.append('‘')
                            "rquote" -> if (skipDepth == 0) output.append('’')
                            "ldblquote" -> if (skipDepth == 0) output.append('“')
                            "rdblquote" -> if (skipDepth == 0) output.append('”')
                            "u" -> if (skipDepth == 0 && hasNumber) {
                                val code = signed * number
                                try {
                                    output.append(String(Character.toChars(code)))
                                } catch (_: Exception) {
                                    output.append('\uFFFD')
                                }
                            }
                            else -> { /* formatting commands are ignored */ }
                        }
                    }
                }
                '\n', '\r' -> index++
                else -> {
                    if (skipDepth == 0) output.append(c)
                    index++
                }
            }
        }
        return output.toString()
            .replace(Regex("""[ \t]+$"""), "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}