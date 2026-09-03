package com.wzvideni.androidmcp.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wzvideni.androidmcp.R
import com.wzvideni.androidmcp.engine.ShizukuBridge
import com.wzvideni.androidmcp.mcp.McpProtocolHandler
import com.wzvideni.androidmcp.ui.activity.MainActivity
import org.koin.android.ext.android.inject

class McpForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "mcp_server_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.wzvideni.androidmcp.ACTION_START"
        const val ACTION_STOP = "com.wzvideni.androidmcp.ACTION_STOP"
        const val EXTRA_PORT = "extra_port"

        /**
         * 运行状态标记（基本数据类型，无任何 Context 引用，避免静态字段内存泄漏）。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context, port: Int = 8080) {
            val intent = Intent(context, McpForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, McpForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    /**
     * HTTP 服务器实例，生命周期与当前 Service 绑定，杜绝作为 static 变量造成 Context 泄漏。
     */
    private var serverInstance: McpHttpServer? = null

    /**
     * 通过 Koin 注入 McpProtocolHandler 单例。
     * Service 由系统创建，无法通过构造函数注入，使用 [inject] 委托属性实现字段注入。
     */
    private val protocolHandler: McpProtocolHandler by inject()

    /**
     * 通过 Koin 注入 NotificationManager 系统服务。
     */
    private val notificationManager: NotificationManager by inject()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ShizukuBridge.initHiddenApiBypass()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(true)
                stopSelf()
            }
            else -> {
                val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
                startServer(port)
            }
        }
        return START_STICKY
    }

    private fun startServer(port: Int) {
        if (serverInstance == null) {
            // 通过 Koin factory 创建 McpHttpServer，传入运行时 port 参数
            serverInstance = McpHttpServer(applicationContext, port, protocolHandler).apply {
                start()
            }
            isRunning = true
        }

        val notification = buildNotification(port)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, 0)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            android.util.Log.e("McpForegroundService", "startForeground failed: ${e.message}", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Throwable) {
            }
        }
    }

    private fun stopServer() {
        serverInstance?.stop()
        serverInstance = null
        isRunning = false
    }

    private fun buildNotification(port: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroidMCP 服务运行中")
            .setContentText("正在监听端口 $port (SSE & HTTP RPC)")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MCP 后台服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持 AndroidMCP 服务在后台持续运行"
        }
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
