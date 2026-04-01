package com.zeroclaw.zero.ui

import android.Manifest
import android.os.Build
import org.junit.Assert.*
import org.junit.Test

class PermissionManagerTest {

    @Test
    fun `DANGEROUS_PERMISSIONS contains core permissions`() {
        val perms = PermissionManager.DANGEROUS_PERMISSIONS.toList()
        assertTrue("RECORD_AUDIO missing", Manifest.permission.RECORD_AUDIO in perms)
        assertTrue("POST_NOTIFICATIONS missing", Manifest.permission.POST_NOTIFICATIONS in perms)
        assertTrue("CAMERA missing", Manifest.permission.CAMERA in perms)
        assertTrue("ACCESS_FINE_LOCATION missing", Manifest.permission.ACCESS_FINE_LOCATION in perms)
        assertTrue("READ_CONTACTS missing", Manifest.permission.READ_CONTACTS in perms)
        assertTrue("SEND_SMS missing", Manifest.permission.SEND_SMS in perms)
        assertTrue("CALL_PHONE missing", Manifest.permission.CALL_PHONE in perms)
    }

    @Test
    fun `DANGEROUS_PERMISSIONS includes BLUETOOTH_CONNECT on API 31+`() {
        val perms = PermissionManager.DANGEROUS_PERMISSIONS.toList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue("BLUETOOTH_CONNECT missing on API 31+",
                Manifest.permission.BLUETOOTH_CONNECT in perms)
        }
    }

    @Test
    fun `DANGEROUS_PERMISSIONS has at least 7 entries`() {
        assertTrue("Expected at least 7 dangerous permissions",
            PermissionManager.DANGEROUS_PERMISSIONS.size >= 7)
    }

    @Test
    fun `TOTAL_COUNT includes dangerous plus 2 special permissions`() {
        val expected = PermissionManager.DANGEROUS_PERMISSIONS.size + 2
        assertEquals(expected, PermissionManager.TOTAL_COUNT)
    }

    @Test
    fun `DANGEROUS_PERMISSIONS has no duplicates`() {
        val perms = PermissionManager.DANGEROUS_PERMISSIONS.toList()
        assertEquals("Duplicate permissions found", perms.size, perms.toSet().size)
    }
}
