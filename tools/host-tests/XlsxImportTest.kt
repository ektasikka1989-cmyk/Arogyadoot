import com.example.arfas.ArogyaPersonCsv
import com.example.arfas.ArogyaXlsx
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

var xf = 0; var xp = 0
fun x(c: Boolean, msg: String) { if (c) { xp++; println("PASS $msg") } else { xf++; println("FAIL $msg") } }

/** A minimal .xlsx built the way Excel writes it (workbook + rels + shared strings + styles + sheet). */
fun buildXlsx(sheet: String, shared: List<String>, styles: String): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        fun put(name: String, body: String) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        put("[Content_Types].xml", "<Types/>")
        put("xl/workbook.xml", """<workbook xmlns:r="r"><sheets><sheet name="People" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        put("xl/_rels/workbook.xml.rels", """<Relationships><Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
        put("xl/sharedStrings.xml", "<sst>" + shared.joinToString("") { "<si><t>$it</t></si>" } + "</sst>")
        put("xl/styles.xml", styles)
        put("xl/worksheets/sheet1.xml", "<worksheet><sheetData>$sheet</sheetData></worksheet>")
    }
    return out.toByteArray()
}

fun main() {
    val styles = """<styleSheet><numFmts count="1"><numFmt numFmtId="164" formatCode="dd/mm/yyyy"/></numFmts>
        <cellXfs count="3"><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="164"/></cellXfs></styleSheet>"""
    val shared = listOf("Name", "Mobile", "LMP", "Village", "सीता देवी", "Rampur &amp; Co")
    val sheet = """
        <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row>
        <row r="2"><c r="A2" t="s"><v>4</v></c><c r="B2"><v>9876543210</v></c><c r="C2" s="2"><v>46280</v></c><c r="D2" t="s"><v>5</v></c></row>
        <row r="3"><c r="A3" t="inlineStr"><is><t>Geeta</t></is></c><c r="C3" s="1"><v>46300</v></c></row>
        <row r="4"></row>"""
    val rows = ArogyaXlsx.rows(buildXlsx(sheet, shared, styles))
    x(rows.size == 3, "header + 2 people, empty row skipped (${rows.size})")
    x(rows[0] == listOf("Name", "Mobile", "LMP", "Village"), "header from shared strings")
    x(rows[1][0] == "सीता देवी", "Hindi shared string")
    x(rows[1][1] == "9876543210", "phone number kept whole: ${rows[1][1]}")
    x(rows[1][2] == "15/09/2026", "custom date format → dd/MM/yyyy: ${rows[1][2]}")
    x(rows[1][3] == "Rampur & Co", "XML entities unescaped")
    x(rows[2][0] == "Geeta" && rows[2][1] == "" && rows[2][2] == "05/10/2026", "inline string, gap kept, built-in date: ${rows[2]}")
    x(ArogyaPersonCsv.matchHeaders(rows[0]).values.containsAll(listOf("name", "phone", "lmp", "village")), "headers map to person fields")

    // CSV: separators and encodings
    x(ArogyaPersonCsv.parse("Name;Age\nSita;24\n") == listOf(listOf("Name", "Age"), listOf("Sita", "24")), "semicolon CSV")
    x(ArogyaPersonCsv.parse("Name\tAge\nSita\t24\n") == listOf(listOf("Name", "Age"), listOf("Sita", "24")), "tab-separated text")
    x(ArogyaPersonCsv.parse("Name,Note\nSita,\"a; b\"\n")[1] == listOf("Sita", "a; b"), "comma CSV with ; inside quotes")
    val hindi = "नाम,उम्र\nसीता,24\n"
    x(ArogyaPersonCsv.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + hindi.toByteArray(Charsets.UTF_16LE)) == hindi, "UTF-16LE with BOM")
    x(ArogyaPersonCsv.decode(hindi.toByteArray(Charsets.UTF_16LE)) == hindi, "UTF-16LE without BOM")
    x(ArogyaPersonCsv.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + hindi.toByteArray()) == hindi, "UTF-8 with BOM")
    x(ArogyaPersonCsv.decode("José,24".toByteArray(charset("windows-1252"))) == "José,24", "ANSI (Windows-1252) fallback")
    println(if (xf == 0) "ALL $xp PASS" else "$xf FAILED, $xp passed")
    if (xf > 0) kotlin.system.exitProcess(1)
}
