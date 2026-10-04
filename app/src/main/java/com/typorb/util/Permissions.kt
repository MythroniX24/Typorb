package com.typorb.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import com.typorb.service.TyporbAccessibilityService

/**
 * Permission state checks and the deep links into system settings used by the dashboard.
 */
object Permissions {

    /**
     * Reads the system's enabled-accessibility-services list.
     *
     * This is the only reliable way to know whether our [TyporbAccessibilityService] is running;
     * `isEnabled` on the service instance is not observable from outside.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, TyporbAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val candidate = splitter.next().trim()
            if (candidate.equals(expected, ignoreCase = true)) return true
            // Some OEMs store a shortened component name.
            if (candidate.substringAfterLast('/').equals(
                    TyporbAccessibilityService::class.java.simpleName,
                    ignoreCase = true,
                )
            ) {
                return true
            }
        }
        return false
    }    fun isMicrophonePermissionGranted(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Whether "Display over other apps" is granted.
     *
     * Only consulted to decide whether the orb may fall back to `TYPE_APPLICATION_OVERLAY` on OEM
     * builds that refuse the accessibility window type. The app is fully functional without it.
     */
    fun isOverlayPermissionGranted(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /** Opens the accessibility settings list, pre-focused on Typorb where supported. */
    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the per-app screen where "Display over other apps" is granted. */
    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** App details page, used for the microphone permission. */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}