package com.example.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class ExportManager(private val context: Context) {

    fun generatePdfFile(title: String, subtitle: String, bodyContent: String): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = title.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(40).ifBlank { "sora_export" }
        val pdfFile = File(exportDir, "${safeName}_${System.currentTimeMillis()}.pdf")

        val doc = PdfDocument()
        val pageWidth = 595 // A4 width in points
        val pageHeight = 842
        val margin = 44f
        val maxWidth = pageWidth - margin * 2

        val titlePaint = Paint().apply {
            color = Color.rgb(30, 27, 75)
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subPaint = Paint().apply {
            color = Color.rgb(90, 98, 122)
            textSize = 10.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val bodyPaint = Paint().apply {
            color = Color.rgb(24, 26, 36)
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val headingPaint = Paint().apply {
            color = Color.rgb(67, 56, 202)
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val lines = wrapLines(bodyContent, bodyPaint, maxWidth)
        var pageNum = 1
        var lineIndex = 0

        while (lineIndex < lines.size || pageNum == 1) {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            var y = margin + 12f

            if (pageNum == 1) {
                canvas.drawText(title.take(70), margin, y, titlePaint)
                y += 18f
                canvas.drawText(subtitle.take(90), margin, y, subPaint)
                y += 12f
                canvas.drawLine(margin, y, pageWidth - margin, y, subPaint)
                y += 22f
            } else {
                canvas.drawText("$title — Page $pageNum", margin, y, subPaint)
                y += 20f
            }

            while (lineIndex < lines.size && y < pageHeight - margin - 20f) {
                val rawLine = lines[lineIndex]
                val isHeading = rawLine.startsWith("#")
                val cleanLine = rawLine.trimStart('#', ' ')
                val paint = if (isHeading) headingPaint else bodyPaint
                canvas.drawText(cleanLine, margin, y, paint)
                y += if (isHeading) 20f else 15.5f
                lineIndex++
            }

            canvas.drawText("SŌRA Academic Export • Page $pageNum", margin, pageHeight - 20f, subPaint)
            doc.finishPage(page)
            pageNum++
            if (lineIndex >= lines.size) break
        }

        FileOutputStream(pdfFile).use { doc.writeTo(it) }
        doc.close()
        return pdfFile
    }

    fun sharePdf(title: String, subtitle: String, bodyContent: String) {
        val file = generatePdfFile(title, subtitle, bodyContent)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export Study Material"))
    }

    fun shareMarkdown(title: String, bodyContent: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "# $title\n\n$bodyContent")
        }
        context.startActivity(Intent.createChooser(intent, "Share Study Notes"))
    }

    fun printStudyMaterial(title: String, subtitle: String, bodyContent: String) {
        val pdfFile = generatePdfFile(title, subtitle, bodyContent)
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder("${title.take(30)}.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                try {
                    FileInputStream(pdfFile).use { input ->
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            input.copyTo(output)
                        }
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }
        printManager.print("SORA_$title", adapter, null)
    }

    private fun wrapLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        text.split("\n").forEach { paragraph ->
            if (paragraph.isBlank()) {
                result.add("")
                return@forEach
            }
            val words = paragraph.split(" ")
            val current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current.clear()
                    current.append(candidate)
                } else {
                    if (current.isNotEmpty()) result.add(current.toString())
                    current.clear()
                    current.append(word)
                }
            }
            if (current.isNotEmpty()) result.add(current.toString())
        }
        return result
    }
}
