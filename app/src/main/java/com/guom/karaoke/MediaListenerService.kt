package com.guom.karaoke

import android.content.ComponentName
import android.content.Context
import android.provider.Settings as SystemSettings
import android.service.notification.NotificationListenerService

/**
 * 알림 접근 권한용 빈 리스너. 이 권한이 있어야 다른 앱(유튜브)의 미디어 세션을
 * MediaSessionManager.getActiveSessions() 로 읽고 제어할 수 있다. 알림 내용은 쓰지 않는다.
 */
class MediaListenerService : NotificationListenerService() {
    companion object {
        fun component(context: Context) = ComponentName(context, MediaListenerService::class.java)

        fun isEnabled(context: Context): Boolean {
            val flat = SystemSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = component(context)
            return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
