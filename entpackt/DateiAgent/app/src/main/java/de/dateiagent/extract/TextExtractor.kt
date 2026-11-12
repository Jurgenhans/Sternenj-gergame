package de.dateiagent.extract

import de.dateiagent.fs.FsException
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/** Optionale Plattform-Bausteine (PDF, Bildverkleinerung); in Unit-Tests ersetzbar. */
interface PdfTextReader { fun extract(bytes: ByteArray): String }
interface ImageEncoder { fun toJpegDataUrl(bytes: ByteArray, maxEdge: Int = 1568): String }

object FileTypes {
    val text = setOf("txt", "md", "log", "csv", "json", "xml", "html", "htm", "yaml", "yml", "tsv")
    val images = setOf("jpg", "jpeg", "png")
    val supported = text + images + setOf("pdf", "docx", "xlsx")
    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
}

data class Extracted(val text: String, val note: String = "")

class TextExtractor(private val pdf: PdfTextReader? = null) {

    fun extract(name: String, bytes: ByteArray): Extracted = when (val e = FileTypes.ext(name)) {
        "pdf" -> {
            val r = pdf ?: throw FsException("PDF-Extraktion ist auf dieser Plattform nicht verfügbar.")
            val t = try { r.extract(bytes) } catch (ex: Exception) { throw FsException("PDF konnte nicht gelesen werden (${ex.message}). Evtl. verschlüsselt oder beschädigt.") }
            if (t.isBlank()) Extracted("", "Kein Text gefunden – vermutlich ein gescanntes PDF (nur Bilder). OCR ist nicht enthalten.") else Extracted(t)
        }
        "docx" -> Extracted(docxText(bytes))
        "xlsx" -> Extracted(xlsxText(bytes))
        "html", "htm" -> Extracted(htmlToText(decode(bytes)))
        "json" -> Extracted(decode(bytes))
        in FileTypes.text -> Extracted(decode(bytes))
        in FileTypes.images -> throw FsException("Bilder enthalten keinen extrahierbaren Text; verwende read_file für eine Bildanalyse.")
        else -> throw FsException("Dateityp '.$e' wird nicht unterstützt. Unterstützt: ${FileTypes.supported.sorted().joinToString()}")
    }

    fun decode(bytes: ByteArray): String {
        // BOM erkennen, sonst UTF-8; bei ungueltigem UTF-8 auf Windows-1252 zurueckfallen.
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        val dec = Charsets.UTF_8.newDecoder()
        return try { dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { String(bytes, charset("windows-1252")) }
    }

    fun htmlToText(html: String): String {
        var s = html.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>|</(p|div|h[1-6]|li|tr)>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&auml;", "ä").replace("&ouml;", "ö").replace("&uuml;", "ü")
            .replace("&Auml;", "Ä").replace("&Ouml;", "Ö").replace("&Uuml;", "Ü").replace("&szlig;", "ß")
        return s.lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    // ---------- DOCX ----------
    fun docxText(bytes: ByteArray): String {
        val xml = zipEntry(bytes, "word/document.xml") ?: throw FsException("Ungültige DOCX-Datei (word/document.xml fehlt).")
        val sb = StringBuilder()
        var inText = false
        parse(xml, object : DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String, a: Attributes?) {
                when (q.substringAfter(':')) { "t" -> inText = true; "tab" -> sb.append('\t'); "br" -> sb.append('\n') }
            }
            override fun endElement(u: String?, l: String?, q: String) {
                when (q.substringAfter(':')) { "t" -> inText = false; "p" -> sb.append('\n') }
            }
            override fun characters(ch: CharArray, s: Int, len: Int) { if (inText) sb.append(ch, s, len) }
        })
        return sb.toString().trim()
    }

    // ---------- XLSX ----------
    fun xlsxText(bytes: ByteArray): String {
        val shared = ArrayList<String>()
        zipEntry(bytes, "xl/sharedStrings.xml")?.let { x ->
            var inT = false; val cur = StringBuilder()
            parse(x, object : DefaultHandler() {
                override fun startElement(u: String?, l: String?, q: String, a: Attributes?) { if (q == "si") cur.setLength(0); if (q == "t") inT = true }
                override fun endElement(u: String?, l: String?, q: String) { if (q == "t") inT = false; if (q == "si") shared += cur.toString() }
                override fun characters(ch: CharArray, s: Int, len: Int) { if (inT) cur.append(ch, s, len) }
            })
        }
        val sheetNames = ArrayList<String>()
        zipEntry(bytes, "xl/workbook.xml")?.let { x ->
            parse(x, object : DefaultHandler() {
                override fun startElement(u: String?, l: String?, q: String, a: Attributes?) { if (q == "sheet") sheetNames += a?.getValue("name") ?: "Blatt" }
            })
        }
        val out = StringBuilder()
        var idx = 1
        while (true) {
            val x = zipEntry(bytes, "xl/worksheets/sheet$idx.xml") ?: break
            out.append("## Blatt: ").append(sheetNames.getOrNull(idx - 1) ?: "Sheet$idx").append('\n')
            val row = ArrayList<String>(); var cellType = ""; var inV = false; var inIs = false; val v = StringBuilder()
            parse(x, object : DefaultHandler() {
                override fun startElement(u: String?, l: String?, q: String, a: Attributes?) {
                    when (q) { "row" -> row.clear(); "c" -> { cellType = a?.getValue("t") ?: ""; v.setLength(0) }; "v" -> inV = true; "t" -> if (cellType == "inlineStr") inIs = true }
                }
                override fun endElement(u: String?, l: String?, q: String) {
                    when (q) {
                        "v" -> inV = false; "t" -> inIs = false
                        "c" -> row += if (cellType == "s") shared.getOrElse(v.toString().trim().toIntOrNull() ?: -1) { "" } else v.toString()
                        "row" -> out.append(row.joinToString("\t")).append('\n')
                    }
                }
                override fun characters(ch: CharArray, s: Int, len: Int) { if (inV || inIs) v.append(ch, s, len) }
            })
            idx++
        }
        if (idx == 1) throw FsException("Ungültige XLSX-Datei (keine Arbeitsblätter gefunden).")
        return out.toString().trim()
    }

    private fun parse(xml: ByteArray, h: DefaultHandler) {
        val f = SAXParserFactory.newInstance()
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        f.newSAXParser().parse(InputSource(ByteArrayInputStream(xml)), h)
    }

    private fun zipEntry(zip: ByteArray, name: String): ByteArray? {
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name == name) return z.readBytes()
                    e = z.nextEntry
                }
            }
        } catch (ex: java.util.zip.ZipException) { throw FsException("Datei ist kein gültiges Office-Dokument (${ex.message}).") }
        return null
    }

    companion object {
        /** Teilt Text in Abschnitte (an Zeilengrenzen, wenn moeglich). */
        fun chunk(text: String, size: Int): List<String> {
            if (text.length <= size) return listOf(text)
            val out = ArrayList<String>(); var i = 0
            while (i < text.length) {
                var end = minOf(i + size, text.length)
                if (end < text.length) { val nl = text.lastIndexOf('\n', end); if (nl > i + size / 2) end = nl + 1 }
                out += text.substring(i, end); i = end
            }
            return out
        }
    }
}
