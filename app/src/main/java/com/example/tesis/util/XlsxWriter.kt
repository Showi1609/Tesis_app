package com.example.tesis.util

import java.io.OutputStream
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Generador mínimo de archivos .xlsx, sin dependencias.
 *
 * Un .xlsx es un zip de documentos XML, así que se puede escribir a mano en vez
 * de arrastrar Apache POI, que pesa varios megas y no está pensado para Android.
 *
 * Lo que aporta frente al CSV, y que motivó hacerlo:
 *
 *  - Los números viajan como números. El CSV obliga a elegir entre punto o coma
 *    decimal y a acertar con la configuración regional de quien lo abra; aquí el
 *    valor es numérico y Excel lo muestra según la del equipo.
 *  - Las fechas viajan como fechas, así que se pueden agrupar por mes o semana
 *    en una tabla dinámica sin convertirlas antes.
 *  - Encabezado fijo al desplazar y filtros por columna, que es lo que hace la
 *    tabla utilizable sin prepararla.
 */

sealed interface XlsxValue {
    /** Celda vacía: se omite del XML, que es como Excel representa "sin dato". */
    data object Blank : XlsxValue
    data class Text(val text: String) : XlsxValue
    data class Num(val value: Double, val decimals: Int = 2) : XlsxValue
    data class Whole(val value: Long) : XlsxValue
    data class DateTime(val millis: Long, val withTime: Boolean = true) : XlsxValue
}

private const val S_DEFAULT = 0
private const val S_HEADER = 1
private const val S_DATE = 2
private const val S_DATETIME = 3
private const val S_DEC2 = 4
private const val S_DEC4 = 5
private const val S_INT = 6

/**
 * Escribe una hoja con encabezado, filtros y panel inmovilizado.
 *
 * @param columnWidths ancho por columna en caracteres; si falta, se estima.
 */
fun writeXlsx(
    out: OutputStream,
    sheetName: String,
    headers: List<String>,
    rows: List<List<XlsxValue>>,
    columnWidths: List<Int>? = null
) {
    val zip = ZipOutputStream(out)

    fun put(path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    put("[Content_Types].xml", CONTENT_TYPES)
    put("_rels/.rels", ROOT_RELS)
    put("xl/workbook.xml", workbookXml(sheetName))
    put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
    put("xl/styles.xml", STYLES)
    put("xl/worksheets/sheet1.xml", sheetXml(headers, rows, columnWidths))

    zip.finish()
    zip.flush()
}

// ---------------------------------------------------------------------------
// Hoja
// ---------------------------------------------------------------------------

private fun sheetXml(
    headers: List<String>,
    rows: List<List<XlsxValue>>,
    columnWidths: List<Int>?
): String {
    val lastCol = columnName(headers.size)
    val lastRow = rows.size + 1
    val range = "A1:$lastCol$lastRow"

    val cols = buildString {
        append("<cols>")
        headers.indices.forEach { i ->
            val width = columnWidths?.getOrNull(i) ?: (headers[i].length + 4).coerceIn(10, 28)
            append("""<col min="${i + 1}" max="${i + 1}" width="$width" customWidth="1"/>""")
        }
        append("</cols>")
    }

    val body = buildString {
        append("<sheetData>")

        append("""<row r="1" ht="30" customHeight="1">""")
        headers.forEachIndexed { i, header ->
            append(cellXml(columnName(i + 1), 1, XlsxValue.Text(header), S_HEADER))
        }
        append("</row>")

        rows.forEachIndexed { rowIndex, row ->
            val r = rowIndex + 2
            append("""<row r="$r">""")
            row.forEachIndexed { i, value ->
                val style = when (value) {
                    is XlsxValue.DateTime -> if (value.withTime) S_DATETIME else S_DATE
                    is XlsxValue.Num -> if (value.decimals >= 4) S_DEC4 else S_DEC2
                    is XlsxValue.Whole -> S_INT
                    else -> S_DEFAULT
                }
                append(cellXml(columnName(i + 1), r, value, style))
            }
            append("</row>")
        }

        append("</sheetData>")
    }

    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<dimension ref="$range"/>
<sheetViews><sheetView tabSelected="1" workbookViewId="0">
<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>
<selection pane="bottomLeft" activeCell="A2" sqref="A2"/>
</sheetView></sheetViews>
<sheetFormatPr defaultRowHeight="15"/>
$cols
$body
<autoFilter ref="$range"/>
</worksheet>"""
}

private fun cellXml(column: String, row: Int, value: XlsxValue, style: Int): String {
    val ref = "$column$row"
    // Sin cadena cruda: una que termine en comilla deja cuatro seguidas y se lee
    // mal, tanto para quien revisa el código como para el resaltador.
    val s = if (style == S_DEFAULT) "" else " s=\"$style\""
    return when (value) {
        is XlsxValue.Blank -> ""
        is XlsxValue.Text ->
            """<c r="$ref"$s t="inlineStr"><is><t xml:space="preserve">${escape(value.text)}</t></is></c>"""
        is XlsxValue.Num ->
            """<c r="$ref"$s><v>${plainNumber(value.value)}</v></c>"""
        is XlsxValue.Whole ->
            """<c r="$ref"$s><v>${value.value}</v></c>"""
        is XlsxValue.DateTime ->
            """<c r="$ref"$s><v>${plainNumber(excelSerial(value.millis))}</v></c>"""
    }
}

/**
 * Convierte a la fecha de serie de Excel.
 *
 * Excel cuenta días desde el 30/12/1899 y no guarda zona horaria, así que hay
 * que sumar el desfase local: de otro modo un muestreo de las 07:00 en Colombia
 * aparecería a las 12:00.
 */
private fun excelSerial(millis: Long): Double {
    val offset = TimeZone.getDefault().getOffset(millis)
    return (millis + offset) / 86_400_000.0 + 25_569.0
}

/** El XML siempre lleva punto decimal, independientemente del idioma del equipo. */
private fun plainNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    return java.math.BigDecimal(value)
        .setScale(8, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}

private fun columnName(index: Int): String {
    var n = index
    val sb = StringBuilder()
    while (n > 0) {
        val rem = (n - 1) % 26
        sb.append(('A' + rem))
        n = (n - 1) / 26
    }
    return sb.reverse().toString()
}

private fun escape(text: String): String = buildString {
    text.forEach { c ->
        when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c == '\'' -> append("&apos;")
            // Excel rechaza los caracteres de control; se sustituyen por espacio.
            c.code < 0x20 -> append(' ')
            else -> append(c)
        }
    }
}

// ---------------------------------------------------------------------------
// Partes fijas del paquete
// ---------------------------------------------------------------------------

private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

private fun workbookXml(sheetName: String): String {
    // Excel limita el nombre de hoja a 31 caracteres y prohíbe : \ / ? * [ ]
    val safe = sheetName.replace(Regex("[:\\\\/?*\\[\\]]"), " ").take(31)
    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="${escape(safe)}" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""
}

private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="3">
<numFmt numFmtId="164" formatCode="dd/mm/yyyy"/>
<numFmt numFmtId="165" formatCode="dd/mm/yyyy\ hh:mm"/>
<numFmt numFmtId="166" formatCode="0.0000"/>
</numFmts>
<fonts count="2">
<font><sz val="11"/><color theme="1"/><name val="Calibri"/></font>
<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
</fonts>
<fills count="3">
<fill><patternFill patternType="none"/></fill>
<fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FF1F5460"/><bgColor indexed="64"/></patternFill></fill>
</fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="7">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="2" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="1" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
