package com.wzvideni.androidmcp.hook

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object MethodInvoker {

    fun clickViewByIdOrTag(activity: Activity, targetId: String?, viewId: Int?): Boolean {
        var clicked = false
        val decorView = activity.window?.decorView ?: return false

        activity.runOnUiThread {
            val targetView = findView(decorView, targetId, viewId)
            if (targetView != null) {
                targetView.isClickable = true
                clicked = targetView.performClick()
            }
        }
        return clicked
    }

    private fun findView(root: View, targetId: String?, viewId: Int?): View? {
        if (viewId != null && root.id == viewId) return root
        if (!targetId.isNullOrBlank()) {
            if (root.tag?.toString() == targetId) return root
            try {
                val resName = root.resources?.getResourceName(root.id)
                if (resName == targetId || resName?.endsWith("/$targetId") == true) return root
            } catch (_: Throwable) {
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                val found = findView(child, targetId, viewId)
                if (found != null) return found
            }
        }
        return null
    }

    fun callMethod(
        target: Any,
        methodName: String,
        args: List<String>
    ): Pair<Boolean, String> {
        return try {
            val clazz = target as? Class<*> ?: target.javaClass
            val instance = if (target is Class<*>) null else target

            // Find matching method
            val methods = clazz.declaredMethods.filter { it.name == methodName }
            if (methods.isEmpty()) {
                return false to "Method '$methodName' not found on class ${clazz.name}"
            }

            var chosenMethod: Method? = null
            var convertedArgs: Array<Any?>? = null

            for (m in methods) {
                val paramTypes = m.parameterTypes
                if (paramTypes.size == args.size) {
                    try {
                        val parsed = Array(paramTypes.size) { i ->
                            convertStringToType(args[i], paramTypes[i])
                        }
                        chosenMethod = m
                        convertedArgs = parsed
                        break
                    } catch (_: Throwable) {
                    }
                }
            }

            if (chosenMethod == null) {
                return false to "No method '$methodName' matched argument count ${args.size}"
            }

            chosenMethod.isAccessible = true
            val result = chosenMethod.invoke(instance, *(convertedArgs ?: emptyArray()))
            true to (result?.toString() ?: "null (void or returned null)")
        } catch (e: Throwable) {
            false to "Invocation failed: ${e.cause?.message ?: e.message}"
        }
    }

    fun getField(target: Any, fieldName: String): Pair<Boolean, String> {
        return try {
            val clazz = target as? Class<*> ?: target.javaClass
            val instance = if (target is Class<*>) null else target

            val field = findFieldRecursive(clazz, fieldName)
                ?: return false to "Field '$fieldName' not found on ${clazz.name}"

            field.isAccessible = true
            val value = field.get(instance)
            true to (value?.toString() ?: "null")
        } catch (e: Throwable) {
            false to "Get field error: ${e.message}"
        }
    }

    fun setField(target: Any, fieldName: String, valueStr: String): Pair<Boolean, String> {
        return try {
            val clazz = target as? Class<*> ?: target.javaClass
            val instance = if (target is Class<*>) null else target

            val field = findFieldRecursive(clazz, fieldName)
                ?: return false to "Field '$fieldName' not found on ${clazz.name}"

            field.isAccessible = true
            val converted = convertStringToType(valueStr, field.type)
            field.set(instance, converted)
            true to "Field '$fieldName' set to '$valueStr'"
        } catch (e: Throwable) {
            false to "Set field error: ${e.message}"
        }
    }

    fun inspectObject(target: Any): Map<String, String> {
        val clazz = target as? Class<*> ?: target.javaClass
        val instance = if (target is Class<*>) null else target
        val map = mutableMapOf<String, String>()

        map["class"] = clazz.name
        map["isInterface"] = clazz.isInterface.toString()

        for (f in clazz.declaredFields) {
            if (Modifier.isStatic(f.modifiers) && instance != null) continue
            try {
                f.isAccessible = true
                val v = f.get(instance)
                map["field:${f.name} (${f.type.simpleName})"] = v?.toString()?.take(100) ?: "null"
            } catch (_: Throwable) {
            }
        }
        for (m in clazz.declaredMethods) {
            val sig = "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}"
            map["method:$sig"] = if (Modifier.isStatic(m.modifiers)) "static" else "instance"
        }
        return map
    }

    private fun findFieldRecursive(clazz: Class<*>?, fieldName: String): Field? {
        var current = clazz
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(fieldName)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun convertStringToType(value: String, type: Class<*>): Any? {
        return when (type) {
            String::class.java -> value
            Int::class.java, java.lang.Integer::class.java -> value.toInt()
            Long::class.java, java.lang.Long::class.java -> value.toLong()
            Boolean::class.java, java.lang.Boolean::class.java -> value.toBooleanStrictOrNull() ?: (value == "1" || value.equals("true", true))
            Float::class.java, java.lang.Float::class.java -> value.toFloat()
            Double::class.java, java.lang.Double::class.java -> value.toDouble()
            Byte::class.java, java.lang.Byte::class.java -> value.toByte()
            Short::class.java, java.lang.Short::class.java -> value.toShort()
            Char::class.java, java.lang.Character::class.java -> value.firstOrNull() ?: ' '
            else -> value
        }
    }
}
