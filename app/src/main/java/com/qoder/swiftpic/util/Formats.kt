package com.qoder.swiftpic.util

import android.content.Context
import android.text.format.Formatter
import com.qoder.swiftpic.data.Photo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formats {

    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA)
    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)

    fun dateTime(ms: Long): String {
        if (ms <= 0) return "日期未知"
        return dateTimeFormatter.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }

    fun dayLabel(ms: Long): String {
        if (ms <= 0) return "日期未知"
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when (day) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> dateFormatter.format(day)
        }
    }

    fun cacheSize(context: Context, bytes: Long): String =
        if (bytes <= 0L) "0 B" else Formatter.formatFileSize(context, bytes)

    fun size(context: Context, bytes: Long): String {
        return if (bytes <= 0) "未知" else Formatter.formatFileSize(context, bytes)
    }

    fun dimensions(width: Int, height: Int): String {
        return if (width > 0 && height > 0) "${width} × ${height}" else "未知"
    }

    fun shortSubtitle(photo: Photo): String {
        val date = if (photo.sortTimeMs > 0) dayLabel(photo.sortTimeMs) else "日期未知"
        val dims = dimensions(photo.width, photo.height)
        return if (dims == "未知") date else "$date · $dims"
    }
}
