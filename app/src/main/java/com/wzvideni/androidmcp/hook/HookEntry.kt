package com.wzvideni.androidmcp.hook

import android.app.Activity
import android.os.Bundle
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import com.wzvideni.androidmcp.BuildConfig

@InjectYukiHookWithXposed
class HookEntry : IYukiHookXposedInit {

    override fun onInit() = configs {
        isDebug = BuildConfig.DEBUG
        isEnableDataChannel = false
    }

    override fun onHook() = encase {
        loadSystem {
            // Start Hook IPC server when injected into system framework (android / system_server)
            HookIpcServer.ensureServerStarted("android")
        }

        loadApp {
            // Avoid hooking our own module process unnecessarily
            if (packageName == BuildConfig.APPLICATION_ID) return@loadApp

            // Start Hook IPC server immediately for this hooked app process (even if no Activity)
            HookIpcServer.ensureServerStarted(packageName)

            // Hook Activity lifecycle to capture current active Activity and start IPC server
            Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java).hook {
                after {
                    val activity = instance<Activity>()
                    HookIpcServer.updateCurrentActivity(activity)
                }
            }

            Activity::class.java.getDeclaredMethod("onResume").hook {
                after {
                    val activity = instance<Activity>()
                    HookIpcServer.updateCurrentActivity(activity)
                }
            }

            Activity::class.java.getDeclaredMethod("onDestroy").hook {
                after {
                    val activity = instance<Activity>()
                    HookIpcServer.onActivityDestroyed(activity)
                }
            }
        }
    }
}