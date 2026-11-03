package com.wzvideni.androidmcp.application

import com.highcapable.yukihookapi.hook.xposed.application.ModuleApplication
import com.wzvideni.androidmcp.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class MainApplication : ModuleApplication() {

    override fun onCreate() {
        super.onCreate()
        /**
         * 跟随系统夜间模式
         * Follow system night mode
         */
        // Your code here.

        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("MainApplication", "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
            originalHandler?.uncaughtException(thread, throwable)
        }

        startKoin {
            // 在 Debug 构建下输出 Koin 日志，Release 下关闭避免性能开销
            androidLogger(if (com.wzvideni.androidmcp.BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(this@MainApplication)
            modules(appModule)
        }
    }
}