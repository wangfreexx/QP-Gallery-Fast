package com.qoder.swiftpic.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import com.qoder.swiftpic.data.Photo
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

class ImagePrintAdapter(
    private val context: Context,
    private val photo: Photo
) : PrintDocumentAdapter() {

    private var layoutAttributes: PrintAttributes? = null

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
        layoutAttributes = newAttributes
        val info = PrintDocumentInfo.Builder(photo.name.ifBlank { "image" })
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
            .setPageCount(1)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        val attributes = layoutAttributes ?: PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .build()
        val pdf = PrintedPdfDocument(context, attributes)
        try {
            val page = pdf.startPage(0)
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            val bitmap = decodeBitmap()
            if (bitmap != null) {
                val margin = 24f
                val pageWidth = page.info.pageWidth.toFloat()
                val pageHeight = page.info.pageHeight.toFloat()
                val scale = min(
                    (pageWidth - margin * 2f) / bitmap.width.toFloat(),
                    (pageHeight - margin * 2f) / bitmap.height.toFloat()
                )
                val drawWidth = bitmap.width * scale
                val drawHeight = bitmap.height * scale
                val left = (pageWidth - drawWidth) / 2f
                val top = (pageHeight - drawHeight) / 2f
                canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(left, top, left + drawWidth, top + drawHeight),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
                bitmap.recycle()
            }
            pdf.finishPage(page)
            FileOutputStream(destination.fileDescriptor).use { pdf.writeTo(it) }
            callback.onWriteFinished(arrayOf(PageRange(0, 0)))
        } catch (t: Throwable) {
            callback.onWriteFailed(t.message ?: "print failed")
        } finally {
            pdf.close()
        }
    }

    private fun decodeBitmap(): Bitmap? {
        return runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, photo.uri)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maxDimension = 3000
                val w = info.size.width
                val h = info.size.height
                if (w > 0 && h > 0) {
                    val scale = min(1f, maxDimension.toFloat() / max(w, h))
                    if (scale < 1f) {
                        decoder.setTargetSize(
                            (w * scale).toInt().coerceAtLeast(1),
                            (h * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            }
            if (decoded.config == Bitmap.Config.ARGB_8888) {
                decoded
            } else {
                decoded.copy(Bitmap.Config.ARGB_8888, false) ?: decoded
            }
        }.getOrNull()
    }
}
