package com.wzvideni.androidmcp.mcp

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import com.wzvideni.androidmcp.engine.InputController
import com.wzvideni.androidmcp.engine.MacroManager
import com.wzvideni.androidmcp.model.RectBounds
import com.wzvideni.androidmcp.model.UiNode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.*
import org.junit.Test

class McpIssuesFixTest {

    @Test
    fun testResourceIdMatchingHelper() {
        val fullResId = "com.wzvideni.scanplus:id/button_scan5"

        fun matchesResId(nodeResId: String?, resId: String, matchType: String = "exact"): Boolean {
            if (nodeResId == null) return false
            return when (matchType) {
                "exact" -> {
                    nodeResId.equals(resId, ignoreCase = true) ||
                    nodeResId.endsWith(":id/$resId", ignoreCase = true) ||
                    nodeResId.endsWith("/$resId", ignoreCase = true) ||
                    nodeResId.endsWith(":$resId", ignoreCase = true) ||
                    nodeResId.substringAfter(":id/").equals(resId, ignoreCase = true) ||
                    nodeResId.substringAfterLast('/').equals(resId, ignoreCase = true) ||
                    nodeResId.substringAfter(':').equals(resId, ignoreCase = true)
                }
                "regex" -> Regex(resId).containsMatchIn(nodeResId)
                else -> {
                    nodeResId.contains(resId, ignoreCase = true) ||
                    nodeResId.substringAfter(":id/").contains(resId, ignoreCase = true) ||
                    nodeResId.substringAfterLast('/').contains(resId, ignoreCase = true)
                }
            }
        }

        // Test exact short name matching against full resource id
        assertTrue("Short name should match exact", matchesResId(fullResId, "button_scan5", "exact"))
        assertTrue("Full name should match exact", matchesResId(fullResId, "com.wzvideni.scanplus:id/button_scan5", "exact"))
        assertTrue("id/ prefix should match exact", matchesResId(fullResId, "id/button_scan5", "exact"))
        assertTrue("Contains match should match", matchesResId(fullResId, "scan5", "contains"))
        assertFalse("Unrelated id should not match", matchesResId(fullResId, "button_other", "exact"))
    }

    @Test
    fun testActivityStackDeduplicationAndResumedParsing() {
        val dumpsysOutput = """
            Display #0 (activities from top to bottom):
              * Task{a1b2c3 #292 type=standard A=org.lineageos.glimpse U=0 visible=true mode=fullscreen}
                * Hist #0: ActivityRecord{111 u0 org.lineageos.glimpse/.PickerActivity t292}
              * Task{d4e5f6 #293 type=standard A=com.wzvideni.scanplus U=0 visible=false mode=fullscreen}
                * Hist #0: ActivityRecord{222 u0 com.wzvideni.scanplus/.MainActivity t293}

            Running activities (most recent first):
              * Task{a1b2c3 #292 type=standard A=org.lineageos.glimpse U=0 visible=true mode=fullscreen}
              * Task{d4e5f6 #293 type=standard A=com.wzvideni.scanplus U=0 visible=false mode=fullscreen}

            topResumedActivity=ActivityRecord{111 u0 org.lineageos.glimpse/.PickerActivity t292}
            mFocusedApp=ActivityRecord{111 u0 org.lineageos.glimpse/.PickerActivity t292}
            mFocusedWindow=Window{333 u0 org.lineageos.glimpse/.PickerActivity}
        """.trimIndent()

        val resumedMatch = Regex("(?:ResumedActivity|topResumedActivity|mResumedActivity)[:=].*?([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)(?:\\s+t(\\d+))?").find(dumpsysOutput)
        assertNotNull(resumedMatch)
        assertEquals("org.lineageos.glimpse", resumedMatch?.groupValues?.get(1))
        assertEquals(".PickerActivity", resumedMatch?.groupValues?.get(2))
        assertEquals("292", resumedMatch?.groupValues?.get(3))

        val taskMatches = Regex("\\* Task\\{([a-f0-9]+)\\s+#(\\d+)\\s+type=([a-zA-Z0-9_]+)(?:\\s+A=[^:]*:([a-zA-Z0-9_.]+))?(?:\\s+I=([a-zA-Z0-9_./]+))?[^}]*?visible=(true|false)[^}]*?mode=([a-zA-Z0-9_]+)").findAll(dumpsysOutput)

        val seenTaskIds = mutableSetOf<String>()
        val tasksList = mutableListOf<String>()
        for (m in taskMatches) {
            val taskId = m.groupValues[2]
            if (seenTaskIds.add(taskId)) {
                tasksList.add(taskId)
            }
        }

        // Deduplication should result in exactly 2 distinct tasks (292 and 293), not 4
        assertEquals(2, tasksList.size)
        assertEquals("292", tasksList[0])
        assertEquals("293", tasksList[1])
    }

    @Test
    fun testForegroundAppLineParsing() {
        val windowDumpSystemUi = """
            mCurrentFocus=Window{5a065c2 u0 NotificationShade}
            mFocusedApp=ActivityRecord{111 u0 com.wzvideni.scanplus/.MainActivity t293}
        """.trimIndent()

        var pkg: String? = null
        var act: String? = null

        for (line in windowDumpSystemUi.lineSequence()) {
            if (line.contains("mCurrentFocus") || line.contains("mFocusedApp") || line.contains("topResumedActivity") || line.contains("mFocusedWindow")) {
                if (line.contains("NotificationShade") || line.contains("NotificationPanel")) {
                    pkg = "com.android.systemui"
                    act = "NotificationShade"
                    break
                }
                val match = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)").find(line)
                if (match != null) {
                    pkg = match.groupValues[1]
                    act = match.groupValues[2]
                    break
                }
            }
        }

        assertEquals("com.android.systemui", pkg)
        assertEquals("NotificationShade", act)
    }

    @Test
    fun testMacroExecutionWithElementIdAndActivityTracking() = runBlocking {
        val actions = listOf(
            buildJsonObject {
                put("action", JsonPrimitive("wait"))
                put("ms", JsonPrimitive(10))
            }
        )

        var activityCalls = 0
        val (ok, report) = MacroManager.executeActions(
            actions = actions,
            delayBetweenMs = 0L,
            getActivityInfo = {
                activityCalls++
                if (activityCalls == 1) {
                    "com.example" to "OldActivity"
                } else {
                    "com.example" to "NewActivity"
                }
            }
        )

        assertTrue(ok)
        assertTrue(report.contains("OldActivity -> com.example/NewActivity"))
    }

    @Test
    fun testWaitForElementNodeMatching() {
        val root = UiNode(
            id = 0,
            className = "android.widget.FrameLayout",
            bounds = RectBounds(0, 0, 1080, 1920),
            children = listOf(
                UiNode(
                    id = 1,
                    resourceId = "com.wzvideni.scanplus:id/button_scan5",
                    className = "android.widget.Button",
                    text = "Scan",
                    clickable = true,
                    bounds = RectBounds(100, 200, 400, 300)
                )
            )
        )

        val allNodes = mutableListOf<UiNode>()
        fun collectAll(n: UiNode) {
            allNodes.add(n)
            for (c in n.children) collectAll(c)
        }
        collectAll(root)

        val resId = "button_scan5"
        val matched = allNodes.firstOrNull { node ->
            node.resourceId.equals(resId, ignoreCase = true) ||
            node.resourceId?.endsWith(":id/$resId", ignoreCase = true) == true ||
            node.resourceId?.endsWith("/$resId", ignoreCase = true) == true ||
            node.resourceId?.endsWith(":$resId", ignoreCase = true) == true ||
            node.resourceId?.substringAfter(":id/")?.equals(resId, ignoreCase = true) == true ||
            node.resourceId?.substringAfterLast('/')?.equals(resId, ignoreCase = true) == true ||
            node.resourceId?.substringAfter(':')?.equals(resId, ignoreCase = true) == true
        }

        assertNotNull("wait_for_element should match short resource_id", matched)
        assertEquals("com.wzvideni.scanplus:id/button_scan5", matched?.resourceId)
        assertEquals(1, matched?.id)
    }

    @Test
    fun testPressKeyAliasesAndKeyCodeResolution() {
        // 1. Test alias extraction from JsonObject
        fun extractKey(args: JsonObject): String {
            val rawKey = args["key"] ?: args["key_code"] ?: args["keyCode"]
            return InputController.resolveKeyName(rawKey)
        }

        val fromKeyCodeNumber = extractKey(buildJsonObject { put("key_code", JsonPrimitive(4)) })
        assertEquals("BACK", fromKeyCodeNumber)

        val fromKeyCodeString = extractKey(buildJsonObject { put("key_code", JsonPrimitive("4")) })
        assertEquals("BACK", fromKeyCodeString)

        val fromCamelKeyCode = extractKey(buildJsonObject { put("keyCode", JsonPrimitive(4)) })
        assertEquals("BACK", fromCamelKeyCode)

        val fromKeyNumber = extractKey(buildJsonObject { put("key", JsonPrimitive(4)) })
        assertEquals("BACK", fromKeyNumber)

        val fromKeyName = extractKey(buildJsonObject { put("key", JsonPrimitive("BACK")) })
        assertEquals("BACK", fromKeyName)

        val fromKeycodePrefix = extractKey(buildJsonObject { put("key_code", JsonPrimitive("KEYCODE_BACK")) })
        assertEquals("BACK", fromKeycodePrefix)

        val fromEmpty = extractKey(buildJsonObject {})
        assertEquals("", fromEmpty)

        // 2. Test common key code resolution
        assertEquals("HOME", InputController.resolveKeyName(JsonPrimitive(3)))
        assertEquals("BACK", InputController.resolveKeyName(JsonPrimitive(4)))
        assertEquals("RECENTS", InputController.resolveKeyName(JsonPrimitive(187)))
        assertEquals("POWER", InputController.resolveKeyName(JsonPrimitive(26)))
        assertEquals("NOTIFICATIONS", InputController.resolveKeyName(JsonPrimitive(83)))
        assertEquals("ENTER", InputController.resolveKeyName(JsonPrimitive(66)))
        assertEquals("DEL", InputController.resolveKeyName(JsonPrimitive(67)))
        assertEquals("TAB", InputController.resolveKeyName(JsonPrimitive(61)))
        assertEquals("VOLUME_UP", InputController.resolveKeyName(JsonPrimitive(24)))
        assertEquals("VOLUME_DOWN", InputController.resolveKeyName(JsonPrimitive(25)))
        assertEquals("VOLUME_MUTE", InputController.resolveKeyName(JsonPrimitive(164)))

        // 3. Test parseKey mapping with accessibility global action and key codes
        val (backCode, backAction) = InputController.parseKey("BACK")
        assertEquals(KeyEvent.KEYCODE_BACK, backCode)
        assertEquals(AccessibilityService.GLOBAL_ACTION_BACK, backAction)

        val (backNumCode, backNumAction) = InputController.parseKey("4")
        assertEquals(KeyEvent.KEYCODE_BACK, backNumCode)
        assertEquals(AccessibilityService.GLOBAL_ACTION_BACK, backNumAction)

        val (homeCode, homeAction) = InputController.parseKey("HOME")
        assertEquals(KeyEvent.KEYCODE_HOME, homeCode)
        assertEquals(AccessibilityService.GLOBAL_ACTION_HOME, homeAction)

        val (homeNumCode, homeNumAction) = InputController.parseKey("3")
        assertEquals(KeyEvent.KEYCODE_HOME, homeNumCode)
        assertEquals(AccessibilityService.GLOBAL_ACTION_HOME, homeNumAction)

        val (recentsCode, recentsAction) = InputController.parseKey("RECENTS")
        assertEquals(KeyEvent.KEYCODE_APP_SWITCH, recentsCode)
        assertEquals(AccessibilityService.GLOBAL_ACTION_RECENTS, recentsAction)

        val (recentsNumCode, recentsNumAction) = InputController.parseKey("187")
        assertEquals(KeyEvent.KEYCODE_APP_SWITCH, recentsNumCode)
        assertEquals(AccessibilityService.GLOBAL_ACTION_RECENTS, recentsNumAction)

        val (powerCode, powerAction) = InputController.parseKey("POWER")
        assertEquals(KeyEvent.KEYCODE_POWER, powerCode)
        assertEquals(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, powerAction)

        val (powerNumCode, powerNumAction) = InputController.parseKey("26")
        assertEquals(KeyEvent.KEYCODE_POWER, powerNumCode)
        assertEquals(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, powerNumAction)

        val (unknownCode, unknownAction) = InputController.parseKey("INVALID_KEY")
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, unknownCode)
        assertNull(unknownAction)
    }

    @Test
    fun testPrivilegedShellCommandDetection() {
        val amInstrumentCmd = "am instrument -w -e class com.huawei.hms.scankit.p.QRCodeDataMaskInstrumentedTest com.wzvideni.scanplus.test/androidx.test.runner.AndroidJUnitRunner"
        assertTrue("am instrument must be detected as privileged", McpProtocolHandler.isPrivilegedShellCommand(amInstrumentCmd))

        val amInstrumentWithUser = "am instrument --user 0 -w -e class com.huawei.hms.scankit.p.QRCodeDataMaskInstrumentedTest com.wzvideni.scanplus.test/androidx.test.runner.AndroidJUnitRunner"
        assertTrue("am instrument with --user must be detected as privileged", McpProtocolHandler.isPrivilegedShellCommand(amInstrumentWithUser))

        assertTrue("pm grant is privileged", McpProtocolHandler.isPrivilegedShellCommand("pm grant com.wzvideni.scanplus android.permission.CAMERA"))
        assertTrue("pm clear is privileged", McpProtocolHandler.isPrivilegedShellCommand("pm clear com.wzvideni.scanplus"))
        assertTrue("pm install is privileged", McpProtocolHandler.isPrivilegedShellCommand("pm install /data/local/tmp/app.apk"))
        assertTrue("settings put is privileged", McpProtocolHandler.isPrivilegedShellCommand("settings put secure enabled_accessibility_services com.example/.Service"))
        assertTrue("dumpsys is privileged", McpProtocolHandler.isPrivilegedShellCommand("dumpsys activity top"))
        assertTrue("cmd is privileged", McpProtocolHandler.isPrivilegedShellCommand("cmd notification allow_listener com.example/.Service"))
        assertTrue("input is privileged", McpProtocolHandler.isPrivilegedShellCommand("input tap 100 200"))
        assertTrue("su is privileged", McpProtocolHandler.isPrivilegedShellCommand("su -c id"))
        assertTrue("piped command containing am instrument is privileged", McpProtocolHandler.isPrivilegedShellCommand("cd /data/local/tmp && am instrument -w com.example.test/androidx.test.runner.AndroidJUnitRunner"))

        assertFalse("echo is not privileged", McpProtocolHandler.isPrivilegedShellCommand("echo 'hello world'"))
        assertFalse("ls is not privileged", McpProtocolHandler.isPrivilegedShellCommand("ls -la /sdcard"))
        assertFalse("pwd is not privileged", McpProtocolHandler.isPrivilegedShellCommand("pwd"))
        assertFalse("empty command is not privileged", McpProtocolHandler.isPrivilegedShellCommand(""))
    }

    @Test
    fun testPermissionDenialDetectionAndGuidance() {
        val userError1 = "java.lang.SecurityException: Permission Denial: startInstrumentation asks to run as user -2 but is calling from uid u0a339; this requires android.permission.INTERACT_ACROSS_USERS_FULL"
        assertTrue("SecurityException Permission Denial must be detected", McpProtocolHandler.isPermissionDenial(userError1))

        val userError2 = "INSTRUMENTATION_STATUS: Error=Permission Denial: instrumentation test ComponentInfo{com.wzvideni.scanplus.test/androidx.test.runner.AndroidJUnitRunner} from pid=8702, uid=10339, pkgName=null not allowed because it's not started from SHELL"
        assertTrue("not allowed because it's not started from SHELL must be detected", McpProtocolHandler.isPermissionDenial(userError2))

        val normalOutput = "com.huawei.hms.scankit.p.QRCodeDataMaskInstrumentedTest:......\nTime: 0.345\nOK (6 tests)"
        assertFalse("Normal test output is not permission denial", McpProtocolHandler.isPermissionDenial(normalOutput))

        val guidance = McpProtocolHandler.getPrivilegeGuidance("am instrument -w ...")
        assertTrue("Guidance should mention Root", guidance.contains("Root"))
        assertTrue("Guidance should mention Shizuku", guidance.contains("Shizuku"))
        assertTrue("Guidance should mention UID", guidance.contains("UID"))
    }

    @Test
    fun testExecuteShellParamParsing() {
        fun parseShellArgs(args: JsonObject): Triple<String, Boolean?, Long> {
            val cmd = args["command"]?.jsonPrimitive?.contentOrNull
                ?: args["cmd"]?.jsonPrimitive?.contentOrNull
                ?: ""

            val explicitRoot = args["as_root"]?.jsonPrimitive?.booleanOrNull
                ?: args["use_root"]?.jsonPrimitive?.booleanOrNull
                ?: args["root"]?.jsonPrimitive?.booleanOrNull
                ?: args["as_root"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: args["use_root"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: args["root"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

            val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull
                ?: args["timeout"]?.jsonPrimitive?.longOrNull
                ?: args["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: args["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: 60000L

            return Triple(cmd, explicitRoot, timeoutMs)
        }

        // Test as_root: true
        val (c1, r1, t1) = parseShellArgs(buildJsonObject {
            put("command", JsonPrimitive("id"))
            put("as_root", JsonPrimitive(true))
        })
        assertEquals("id", c1)
        assertEquals(true, r1)
        assertEquals(60000L, t1)

        // Test use_root alias
        val (c2, r2, _) = parseShellArgs(buildJsonObject {
            put("command", JsonPrimitive("id"))
            put("use_root", JsonPrimitive(true))
        })
        assertEquals(true, r2)

        // Test root alias
        val (c3, r3, _) = parseShellArgs(buildJsonObject {
            put("cmd", JsonPrimitive("id"))
            put("root", JsonPrimitive(false))
        })
        assertEquals("id", c3)
        assertEquals(false, r3)

        // Test omitted root -> null
        val (c4, r4, _) = parseShellArgs(buildJsonObject {
            put("command", JsonPrimitive("am instrument ..."))
        })
        assertNull(r4)

        // Test custom timeout_ms
        val (_, _, t5) = parseShellArgs(buildJsonObject {
            put("command", JsonPrimitive("test"))
            put("timeout_ms", JsonPrimitive(120000))
        })
        assertEquals(120000L, t5)
    }
}
