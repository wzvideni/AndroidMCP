package com.wzvideni.androidmcp.engine

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.net.toUri
import com.highcapable.yukihookapi.YukiHookAPI
import com.wzvideni.androidmcp.hook.HookClientManager
import com.wzvideni.androidmcp.model.DeviceInfo
import com.wzvideni.androidmcp.model.PrivilegeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.util.Collections

/**
 * 特权与系统状态管理器。
 * 通过依赖注入接收系统服务（WindowManager, BatteryManager, PackageManager, UsageStatsManager），
 * 负责设备信息读取、前台应用检测、应用生命周期管理（启动/强行停止/清除数据）等。
 */
class PrivilegeManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val batteryManager: BatteryManager,
    private val packageManager: PackageManager,
    private val usageStatsManager: UsageStatsManager? = null
) {

    suspend fun getActiveHookedApps(): List<String> = withContext(Dispatchers.IO) {
        val found = mutableSetOf<String>()
        if (RootBridge.checkRootAsync()) {
            val (_, out) = RootBridge.exec("cat /proc/net/unix 2>/dev/null | grep androidmcp_hook_")
            Regex("androidmcp_hook_([a-zA-Z0-9_.]+)").findAll(out).forEach {
                found.add(it.groupValues[1])
            }
        }
        val candidateScopes = listOf("android", "com.android.systemui")
        for (pkg in candidateScopes) {
            if (!found.contains(pkg) && HookClientManager.isAppHookedAndActive(pkg)) {
                found.add(pkg)
            }
        }
        found.toList()
    }

    suspend fun getPrivilegeStatus(): PrivilegeStatus = withContext(Dispatchers.IO) {
        val hookedApps = getActiveHookedApps()
        val shizukuRunning = ShizukuBridge.isRunning()
        val shizukuAuthorized = ShizukuBridge.hasPermission()
        val rootAvailable = RootBridge.checkRootAsync()

        // Auto grant notification and accessibility services if privileged
        if (shizukuAuthorized || rootAvailable) {
            if (!com.wzvideni.androidmcp.notification.McpNotificationListenerService.isPermissionGranted(context)) {
                com.wzvideni.androidmcp.notification.McpNotificationListenerService.autoGrantPermission(context)
            }
            if (!McpAccessibilityService.isRunning && !McpAccessibilityService.isPermissionGranted(context)) {
                McpAccessibilityService.autoGrantPermission(context)
            }
        }

        PrivilegeStatus(
            lsposedActive = YukiHookAPI.Status.isXposedModuleActive || hookedApps.isNotEmpty(),
            hookedAppsCount = if (hookedApps.isNotEmpty()) hookedApps.size else (if (YukiHookAPI.Status.isXposedModuleActive) 1 else 0),
            hookedApps = hookedApps,
            shizukuRunning = shizukuRunning,
            shizukuAuthorized = shizukuAuthorized,
            rootAvailable = rootAvailable,
            accessibilityActive = McpAccessibilityService.isRunning || McpAccessibilityService.isPermissionGranted(context),
            notificationActive = com.wzvideni.androidmcp.notification.McpNotificationListenerService.isRunning ||
                    com.wzvideni.androidmcp.notification.McpNotificationListenerService.isPermissionGranted(context)
        )
    }

    suspend fun getDeviceInfo(
        ctx: Context = context,
        wm: WindowManager = windowManager,
        bm: BatteryManager = batteryManager
    ): DeviceInfo = withContext(Dispatchers.IO) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging =
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING

        val orientation = if (ctx.resources.configuration.orientation == 1) "PORTRAIT" else "LANDSCAPE"
        val (currentPkg, currentAct) = getForegroundApp(ctx)

        DeviceInfo(
            brand = Build.BRAND,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            orientation = orientation,
            batteryLevel = batteryPct,
            isCharging = isCharging,
            currentPackage = currentPkg,
            currentActivity = currentAct,
            ipAddresses = getLocalIpAddresses()
        )
    }

    suspend fun getForegroundApp(ctx: Context = context): Pair<String?, String?> = withContext(Dispatchers.IO) {
        // 1. Real-time McpAccessibilityService state (fast path: <1ms, avoids heavy shell dumpsys)
        val a11y = McpAccessibilityService.instance
        if (a11y != null) {
            val root = a11y.rootInActiveWindow
            val activePkg = root?.packageName?.toString() ?: a11y.currentPackageName
            val activeCls = a11y.currentActivityName
            if (!activePkg.isNullOrBlank()) {
                return@withContext activePkg to activeCls
            }
        }

        // 2. Try via Shizuku/Root dumpsys window
        if (ShizukuBridge.hasPermission() || RootBridge.isRootAvailable()) {
            val (code, out) = if (ShizukuBridge.hasPermission()) {
                ShizukuBridge.exec("dumpsys", "window")
            } else {
                RootBridge.exec("dumpsys window")
            }
            if (code == 0 && out.isNotBlank()) {
                for (line in out.lineSequence()) {
                    if (line.contains("mCurrentFocus") || line.contains("mFocusedApp") || line.contains("topResumedActivity") || line.contains("mFocusedWindow")) {
                        // System UI special cases
                        if (line.contains("NotificationShade") || line.contains("NotificationPanel")) {
                            return@withContext "com.android.systemui" to "NotificationShade"
                        }
                        if (line.contains("StatusBar")) {
                            return@withContext "com.android.systemui" to "StatusBar"
                        }
                        if (line.contains("Keyguard") || line.contains("LockScreen")) {
                            return@withContext "com.android.systemui" to "Keyguard"
                        }

                        val match = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)").find(line)
                        if (match != null) {
                            val pkg = match.groupValues[1]
                            val act = match.groupValues[2]
                            return@withContext pkg to act
                        }
                    }
                }
            }

            // Fallback via dumpsys activity activities
            val (actCode, actOut) = if (ShizukuBridge.hasPermission()) {
                ShizukuBridge.exec("dumpsys", "activity", "activities")
            } else {
                RootBridge.exec("dumpsys activity activities")
            }
            if (actCode == 0 && actOut.isNotBlank()) {
                val resumedMatch = Regex("(?:topResumedActivity|mResumedActivity|ResumedActivity):.*?([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)").find(actOut)
                if (resumedMatch != null) {
                    return@withContext resumedMatch.groupValues[1] to resumedMatch.groupValues[2]
                }
            }
        }

        // 3. Fallback via injected UsageStatsManager
        try {
            val usm = usageStatsManager ?: (ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager)
            val time = System.currentTimeMillis()
            val stats = usm?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
            val topApp = stats?.filter { it.packageName != "com.wzvideni.androidmcp" }?.maxByOrNull { it.lastTimeUsed }?.packageName
            if (topApp != null) {
                return@withContext topApp to null
            }
        } catch (_: Throwable) {
        }

        null to null
    }

    suspend fun launchApp(
        ctx: Context = context,
        packageName: String,
        activityName: String? = null,
        uri: String? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // 1. Resolve launch intent via PackageManager
            val launchIntent = if (uri != null) {
                Intent(Intent.ACTION_VIEW, uri.toUri()).apply { setPackage(packageName) }
            } else {
                packageManager.getLaunchIntentForPackage(packageName)
            }

            fun formatComponent(pkg: String, act: String): String {
                val cleanAct = if (act.startsWith(".")) "$pkg$act" else act
                return if (cleanAct.contains("/")) cleanAct else "$pkg/$cleanAct"
            }

            // 2. If Root is available, Root (UID 0) is preferred for launching unexported activities without SecurityException
            if (RootBridge.isRootAvailable()) {
                val cmd = if (activityName != null) {
                    "am start -n ${formatComponent(packageName, activityName)}"
                } else if (uri != null) {
                    "am start -a android.intent.action.VIEW -d \"$uri\" -p $packageName"
                } else if (launchIntent?.component != null) {
                    "am start -n ${launchIntent.component!!.flattenToShortString()}"
                } else {
                    "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName"
                }
                val (code, out) = RootBridge.exec(cmd)
                if (code == 0 && !out.contains("SecurityException") && !out.contains("Error: Activity class")) {
                    return@withContext true to (if (out.isNotBlank()) out else "Launched $packageName via Root")
                }
            }

            // 3. Try Shizuku (UID 2000 shell)
            if (ShizukuBridge.hasPermission()) {
                val cmd = if (activityName != null) {
                    listOf("am", "start", "-n", formatComponent(packageName, activityName))
                } else if (uri != null) {
                    listOf("am", "start", "-a", "android.intent.action.VIEW", "-d", uri, "-p", packageName)
                } else if (launchIntent?.component != null) {
                    listOf("am", "start", "-n", launchIntent.component!!.flattenToShortString())
                } else {
                    listOf("am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", packageName)
                }
                val (code, out) = ShizukuBridge.exec(*cmd.toTypedArray())
                if (code == 0 && !out.contains("SecurityException") && !out.contains("Error:")) {
                    return@withContext true to (if (out.isNotBlank()) out else "Launched $packageName via Shizuku")
                }
            }

            // 4. Try Context startActivity
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launchIntent)
                return@withContext true to "Launched $packageName via Application Context"
            }

            // 5. Fallback via monkey
            if (RootBridge.isRootAvailable()) {
                val (code, out) = RootBridge.exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
                if (code == 0) return@withContext true to "Launched $packageName via Root monkey"
            }
            if (ShizukuBridge.hasPermission()) {
                val (code, out) = ShizukuBridge.exec("monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")
                if (code == 0) return@withContext true to "Launched $packageName via Shizuku monkey"
            }

            false to "Failed to launch $packageName: No launch intent found and shell commands failed"
        } catch (e: Throwable) {
            false to "Failed to launch $packageName: ${e.message}"
        }
    }

    suspend fun stopApp(packageName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (ShizukuBridge.hasPermission()) {
            val (code, out) = ShizukuBridge.exec("am", "force-stop", packageName)
            return@withContext (code == 0) to (if (code == 0) "Stopped $packageName" else out)
        }
        if (RootBridge.isRootAvailable()) {
            val (code, out) = RootBridge.exec("am force-stop $packageName")
            return@withContext (code == 0) to (if (code == 0) "Stopped $packageName" else out)
        }
        false to "Requires Shizuku or Root to force stop applications"
    }

    suspend fun clearAppData(packageName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (ShizukuBridge.hasPermission()) {
            val (code, out) = ShizukuBridge.exec("pm", "clear", packageName)
            return@withContext (code == 0) to out
        }
        if (RootBridge.isRootAvailable()) {
            val (code, out) = RootBridge.exec("pm clear $packageName")
            return@withContext (code == 0) to out
        }
        false to "Requires Shizuku or Root to clear application data"
    }

    suspend fun getInstalledApps(
        pm: PackageManager = packageManager,
        includeSystem: Boolean = false
    ): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val list = mutableListOf<Map<String, String>>()
        for (app in packages) {
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && isSystem) continue
            val label = pm.getApplicationLabel(app).toString()
            list.add(
                mapOf(
                    "packageName" to app.packageName,
                    "label" to label,
                    "isSystem" to isSystem.toString(),
                    "enabled" to app.enabled.toString()
                )
            )
        }
        list.sortedBy { it["label"]?.lowercase() }
    }

    fun getLocalIpAddresses(): List<String> = Companion.getLocalIpAddresses()

    companion object {
        fun getLocalIpAddresses(): List<String> {
            val ips = mutableListOf<String>()
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress) {
                            val sAddr = addr.hostAddress ?: continue
                            val isIPv4 = sAddr.indexOf(':') < 0
                            if (isIPv4) ips.add(sAddr)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            return ips
        }
    }
}
