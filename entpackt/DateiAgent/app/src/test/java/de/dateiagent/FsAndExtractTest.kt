package de.dateiagent

import de.dateiagent.extract.*
import de.dateiagent.fs.*
import org.junit.Assert.*
import org.junit.Test

class PathValidatorTest {
    @Test fun normalizesAndRejectsEscapes() {
        assertEquals("a/b", PathValidator.normalize("/a//b/./"))
        assertEquals("", PathValidator.normalize(null))
        listOf("../x", "a/../../b", "a\\b", "content://x/y", "a/b\u0000c", "a/..").forEach { p ->
            assertThrows(p, FsException::class.java) { PathValidator.normalize(p) }
        }
    }
    @Test fun rejectsBadNames() {
        listOf("", "..", "a/b", "x?y", "a:b").forEach { n -> assertThrows(n, FsException::class.java) { PathValidator.validateName(n) } }
        assertEquals("Rechnung 1.pdf", PathValidator.validateName(" Rechnung 1.pdf "))
    }
}

class ExtractorTest {
    private val ex = TextExtractor()
    @Test fun docxRoundtrip() { assertEquals("Hallo Welt\nZweite Zeile & <Test>", ex.extract("a.docx", OfficeWriter.docx("Hallo Welt\nZweite Zeile & <Test>")).text) }
    @Test fun xlsxRoundtrip() {
        val rows = OfficeWriter.parseCsv("Datum;Nummer;Firma;Betrag\n2025-03-01;R-1;Müller & Söhne;119,50\n")
        assertEquals(2, rows.size); assertEquals("119,50", rows[1][3])
        val text = ex.extract("r.xlsx", OfficeWriter.xlsx(rows)).text
        assertTrue(text, text.contains("Müller & Söhne")); assertTrue(text.contains("119.5")); assertTrue(text.startsWith("## Blatt: Tabelle1"))
    }
    @Test fun csvQuoted() { assertEquals(listOf("a,b", "c\"d"), OfficeWriter.parseCsv("\"a,b\",\"c\"\"d\"")[0]) }
    @Test fun htmlStripped() { assertEquals("Titel\nText ä", ex.extract("x.html", "<html><head><style>x{}</style></head><body><h1>Titel</h1><p>Text &auml;</p><script>evil()</script></body></html>".toByteArray()).text) }
    @Test fun latin1Fallback() { assertEquals("Größe", ex.extract("x.txt", "Größe".toByteArray(charset("windows-1252"))).text) }
    @Test fun unsupportedAndBrokenOffice() {
        assertThrows(FsException::class.java) { ex.extract("x.exe", byteArrayOf(1)) }
        assertThrows(FsException::class.java) { ex.extract("x.docx", "kein zip".toByteArray()) }
        assertThrows(FsException::class.java) { ex.extract("x.pdf", byteArrayOf(1)) }  // ohne PDF-Reader
    }
    @Test fun chunking() {
        val text = (1..1000).joinToString("\n") { "Zeile $it" }
        val parts = TextExtractor.chunk(text, 500)
        assertTrue(parts.size > 5); assertEquals(text, parts.joinToString("")); assertTrue(parts.all { it.length <= 500 })
    }
}
