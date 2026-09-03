package com.wzvideni.androidmcp.di

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.PowerManager
import android.view.WindowManager
import com.wzvideni.androidmcp.engine.InputController
import com.wzvideni.androidmcp.engine.PrivilegeManager
import com.wzvideni.androidmcp.engine.RootBridge
import com.wzvideni.androidmcp.engine.ShizukuBridge
import com.wzvideni.androidmcp.hook.HookClientManager
import com.wzvideni.androidmcp.mcp.McpProtocolHandler
import com.wzvideni.androidmcp.server.McpHttpServer
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Koin 主 DI 模块。
 *
 * 架构层次：
 *  - 系统服务层（System Services Layer）：PackageManager, WindowManager, BatteryManager, NotificationManager, ClipboardManager 等
 *  - 底层引擎层（Engine Layer）：ShizukuBridge, RootBridge, InputController, PrivilegeManager
 *  - Hook 层（Hook Layer）：HookClientManager
 *  - 协议层（Protocol Layer）：McpProtocolHandler
 *  - 服务层（Server Layer）：McpHttpServer（factory，按端口参数生成）
 */
val appModule = module {

    // ── Android 系统 Manager（System Services Layer）──────────────────────────
    // 通过 androidContext().getSystemService() / androidContext().packageManager 获取并注册为全局单例。
    // 在各业务组件（PrivilegeManager, McpProtocolHandler, Services, Activities）中直接注入，解耦系统硬编码调用。

    /** 包管理器（应用查询、安装/禁用组件） */
    single { androidContext().packageManager }

    /** 屏幕尺寸、密度、旋转方向查询 */
    single { androidContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    /** 电量百分比、充电状态查询 */
    single { androidContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager }

    /** 发送/管理系统通知 */
    single { androidContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    /** 前台应用统计（需 PACKAGE_USAGE_STATS 权限） */
    single<UsageStatsManager?> { androidContext().getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager }

    /** 系统剪贴板读写 */
    single { androidContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    /** 显示器信息（多屏/虚拟屏场景） */
    single { androidContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }

    /** 电源管理器（控制亮屏、灭屏与休眠） */
    single { androidContext().getSystemService(Context.POWER_SERVICE) as PowerManager }

    /** 活动/进程运行状态管理器 */
    single { androidContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }

    /** 键盘锁/锁屏状态管理器 */
    single { androidContext().getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }

    /** 网络连接状态管理器 */
    single { androidContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    // ── 底层引擎（Engine Layer）────────────────────────────────────────────────

    /**
     * Shizuku 免 Root 系统级 API 桥接层。
     * 管理 Shizuku Binder 连接、权限检测及命令执行。
     */
    single { ShizukuBridge }

    /**
     * Root 权限桥接层。
     * 基于 libsu，管理 su 可用性检测与特权 Shell 命令执行。
     */
    single { RootBridge }

    /**
     * 输入事件控制器。
     * 按优先级依次通过 LSPosed Hook → Shizuku → Root → Accessibility 派发触控/按键。
     */
    single { InputController }

    /**
     * 特权管理器。
     * 依赖注入各个 Android 系统 Manager，负责前台检测、生命周期管理、设备状态提取。
     */
    single {
        PrivilegeManager(
            context = androidContext(),
            windowManager = get(),
            batteryManager = get(),
            packageManager = get(),
            usageStatsManager = getOrNull()
        )
    }

    // ── Hook 层（Hook Layer）──────────────────────────────────────────────────

    /**
     * LSPosed IPC 客户端管理器。
     * 通过 Loopback TCP / Abstract LocalSocket 与目标应用进程内的 HookIpcServer 通信。
     */
    single { HookClientManager }

    // ── 协议层（Protocol Layer）───────────────────────────────────────────────

    /**
     * MCP JSON-RPC 协议处理器。
     * 依赖注入 PrivilegeManager 和 ClipboardManager，处理所有 MCP tools/call、resources 等。
     */
    single {
        McpProtocolHandler(
            context = androidContext(),
            privilegeManager = get(),
            clipboardManager = get()
        )
    }

    // ── 服务层（Server Layer）─────────────────────────────────────────────────

    /**
     * MCP HTTP 服务器（factory）。
     * 使用 factory 而非 single，因其携带运行时 port 参数。
     * 注入已有的 McpProtocolHandler 单例和 PrivilegeManager 单例。
     * 调用方式：val server: McpHttpServer = get { parametersOf(8080) }
     */
    factory { (port: Int) ->
        McpHttpServer(
            context = androidContext(),
            port = port,
            handler = get(),
            privilegeManager = get()
        )
    }
}
