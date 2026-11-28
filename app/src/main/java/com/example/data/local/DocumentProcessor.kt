package com.example.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.util.Xml
import com.example.core.InputValidator
import com.example.data.ai.SoraAiService
import com.example.data.model.BookPageContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

private const val TAG = "DocumentProcessor"
internal const val MAX_INGEST_BYTES = 50L * 1024 * 1024 // 50 MB ingestion safety limit

data class IngestionResult(
    val localBookId: String,
    val sourceType: String,
    val fileName: String,
    val pages: List<BookPageContent>,
    val failedPages: List<Int>,
    val extractionStatus: String,
    val extractionError: String = ""
)

class DocumentProcessor(
    private val context: Context,
    private val aiService: SoraAiService
) {
    private fun bookDir(localBookId: String): File {
        val safeId = InputValidator.sanitizeFileName(localBookId, "book_default")
        return File(context.filesDir, "books/$safeId").apply { mkdirs() }
    }

    fun getLocalFile(localBookId: String, fileName: String): File {
        val safeName = InputValidator.sanitizeFileName(fileName, "document.bin")
        val dir = bookDir(localBookId)
        val candidate = File(dir, safeName)
        val canonicalDir = dir.canonicalFile
        val canonicalFile = candidate.canonicalFile
        require(canonicalFile.path.startsWith(canonicalDir.path)) { "Invalid local file path" }
        return candidate
    }

    fun deleteBookCache(localBookId: String): Boolean {
        val safeId = InputValidator.sanitizeFileName(localBookId, "book_default")
        val dir = File(context.filesDir, "books/$safeId")
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    fun clearAllBooksCache(): Boolean {
        val root = File(context.filesDir, "books")
        return if (root.exists()) root.deleteRecursively() else true
    }

    fun savePages(localBookId: String, pages: List<BookPageContent>) {
        val arr = JSONArray()
        pages.forEach { p ->
            arr.put(JSONObject().apply {
                put("pageNumber", p.pageNumber)
                put("text", p.text)
                put("extractionMethod", p.extractionMethod)
                if (p.error != null) put("error", p.error)
            })
        }
        writeFileAtomic(File(bookDir(localBookId), "pages.json"), arr.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeFileAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tmp).use { it.write(bytes) }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                FileOutputStream(target).use { it.write(bytes) }
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    internal fun copyStreamWithLimit(
        input: java.io.InputStream,
        destFile: File,
        maxBytes: Long = MAX_INGEST_BYTES
    ): Boolean {
        destFile.parentFile?.mkdirs()
        val tmp = File(destFile.parentFile, ".${destFile.name}.${System.nanoTime()}.tmp")
        return try {
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            FileOutputStream(tmp).use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) {
                        output.flush()
                        tmp.delete()
                        return false
                    }
                    output.write(buffer, 0, read)
                }
            }
            if (destFile.exists()) destFile.delete()
            if (!tmp.renameTo(destFile)) {
                tmp.copyTo(destFile, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    fun loadPages(localBookId: String): List<BookPageContent> {
        val file = File(bookDir(localBookId), "pages.json")
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                BookPageContent(
                    pageNumber = obj.getInt("pageNumber"),
                    text = obj.optString("text", ""),
                    extractionMethod = obj.optString("extractionMethod", "TEXT"),
                    error = obj.optString("error", "").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pages.json for $localBookId", e)
            emptyList()
        }
    }

    suspend fun renderPdfPageBitmap(
        localBookId: String,
        fileName: String,
        pageIndex: Int,
        maxWidth: Int = 1400
    ): Bitmap? = withContext(Dispatchers.IO) {
        val pdfFile = getLocalFile(localBookId, fileName)
        if (!pdfFile.exists()) return@withContext null
        try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null
                    renderer.openPage(pageIndex).use { page ->
                        val scale = maxWidth.toFloat() / page.width.coerceAtLeast(1)
                        val w = (page.width * scale).toInt().coerceIn(300, 2200)
                        val h = (page.height * scale).toInt().coerceIn(300, 3200)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        canvas.drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PDF render failed for page $pageIndex", e)
            null
        }
    }

    fun loadStoredImageBitmap(localBookId: String, fileName: String): Bitmap? {
        val imgFile = getLocalFile(localBookId, fileName)
        if (!imgFile.exists()) return null
        return try {
            BitmapFactory.decodeFile(imgFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode image $fileName", e)
            null
        }
    }

    suspend fun ingestFromUri(
        uri: Uri,
        localBookId: String,
        onProgress: (processed: Int, total: Int, statusLabel: String) -> Unit
    ): IngestionResult = withContext(Dispatchers.IO) {
        val rawName = queryFileName(uri) ?: "study_material"
        val resolvedName = InputValidator.sanitizeFileName(rawName, "study_material")
        val lower = resolvedName.lowercase()
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        val sourceType = when {
            lower.endsWith(".pdf") || mime.contains("pdf") -> "PDF"
            lower.endsWith(".docx") || mime.contains("wordprocessingml") -> "DOCX"
            lower.endsWith(".pptx") || mime.contains("presentationml") -> "PPTX"
            mime.startsWith("image/") || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") -> "IMAGE"
            else -> "TEXT"
        }

        val targetFileName = when (sourceType) {
            "PDF" -> if (lower.endsWith(".pdf")) resolvedName else "$resolvedName.pdf"
            "DOCX" -> if (lower.endsWith(".docx")) resolvedName else "$resolvedName.docx"
            "PPTX" -> if (lower.endsWith(".pptx")) resolvedName else "$resolvedName.pptx"
            "IMAGE" -> if (lower.endsWith(".jpg") || lower.endsWith(".png")) resolvedName else "$resolvedName.jpg"
            else -> if (lower.endsWith(".txt") || lower.endsWith(".md")) resolvedName else "$resolvedName.txt"
        }

        val destFile = getLocalFile(localBookId, targetFileName)
        val withinLimit = context.contentResolver.openInputStream(uri)?.use { input ->
            copyStreamWithLimit(input, destFile, MAX_INGEST_BYTES)
        } ?: return@withContext IngestionResult(
            localBookId = localBookId,
            sourceType = sourceType,
            fileName = targetFileName,
            pages = emptyList(),
            failedPages = listOf(1),
            extractionStatus = "FAILED",
            extractionError = "Unable to open selected file stream."
        )

        if (!withinLimit) {
            return@withContext IngestionResult(
                localBookId = localBookId,
                sourceType = sourceType,
                fileName = targetFileName,
                pages = emptyList(),
                failedPages = listOf(1),
                extractionStatus = "FAILED",
                extractionError = "Selected file exceeds the 50 MB size limit."
            )
        }

        when (sourceType) {
            "PDF" -> processPdfFile(localBookId, targetFileName, destFile, onProgress)
            "DOCX" -> processDocxFile(localBookId, targetFileName, destFile, onProgress)
            "PPTX" -> processPptxFile(localBookId, targetFileName, destFile, onProgress)
            "IMAGE" -> processImageFile(localBookId, targetFileName, destFile, onProgress)
            else -> {
                val rawText = destFile.readText(Charsets.UTF_8)
                ingestPlainText(localBookId, targetFileName, "TEXT", rawText, onProgress)
            }
        }
    }

    suspend fun ingestPlainText(
        localBookId: String,
        fileName: String,
        sourceType: String,
        rawText: String,
        onProgress: (processed: Int, total: Int, statusLabel: String) -> Unit
    ): IngestionResult = withContext(Dispatchers.IO) {
        val cleaned = rawText.replace("\r\n", "\n").trim()
        if (cleaned.isBlank()) {
            return@withContext IngestionResult(
                localBookId = localBookId,
                sourceType = sourceType,
                fileName = fileName,
                pages = emptyList(),
                failedPages = listOf(1),
                extractionStatus = "FAILED",
                extractionError = "Document contained no readable text."
            )
        }
        val destFile = getLocalFile(localBookId, fileName)
        if (!destFile.exists()) {
            writeFileAtomic(destFile, cleaned.toByteArray(Charsets.UTF_8))
        }
        val chunks = paginateText(cleaned, maxCharsPerPage = 1800)
        val pages = chunks.mapIndexed { idx, chunk ->
            onProgress(idx + 1, chunks.size, "Organizing page ${idx + 1} of ${chunks.size}")
            BookPageContent(pageNumber = idx + 1, text = chunk, extractionMethod = sourceType)
        }
        savePages(localBookId, pages)
        IngestionResult(
            localBookId = localBookId,
            sourceType = sourceType,
            fileName = fileName,
            pages = pages,
            failedPages = emptyList(),
            extractionStatus = "COMPLETE"
        )
    }

    suspend fun retryFailedPages(
        localBookId: String,
        fileName: String,
        sourceType: String,
        failedPageNumbers: List<Int>,
        onProgress: (processed: Int, total: Int, statusLabel: String) -> Unit
    ): IngestionResult = withContext(Dispatchers.IO) {
        val currentPages = loadPages(localBookId).toMutableList()
        val newlyFailed = mutableListOf<Int>()
        val total = failedPageNumbers.size.coerceAtLeast(1)

        failedPageNumbers.forEachIndexed { idx, pageNum ->
            coroutineContext.ensureActive()
            onProgress(idx + 1, total, "Retrying OCR on page $pageNum...")
            val bitmap = if (sourceType == "PDF") {
                renderPdfPageBitmap(localBookId, fileName, pageNum - 1, maxWidth = 1280)
            } else if (sourceType == "IMAGE") {
                loadStoredImageBitmap(localBookId, fileName)
            } else null

            if (bitmap == null) {
                newlyFailed.add(pageNum)
            } else {
                val ocrResult = try {
                    aiService.extractTextFromPageImage(bitmap, pageNum)
                } finally {
                    bitmap.recycle()
                }
                if (ocrResult.isSuccess) {
                    val extracted = ocrResult.getOrNull().orEmpty()
                    val existingIdx = currentPages.indexOfFirst { it.pageNumber == pageNum }
                    val updatedPage = BookPageContent(pageNum, extracted, "GEMINI_OCR", null)
                    if (existingIdx >= 0) currentPages[existingIdx] = updatedPage
                    else currentPages.add(updatedPage)
                } else {
                    newlyFailed.add(pageNum)
                }
            }
        }

        val sortedPages = currentPages.sortedBy { it.pageNumber }
        savePages(localBookId, sortedPages)
        val status = when {
            sortedPages.isEmpty() -> "FAILED"
            newlyFailed.isNotEmpty() -> "PARTIAL"
            else -> "COMPLETE"
        }
        IngestionResult(
            localBookId = localBookId,
            sourceType = sourceType,
            fileName = fileName,
            pages = sortedPages,
            failedPages = newlyFailed,
            extractionStatus = status,
            extractionError = if (newlyFailed.isNotEmpty()) "Pages ${newlyFailed.joinToString()} failed OCR." else ""
        )
    }

    private suspend fun processPdfFile(
        localBookId: String,
        fileName: String,
        pdfFile: File,
        onProgress: (processed: Int, total: Int, statusLabel: String) -> Unit
    ): IngestionResult {
        val pageCount = try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PdfRenderer failed to open PDF", e)
            return IngestionResult(
                localBookId = localBookId,
                sourceType = "PDF",
                fileName = fileName,
                pages = emptyList(),
                failedPages = listOf(1),
                extractionStatus = "FAILED",
                extractionError = "Could not read PDF file: ${e.localizedMessage ?: "Invalid PDF"}"
            )
        }

        if (pageCount <= 0) {
            return IngestionResult(
                localBookId = localBookId,
                sourceType = "PDF",
                fileName = fileName,
                pages = emptyList(),
                failedPages = listOf(1),
                extractionStatus = "FAILED",
                extractionError = "PDF has 0 pages."
            )
        }

        // Fast pass: attempt lightweight PDF stream text extraction for digital PDFs
        val rawPdfExtracted = extractDigitalPdfPageStreams(pdfFile, pageCount)
        val pages = mutableListOf<BookPageContent>()
        val failed = mutableListOf<Int>()

        for (i in 0 until pageCount) {
            coroutineContext.ensureActive()
            val pageNum = i + 1
            val heuristicText = rawPdfExtracted.getOrNull(i).orEmpty().trim()
            if (heuristicText.length >= 120) {
                onProgress(pageNum, pageCount, "Extracted text from page $pageNum / $pageCount")
                pages.add(BookPageContent(pageNum, heuristicText, "PDF_TEXT"))
            } else {
                // Scanned or image-heavy page: run multimodal OCR on rendered page bitmap
                onProgress(pageNum, pageCount, "Running AI OCR on page $pageNum / $pageCount")
                val bmp = renderPdfPageBitmap(localBookId, fileName, i, maxWidth = 1280)
                if (bmp == null) {
                    failed.add(pageNum)
                    pages.add(BookPageContent(pageNum, heuristicText, "FAILED", "Could not render page"))
                } else {
                    val ocr = try {
                        aiService.extractTextFromPageImage(bmp, pageNum)
                    } finally {
                        bmp.recycle()
                    }
                    if (ocr.isSuccess && !ocr.getOrNull().isNullOrBlank()) {
                        pages.add(BookPageContent(pageNum, ocr.getOrThrow(), "GEMINI_OCR"))
                    } else if (heuristicText.isNotBlank()) {
                        pages.add(BookPageContent(pageNum, heuristicText, "PDF_TEXT_PARTIAL"))
                    } else {
                        failed.add(pageNum)
                        pages.add(
                            BookPageContent(
                                pageNum,
                                "",
                                "FAILED",
                                ocr.exceptionOrNull()?.localizedMessage ?: "OCR failed"
                            )
                        )
                    }
                }
            }
        }

        savePages(localBookId, pages)
        val successPages = pages.count { it.text.isNotBlank() }
        val status = when {
            successPages == 0 -> "FAILED"
            failed.isNotEmpty() -> "PARTIAL"
            else -> "COMPLETE"
        }
        return IngestionResult(
            localBookId = localBookId,
            sourceType = "PDF",
            fileName = fileName,
            pages = pages,
            failedPages = failed,
            extractionStatus = status,
            extractionError = if (failed.isNotEmpty()) "${failed.size} page(s) require retry." else ""
        )
    }

    private suspend fun processDocxFile(
        localBookId: String,
        fileName: String,
        docxFile: File,
        onProgress: (processed: Int, total: Int, statusLabel: String) -> Unit
    ): IngestionResult {
        onProgress(0, 1, "Parsing DOCX structure...")
        return try {
            val sb = StringBuilder()
            ZipInputStream(FileInputStream(docxFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val parser = Xml.newPullParser()
                        parser.setInput(zis, "UTF-8")
                        var event = parser.eventType
                        while (event != XmlPullParser.END_DOCUMENT) {
                            when (event) {
                                XmlPullParser.START_TAG -> {
                                    if (parser.name == "t") {
                                        sb.append(parser.nextText())
                                    } else if (parser.name == "tab") {
                                        sb.append("    ")
                                    }
                                }
                                XmlPullParser.END_TAG -> {
                                    if (parser.name == "p") {
                                        sb.append("\n")
                                    }
                                }
                            }
                            event = parser.next()
                        }
                        break
                    }
                    entry = zis.nextEntry
                }
            }
            ingestPlainText(localBookId, fileName, "DOCX", sb.toString(), onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "DOCX parse error", e)
            IngestionResult(
                localBookId = localBookId,
                sourceType = "DOCX",
                fileName = fileName,
                pages = emptyList(),
                failedPages = listOf(1),
                extractionStatus = "FAILED",
                extractionError = "Failed to parse DOCX: ${e.localizedMessage ?: "Unknown error"}"
            )
        }
    }

    private suspend fun processPptxFile(
        localBookId: String,
        fileName: String,
        pptxFile: File,
        onProgress: (processed: Int, total: Int, statusLabel: String) -> Unit
    ): IngestionResult {
        onProgress(0, 1, "Extracting PPTX slides...")
        return try {
            val slideMap = mutableMapOf<Int, String>()
            val slideRegex = Regex("""ppt/slides/slide(\d+)\.xml""")
            ZipInputStream(FileInputStream(pptxFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val match = slideRegex.matchEntire(entry.name)
                    if (match != null) {
                        val slideNum = match.groupValues[1].toIntOrNull() ?: 1
                        val bytes = zis.readBytes()
                        val parser = Xml.newPullParser()
                        parser.setInput(bytes.inputStream(), "UTF-8")
                        val sb = StringBuilder()
                        var event = parser.eventType
                        while (event != XmlPullParser.END_DOCUMENT) {
                            when (event) {
                                XmlPullParser.START_TAG -> {
                                    if (parser.name == "t") sb.append(parser.nextText()).append(" ")
                                }
                                XmlPullParser.END_TAG -> {
                                    if (parser.name == "p") sb.append("\n")
                                }
                            }
                            event = parser.next()
                        }
                        slideMap[slideNum] = sb.toString().trim()
                    }
                    entry = zis.nextEntry
                }
            }
            val ordered = slideMap.toSortedMap()
            if (ordered.isEmpty()) {
                return IngestionResult(
                    localBookId = localBookId,
                    sourceType = "PPTX",
                    fileName = fileName,
                    pages = emptyList(),
                    failedPages = listOf(1),
                    extractionStatus = "FAILED",
                    extractionError = "No slides found in PPTX presentation."
                )
            }
            val pages = ordered.entries.mapIndexed { idx, (_, text) ->
                val pageNum = idx + 1
                onProgress(pageNum, ordered.size, "Slide $pageNum / ${ordered.size}")
                BookPageContent(pageNumber = pageNum, text = text, extractionMethod = "PPTX")
            }
            savePages(localBookId, pages)
            IngestionResult(
                localBookId = localBookId,
                sourceType = "PPTX",
                fileName = fileName,
                pages = pages,
                failedPages = emptyList(),
                extractionStatus = "COMPLETE"
            )
        } catch (e: Exception) {
            Log.e(TAG, "PPTX parse error", e)
            IngestionResult(
                localBookId = localBookId,
                sourceType = "PPTX",
                fileName = fileName,
                pages = emptyList(),
                failedPages = listOf(1),
                extractionStatus = "FAILED",
                extractionError = "Failed to parse PPTX: ${e.localizedMessage ?: "Unknown error"}"
            )
        }
    }

    private suspend fun processImageFile(
        localBookId: String,
        fileName: String,
        imageFile: File,
        onProgress: (processed: Int, total: Int, statusLabel: String) -> Unit
    ): IngestionResult {
        onProgress(0, 1, "Running AI OCR on image...")
        val rawBmp = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: return IngestionResult(
                localBookId = localBookId,
                sourceType = "IMAGE",
                fileName = fileName,
                pages = emptyList(),
                failedPages = listOf(1),
                extractionStatus = "FAILED",
                extractionError = "Unable to decode image file."
            )
        val scaled = scaleBitmapForAi(rawBmp, 1600)
        if (scaled !== rawBmp) rawBmp.recycle()

        val ocr = aiService.extractTextFromPageImage(scaled, 1)
        scaled.recycle()

        return if (ocr.isSuccess && !ocr.getOrNull().isNullOrBlank()) {
            val pages = listOf(BookPageContent(1, ocr.getOrThrow(), "GEMINI_OCR"))
            savePages(localBookId, pages)
            onProgress(1, 1, "OCR complete")
            IngestionResult(
                localBookId = localBookId,
                sourceType = "IMAGE",
                fileName = fileName,
                pages = pages,
                failedPages = emptyList(),
                extractionStatus = "COMPLETE"
            )
        } else {
            val pages = listOf(
                BookPageContent(
                    1,
                    "",
                    "FAILED",
                    ocr.exceptionOrNull()?.localizedMessage ?: "OCR failed"
                )
            )
            savePages(localBookId, pages)
            IngestionResult(
                localBookId = localBookId,
                sourceType = "IMAGE",
                fileName = fileName,
                pages = pages,
                failedPages = listOf(1),
                extractionStatus = "FAILED",
                extractionError = ocr.exceptionOrNull()?.localizedMessage ?: "Image OCR failed."
            )
        }
    }

    private fun extractDigitalPdfPageStreams(pdfFile: File, expectedPages: Int): List<String> {
        // Lightweight fallback extractor for uncompressed or FlateDecode PDF content streams.
        return try {
            val rawBytes = pdfFile.readBytes()
            val latin1 = String(rawBytes, Charsets.ISO_8859_1)
            val streamRegex = Regex("""stream\r?\n(.*?)\r?\nendstream""", RegexOption.DOT_MATCHES_ALL)
            val extractedChunks = mutableListOf<String>()

            streamRegex.findAll(latin1).forEach { match ->
                val range = match.groups[1]?.range ?: return@forEach
                val streamBytes = rawBytes.copyOfRange(range.first, (range.last + 1).coerceAtMost(rawBytes.size))
                val contentStr = tryInflate(streamBytes) ?: String(streamBytes, Charsets.ISO_8859_1)
                if (contentStr.contains("BT") && contentStr.contains("ET")) {
                    val text = extractTextOperators(contentStr)
                    if (text.isNotBlank()) extractedChunks.add(text)
                }
            }

            if (extractedChunks.isEmpty()) {
                List(expectedPages) { "" }
            } else if (extractedChunks.size == expectedPages) {
                extractedChunks
            } else {
                // Distribute proportionally if stream count differs from page count
                val joined = extractedChunks.joinToString("\n\n")
                val perPage = (joined.length / expectedPages.coerceAtLeast(1)).coerceAtLeast(1)
                (0 until expectedPages).map { i ->
                    val start = i * perPage
                    val end = if (i == expectedPages - 1) joined.length else ((i + 1) * perPage).coerceAtMost(joined.length)
                    if (start < joined.length) joined.substring(start, end) else ""
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Digital PDF heuristic parser skipped: ${e.message}")
            List(expectedPages) { "" }
        }
    }

    private fun tryInflate(bytes: ByteArray): String? {
        return try {
            val inflater = Inflater()
            inflater.setInput(bytes)
            val buffer = ByteArray(4096)
            val out = ByteArrayOutputStream()
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.needsInput()) break
                if (count > 0) out.write(buffer, 0, count) else break
            }
            inflater.end()
            if (out.size() > 0) out.toString(Charsets.ISO_8859_1.name()) else null
        } catch (_: Exception) {
            null
        }
    }

    internal fun extractTextOperators(stream: String): String {
        val sb = StringBuilder()
        val btEtRegex = Regex("""BT(.*?)ET""", RegexOption.DOT_MATCHES_ALL)
        val tokenRegex = Regex("""\(([^()\\]*(?:\\.[^()\\]*)*)\)|<([0-9A-Fa-f\s]+)>""")
        btEtRegex.findAll(stream).forEach { block ->
            tokenRegex.findAll(block.groupValues[1]).forEach { tok ->
                val parenGroup = tok.groups[1]?.value
                val hexGroup = tok.groups[2]?.value
                when {
                    parenGroup != null -> {
                        val decoded = parenGroup
                            .replace("\\(", "(")
                            .replace("\\)", ")")
                            .replace("\\n", "\n")
                            .replace("\\r", "")
                            .replace("\\t", " ")
                            .replace("\\\\", "\\")
                        sb.append(decoded)
                    }
                    hexGroup != null -> {
                        val decodedHex = decodePdfHexString(hexGroup)
                        if (decodedHex.isNotEmpty()) sb.append(decodedHex)
                    }
                }
            }
            sb.append(" ")
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun decodePdfHexString(rawHex: String): String {
        val clean = rawHex.filter { !it.isWhitespace() }
        if (clean.isEmpty()) return ""
        val padded = if (clean.length % 2 == 1) "${clean}0" else clean
        val bytes = ByteArray(padded.length / 2) { idx ->
            padded.substring(idx * 2, idx * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
        }
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xFE) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        // Check if interleaved UTF-16BE without BOM (common in PDF CID streams with ASCII chars)
        val hasNullHighBytes = bytes.size >= 4 && bytes.size % 2 == 0 &&
            (0 until bytes.size step 2).all { bytes[it].toInt() == 0 }
        return if (hasNullHighBytes) {
            String(bytes, Charsets.UTF_16BE)
        } else {
            buildString(bytes.size) {
                for (b in bytes) {
                    val v = b.toInt() and 0xFF
                    if (v in 32..126 || v >= 160 || v == 10 || v == 9) append(v.toChar())
                }
            }
        }
    }

    private fun paginateText(text: String, maxCharsPerPage: Int): List<String> {
        val paragraphs = text.split(Regex("\n{2,}"))
        val pages = mutableListOf<String>()
        val current = StringBuilder()
        for (para in paragraphs) {
            val trimmed = para.trim()
            if (trimmed.isEmpty()) continue
            if (current.length + trimmed.length + 2 > maxCharsPerPage && current.isNotEmpty()) {
                pages.add(current.toString().trim())
                current.clear()
            }
            if (trimmed.length > maxCharsPerPage) {
                var idx = 0
                while (idx < trimmed.length) {
                    val end = (idx + maxCharsPerPage).coerceAtMost(trimmed.length)
                    pages.add(trimmed.substring(idx, end))
                    idx = end
                }
            } else {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(trimmed)
            }
        }
        if (current.isNotEmpty()) pages.add(current.toString().trim())
        return pages
    }

    private fun queryFileName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && cursor.moveToFirst()) cursor.getString(nameIdx) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleBitmapForAi(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }
}
