# 🤖 AndroidMCP

<div align="center">

![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Language-Kotlin_2.4.10-7F52FF?logo=kotlin&logoColor=white)
![AGP](https://img.shields.io/badge/AGP-9.4.0-34A853?logo=android)
![Compose](https://img.shields.io/badge/UI-Jetpack_Compose_BOM_2026.08-4285F4?logo=jetpackcompose&logoColor=white)
![Ktor](https://img.shields.io/badge/Server-Ktor_3.5.2-087CFA?logo=ktor&logoColor=white)
![LSPosed](https://img.shields.io/badge/Hook-LSPosed_/_YukiHookAPI-FF6F00?logo=xposed&logoColor=white)
![MCP](https://img.shields.io/badge/Protocol-Model_Context_Protocol-009688)
![Root & Shizuku](https://img.shields.io/badge/Privilege-Root_|_Sui_|_Shizuku-E91E63)
![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)

<p align="center">
  <b>首个原生运行于 Android 系统的特权级 Model Context Protocol (MCP) 服务端</b><br>
  融合 <b>LSPosed 内存注入 + Root 特权 + Sui/Shizuku 跨进程 + 无障碍服务</b>，为 AI Agent 赋予完全掌控 Android 系统的超级智能体能力。
</p>

[✨ 核心特性](#-核心特性) • [🛠️ MCP 工具清单](#️-mcp-工具清单) • [🖥️ Web 控制台](#️-web-控制台) • [🚀 快速开始](#-快速开始) • [🏗️ 架构设计](#️-架构设计)

</div>

---

## 🌟 项目简介

**AndroidMCP** 是一个直接部署在 Android 设备上的开源服务框架，实现了标准 [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) 规范。

它让各类大语言模型 Agent（如 **Claude Desktop**, **Google Antigravity**, **Cline**, **Cursor**, **Roocode** 等）能够通过标准 JSON-RPC 2.0 协议直接与真实 Android 设备进行全方位的智能交互，涵盖从 **视觉感知、UI 触控自动化、特权指令执行** 到 **目标 App 内部内存反射与逆向提取** 的全流程操作。

---

## ✨ 核心特性

### 1. 🧬 四维全权限与智能降级架构
- **LSPosed / Xposed 进程内注入**：深入目标应用进程内部，直接反射调用 Java/Kotlin 方法、读写私有 Field 变量、实时抓取原始 View / Jetpack Compose 语义树。
- **Root 级超级特权**：直接穿透应用沙箱读写 `/data/data/<pkg>/` 私有 SQLite 数据库与 SharedPreferences XML 配置，动态接管系统硬件（Wi-Fi、代理、分辨率、权限）。
- **Sui / Shizuku 跨进程特权**：无需触发无障碍延迟，高保真分发毫秒级触控、滑动、物理按键及全系统剪贴板读写。
- **Accessibility 无障碍服务**：免 Root 机型的全自动 UI 节点抓取与手势分发兜底保障。

### 2. 👁️ 视觉感知与 Set-of-Mark (SoM) 标注
- **实时 SoM 目标标号**：截图中自动为每个可交互组件渲染高对比度数字角标（Set-of-Mark），让多模态视觉大模型实现 100% 精准的目标指代。
- **Compact Token-Saving Prompt**：独创 UI 树扁平化算法，将冗长的 XML 视图树压缩为对 LLM 极其友好的紧凑文本结构，大幅降低 Token 开销。

### 3. 🖥️ 现代化拟物 Web 控制台
- 访问 `http://<设备IP>:8080/` 即可直接在电脑浏览器中进行沉浸式远程操作：
  - **真实拟物手机屏幕镜像 & 低延迟 MJPEG 实时流**：支持高帧率零延迟实时推流 (`/api/stream`)，鼠标单击即点、拖动即滑动、实时光标坐标反馈、物理按键悬浮栏。
  - **7 大超级工作台 Tab**：快捷文本/应用控制、UI 树实时搜索与点击、**📁 可视化文件管理**（跨分区浏览、下载、上传、删除）、**🔴 自动化宏录制与回放引擎**、特权 Shell 终端、MCP 工具在线 Playground、实时 Logcat 日志过滤。

---

## 🛠️ MCP 工具清单 (35+ 原生超级工具)

### 🎯 智能 UI 触控与感知
| 工具名称 | 参数 | 说明 |
| :--- | :--- | :--- |
| `get_ui_hierarchy` | `annotate_som?: boolean` | 智能抓取当前屏幕 UI 树（优先 LSPosed 内存树 -> 其次 A11y -> 兜底 UiAutomator dump），生成 Token 紧凑格式。 |
| `get_activity_stack` | `max_tasks?: int` | **任务栈与 Activity 深度解析**：提取前台/后台 Task 栈层级、当前聚焦 Activity、任务 ID 及可见性。 |
| `click_by_selector` | `text?`, `resource_id?`, `content_desc?`, `class_name?`, `match_type?` | **智能选择器点击**：无需计算坐标，直接根据文字或 ID 自动查找目标节点、计算中心点并完成点击。 |
| `wait_for_element` | `text?`, `resource_id?`, `condition?`, `timeout_ms?`, `auto_click?: boolean` | **智能等待元素**：持续监听 UI 树直至目标元素出现/消失，支持自动直接点击，彻底解决异步加载竞态。 |
| `capture_screenshot` | `quality?: int`, `annotate_som?: boolean` | 抓取屏幕图像（支持 Base64 返回与 SoM 角标实时绘制）。 |
| `tap` | `x: float`, `y: float`, `element_id?: int` | 模拟屏幕绝对坐标点击或通过 SoM ID 点击。 |
| `long_press` | `x?`, `y?`, `element_id?`, `duration_ms?: int` | **长按手势**：支持按坐标或 SoM ID 长按触发上下文菜单或拖拽起始。 |
| `swipe` | `x1`, `y1`, `x2`, `y2`, `duration_ms?: int` | 模拟任意轨迹的平滑滑动与手势滚动。 |
| `drag_and_drop` | `from_element_id?`, `to_element_id?`, `x1?`, `y1?`, `x2?`, `y2?` | **拖拽手势**：模拟长按并拖拽至目标位置/组件。 |
| `input_text` | `text: string` | 输入文本（支持完整 Unicode 与中文字符直接注入）。 |
| `press_key` | `key: string` | 模拟物理按键（`BACK`, `HOME`, `RECENTS`, `POWER`, `VOLUME_UP`, `VOLUME_DOWN` 等）。 |

### ⚡ 批处理与自动化宏引擎 (Macro Engine)
| 工具名称 | 参数 | 说明 |
| :--- | :--- | :--- |
| `run_actions` | `actions: Array<ActionItem>`, `default_delay_ms?: int` | **原子级动作流批处理**：按序连续执行 `tap`, `long_press`, `swipe`, `drag_and_drop`, `input_text`, `press_key`, `wait`，可自定义延时，极大减少网络往返延迟。 |
| `manage_macro` | `action: 'save' \| 'get' \| 'list' \| 'delete' \| 'run'`, `name?: string`, `actions?: Array` | **脚本与宏管理引擎**：支持宏持久化保存、按名称调用回放、枚举与删除，结合 Web 控制台实现「录制-保存-调度」一体化。 |

### 🕵️‍♂️ 深度逆向与数据提取 (Root & LSPosed)
| 工具名称 | 参数 | 说明 |
| :--- | :--- | :--- |
| `hook_dump_sqlite` | `package_name`, `db_name?`, `query?`, `limit?: int` | **私有数据库查询**：直接穿透沙箱读取目标 App 的 SQLite 数据库并以 JSON 表格返回。 |
| `hook_dump_shared_prefs` | `package_name`, `file_name?`, `key?` | **私有偏好配置解析**：提取并解析目标 App 的 SharedPreferences XML 为结构化 JSON。 |
| `hook_inspect_activity` | `package_name?` | [LSPosed] 实时分析当前前台 Activity 的类结构、所有属性与已加载组件。 |
| `hook_get_fragments` | `package_name?` | [LSPosed] **Fragment 层级穿透**：抓取前台 Activity 中活跃的 Fragment 树、Tag、可见性及所属 View。 |
| `hook_trace_method` | `action: 'start' \| 'get' \| 'stop' \| 'clear'`, `package_name?`, `class_name?`, `method_name?`, `capture_args?`, `capture_return?` | [LSPosed] **动态方法调用追踪**：动态挂载 Hook 捕获入参/返回值/耗时并写入环形缓冲区，支持随时提取与分析。 |
| `hook_call_method` | `package_name`, `class_name?`, `method_name`, `params?: string[]` | [LSPosed] 在目标 App 内存中反射执行任意 Java/Kotlin 方法。 |
| `hook_set_field` | `package_name`, `class_name?`, `field_name`, `field_value` | [LSPosed] 在目标 App 内存中动态篡改私有/公开变量值。 |
| `hook_get_view_tree` | `package_name` | [LSPosed] 直接从目标 App 的 `DecorView` 获取高保真 View 与 Compose 语义树。 |

### 📱 系统与特权控制
| 工具名称 | 参数 | 说明 |
| :--- | :--- | :--- |
| `install_apk` | `file_path`, `grant_permissions?: boolean`, `allow_downgrade?: boolean` | **静默安装 APK**：通过 Shizuku/Root 静默安装 APK，支持全权限预授权与降级安装（自动适配 Android 16+ FUSE staging）。 |
| `pull_apk` | `package_name`, `destination_path` | **APK 提取备份**：定位设备上已安装 App 的 base.apk 路径并复制到指定目录。 |
| `get_notifications` | `package_name?`, `filter?`, `limit?: int`, `clear?: boolean` | **系统通知读取**：实时抓取与历史过滤短信验证码、第三方 App 推送、状态栏通知。 |
| `wait_for_notification` | `package_name?`, `text_contains?`, `timeout_ms?: int` | **异步等待通知**：挂起等待新收到的指定短信/通知（专为 2FA/OTP 验证码自动化设计）。 |
| `manage_clipboard` | `action: 'get' \| 'set'`, `text?: string` | 全系统剪贴板读取与写入，不受前台焦点限制。 |
| `system_control` | `action`, `param?` | 深度系统管控（`wifi_on`/`off`, `set_proxy`, `airplane_on`, `set_screen_density`, `grant_all_permissions`, `keep_alive_whitelist` 等）。 |
| `system_file_ops` | `action: 'read' \| 'write' \| 'list' \| 'delete'`, `path`, `content?`, `as_base64?: boolean` | 对系统分区与私有沙箱进行任意文件读写和管理。 |
| `send_intent` | `type`, `action`, `data_uri?`, `package_name?`, `extras?: object` | 向系统发送任意 Activity / Broadcast / Service Intent。 |
| `execute_shell` | `command: string`, `use_root?: boolean` | 执行 Shell 脚本指令。 |
| `launch_app` / `stop_app` / `clear_app_data` | `package_name: string` | 应用生命周期控制与数据重置。 |
| `get_device_info` / `get_recent_logs` | - | 获取硬件与特权状态信息 / 实时抓取过滤 Logcat 日志。 |

---

## 🖥️ Web 控制台

启动服务后，同一局域网下的任意电脑浏览器直接访问：

```text
http://<手机IP>:8080/
```

- **真实拟物手机屏幕镜像 & MJPEG 实时推流**：支持 15fps 高帧率实时推流，鼠标单击即点、按住拖拽即滑动；
- **7 大超级工作台 Tab**：
  - ⚡ **快捷控制**：快速文本注入、常用应用一键拉起、屏幕滑动快捷键；
  - 🌲 **UI 布局树**：动态提取与过滤 UI 层次，点击直达对应组件；
  - 📁 **文件管理**：跨分区浏览（`/sdcard/`、`/data/local/tmp/`、`/data/data/<pkg>/` 等）、直接下载、删除与极速上传文件；
  - 🔴 **自动化宏**：录制用户屏幕操作并持久化保存为宏，一键批量回放与测试动作流 (`run_actions`)；
  - 💻 **特权终端**：Root/Shell 终端调试与系统监控指令预设；
  - 🛠️ **MCP 工具台**：全 35 个原生 MCP 工具全参数模板一键调试调用；
  - 📜 **实时日志**：按 Tag 与关键字即时抓取过滤 Logcat。


---

## 🚀 快速开始

### 1. 设备环境准备
- **操作系统**：Android 8.0 ~ Android 16+
- **推荐环境**：
  - Magisk / KernelSU / APatch（已获取 Root 权限）
  - LSPosed 框架（推荐在作用域中勾选“系统框架”或目标测试应用）
  - Sui 或 Shizuku（提供高性能跨进程 Binder）

### 2. 编译与安装
```bash
# 编译并签名生成 Release 优化包 (仅 ~2.7MB)
./gradlew assembleRelease

# 安装至测试机
adb install -r app/build/outputs/apk/release/AndroidMCP_v*.apk
```

### 3. 启动 MCP 服务
- **方式一（App 界面）**：在手机上打开 AndroidMCP 应用，点击首页的 **「启动 MCP 服务」** 按钮；
- **方式二（ADB 命令行静默启动）**：
  ```bash
  adb shell "su -c am start-foreground-service -a com.wzvideni.androidmcp.ACTION_START -n com.wzvideni.androidmcp/.server.McpForegroundService --ei extra_port 8080"
  ```

### 4. 接入 MCP 客户端

在您的 MCP 客户端配置文件（如 `claude_desktop_config.json` 或 `settings.json`）中添加配置：

```json
{
  "mcpServers": {
    "android": {
      "type": "http",
      "url": "http://<手机IP>:8080/mcp"
    }
  }
}
```

---

## 🏗️ 架构设计

```mermaid
graph TD
    Client["AI Agent / Claude / Antigravity"] -->|HTTP / JSON-RPC 2.0| Server["Ktor 3.5 Embedded Server :8080"]
    WebUI["PC Web Dashboard"] -->|HTTP REST / SSE| Server
    
    Server --> Handler["McpProtocolHandler"]
    
    Handler -->|内存级 Hook| HookMgr["LSPosed / YukiHookAPI"]
    Handler -->|特权 Shell / FS / DB| Root["RootBridge / su"]
    Handler -->|系统级输入 / 剪贴板| Sui["Sui / Shizuku Bridge"]
    Handler -->|视觉与 UI 分析| Vision["ScreenCapturer & UiTreeFlattener"]
    Handler -->|无障碍备用| A11y["AccessibilityService"]
    
    HookMgr --> TargetApp["目标应用进程 (In-Process)"]
    Root --> AndroidOS["Android 系统层 (System)"]
    Sui --> AndroidOS
```

---

## 📄 开源许可证

本项目基于 [Apache 2.0 License](LICENSE) 开源。
