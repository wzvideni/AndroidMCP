package com.wzvideni.androidmcp.hook

import android.app.Activity
import android.os.Bundle
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.type.android.ActivityClass
import com.highcapable.yukihookapi.hook.type.android.BundleClass
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import com.wzvideni.androidmcp.BuildConfig

@InjectYukiHookWithXposed
class HookEntry : IYukiHookXposedInit {

    override fun onInit() = configs {
        isDebug = BuildConfig.DEBUG
        isEnableDataChannel = false
    }

    override fun onHook() = encase {
        loadApp {
            // Avoid hooking our own module process unnecessarily
            if (packageName == BuildConfig.APPLICATION_ID) return@loadApp

            // Hook Activity lifecycle to capture current active Activity and start IPC server
            ActivityClass.hook {
                injectMember {
                    method {
                        name = "onCreate"
                        param(BundleClass)
                    }
                    afterHook {
                        val activity = instance<Activity>()
                        HookIpcServer.updateCurrentActivity(activity)
                    }
                }
                injectMember {
                    method {
                        name = "onResume"
                        emptyParam()
                    }
                    afterHook {
                        val activity = instance<Activity>()
                        HookIpcServer.updateCurrentActivity(activity)
                    }
                }
                injectMember {
                    method {
                        name = "onDestroy"
                        emptyParam()
                    }
                    afterHook {
                        val activity = instance<Activity>()
                        HookIpcServer.onActivityDestroyed(activity)
                    }
                }
            }
        }
    }
}