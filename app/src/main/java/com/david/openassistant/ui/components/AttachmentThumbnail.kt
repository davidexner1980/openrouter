package com.david.openassistant.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.david.openassistant.data.openrouter.ChatAttachment
import java.io.File
import kotlin.math.max

@Composable
fun AttachmentThumbnail(
    attachment: ChatAttachment,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap = remember(attachment.fileName, attachment.sizeBytes) {
        val file = File(File(context.filesDir, "chat_attachments"), attachment.fileName)
        if (file.isFile) decodeAttachmentThumbnail(file) else null
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = attachment.displayName,
            modifier = modifier.clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text("Image unavailable", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun decodeAttachmentThumbnail(file: File): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > 768) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        },
    )
}
