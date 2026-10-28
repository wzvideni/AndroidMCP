package com.wzvideni.androidmcp.mcp

import com.wzvideni.androidmcp.engine.MacroManager
import com.wzvideni.androidmcp.model.RectBounds
import com.wzvideni.androidmcp.model.UiNode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
}
