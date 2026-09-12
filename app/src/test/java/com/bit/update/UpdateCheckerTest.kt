package com.bit.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun testIsNewerVersion() {
        val checker = UpdateCheckerHelper()

        // Newer minor/patch
        assertTrue(checker.isNewerVersion("2.1.2", "v2.1.3"))
        assertTrue(checker.isNewerVersion("2.1.2", "2.1.3"))
        assertTrue(checker.isNewerVersion("2.1.2", "v2.2.0"))
        assertTrue(checker.isNewerVersion("2.1.2", "v3.0.0"))

        // Same version
        assertFalse(checker.isNewerVersion("2.1.2", "v2.1.2"))
        assertFalse(checker.isNewerVersion("2.1.2", "2.1.2"))
        assertFalse(checker.isNewerVersion("v2.1.2", "v2.1.2"))

        // Older version
        assertFalse(checker.isNewerVersion("2.1.2", "v2.1.1"))
        assertFalse(checker.isNewerVersion("2.1.2", "v2.0.9"))
        assertFalse(checker.isNewerVersion("2.1.2", "v1.9.9"))

        // Versions with different length components
        assertTrue(checker.isNewerVersion("2.1", "2.1.1"))
        assertFalse(checker.isNewerVersion("2.1.1", "2.1"))
    }

    @Test
    fun testPlayStoreInstallerPackageNames() {
        val playStoreInstallers = UpdateChecker.PLAY_STORE_INSTALLERS

        assertTrue(playStoreInstallers.contains("com.android.vending"))
        assertTrue(playStoreInstallers.contains("com.google.android.feedback"))

        assertFalse(playStoreInstallers.contains("com.google.android.packageinstaller"))
        assertFalse(playStoreInstallers.contains("com.android.packageinstaller"))
        assertFalse(playStoreInstallers.contains("com.android.chrome"))
        assertFalse(playStoreInstallers.contains("org.mozilla.firefox"))
    }

    // Lightweight helper to test internal comparison without needing Android Context
    private class UpdateCheckerHelper {
        fun isNewerVersion(current: String, latest: String): Boolean {
            val cur = current.removePrefix("v").removePrefix("V")
                .split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val lat = latest.removePrefix("v").removePrefix("V")
                .split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

            val maxLen = maxOf(cur.size, lat.size)
            for (i in 0 until maxLen) {
                val c = cur.getOrElse(i) { 0 }
                val l = lat.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        }
    }
}
