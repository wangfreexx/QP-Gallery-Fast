package com.qoder.swiftpic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MotionPhotoParserTest {

    @Test
    fun googleMicroVideoOffset() {
        val head = (
            "<x:xmpmeta><rdf:Description " +
                "GCamera:MotionPhoto=\"1\" GCamera:MicroVideo=\"1\" " +
                "GCamera:MicroVideoOffset=\"10000\"/></x:xmpmeta>"
            ).toByteArray(Charsets.UTF_8)
        val fileSize = 1_000_000L
        val part = MotionPhotoParser.parse(head, ByteArray(0), fileSize, fileSize)
        assertNotNull(part)
        assertEquals(990_000L, part!!.offset)
        assertEquals(10_000L, part.length)
    }

    @Test
    fun containerDirectoryMotionPhoto() {
        val head = (
            "<x:xmpmeta><Container:Directory><rdf:Seq><rdf:li><Container:Item " +
                "Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"900000\"/>" +
                "</rdf:li><rdf:li><Container:Item Item:Mime=\"video/mp4\" " +
                "Item:Semantic=\"MotionPhoto\" Item:Length=\"50000\"/></rdf:li></rdf:Seq>" +
                "</Container:Directory></x:xmpmeta>"
            ).toByteArray(Charsets.UTF_8)
        val fileSize = 1_000_000L
        val part = MotionPhotoParser.parse(head, ByteArray(0), fileSize, fileSize)
        assertNotNull(part)
        assertEquals(950_000L, part!!.offset)
        assertEquals(50_000L, part.length)
    }

    @Test
    fun samsungMarker() {
        val tailStart = 1_000_000L
        val tail = ByteArray(40_000)
        val marker = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)
        marker.copyInto(tail, 100)
        val ftypOffset = 116 + 4
        tail[116] = 0
        tail[117] = 0
        tail[118] = 0
        tail[119] = 24
        "ftypmp42".toByteArray(Charsets.US_ASCII).copyInto(tail, ftypOffset)
        "mdat".toByteArray(Charsets.US_ASCII).copyInto(tail, 20_000)
        val fileSize = tailStart + tail.size
        val part = MotionPhotoParser.parse(ByteArray(0), tail, tailStart, fileSize)
        assertNotNull(part)
        assertEquals(tailStart + 116, part!!.offset)
        assertEquals(tail.size - 116L, part.length)
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
        "ftypheic".toByteArray(Charsets.US_ASCII).copyInto(head, 4)
        val tail = ByteArray(16_000)
        // A HEIC brand ftyp must not be mistaken for a motion video.
        "ftypheic".toByteArray(Charsets.US_ASCII).copyInto(tail, 100)
        assertNull(MotionPhotoParser.parse(head, tail, fileSize - tail.size, fileSize))
    }

    @Test
    fun realSamsungSamples() {
        val dir = File(System.getenv("MOTION_SAMPLES") ?: "Z:\\TEMP\\motion")
        assumeTrue(dir.isDirectory)
        val names = listOf("photo.jpg", "photo.heic", "photo-sg22-ultra.heic")
        names.forEach { name ->
            val file = File(dir, name)
            assumeTrue(file.isFile)
            val data = file.readBytes()
            val head = data.copyOfRange(0, minOf(data.size, 512 * 1024))
            val tailLen = minOf(data.size, 16 * 1024 * 1024)
            val tailStart = data.size - tailLen
            val tail = data.copyOfRange(tailStart, data.size)
            val part = MotionPhotoParser.parse(head, tail, tailStart.toLong(), data.size.toLong())
            assertNotNull("no motion video found in $name", part)
            val offset = part!!.offset.toInt()
            val boxType = String(data, offset + 4, 4, Charsets.US_ASCII)
            assertEquals("$name should point at an ftyp box", "ftyp", boxType)
            val moov = indexOf(data, "moov".toByteArray(), offset)
            assertTrue("$name should contain moov after the video start", moov > offset)
            assertTrue(part.length > 1_000_000L)
        }
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
