package com.wzvideni.androidmcp.engine

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.highcapable.yukihookapi.YukiHookAPI
import com.wzvideni.androidmcp.model.DeviceInfo
import com.wzvideni.androidmcp.model.PrivilegeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.util.Collections

object PrivilegeManager {

    fun getPrivilegeStatus(): PrivilegeStatus {
        return PrivilegeStatus(
            lsposedActive = YukiHookAPI.Status.isXposedModuleActive,
            hookedAppsCount = if (YukiHookAPI.Status.isXposedModuleActive) 1 else 0,
            shizukuRunning = ShizukuBridge.isRunning(),
            shizukuAuthorized = ShizukuBridge.hasPermission(),
            rootAvailable = RootBridge.isRootAvailable(),
            accessibilityActive = McpAccessibilityService.isRunning
        )
    }

    suspend fun getDeviceInfo(context: Context): DeviceInfo = withContext(Dispatchers.IO) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
        } else false

        val orientation = if (context.resources.configuration.orientation == 1) "PORTRAIT" else "LANDSCAPE"
        val (currentPkg, currentAct) = getForegroundApp(context)

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

    suspend fun getForegroundApp(context: Context): Pair<String?, String?> = withContext(Dispatchers.IO) {
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

        // Fallback via UsageStatsManager
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
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
        context: Context,
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

            // Normal context launch
            val intent = if (uri != null) {
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply { setPackage(packageName) }
            } else {
                context.packageManager.getLaunchIntentForPackage(packageName)
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
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

    suspend fun getInstalledApps(context: Context, includeSystem: Boolean = false): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
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
