package com.music.vivi.vivimusic

import android.Manifest
import android.app.Notification
import android.graphics.drawable.Icon
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.music.vivi.MainActivity
import com.music.vivi.R
import com.music.vivi.constants.EnableNotificationsKey
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.get

object UpdateNotificationHelper {
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 1001
    fun cancel(context: Context) { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }

    /** Returns true only when a notification was actually posted, for persistent deduplication. */
    fun showUpdateNotification(context: Context, versionName: String): Boolean {
        if (!context.dataStore.get(EnableNotificationsKey, true) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.app_updates_title), NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).putExtra("tinium_open_updater", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.vivimusicnotification).setContentTitle(context.getString(R.string.tinium_update_available))
                .setContentText(context.getString(R.string.tinium_update_release_version, versionName)).setContentIntent(pending).setAutoCancel(true).build().let { notification ->
                    Notification.Builder.recoverBuilder(context, notification)
                        .setLargeIcon(Icon.createWithResource(context, R.drawable.tinium_logo_background)).build()
                })
            true
        } catch (_: SecurityException) { false }
    }
}
