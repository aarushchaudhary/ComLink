package com.aarushchaudhary.comlink.messaging

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.aarushchaudhary.comlink.ComLinkApp
import com.aarushchaudhary.comlink.MainActivity
import com.aarushchaudhary.comlink.R

/**
 * Foreground service that keeps the mesh (Bluetooth + [MessagingEngine]) alive while the app is
 * in the background, so messages are delivered and relayed instantly. Controlled by the
 * "Background delivery" switch in Settings.
 *
 * Works from Android 8 (API 26): notification channel and startForegroundService from 26,
 * FOREGROUND_SERVICE permission for 28+, and the connectedDevice type for 34+.
 */
class MeshService : Service() {

    companion object {
        private const val TAG = "MeshService"
        private const val CHANNEL_ID = "mesh_background"
        private const val NOTIFICATION_ID = 1001

        /** Settings switch; on by default. */
        val BACKGROUND_DELIVERY_PREF = booleanPreferencesKey("background_delivery")

        /** Android 12+ needs the runtime Bluetooth permissions (Android 14 refuses the service type without them). */
        fun hasBluetoothPermissions(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        }

        /** Starts or stops the service to match the switch. Call while the app is on screen. */
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, MeshService::class.java)
            if (enabled && hasBluetoothPermissions(context)) {
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    // e.g. Android 12+ refusing a start from the background
                    Log.w(TAG, "Could not start background delivery: ${e.message}")
                }
            } else {
                context.stopService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasBluetoothPermissions(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            Log.w(TAG, "Could not enter the foreground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        // Safe to call repeatedly: it only starts Bluetooth once
        (application as ComLinkApp).bluetoothService.startListening()
        // If Android kills the process, bring the mesh back when it can
        return START_STICKY
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.background_delivery_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // If notifications are denied (Android 13+), Android hides this but the service still runs
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mesh)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.background_delivery_notification))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .build()
    }
}
