package com.wzvideni.androidmcp.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.highcapable.yukihookapi.YukiHookAPI
import com.wzvideni.androidmcp.BuildConfig
import com.wzvideni.androidmcp.engine.McpAccessibilityService
import com.wzvideni.androidmcp.engine.PrivilegeManager
import com.wzvideni.androidmcp.engine.RootBridge
import com.wzvideni.androidmcp.engine.ShizukuBridge
import com.wzvideni.androidmcp.notification.McpNotificationListenerService
import com.wzvideni.androidmcp.server.McpForegroundService
import com.wzvideni.androidmcp.ui.theme.AndroidMCPTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    /** 通过 Koin 注入 ShizukuBridge，替代直接引用 object（便于测试时替换 mock） */
    private val shizukuBridge: ShizukuBridge by inject()

    /** 通过 Koin 注入 RootBridge */
    private val rootBridge: RootBridge by inject()

    /** 通过 Koin 注入 PackageManager 系统服务 */
    private val appPackageManager: PackageManager by inject()

    private val homeComponent by lazy {
        ComponentName(
            packageName,
            "${BuildConfig.APPLICATION_ID}.Home"
        )
    }

    private var refreshTrigger by mutableIntStateOf(0)

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refreshTrigger++
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshTrigger++
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        refreshTrigger++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        } catch (_: Throwable) {
        }

        // Asynchronous initialization on background thread
        lifecycleScope.launch(Dispatchers.IO) {
            shizukuBridge.initHiddenApiBypass()
            rootBridge.checkRootAsync()
        }

        setContent {
            AndroidMCPTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    McpDashboardScreen(
                        refreshKey = refreshTrigger,
                        onRefresh = { refreshTrigger++ },
                        onToggleServer = { start ->
                            if (start) {
                                McpForegroundService.start(this@MainActivity, 8080)
                            } else {
                                McpForegroundService.stop(this@MainActivity)
                            }
                            window.decorView.postDelayed({ refreshTrigger++ }, 300)
                        },
                        isLauncherIconShowing = isLauncherIconShowing,
                        onToggleLauncherIcon = { show -> hideOrShowLauncherIcon(show) },
                        onRequestShizukuPermission = { shizukuBridge.requestPermission() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTrigger++
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        } catch (_: Throwable) {
        }
    }

    private fun hideOrShowLauncherIcon(isShow: Boolean) {
        if (isShow) {
            appPackageManager.setComponentEnabledSetting(
                homeComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } else {
            appPackageManager.setComponentEnabledSetting(
                homeComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private val isLauncherIconShowing: Boolean
        get() = try {
            appPackageManager.getComponentEnabledSetting(homeComponent) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (_: Throwable) {
            true
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpDashboardScreen(
    refreshKey: Int,
    onRefresh: () -> Unit,
    onToggleServer: (Boolean) -> Unit,
    isLauncherIconShowing: Boolean,
    onToggleLauncherIcon: (Boolean) -> Unit,
    /** Shizuku 授权请求回调，由 Activity 传入（内部调用 ShizukuBridge.requestPermission()） */
    onRequestShizukuPermission: () -> Unit = {}
) {
    val context = LocalContext.current
    val isServerRunning = McpForegroundService.isRunning

    // 通过 Koin 在 Composable 内获取各组件及系统 Manager 引用
    val shizukuBridge: ShizukuBridge = koinInject()
    val rootBridge: RootBridge = koinInject()
    val privilegeManager: PrivilegeManager = koinInject()
    val clipboardManager: ClipboardManager = koinInject()

    // State loaded fully asynchronously to prevent any main thread blocking
    var isLsposedActive by remember { mutableStateOf(false) }
    var isShizukuRunning by remember { mutableStateOf(false) }
    var isShizukuActive by remember { mutableStateOf(false) }
    var isRootActive by remember { mutableStateOf(rootBridge.isRootCached()) }
    var isA11yActive by remember { mutableStateOf(McpAccessibilityService.isRunning) }
    var isNotificationActive by remember { mutableStateOf(false) }
    var localIps by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(refreshKey) {
        withContext(Dispatchers.IO) {
            val lsp = try { YukiHookAPI.Status.isXposedModuleActive } catch (_: Throwable) { false }
            val shizukuRun = shizukuBridge.isRunning()
            val shizukuPerm = if (shizukuRun) shizukuBridge.hasPermission() else false
            val root = rootBridge.checkRootAsync()
            val a11y = McpAccessibilityService.isRunning
            val notif = McpNotificationListenerService.isRunning || McpNotificationListenerService.isPermissionGranted(context)
            val ips = privilegeManager.getLocalIpAddresses()

            withContext(Dispatchers.Main) {
                isLsposedActive = lsp
                isShizukuRunning = shizukuRun
                isShizukuActive = shizukuPerm
                isRootActive = root
                isA11yActive = a11y
                isNotificationActive = notif
                localIps = ips
            }
        }
    }


    val primaryLanIp = localIps.firstOrNull() ?: "127.0.0.1"
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "🤖 AndroidMCP 服务",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text("v${BuildConfig.VERSION_NAME}", fontSize = 11.sp) }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 服务控制与局域网访问卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isServerRunning) Color(0xFF064E3B) else Color(0xFF1E293B)
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (isServerRunning) "🟢 MCP 服务运行中" else "⚪ MCP 服务已停止",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "监听端口：8080 (局域网全网卡 0.0.0.0 开放)",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Switch(
                            checked = isServerRunning,
                            onCheckedChange = { onToggleServer(it) }
                        )
                    }

                    if (isServerRunning) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF090D16),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "🌐 局域网 SSE: http://$primaryLanIp:8080/mcp/v1/sse",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFF38BDF8)
                                )
                                Text(
                                    text = "🌐 局域网 RPC: http://$primaryLanIp:8080/mcp/v1/rpc",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFFA5F3FC)
                                )
                                Text(
                                    text = "💻 本机 ADB:   http://localhost:8080/mcp/v1/sse",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val lanUrl = "http://$primaryLanIp:8080/mcp/v1/sse"
                                    clipboardManager.setPrimaryClip(ClipData.newPlainText("lan_sse", lanUrl))
                                    Toast.makeText(context, "已复制局域网地址：$lanUrl", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Wifi,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("复制局域网", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "adb",
                                            "adb forward tcp:8080 tcp:8080"
                                        )
                                    )
                                    Toast.makeText(
                                        context,
                                        "已复制：adb forward tcp:8080 tcp:8080",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("复制 ADB", fontSize = 11.sp)
                            }
                            Button(
                                onClick = {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        "http://127.0.0.1:8080/".toUri()
                                    )
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("网页控制台", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // 2. 特权与控制后端状态卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "⚡ 特权与自动化执行引擎",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    PrivilegeStatusItem(
                        icon = Icons.Default.Extension,
                        title = "LSPosed 模块 (进程内深度逆向)",
                        desc = if (isLsposedActive) "已激活：支持内存 View/Compose 树提取与方法反射调用" else "未激活：请在 LSPosed 管理器中启用模块并勾选作用域",
                        isActive = isLsposedActive
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    PrivilegeStatusItem(
                        icon = Icons.Default.Shield,
                        title = "Shizuku (免 Root 系统级 API)",
                        desc = if (isShizukuActive) "已授权：支持毫秒级触控注入与应用生命周期管控" else if (isShizukuRunning) "运行中 (点击右侧按钮申请授权)" else "未运行 (请在 Shizuku 应用中启动服务)",
                        isActive = isShizukuActive,
                        actionLabel = if (!isShizukuActive && isShizukuRunning) "授权" else null,
                        onAction = { onRequestShizukuPermission() }
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    PrivilegeStatusItem(
                        icon = Icons.Default.Terminal,
                        title = "Root 权限 (Magisk / KernelSU / APatch)",
                        desc = if (isRootActive) "已获得授权：支持特权 Shell、/dev/input 与底层快速截屏" else "未获取 / 不可用",
                        isActive = isRootActive
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    PrivilegeStatusItem(
                        icon = Icons.Default.Accessibility,
                        title = "无障碍服务 (UI 兜底方案)",
                        desc = if (isA11yActive) "已开启：支持无障碍布局树遍历与手势模拟" else "未开启：点击右侧按钮前往系统设置开启服务",
                        isActive = isA11yActive,
                        actionLabel = if (!isA11yActive) "去开启" else null,
                        onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    PrivilegeStatusItem(
                        icon = Icons.Default.Notifications,
                        title = "通知监听服务 (验证码与弹窗感知)",
                        desc = if (isNotificationActive) "已开启：支持短信验证码与系统通知实时拦截" else "未开启：点击右侧按钮前往系统设置授予通知读取权限",
                        isActive = isNotificationActive,
                        actionLabel = if (!isNotificationActive) "去授权" else null,
                        onAction = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    )
                }
            }

            // 3. 应用设置卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "⚙️ 模块与显示设置",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    var hideIcon by remember { mutableStateOf(!isLauncherIconShowing) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "在桌面隐藏应用图标",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "隐藏后可在 LSPosed 管理器中点击模块进入设置",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Switch(
                            checked = hideIcon,
                            onCheckedChange = {
                                hideIcon = it
                                onToggleLauncherIcon(!it)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PrivilegeStatusItem(
    icon: ImageVector,
    title: String,
    desc: String,
    isActive: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) Color(0xFF10B981) else Color(0xFFF59E0B),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(text = desc, fontSize = 11.sp, color = Color(0xFF94A3B8))
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(actionLabel, fontSize = 11.sp)
            }
        }
    }
}