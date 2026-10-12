package com.pardocs.app.engine

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipFile

/**
 * Plain-text extraction from OOXML documents (docx / xlsx / pptx) using the
 * XML pull parser that ships with Android. No heavy libraries involved, which
 * keeps the APK small and the pipeline fully local.
 */
object OoxmlExtractor {

    fun extract(file: File): String = when (formatOf(file)) {
        "docx" -> docxText(file)
        "xlsx" -> xlsxText(file)
        "pptx" -> pptxText(file)
        else -> throw UnsupportedFormatException(
            "«${file.name}» no es un documento Office XML (docx/xlsx/pptx).",
        )
    }

    private fun formatOf(file: File): String? {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".docx") -> "docx"
            name.endsWith(".xlsx") -> "xlsx"
            name.endsWith(".pptx") -> "pptx"
            else -> null
        }
    }

    // ------------------------------------------------------------------ docx

    private fun docxText(file: File): String {
        val output = StringBuilder()
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return ""
            val parser = pullParser(zip, entry)
            var inText = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "w:p" -> output.append('\n')
                        "w:tab" -> output.append('\t')
                        "w:t" -> inText = true
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "w:t") inText = false
                    XmlPullParser.TEXT -> if (inText) parser.text?.let { output.append(it) }
                }
                parser.next()
            }
        }
        return clean(output.toString())
    }

    // ------------------------------------------------------------------ xlsx

    private fun xlsxText(file: File): String {
        val output = StringBuilder()
        ZipFile(file).use { zip ->
            val sharedStrings = readSharedStrings(zip)
            val sheets = zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .sortedWith(compareBy { numericSuffix(it) })
            for (sheetName in sheets) {
                val entry = zip.getEntry(sheetName) ?: continue
                output.append("\n━━ Hoja ").append(numericSuffix(sheetName)).append(" ━━\n")
                val parser = pullParser(zip, entry)
                val row = StringBuilder()
                var inCell = false
                var cellText = StringBuilder()
                var cellType: String? = null
                var inValue = false
                var hasCellContent = false
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "c" -> {
                                inCell = true
                                cellText = StringBuilder()
                                cellType = parser.getAttributeValue(null, "t")
                                inValue = false
                                hasCellContent = false
                            }
                            "v", "t" -> inValue = true
                        }
                        XmlPullParser.END_TAG -> when (parser.name) {
                            "c" -> {
                                inCell = false
                                val raw = cellText.toString().trim()
                                val value = if (cellType == "s" && raw.isNotEmpty()) {
                                    sharedStrings.getOrNull(raw.toIntOrNull() ?: -1) ?: raw
                                } else raw
                                if (value.isNotEmpty()) {
                                    if (row.isNotEmpty()) row.append('\t')
                                    row.append(value)
                                    hasCellContent = true
                                }
                            }
                            "v", "t" -> inValue = false
                            "row" -> {
                                if (hasCellContent) {
                                    output.append(row)
                                    output.append('\n')
                                }
                                row.setLength(0)
                            }
                        }
                        XmlPullParser.TEXT -> if (inCell && inValue) parser.text?.let { cellText.append(it) }
                    }
                    parser.next()
                }
            }
        }
        return clean(output.toString())
    }

    private fun readSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val parser = pullParser(zip, entry)
        val result = ArrayList<String>()
        var inSi = false
        val current = StringBuilder()
        var inText = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> {
                        inSi = true
                        current.setLength(0)
                    }
                    "t" -> inText = true
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "si" -> {
                        result += current.toString()
                        inSi = false
                    }
                    "t" -> inText = false
                }
                XmlPullParser.TEXT -> if (inSi && inText) parser.text?.let { current.append(it) }
            }
            parser.next()
        }
        return result
    }

    // ------------------------------------------------------------------ pptx

    private fun pptxText(file: File): String {
        val output = StringBuilder()
        ZipFile(file).use { zip ->
            val slides = zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                .sortedWith(compareBy { numericSuffix(it) })
            for (slideName in slides) {
                val entry = zip.getEntry(slideName) ?: continue
                output.append("\n—— Diapositiva ").append(numericSuffix(slideName)).append(" ——\n")
                val parser = pullParser(zip, entry)
                var inText = false
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "a:p" -> output.append('\n')
                            "a:tab" -> output.append('\t')
                            "a:t" -> inText = true
                        }
                        XmlPullParser.END_TAG -> if (parser.name == "a:t") inText = false
                        XmlPullParser.TEXT -> if (inText) parser.text?.let { output.append(it) }
                    }
                    parser.next()
                }
            }
        }
        return clean(output.toString())
    }

    // -------------------------------------------------------------- helpers

    private fun pullParser(zip: ZipFile, entry: java.util.zip.ZipEntry): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(zip.getInputStream(entry), null)
        return parser
    }

    private fun numericSuffix(path: String): Int {
        val digits = path.filter { it.isDigit() }
        return digits.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun clean(text: String): String {
        return text.replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}