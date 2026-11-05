package com.qoder.swiftpic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All fixtures are built in memory. The parser now validates real ftyp boxes,
 * so a "head says there is a video" sample must also contain a valid MP4 box
 * at the claimed offset -- otherwise the test would only verify arithmetic.
 */
class MotionPhotoParserTest {

    @Test
    fun googleMicroVideoOffset() {
        val fileSize = 1_000_000L
        val videoLength = 10_000L
        val tail = ByteArray(20_000)
        val tailStart = fileSize - tail.size
        putFtyp(tail, relativeOffset(fileSize, videoLength, tailStart), 24, "isom")

        val part = MotionPhotoParser.parse(
            headWithMicroVideo(videoLength), tail, tailStart, fileSize
        )
        assertNotNull(part)
        assertEquals(990_000L, part!!.offset)
        assertEquals(videoLength, part.length)
    }

    @Test
    fun containerDirectoryMotionPhoto() {
        val fileSize = 1_000_000L
        val videoLength = 50_000L
        val tail = ByteArray(60_000)
        val tailStart = fileSize - tail.size
        putFtyp(tail, relativeOffset(fileSize, videoLength, tailStart), 24, "mp42")

        val head = (
            "<x:xmpmeta><Container:Directory><rdf:Seq><rdf:li><Container:Item " +
                "Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"900000\"/>" +
                "</rdf:li><rdf:li><Container:Item Item:Mime=\"video/mp4\" " +
                "Item:Semantic=\"MotionPhoto\" Item:Length=\"$videoLength\"/></rdf:li></rdf:Seq>" +
                "</Container:Directory></x:xmpmeta>"
            ).toByteArray(Charsets.UTF_8)
        val part = MotionPhotoParser.parse(head, tail, tailStart, fileSize)
        assertNotNull(part)
        assertEquals(950_000L, part!!.offset)
        assertEquals(videoLength, part.length)
    }

    @Test
    fun samsungMarker() {
        val tailStart = 1_000_000L
        val tail = ByteArray(40_000)
        "MotionPhoto_Data".toByteArray(Charsets.US_ASCII).copyInto(tail, 100)
        putFtyp(tail, 116, 24, "mp42")
        "mdat".toByteArray(Charsets.US_ASCII).copyInto(tail, 20_000)
        val fileSize = tailStart + tail.size
        val part = MotionPhotoParser.parse(ByteArray(0), tail, tailStart, fileSize)
        assertNotNull(part)
        assertEquals(tailStart + 116, part!!.offset)
        assertEquals(tail.size - 116L, part.length)
    }

    /** Replaces the old environment-dependent realSamsungSamples test. */
    @Test
    fun syntheticSamsungMotionPhoto() {
        val headLength = 4096
        val tailLength = 40_000
        val fileSize = (headLength + tailLength).toLong()
        val data = ByteArray(fileSize.toInt())
        data[0] = 0xFF.toByte()
        data[1] = 0xD8.toByte()
        val marker = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)
        marker.copyInto(data, headLength)
        val ftypOffset = headLength + marker.size
        putFtyp(data, ftypOffset, 24, "isom")
        "moov".toByteArray(Charsets.US_ASCII).copyInto(data, ftypOffset + 24)

        val head = data.copyOfRange(0, headLength)
        val tail = data.copyOfRange(headLength, data.size)
        val part = MotionPhotoParser.parse(head, tail, headLength.toLong(), fileSize)
        assertNotNull(part)
        assertEquals(ftypOffset.toLong(), part!!.offset)
        val offset = part.offset.toInt()
        assertTrue(offset + 8 <= data.size)
        assertEquals("ftyp", String(data, offset + 4, 4, Charsets.US_ASCII))
        assertTrue(indexOf(data, "moov".toByteArray(), offset) > offset)
        assertEquals(fileSize - offset, part.length)
    }

    @Test
    fun stillJpegWithoutMotionReturnsNull() {
        val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(4096) { 1 }
        val tail = ByteArray(16_000)
        assertNull(MotionPhotoParser.parse(head, tail, 0, head.size + tail.size.toLong()))
    }

    @Test
    fun stillHeicWithOwnFtypReturnsNull() {
        val fileSize = 200_000L
        val head = ByteArray(4096)
        head[0] = 0xFF.toByte()
        head[1] = 0xD8.toByte()
        "ftypheic".toByteArray(Charsets.US_ASCII).copyInto(head, 4)
        val tail = ByteArray(16_000)
        putFtyp(tail, 100, 24, "heic") // valid box, but HEIC is not an MP4 brand
        assertNull(MotionPhotoParser.parse(head, tail, fileSize - tail.size, fileSize))
    }

    @Test
    fun microVideoOffsetWithEmptyTailIsRejected() {
        val fileSize = 1_000_000L
        // Simulates a failed tail read: the XMP offset must not be trusted.
        assertNull(
            MotionPhotoParser.parse(
                headWithMicroVideo(10_000L), ByteArray(0), fileSize, fileSize
            )
        )
    }

    @Test
    fun microVideoOffsetOutsideTailIsRejected() {
        val fileSize = 1_000_000L
        val tail = ByteArray(4_096)
        val tailStart = fileSize - tail.size
        // offset 990_000 lies before tailStart -> no way to validate the box.
        assertNull(
            MotionPhotoParser.parse(headWithMicroVideo(10_000L), tail, tailStart, fileSize)
        )
    }

    @Test
    fun invalidFtypBoxIsRejected() {
        val fileSize = 1_000_000L
        val videoLength = 10_000L
        val tail = ByteArray(20_000)
        val tailStart = fileSize - tail.size
        val rel = relativeOffset(fileSize, videoLength, tailStart)
        putFtyp(tail, rel, 4, "isom") // box size below the 8-byte minimum
        assertNull(
            MotionPhotoParser.parse(headWithMicroVideo(videoLength), tail, tailStart, fileSize)
        )
    }

    @Test
    fun unknownBrandIsRejected() {
        val fileSize = 1_000_000L
        val videoLength = 10_000L
        val tail = ByteArray(20_000)
        val tailStart = fileSize - tail.size
        putFtyp(tail, relativeOffset(fileSize, videoLength, tailStart), 24, "abcd")
        assertNull(
            MotionPhotoParser.parse(headWithMicroVideo(videoLength), tail, tailStart, fileSize)
        )
    }

    @Test
    fun lengthBeyondFileSizeIsRejected() {
        val fileSize = 1_000L
        val head = headWithMicroVideo(2_000L)
        assertNull(MotionPhotoParser.parse(head, ByteArray(512), 488L, fileSize))
    }

    @Test
    fun invalidBoxSizeBeyondRemainingIsRejected() {
        val fileSize = 1_000_000L
        val videoLength = 10_000L
        val tail = ByteArray(20_000)
        val tailStart = fileSize - tail.size
        putFtyp(tail, relativeOffset(fileSize, videoLength, tailStart), 99_999, "isom")
        assertNull(
            MotionPhotoParser.parse(headWithMicroVideo(videoLength), tail, tailStart, fileSize)
        )
    }

    private fun headWithMicroVideo(offsetFromEnd: Long): ByteArray =
        (
            "<x:xmpmeta><rdf:Description GCamera:MotionPhoto=\"1\" " +
                "GCamera:MicroVideo=\"1\" " +
                "GCamera:MicroVideoOffset=\"$offsetFromEnd\"/></x:xmpmeta>"
            ).toByteArray(Charsets.UTF_8)

    private fun relativeOffset(fileSize: Long, length: Long, tailStart: Long): Int =
        (fileSize - length - tailStart).toInt()

    /** Writes a minimal but structurally valid ftyp box at [offset]. */
    private fun putFtyp(data: ByteArray, offset: Int, boxSize: Int, brand: String) {
        require(brand.length == 4)
        data[offset] = (boxSize ushr 24).toByte()
        data[offset + 1] = (boxSize ushr 16).toByte()
        data[offset + 2] = (boxSize ushr 8).toByte()
        data[offset + 3] = boxSize.toByte()
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(data, offset + 4)
        brand.toByteArray(Charsets.US_ASCII).copyInto(data, offset + 8)
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
