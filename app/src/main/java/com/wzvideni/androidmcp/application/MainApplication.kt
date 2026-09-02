package com.wzvideni.androidmcp.application

import com.highcapable.yukihookapi.hook.xposed.application.ModuleApplication

class MainApplication : ModuleApplication() {

    override fun onCreate() {
        super.onCreate()
        /**
         * 跟随系统夜间模式
         * Follow system night mode
         */
        // Your code here.
    }
}