package com.qoder.swiftpic.data

/**
 * Locates the video payload of a motion photo. Pure JVM code so it can be unit tested.
 *
 * Supported layouts:
 *  - Google Motion Photo v1: XMP GCamera:MicroVideoOffset (distance from EOF)
 *  - Google Motion Photo v2/v3 (container): Item:Semantic="MotionPhoto" + Item:Length
 *  - Samsung: "MotionPhoto_Data" marker followed by an appended MP4
 *  - Generic appended MP4 discovered by a valid ftyp box near EOF
 */
object MotionPhotoParser {

    private const val MIN_VIDEO_BYTES = 8L * 1024L
    private const val MAX_CONTAINER_TAG = 4096

    private val microVideoOffsetRegex =
        Regex("""MicroVideoOffset\s*=\s*["'](\d{1,12})["']""", RegexOption.IGNORE_CASE)
    private val lengthRegex =
        Regex("""(?:Item:)?Length\s*=\s*["'](\d{1,12})["']""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("<[^<>]{0,$MAX_CONTAINER_TAG}>")
    private val samsungMarker = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)

    private val mp4Brands = setOf(
        "isom", "iso2", "iso4", "iso5", "iso6",
        "mp41", "mp42", "avc1", "avc3", "hvc1", "hev1",
        "qt  ", "MSNV", "dash", "M4V ", "M4A ", "3gp4", "3gp5"
    )

    fun parse(head: ByteArray, tail: ByteArray, tailStart: Long, fileSize: Long): MotionPart? {
        if (fileSize <= 0) return null
        val headText = String(head, Charsets.ISO_8859_1)
        val declaresMotion = headText.contains("MicroVideo", ignoreCase = true) ||
            headText.contains("MotionPhoto", ignoreCase = true) ||
            headText.contains("Container:Directory", ignoreCase = true)

        microVideoOffsetRegex.find(headText)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?.let { offsetFromEnd ->
                val part = partFromEnd(fileSize, offsetFromEnd)
                if (part != null) return part
            }

        containerVideoLength(headText, fileSize)?.let { return it }

        samsungMarker(tail, tailStart, fileSize)?.let { return it }

        val isJpeg = head.size >= 2 &&
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
        if (isJpeg || declaresMotion) {
            findMp4InTail(tail, tailStart, fileSize)?.let { return it }
        }

        return null
    }

    private fun partFromEnd(fileSize: Long, length: Long): MotionPart? {
        if (length < MIN_VIDEO_BYTES || length >= fileSize) return null
        val offset = fileSize - length
        if (offset <= 0) return null
        return MotionPart(offset, length)
    }

    private fun containerVideoLength(headText: String, fileSize: Long): MotionPart? {
        var found: MotionPart? = null
        tagRegex.findAll(headText).forEach { match ->
            val tag = match.value
            val lower = tag.lowercase()
            if (!lower.contains("motionphoto")) return@forEach
            if (!lower.contains("semantic") && !lower.contains("video")) return@forEach
            if (!lower.contains("item")) return@forEach
            val length = lengthRegex.find(tag)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: return@forEach
            val part = partFromEnd(fileSize, length)
            if (part != null) found = part
        }
        return found
    }

    private fun samsungMarker(tail: ByteArray, tailStart: Long, fileSize: Long): MotionPart? {
        val markerPos = indexOf(tail, samsungMarker)
        if (markerPos < 0) return null
        val afterMarker = markerPos + samsungMarker.size
        val scanEnd = minOf(tail.size - 8, afterMarker + 64 * 1024)
        var i = afterMarker
        while (i < scanEnd) {
            if (tail[i] == 'f'.code.toByte() && tail[i + 1] == 't'.code.toByte() &&
                tail[i + 2] == 'y'.code.toByte() && tail[i + 3] == 'p'.code.toByte()
            ) {
                val boxStart = if (i >= 4) i - 4 else i
                val boxSize = readUInt32(tail, boxStart)
                val absStart = tailStart + boxStart
                val length = fileSize - absStart
                if (boxSize >= 8 && absStart + boxSize <= fileSize &&
                    length >= MIN_VIDEO_BYTES && absStart > 0
                ) {
                    return MotionPart(absStart, length)
                }
            }
            i++
        }
        return null
    }

    private fun findMp4InTail(tail: ByteArray, tailStart: Long, fileSize: Long): MotionPart? {
        var i = 4
        while (i < tail.size - 8) {
            if (tail[i] == 'f'.code.toByte() && tail[i + 1] == 't'.code.toByte() &&
                tail[i + 2] == 'y'.code.toByte() && tail[i + 3] == 'p'.code.toByte()
            ) {
                val boxStart = i - 4
                val boxSize = readUInt32(tail, boxStart)
                val absStart = tailStart + boxStart
                val length = fileSize - absStart
                val brand = String(tail, i + 4, 4, Charsets.US_ASCII)
                if (brand in mp4Brands && boxSize >= 8 && absStart + boxSize <= fileSize &&
                    length >= MIN_VIDEO_BYTES && absStart > 0
                ) {
                    val windowEnd = minOf(tail.size - 4, i + 2 * 1024 * 1024)
                    var hasPayload = false
                    var j = i + 8
                    while (j < windowEnd) {
                        if (atAscii(tail, j, "moov") || atAscii(tail, j, "mdat")) {
                            hasPayload = true
                            break
                        }
                        j++
                    }
                    if (hasPayload) return MotionPart(absStart, length)
                }
            }
            i++
        }
        return null
    }

    private fun atAscii(data: ByteArray, index: Int, value: String): Boolean {
        if (index + value.length > data.size) return false
        for (k in value.indices) {
            if (data[index + k] != value[k].code.toByte()) return false
        }
        return true
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > data.size) return 0
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }
}
