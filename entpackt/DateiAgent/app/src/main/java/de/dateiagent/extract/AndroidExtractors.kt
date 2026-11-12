package de.dateiagent.extract

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AndroidPdfReader(context: Context) : PdfTextReader {
    init { PDFBoxResourceLoader.init(context.applicationContext) }
    override fun extract(bytes: ByteArray): String =
        PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
            if (doc.isEncrypted) throw IllegalStateException("PDF ist passwortgeschützt")
            PDFTextStripper().getText(doc)
        }
}

class AndroidImageEncoder : ImageEncoder {
    override fun toJpegDataUrl(bytes: ByteArray, maxEdge: Int): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxEdge * 2 || bounds.outHeight / sample > maxEdge * 2) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("Bild konnte nicht dekodiert werden")
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
