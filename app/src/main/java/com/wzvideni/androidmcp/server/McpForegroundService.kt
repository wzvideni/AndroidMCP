package com.wzvideni.androidmcp.server

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wzvideni.androidmcp.R
import com.wzvideni.androidmcp.engine.ShizukuBridge
import com.wzvideni.androidmcp.ui.activity.MainActivity

class McpForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "mcp_server_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.wzvideni.androidmcp.ACTION_START"
        const val ACTION_STOP = "com.wzvideni.androidmcp.ACTION_STOP"
        const val EXTRA_PORT = "extra_port"

        var serverInstance: McpHttpServer? = null
            private set

        val isRunning: Boolean get() = serverInstance?.isRunning == true

        fun start(context: Context, port: Int = 8080) {
            val intent = Intent(context, McpForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, McpForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

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
            serverInstance = McpHttpServer(applicationContext, port).apply {
                start()
            }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MCP 后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 AndroidMCP 服务在后台持续运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
