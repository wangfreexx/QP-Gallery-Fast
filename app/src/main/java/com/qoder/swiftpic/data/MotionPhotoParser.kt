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
    private const val MAX_FTYP_CANDIDATES = 64
    private const val MAX_BOX_WALK = 4096
    private const val SAMSUNG_SCAN_WINDOW = 64 * 1024

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
                val part = partFromEnd(fileSize, offsetFromEnd, tail, tailStart)
                if (part != null) return part
            }

        containerVideoLength(headText, fileSize, tail, tailStart)?.let { return it }

        samsungMarker(tail, tailStart, fileSize)?.let { return it }

        val isJpeg = head.size >= 2 &&
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
        if (isJpeg || declaresMotion) {
            findMp4InTail(tail, tailStart, fileSize)?.let { return it }
        }

        return null
    }

    /**
     * Builds a [MotionPart] for a length measured from EOF, but only after
     * confirming that the resulting offset really begins a valid ftyp box in
     * the tail. This stops arbitrary XMP text/numbers from being reported as
     * a video payload.
     */
    private fun partFromEnd(
        fileSize: Long,
        length: Long,
        tail: ByteArray,
        tailStart: Long
    ): MotionPart? {
        if (length < MIN_VIDEO_BYTES || length >= fileSize) return null
        val offset = fileSize - length
        if (offset <= 0) return null
        val rel = (offset - tailStart).toInt()
        if (rel < 0 || !validFtypBox(tail, rel, tailStart, fileSize)) return null
        return MotionPart(offset, length)
    }

    private fun containerVideoLength(
        headText: String,
        fileSize: Long,
        tail: ByteArray,
        tailStart: Long
    ): MotionPart? {
        var found: MotionPart? = null
        tagRegex.findAll(headText).forEach { match ->
            val tag = match.value
            val lower = tag.lowercase()
            if (!lower.contains("motionphoto")) return@forEach
            if (!lower.contains("semantic") && !lower.contains("video")) return@forEach
            if (!lower.contains("item")) return@forEach
            val length = lengthRegex.find(tag)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: return@forEach
            val part = partFromEnd(fileSize, length, tail, tailStart)
            if (part != null) found = part
        }
        return found
    }

    private fun samsungMarker(tail: ByteArray, tailStart: Long, fileSize: Long): MotionPart? {
        val markerPos = indexOf(tail, samsungMarker)
        if (markerPos < 0) return null
        val afterMarker = markerPos + samsungMarker.size
        val scanEnd = minOf(tail.size - 12, afterMarker + SAMSUNG_SCAN_WINDOW)
        var candidates = 0
        var i = afterMarker
        while (i < scanEnd && candidates < MAX_FTYP_CANDIDATES) {
            if (atAscii(tail, i, "ftyp")) {
                candidates++
                val boxStart = i - 4
                if (boxStart >= 0 && validFtypBox(tail, boxStart, tailStart, fileSize)) {
                    val absStart = tailStart + boxStart
                    return MotionPart(absStart, fileSize - absStart)
                }
            }
            i++
        }
        return null
    }

    /**
     * Walks the appended MP4 box structure (size + type) instead of scanning
     * megabytes byte-by-byte for every ftyp occurrence. The number of candidate
     * ftyp boxes and the number of boxes walked are both bounded.
     */
    private fun findMp4InTail(tail: ByteArray, tailStart: Long, fileSize: Long): MotionPart? {
        var candidates = 0
        val limit = tail.size - 8
        var i = 4
        while (i < limit && candidates < MAX_FTYP_CANDIDATES) {
            if (atAscii(tail, i, "ftyp")) {
                candidates++
                val boxStart = i - 4
                if (boxStart >= 0 && validFtypBox(tail, boxStart, tailStart, fileSize) &&
                    hasMp4Payload(tail, boxStart)
                ) {
                    val absStart = tailStart + boxStart
                    return MotionPart(absStart, fileSize - absStart)
                }
            }
            i++
        }
        return null
    }

    /**
     * Validates a real ftyp box at [boxStart] (the 4-byte size field):
     *  - size in [8, remaining file length]
     *  - the type field (boxStart + 4) is exactly "ftyp"
     *  - the major brand (boxStart + 8) is in the MP4 brand whitelist
     *  - the resulting absolute start is a plausible video (> MIN_VIDEO_BYTES)
     */
    private fun validFtypBox(
        data: ByteArray,
        boxStart: Int,
        tailStart: Long,
        fileSize: Long
    ): Boolean {
        if (boxStart < 0 || boxStart + 12 > data.size) return false
        val boxSize = readUInt32(data, boxStart)
        val absStart = tailStart + boxStart
        val remaining = fileSize - absStart
        if (absStart <= 0 || remaining < MIN_VIDEO_BYTES) return false
        if (boxSize < 8 || boxSize > remaining) return false
        if (!atAscii(data, boxStart + 4, "ftyp")) return false
        if (ascii4(data, boxStart + 8) !in mp4Brands) return false
        return true
    }

    /** Steps box-by-box (size + type) looking for a moov/mdat payload. */
    private fun hasMp4Payload(data: ByteArray, boxStart: Int): Boolean {
        var pos = boxStart
        var boxes = 0
        while (boxes < MAX_BOX_WALK && pos + 8 <= data.size) {
            val size = readUInt32(data, pos)
            val type = ascii4(data, pos + 4)
            if (type == "moov" || type == "mdat") return true
            if (size < 8 || size > (data.size - pos).toLong()) break
            pos += size.toInt()
            boxes++
        }
        return false
    }

    private fun ascii4(data: ByteArray, offset: Int): String {
        if (offset < 0 || offset + 4 > data.size) return ""
        return String(data, offset, 4, Charsets.US_ASCII)
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
