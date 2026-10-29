package com.music.spotui.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfflineStoreHelpersTest {
    @Test fun enoughSpaceAllowsUnknown() {
        assertTrue(enoughSpace(-1L, 100L))
    }

    @Test fun enoughSpaceRejectsWhenFull() {
        assertFalse(enoughSpace(100L, 200L, 0L))
    }

    @Test fun enoughSpaceAcceptsWhenEnough() {
        assertTrue(enoughSpace(1000L, 100L, 0L))
    }

    @Test fun commitFileReplacesExisting() {
        val dir = File(System.getProperty("java.io.tmpdir"), "t").apply { mkdirs() }
        val from = File(dir, "from").apply { writeText("data") }
        val dest = File(dir, "dest").apply { writeText("old") }
        assertTrue(commitFile(from, dest))
        assertTrue(dest.exists())
        assertFalse(from.exists())
        assertEquals("data", dest.readText())
    }
}