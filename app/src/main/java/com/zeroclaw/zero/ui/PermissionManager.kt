package com.zeroclaw.zero.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionManager {

    /** All standard dangerous permissions the app needs (runtime-requestable). */
    val DANGEROUS_PERMISSIONS: Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()

    /** Total count of all permissions (dangerous + special). */
    val TOTAL_COUNT: Int get() = DANGEROUS_PERMISSIONS.size + 2 // +WRITE_SETTINGS, +DND access

    // ── Check methods ────────────────────────────────────────────────────────

    fun getMissingDangerousPermissions(context: Context): List<String> =
        DANGEROUS_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun canWriteSettings(context: Context): Boolean =
        Settings.System.canWrite(context)

    fun hasDndAccess(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    /** Count of all granted permissions (dangerous + special). */
    fun grantedCount(context: Context): Int {
        val dangerousGranted = DANGEROUS_PERMISSIONS.size - getMissingDangerousPermissions(context).size
        val specialGranted = (if (canWriteSettings(context)) 1 else 0) +
            (if (hasDndAccess(context)) 1 else 0)
        return dangerousGranted + specialGranted
    }

    /** True when every permission (dangerous + special) is granted. */
    fun allGranted(context: Context): Boolean = grantedCount(context) == TOTAL_COUNT

    // ── Intent builders for special permissions ──────────────────────────────

    fun writeSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun dndAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
