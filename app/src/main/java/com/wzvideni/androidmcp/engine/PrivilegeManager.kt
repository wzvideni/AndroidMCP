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
        if (RootBridge.isRootAvailable()) {
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
        PrivilegeStatus(
            lsposedActive = YukiHookAPI.Status.isXposedModuleActive || hookedApps.isNotEmpty(),
            hookedAppsCount = if (hookedApps.isNotEmpty()) hookedApps.size else (if (YukiHookAPI.Status.isXposedModuleActive) 1 else 0),
            hookedApps = hookedApps,
            shizukuRunning = ShizukuBridge.isRunning(),
            shizukuAuthorized = ShizukuBridge.hasPermission(),
            rootAvailable = RootBridge.isRootAvailable(),
            accessibilityActive = McpAccessibilityService.isRunning
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
        // Try via Shizuku/Root dumpsys window
        if (ShizukuBridge.hasPermission() || RootBridge.isRootAvailable()) {
            val (code, out) = if (ShizukuBridge.hasPermission()) {
                ShizukuBridge.exec("dumpsys", "window")
            } else {
                RootBridge.exec("dumpsys window")
            }
            if (code == 0) {
                val mFocused = out.lineSequence().find {
                    it.contains("mCurrentFocus") || it.contains("mFocusedApp") || it.contains("topResumedActivity")
                }
                if (mFocused != null) {
                    val match = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)").find(mFocused)
                    if (match != null) {
                        val pkg = match.groupValues[1]
                        val act = match.groupValues[2]
                        return@withContext pkg to act
                    }
                }
            }
        }

        // Fallback via injected UsageStatsManager
        try {
            val usm = usageStatsManager ?: (ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager)
            val time = System.currentTimeMillis()
            val stats = usm?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
            val topApp = stats?.maxByOrNull { it.lastTimeUsed }?.packageName
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
            if (ShizukuBridge.hasPermission()) {
                val cmd = if (activityName != null) {
                    listOf("am", "start", "-n", "$packageName/$activityName")
                } else if (uri != null) {
                    listOf("am", "start", "-a", "android.intent.action.VIEW", "-d", uri, "-p", packageName)
                } else {
                    listOf("monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")
                }
                val (code, out) = ShizukuBridge.exec(*cmd.toTypedArray())
                return@withContext (code == 0) to out
            }

            if (RootBridge.isRootAvailable()) {
                val cmd = if (activityName != null) "am start -n $packageName/$activityName" else "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
                val (code, out) = RootBridge.exec(cmd)
                return@withContext (code == 0) to out
            }

            // Normal context launch via injected PackageManager
            val intent = if (uri != null) {
                Intent(Intent.ACTION_VIEW, uri.toUri()).apply { setPackage(packageName) }
            } else {
                packageManager.getLaunchIntentForPackage(packageName)
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                true to "Launched $packageName"
            } else {
                false to "No launch intent found for $packageName"
            }
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
