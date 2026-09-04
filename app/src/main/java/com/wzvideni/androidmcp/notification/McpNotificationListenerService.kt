package com.wzvideni.androidmcp.notification

import android.app.Notification
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.wzvideni.androidmcp.engine.RootBridge
import com.wzvideni.androidmcp.engine.ShizukuBridge
import com.wzvideni.androidmcp.model.NotificationItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentLinkedDeque

class McpNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "McpNotificationService"
        private const val MAX_HISTORY = 100

        var instance: McpNotificationListenerService? = null
            private set

        val isRunning: Boolean get() = instance != null

        val notificationHistory = ConcurrentLinkedDeque<NotificationItem>()
        private val _notificationEvents = MutableSharedFlow<NotificationItem>(extraBufferCapacity = 64)
        val notificationEvents = _notificationEvents.asSharedFlow()

        fun isPermissionGranted(context: Context): Boolean {
            val pkgName = context.packageName
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            return flat != null && flat.contains(pkgName)
        }

        fun extractNotificationItem(sbn: StatusBarNotification): NotificationItem {
            val n = sbn.notification
            val extras = n.extras

            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras?.getCharSequence("android.title")?.toString()
            val text = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras?.getCharSequence("android.text")?.toString()
            val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
                ?: extras?.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()

            return NotificationItem(
                id = sbn.id,
                key = sbn.key,
                packageName = sbn.packageName,
                title = title?.trim()?.ifBlank { null },
                text = text?.trim()?.ifBlank { null },
                subText = subText?.trim()?.ifBlank { null },
                postTime = sbn.postTime,
                isClearable = sbn.isClearable
            )
        }

        /**
         * Fallback mechanism: queries notification dump via Shizuku or Root when NotificationListenerService is not granted.
         */
        suspend fun queryNotificationsViaShell(): List<NotificationItem> {
            val (code, out) = if (ShizukuBridge.hasPermission()) {
                ShizukuBridge.exec("dumpsys", "notification", "--noredact")
            } else if (RootBridge.isRootAvailable()) {
                RootBridge.exec("dumpsys notification --noredact")
            } else {
                return emptyList()
            }

            if (code != 0 || out.isBlank()) return emptyList()

            val items = mutableListOf<NotificationItem>()
            val lines = out.lines()
            var currentPkg: String? = null
            var currentTitle: String? = null
            var currentText: String? = null
            var currentKey: String? = null

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("NotificationRecord(") || trimmed.startsWith("NotificationRecord{")) {
                    if (currentPkg != null && (currentTitle != null || currentText != null)) {
                        items.add(
                            NotificationItem(
                                packageName = currentPkg,
                                key = currentKey,
                                title = currentTitle,
                                text = currentText
                            )
                        )
                    }
                    currentPkg = Regex("pkg=([a-zA-Z0-9_.]+)").find(trimmed)?.groupValues?.get(1)
                    currentKey = Regex("key=([^\\s,]+)").find(trimmed)?.groupValues?.get(1)
                    currentTitle = null
                    currentText = null
                } else if (trimmed.startsWith("android.title=")) {
                    currentTitle = trimmed.substringAfter("android.title=").trim().removeSurrounding("\"")
                } else if (trimmed.startsWith("android.text=")) {
                    currentText = trimmed.substringAfter("android.text=").trim().removeSurrounding("\"")
                } else if (trimmed.startsWith("android.bigText=")) {
                    currentText = trimmed.substringAfter("android.bigText=").trim().removeSurrounding("\"")
                }
            }

            if (currentPkg != null && (currentTitle != null || currentText != null)) {
                items.add(
                    NotificationItem(
                        packageName = currentPkg,
                        key = currentKey,
                        title = currentTitle,
                        text = currentText
                    )
                )
            }

            return items
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "McpNotificationListenerService connected")
        refreshActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) instance = null
        Log.i(TAG, "McpNotificationListenerService disconnected")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        Log.i(TAG, "McpNotificationListenerService destroyed")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val item = extractNotificationItem(sbn)

        // Store into history
        notificationHistory.addFirst(item)
        while (notificationHistory.size > MAX_HISTORY) {
            notificationHistory.pollLast()
        }

        // Emit to real-time subscribers
        _notificationEvents.tryEmit(item)
        Log.d(TAG, "Notification posted: [${item.packageName}] ${item.title}: ${item.text}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        Log.d(TAG, "Notification removed: [${sbn.packageName}] key=${sbn.key}")
    }

    fun getActiveNotificationsList(): List<NotificationItem> {
        return try {
            activeNotifications?.map { extractNotificationItem(it) } ?: emptyList()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get active notifications: ${e.message}")
            emptyList()
        }
    }

    fun clearNotificationByKey(key: String): Boolean {
        return try {
            cancelNotification(key)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to cancel notification: ${e.message}")
            false
        }
    }

    fun clearAll(): Boolean {
        return try {
            cancelAllNotifications()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to cancel all notifications: ${e.message}")
            false
        }
    }

    private fun refreshActiveNotifications() {
        try {
            activeNotifications?.forEach { sbn ->
                val item = extractNotificationItem(sbn)
                if (notificationHistory.none { it.key == item.key }) {
                    notificationHistory.addLast(item)
                }
            }
        } catch (_: Throwable) {
        }
    }
}
