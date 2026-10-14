package com.music.vivi.playback

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.music.vivi.R

/** Preserve Media3's controls/artwork; brand only notifications without album artwork. */
@OptIn(UnstableApi::class)
class BrandedMediaNotificationProvider(context: Context, private val delegate: MediaNotification.Provider) :
    MediaNotification.Provider by delegate {
    private val appContext = context.applicationContext

    private fun withBranding(notification: MediaNotification): MediaNotification {
        if (notification.notification.getLargeIcon() != null) return notification
        val branded = Notification.Builder.recoverBuilder(appContext, notification.notification)
            .setLargeIcon(Icon.createWithResource(appContext, R.drawable.tinium_logo_background)).build()
        return MediaNotification(notification.notificationId, branded)
    }

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification = withBranding(delegate.createNotification(
        mediaSession, customLayout, actionFactory,
        { onNotificationChangedCallback.onNotificationChanged(withBranding(it)) },
    ))
}
