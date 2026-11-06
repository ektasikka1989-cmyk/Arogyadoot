package com.example.arfas

import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/**
 * Reads the first worksheet of an Excel .xlsx file into rows of cell text, so a patient list made in
 * Excel can be imported directly (the ASHA no longer has to "Save As CSV" on a phone).
 *
 * Pure Kotlin (zip + text XML, no Android or POI), host-tested. Handles shared strings, inline strings,
 * formula string results, booleans, numbers (phone numbers kept whole, no "9.87E9"), and cells formatted
 * as dates (Excel serial → dd/MM/yyyy). Empty cells keep their column position. Old binary .xls is not read.
 */
object ArogyaXlsx {
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val BUILTIN_DATE_FORMATS = (14..22).toSet() + setOf(27, 30, 36, 45, 46, 47, 50, 57)

    fun rows(bytes: ByteArray, maxRows: Int = 2000): List<List<String>> {
        val parts = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.trimStart('/')
                if (!e.isDirectory && (name.endsWith(".xml") || name.endsWith(".rels"))) {
                    val buf = java.io.ByteArrayOutputStream(); val chunk = ByteArray(16 * 1024); var total = 0
                    while (true) { val n = zip.read(chunk); if (n < 0) break; total += n; require(total <= MAX_ENTRY_BYTES) { "Excel file part too large" }; buf.write(chunk, 0, n) }
                    parts[name] = buf.toString("UTF-8")
                }
            }
        }
        val sheetXml = parts[firstSheetPath(parts)] ?: parts.entries.filter { it.key.startsWith("xl/worksheets/") && it.key.endsWith(".xml") }
            .minByOrNull { it.key }?.value ?: error("This Excel file has no worksheet.")
        val shared = sharedStrings(parts["xl/sharedStrings.xml"].orEmpty())
        val dateStyles = dateStyleIndexes(parts["xl/styles.xml"].orEmpty())
        val out = ArrayList<List<String>>()
        Regex("""<row\b[^>]*?(?:/>|>(.*?)</row>)""", RegexOption.DOT_MATCHES_ALL).findAll(sheetXml).forEach { rm ->
            if (out.size >= maxRows) return@forEach
            val cells = sortedMapOf<Int, String>()
            var nextCol = 0
            Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL).findAll(rm.groupValues[1]).forEach { cm ->
                val attrs = cm.groupValues[1]; val body = cm.groupValues[2]
                val col = attr(attrs, "r")?.let { colIndex(it) } ?: nextCol
                nextCol = col + 1
                val type = attr(attrs, "t").orEmpty()
                val v = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::unescape)
                val text = when (type) {
                    "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> textRuns(body)
                    "str", "e" -> v.orEmpty()
                    "b" -> if (v == "1") "TRUE" else "FALSE"
                    else -> v?.let { num -> number(num, attr(attrs, "s")?.toIntOrNull()?.let { it in dateStyles } == true) }.orEmpty()
                }
                cells[col] = text.trim()
            }
            if (cells.values.any { it.isNotBlank() }) {
                val width = cells.lastKey() + 1
                out += List(width) { cells[it].orEmpty() }
            }
        }
        return out
    }

    private fun number(raw: String, isDate: Boolean): String {
        val bd = raw.trim().toBigDecimalOrNull() ?: return raw.trim()
        if (isDate && bd.signum() > 0 && bd < BigDecimal(2958466)) {
            return LocalDate.of(1899, 12, 30).plusDays(bd.toLong()).format(DATE_FMT)
        }
        return bd.stripTrailingZeros().toPlainString()
    }

    private fun firstSheetPath(parts: Map<String, String>): String? {
        val wb = parts["xl/workbook.xml"] ?: return null
        val rid = Regex("""<sheet\b[^>]*?\br:id="([^"]+)"""").find(wb)?.groupValues?.get(1) ?: return null
        val rels = parts["xl/_rels/workbook.xml.rels"] ?: return null
        val rel = Regex("""<Relationship\b[^>]*>""").findAll(rels).map { it.value }.firstOrNull { attr(it, "Id") == rid } ?: return null
        val target = attr(rel, "Target") ?: return null
        return if (target.startsWith("/")) target.trimStart('/') else "xl/" + target.removePrefix("./")
    }

    private fun sharedStrings(xml: String): List<String> =
        Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { textRuns(it.groupValues[1]) }.toList()

    /** Concatenates the <t> runs of a rich-text cell, skipping phonetic (<rPh>) hints. */
    private fun textRuns(xml: String): String =
        Regex("""<t\b[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL).findAll(xml.replace(Regex("""<rPh\b.*?</rPh>""", RegexOption.DOT_MATCHES_ALL), ""))
            .joinToString("") { unescape(it.groupValues[1]) }

    private fun dateStyleIndexes(xml: String): Set<Int> {
        if (xml.isBlank()) return emptySet()
        val custom = Regex("""<numFmt\b[^>]*>""").findAll(xml).mapNotNull { m ->
            val id = attr(m.value, "numFmtId")?.toIntOrNull(); val code = attr(m.value, "formatCode")?.let(::unescape)?.lowercase() ?: return@mapNotNull null
            val stripped = code.replace(Regex("""\[[^]]*]|"[^"]*""""), "")
            if (id != null && stripped.contains('d') && stripped.contains('y')) id else null
        }.toSet()
        val xfs = Regex("""<cellXfs\b[^>]*>(.*?)</cellXfs>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: return emptySet()
        return Regex("""<xf\b[^>]*>""").findAll(xfs).mapIndexedNotNull { i, m ->
            val f = attr(m.value, "numFmtId")?.toIntOrNull() ?: return@mapIndexedNotNull null
            if (f in BUILTIN_DATE_FORMATS || f in custom) i else null
        }.toSet()
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""(?:^|\s)$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break }
        return (n - 1).coerceAtLeast(0)
    }

    private fun unescape(s: String): String = s
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
