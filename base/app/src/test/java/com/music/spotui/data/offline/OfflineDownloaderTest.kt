package com.music.spotui.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloaderTest {
    @Test fun contentRangeTotalReadsTheFullSize() {
        assertEquals(1234L, OfflineDownloader.contentRangeTotal("bytes 0-99/1234"))
        assertNull(OfflineDownloader.contentRangeTotal("bytes 0-99/*"))
        assertNull(OfflineDownloader.contentRangeTotal(null))
    }

    @Test fun normalizeIsrcAcceptsDashesAndCase() {
        assertEquals("USUM71703861", OfflineDownloader.normalizeIsrc("us-um7-17-03861"))
        assertEquals("FRZ039800212", OfflineDownloader.normalizeIsrc("FR Z03 98 00212"))
    }

    @Test fun normalizeIsrcRejectsOtherIds() {
        assertNull(OfflineDownloader.normalizeIsrc(""))
        assertNull(OfflineDownloader.normalizeIsrc("0602557362806")) // a UPC
        assertNull(OfflineDownloader.normalizeIsrc("USUM7170386"))   // one digit short
    }

    @Test fun lengthMismatchRefusesPreviewsAndOtherVersions() {
        assertNull(OfflineDownloader.lengthMismatch(163, 163_400))
        assertNull(OfflineDownloader.lengthMismatch(160, 163_400))   // a few seconds trimmed
        assertNull(OfflineDownloader.lengthMismatch(175, 163_400))   // trailing silence
        assertNull(OfflineDownloader.lengthMismatch(30, 0))          // the song's length unknown
        assertTrue(OfflineDownloader.lengthMismatch(30, 163_400)!!.contains("preview"))
        assertTrue(OfflineDownloader.lengthMismatch(312, 163_400)!!.contains("another version"))
    }

    @Test fun localIdsNeverCollideWithMediaStoreIds() {
        for (id in listOf("4uLU6hMCjMI75M1A2tKUQC", "0", "", "zzzzzzzzzzzzzzzzzzzzzz")) {
            assertTrue(OfflineStore.localIdOf(id) < 0)
        }
    }

    @Test fun flacDurationSecParsesValidHeader() {
        // Valid fLaC header with 44100 Hz, 2 channels, 16-bit, 44100 samples (1 second)
        val validHeader = "fLaC".toByteArray() + ByteArray(4) + 
            byteArrayOf(0xAC.toByte(), 0x22, 0x00, 0x00, 0x00, 0x02, 0xAC.toByte(), 0x22)
        assertEquals(1, OfflineDownloader.flacDurationSec(validHeader))
    }

    @Test fun flacDurationSecRejectsInvalidMagic() {
        val invalid = "xxxx".toByteArray() + ByteArray(38)
        assertNull(OfflineDownloader.flacDurationSec(invalid))
    }
}
