package top.gtian.clearall

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Modern Xposed API (102) 模块入口。
 * 框架要求：无参构造，入口全类名写在 META-INF/xposed/java_init.list。
 */
class Main : XposedModule() {

    companion object {
        const val TAG = "ClearAllTrebuchet"
        const val TARGET_PKG = "com.android.launcher3"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "module loaded in ${param.processName}")
    }

    /**
     * Trebuchet (com.android.launcher3) 进程加载时注入 hook。
     * 在最近任务底部操作栏添加"清除全部"按钮。
     */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        if (param.packageName != TARGET_PKG) return
        runCatching { ClearAllHook.hook(this, param) }
            .onFailure { log(Log.ERROR, TAG, "hook failed", it) }
    }
}
