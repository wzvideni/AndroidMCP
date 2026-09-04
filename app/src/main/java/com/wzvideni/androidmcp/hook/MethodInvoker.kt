package com.wzvideni.androidmcp.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

@SuppressLint("PrivateApi")
object MethodInvoker {

    /**
     * Resolves a Class by name using multiple fallback ClassLoaders (target app, context, system).
     */
    fun loadClass(className: String, preferredClassLoader: ClassLoader? = null): Class<*>? {
        val appClassLoader = try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = atClass.getMethod("currentApplication")
            val app = currentAppMethod.invoke(null) as? Application
            app?.classLoader
        } catch (_: Throwable) {
            null
        }

        val loaders = listOfNotNull(
            preferredClassLoader,
            appClassLoader,
            Thread.currentThread().contextClassLoader,
            MethodInvoker::class.java.classLoader,
            ClassLoader.getSystemClassLoader()
        )

        for (loader in loaders) {
            try {
                return Class.forName(className, false, loader)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * Attempts to resolve an instantiated object from a Class (e.g. Application, Activity, Singleton, Kotlin object).
     */
    fun resolveInstance(clazz: Class<*>, currentActivity: Activity? = null): Any {
        if (currentActivity != null && clazz.isInstance(currentActivity)) {
            return currentActivity
        }

        // 1. Try Application / Context singleton
        if (Application::class.java.isAssignableFrom(clazz) || Context::class.java.isAssignableFrom(clazz)) {
            try {
                val atClass = Class.forName("android.app.ActivityThread")
                val app = atClass.getMethod("currentApplication").invoke(null)
                if (app != null && clazz.isInstance(app)) return app
            } catch (_: Throwable) {
            }
        }

        // 2. Try Kotlin object "INSTANCE" field
        try {
            val instanceField = clazz.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            val obj = instanceField.get(null)
            if (obj != null) return obj
        } catch (_: Throwable) {
        }

        // 3. Try standard singleton fields "sInstance", "instance"
        for (fName in listOf("sInstance", "instance", "mInstance")) {
            try {
                val f = clazz.getDeclaredField(fName)
                f.isAccessible = true
                val obj = f.get(null)
                if (obj != null) return obj
            } catch (_: Throwable) {
            }
        }

        // 4. Try standard singleton methods "getInstance()", "getDefault()"
        for (mName in listOf("getInstance", "getDefault", "get")) {
            try {
                val m = clazz.getDeclaredMethod(mName)
                if (Modifier.isStatic(m.modifiers)) {
                    m.isAccessible = true
                    val obj = m.invoke(null)
                    if (obj != null) return obj
                }
            } catch (_: Throwable) {
            }
        }

        return clazz
    }

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

    fun longClickViewByIdOrTag(activity: Activity, targetId: String?, viewId: Int?): Boolean {
        var clicked = false
        val decorView = activity.window?.decorView ?: return false

        activity.runOnUiThread {
            val targetView = findView(decorView, targetId, viewId)
            if (targetView != null) {
                targetView.isLongClickable = true
                clicked = targetView.performLongClick()
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

            // Find matching methods including superclasses and interfaces
            val methods = findMethodsRecursive(clazz, methodName)
            if (methods.isEmpty()) {
                return false to "Method '$methodName' not found on class ${clazz.name} or its superclasses"
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
                return false to "No method '$methodName' matched parameter count ${args.size} on class ${clazz.name}"
            }

            chosenMethod.isAccessible = true
            val execInstance = if (Modifier.isStatic(chosenMethod.modifiers)) null else (instance ?: resolveInstance(clazz))
            val result = chosenMethod.invoke(execInstance, *(convertedArgs ?: emptyArray()))
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
                ?: return false to "Field '$fieldName' not found on ${clazz.name} or its superclasses"

            field.isAccessible = true
            val execInstance = if (Modifier.isStatic(field.modifiers)) null else (instance ?: resolveInstance(clazz))
            val value = field.get(execInstance)
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
                ?: return false to "Field '$fieldName' not found on ${clazz.name} or its superclasses"

            field.isAccessible = true
            val execInstance = if (Modifier.isStatic(field.modifiers)) null else (instance ?: resolveInstance(clazz))
            val converted = convertStringToType(valueStr, field.type)
            field.set(execInstance, converted)
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

        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (f in current.declaredFields) {
                if (Modifier.isStatic(f.modifiers) && instance != null) continue
                try {
                    f.isAccessible = true
                    val v = f.get(instance)
                    map["field:${f.name} (${f.type.simpleName})"] = v?.toString()?.take(100) ?: "null"
                } catch (_: Throwable) {
                }
            }
            for (m in current.declaredMethods) {
                val sig = "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}"
                val key = "method:$sig"
                if (!map.containsKey(key)) {
                    map[key] = if (Modifier.isStatic(m.modifiers)) "static" else "instance"
                }
            }
            current = current.superclass
        }
        return map
    }

    private fun findMethodsRecursive(clazz: Class<*>?, methodName: String): List<Method> {
        val list = mutableListOf<Method>()
        var current = clazz
        while (current != null && current != Any::class.java) {
            list.addAll(current.declaredMethods.filter { it.name == methodName })
            current = current.superclass
        }
        return list
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
            Int::class.java, Int::class.javaObjectType -> value.toInt()
            Long::class.java, Long::class.javaObjectType -> value.toLong()
            Boolean::class.java, Boolean::class.javaObjectType -> value.toBooleanStrictOrNull() ?: (value == "1" || value.equals("true", true))
            Float::class.java, Float::class.javaObjectType -> value.toFloat()
            Double::class.java, Double::class.javaObjectType -> value.toDouble()
            Byte::class.java, Byte::class.javaObjectType -> value.toByte()
            Short::class.java, Short::class.javaObjectType -> value.toShort()
            Char::class.java, Char::class.javaObjectType -> value.firstOrNull() ?: ' '
            else -> value
        }
    }
}
