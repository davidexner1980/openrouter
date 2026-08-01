package com.david.openassistant.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.david.openassistant.data.openrouter.ChatAttachment
import com.david.openassistant.data.openrouter.ChatAttachmentKind
import com.david.openassistant.domain.attachments.calculateImageSampleSize
import com.david.openassistant.domain.attachments.scaledImageDimensions
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

class AttachmentStore(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val directory = File(appContext.filesDir, ATTACHMENTS_DIRECTORY).apply { mkdirs() }

    fun importImage(uri: Uri): ChatAttachment {
        val mimeType = resolver.getType(uri).orEmpty()
        require(mimeType.startsWith("image/")) { "Choose an image file." }

        val sourceSize = querySize(uri)
        require(sourceSize == null || sourceSize <= MAX_SOURCE_IMAGE_BYTES) {
            "The selected image is too large. Choose an image under 25 MB."
        }
        val sourceName = queryDisplayName(uri)
            .replace(Regex("[\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "Selected image" }
        val decoded = decodeScaledBitmap(uri)
        val oriented = rotateFromExif(uri, decoded)
        if (oriented !== decoded) decoded.recycle()

        val targetDimensions = scaledImageDimensions(
            width = oriented.width,
            height = oriented.height,
            maxDimension = MAX_IMAGE_DIMENSION,
        )
        val scaled = if (
            targetDimensions.width != oriented.width ||
            targetDimensions.height != oriented.height
        ) {
            oriented.scale(
                targetDimensions.width,
                targetDimensions.height,
                filter = true,
            ).also {
                if (it !== oriented) oriented.recycle()
            }
        } else {
            oriented
        }

        val flattened = flattenToWhiteBackground(scaled)
        if (flattened !== scaled) scaled.recycle()

        val fileName = "${UUID.randomUUID()}.jpg"
        val target = File(directory, fileName)
        try {
            writeJpeg(flattened, target, JPEG_QUALITY)
            if (target.length() > MAX_STORED_IMAGE_BYTES) {
                writeJpeg(flattened, target, JPEG_FALLBACK_QUALITY)
            }
            require(target.length() in 1L..MAX_STORED_IMAGE_BYTES) {
                "The selected image is too large after optimization. Choose a smaller image."
            }
            return ChatAttachment(
                id = UUID.randomUUID().toString(),
                kind = ChatAttachmentKind.IMAGE,
                displayName = sourceName.take(MAX_DISPLAY_NAME_LENGTH),
                mimeType = "image/jpeg",
                fileName = fileName,
                sizeBytes = target.length(),
                width = flattened.width,
                height = flattened.height,
            )
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            flattened.recycle()
        }
    }

    fun importPdf(uri: Uri): ChatAttachment {
        val mimeType = resolver.getType(uri).orEmpty()
        require(mimeType.lowercase(Locale.US) == "application/pdf") { "Choose a PDF file." }

        val sourceSize = querySize(uri)
        require(sourceSize == null || sourceSize <= MAX_SOURCE_PDF_BYTES) {
            "The selected PDF is too large. Choose a file under 50 MB."
        }
        val sourceName = queryDisplayName(uri)
            .replace(Regex("[\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "Selected document" }

        val fileName = "${UUID.randomUUID()}.pdf"
        val target = File(directory, fileName)
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("The selected PDF could not be opened.")
            
            return ChatAttachment(
                id = UUID.randomUUID().toString(),
                kind = ChatAttachmentKind.PDF,
                displayName = sourceName.take(MAX_DISPLAY_NAME_LENGTH),
                mimeType = "application/pdf",
                fileName = fileName,
                sizeBytes = target.length(),
                pageCount = 0
            )
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun toDataUrl(attachment: ChatAttachment): String {
        require(attachment.kind == ChatAttachmentKind.IMAGE) { "Unsupported attachment type." }
        val file = resolveFile(attachment.fileName)
        require(file.isFile) { "The attached image is no longer available on this device." }
        require(file.length() in 1L..MAX_STORED_IMAGE_BYTES) { "The attached image is invalid or too large." }
        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return "data:${attachment.mimeType};base64,$encoded"
    }

    fun delete(attachment: ChatAttachment) {
        runCatching { resolveFile(attachment.fileName).delete() }
    }

    fun pruneTo(allowedFileNames: Set<String>) {
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.name !in allowedFileNames) {
                file.delete()
            }
        }
    }

    private fun decodeScaledBitmap(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected image could not be decoded." }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateImageSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxDimension = MAX_IMAGE_DIMENSION,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: error("The selected image could not be opened.")
    }

    private fun rotateFromExif(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun flattenToWhiteBackground(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        return createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { target ->
            Canvas(target).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, 0f, 0f, null)
            }
        }
    }

    private fun writeJpeg(bitmap: Bitmap, target: File, quality: Int) {
        FileOutputStream(target, false).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                "The image could not be optimized."
            }
        }
    }

    private fun querySize(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    }.getOrNull().orEmpty()

    private fun resolveFile(fileName: String): File {
        require(fileName.isNotBlank() && fileName == File(fileName).name) { "Invalid attachment file name." }
        val file = File(directory, fileName)
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "Invalid attachment path." }
        return file
    }

    private companion object {
        const val ATTACHMENTS_DIRECTORY = "chat_attachments"
        const val MAX_IMAGE_DIMENSION = 1920
        const val MAX_SOURCE_IMAGE_BYTES = 25L * 1024L * 1024L
        const val MAX_STORED_IMAGE_BYTES = 5L * 1024L * 1024L
        const val MAX_SOURCE_PDF_BYTES = 50L * 1024L * 1024L
        const val JPEG_QUALITY = 85
        const val JPEG_FALLBACK_QUALITY = 70
        const val MAX_DISPLAY_NAME_LENGTH = 120
    }
}
