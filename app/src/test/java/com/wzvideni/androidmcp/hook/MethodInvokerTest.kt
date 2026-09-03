package com.wzvideni.androidmcp.hook

import org.junit.Assert.*
import org.junit.Test

class MethodInvokerTest {

    open class BaseClass {
        protected var baseField: String = "base_init"
        fun baseMethod(msg: String): String = "base:$msg"
    }

    class SubClass : BaseClass() {
        private var subInt: Int = 100
        fun calculate(a: Int, b: Int): Int = a + b
        companion object {
            @JvmStatic
            fun staticHello(name: String): String = "Hello, $name"
        }
    }

    @Test
    fun testCallMethod() {
        val obj = SubClass()
        val (ok1, res1) = MethodInvoker.callMethod(obj, "calculate", listOf("15", "27"))
        assertTrue(ok1)
        assertEquals("42", res1)

        val (ok2, res2) = MethodInvoker.callMethod(obj, "baseMethod", listOf("test"))
        assertTrue(ok2)
        assertEquals("base:test", res2)

        val (ok3, res3) = MethodInvoker.callMethod(SubClass::class.java, "staticHello", listOf("World"))
        assertTrue(ok3)
        assertEquals("Hello, World", res3)
    }

    @Test
    fun testGetAndSetField() {
        val obj = SubClass()
        val (okGet1, val1) = MethodInvoker.getField(obj, "subInt")
        assertTrue(okGet1)
        assertEquals("100", val1)

        val (okSet, _) = MethodInvoker.setField(obj, "subInt", "999")
        assertTrue(okSet)

        val (okGet2, val2) = MethodInvoker.getField(obj, "subInt")
        assertTrue(okGet2)
        assertEquals("999", val2)

        // Superclass field access
        val (okBaseGet, baseVal) = MethodInvoker.getField(obj, "baseField")
        assertTrue(okBaseGet)
        assertEquals("base_init", baseVal)
    }

    @Test
    fun testInspectObject() {
        val obj = SubClass()
        val inspection = MethodInvoker.inspectObject(obj)
        assertEquals(SubClass::class.java.name, inspection["class"])
        assertTrue(inspection.keys.any { it.startsWith("field:subInt") })
        assertTrue(inspection.keys.any { it.startsWith("method:calculate") })
    }
}
