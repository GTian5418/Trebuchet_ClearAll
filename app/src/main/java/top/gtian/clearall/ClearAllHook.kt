package top.gtian.clearall

import android.content.Context
import android.content.res.Resources
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 为 Trebuchet (LineageOS launcher3) 最近任务底部操作栏添加"清除全部"按钮。
 *
 * 原理：
 *  1. Hook RecentsView.init(OverviewActionsView, ...) 建立 actionsView → recentsView 映射
 *  2. Hook OverviewActionsView.onFinishInflate() 在操作栏中添加"清除全部"按钮
 *  3. 按钮点击时调用 RecentsView.dismissAllTasks(View) 清除所有最近任务
 *
 * 安全措施：
 *  - ExceptionMode.PROTECTIVE 防止 hook 异常崩溃宿主
 *  - WeakReference / WeakHashMap 避免内存泄漏
 *  - 反射查找全部 try-catch，失败时降级
 *  - processedViews 防止重复添加按钮
 */
object ClearAllHook {

    private const val TAG = "${Main.TAG}/hook"
    private const val PKG = Main.TARGET_PKG

    /** OverviewActionsView → RecentsView 弱引用映射 */
    private val recentsViewMap =
        Collections.synchronizedMap(mutableMapOf<View, WeakReference<Any>>())

    /** 已添加按钮的视图集合（弱引用，防止重复添加） */
    private val processedViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** RecentsView.dismissAllTasks(View) 方法引用 */
    @Volatile
    private var dismissAllTasksMethod: Method? = null

    fun hook(module: Main, param: PackageLoadedParam) {
        val cl = param.defaultClassLoader
        var count = 0
        count += hookRecentsViewInit(module, cl)
        count += hookOverviewActionsInflate(module, cl)
        module.log(Log.INFO, TAG, "hooks installed: $count")
    }

    // -----------------------------------------------------------------------
    // Hook 1: RecentsView.init(OverviewActionsView, SplitSelectStateController)
    // -----------------------------------------------------------------------
    private fun hookRecentsViewInit(module: Main, cl: ClassLoader): Int {
        val recentsViewCls = loadClass(module, cl, "com.android.quickstep.views.RecentsView")
            ?: return 0
        val actionsCls = loadClass(module, cl, "com.android.quickstep.views.OverviewActionsView")
            ?: return 0

        // 查找 init(OverviewActionsView, *) 方法
        val initMethod = recentsViewCls.declaredMethods.firstOrNull {
            it.name == "init" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == actionsCls
        } ?: run {
            module.log(Log.ERROR, TAG, "RecentsView.init(OverviewActionsView, *) not found")
            return 0
        }

        // 解析 dismissAllTasks 方法
        dismissAllTasksMethod = resolveDismissAllTasks(recentsViewCls)
        if (dismissAllTasksMethod == null) {
            module.log(Log.WARN, TAG, "dismissAllTasks not found — button will be inactive")
        }

        module.hook(initMethod)
            .setId("RecentsView/init")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                chain.proceed()
                val recentsView = chain.thisObject
                val actionsView = chain.args[0] as? View
                if (actionsView != null) {
                    recentsViewMap[actionsView] = WeakReference(recentsView)
                }
            }
        module.log(Log.INFO, TAG, "hooked RecentsView.init")
        return 1
    }

    /** 尝试解析 dismissAllTasks(View) 或 dismissAllTasks() */
    private fun resolveDismissAllTasks(cls: Class<*>): Method? {
        // 优先 dismissAllTasks(View)
        runCatching {
            return cls.getDeclaredMethod("dismissAllTasks", View::class.java)
                .apply { isAccessible = true }
        }
        // 降级 dismissAllTasks()
        runCatching {
            return cls.getDeclaredMethod("dismissAllTasks")
                .apply { isAccessible = true }
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Hook 2: OverviewActionsView.onFinishInflate()
    // -----------------------------------------------------------------------
    private fun hookOverviewActionsInflate(module: Main, cl: ClassLoader): Int {
        val actionsCls = loadClass(module, cl, "com.android.quickstep.views.OverviewActionsView")
            ?: return 0

        val onFinishInflate = runCatching {
            actionsCls.getDeclaredMethod("onFinishInflate")
        }.getOrElse {
            module.log(Log.ERROR, TAG, "onFinishInflate not found", it)
            return 0
        }

        module.hook(onFinishInflate)
            .setId("OverviewActions/onFinishInflate")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                chain.proceed()
                runCatching {
                    addClearAllButton(module, chain.thisObject as View)
                }.onFailure {
                    module.log(Log.WARN, TAG, "addClearAllButton failed", it)
                }
            }
        module.log(Log.INFO, TAG, "hooked OverviewActionsView.onFinishInflate")
        return 1
    }

    // -----------------------------------------------------------------------
    // 添加"清除全部"按钮
    // -----------------------------------------------------------------------
    private fun addClearAllButton(module: Main, actionsView: View) {
        if (actionsView in processedViews) return
        processedViews.add(actionsView)

        val res = actionsView.resources
        val ctx = actionsView.context

        // 查找 action_buttons 容器
        val container = findActionButtonsContainer(actionsView, res)
        if (container == null) {
            module.log(Log.WARN, TAG, "action_buttons container not found")
            return
        }

        // 查找 screenshot 按钮作为样式参考
        val screenshotBtn = findViewByIdName(actionsView, res, "action_screenshot") as? Button

        // 创建"清除全部"按钮
        val clearAllBtn = createClearAllButton(ctx, res, screenshotBtn)

        // 复制 LayoutParams
        applyLayoutParams(screenshotBtn, clearAllBtn)

        // 点击监听器 → 调用 RecentsView.dismissAllTasks
        clearAllBtn.setOnClickListener { btn ->
            onClearAllClicked(module, actionsView, btn)
        }

        container.addView(clearAllBtn)
        module.log(Log.INFO, TAG, "clear all button added to action bar")
    }

    private fun onClearAllClicked(module: Main, actionsView: View, btn: View) {
        runCatching {
            val recentsView = recentsViewMap[actionsView]?.get()
            if (recentsView == null) {
                module.log(Log.WARN, TAG, "RecentsView not mapped yet")
                return
            }
            val method = dismissAllTasksMethod
            if (method == null) {
                module.log(Log.ERROR, TAG, "dismissAllTasks method unavailable")
                return
            }
            if (method.parameterTypes.isEmpty()) {
                method.invoke(recentsView)
            } else {
                method.invoke(recentsView, btn)
            }
            module.log(Log.INFO, TAG, "dismissAllTasks invoked")
        }.onFailure {
            module.log(Log.ERROR, TAG, "dismissAllTasks failed", it)
        }
    }

    // -----------------------------------------------------------------------
    // 按钮创建与样式
    // -----------------------------------------------------------------------
    private fun createClearAllButton(
        ctx: Context,
        res: Resources,
        refBtn: Button?
    ): Button {
        // 尝试用 OverviewClearAllButton 样式创建
        val styleId = res.getIdentifier("OverviewClearAllButton", "style", PKG)
        val btn = if (styleId != 0) {
            runCatching { Button(ctx, null, 0, styleId) }
                .getOrElse { Button(ctx) }
        } else {
            Button(ctx)
        }

        // 设置文本
        val textId = res.getIdentifier("recents_clear_all", "string", PKG)
        btn.text = if (textId != 0) res.getString(textId) else "清除全部"

        // 如果未使用样式，从参考按钮复制样式
        if (styleId == 0 && refBtn != null) {
            copyButtonStyle(refBtn, btn)
        }

        // 设置背景（如果样式未提供）
        if (btn.background == null) {
            val bgId = res.getIdentifier("bg_overview_clear_all_button", "drawable", PKG)
            if (bgId != 0) {
                runCatching { btn.setBackgroundResource(bgId) }
            }
        }

        return btn
    }

    private fun copyButtonStyle(src: Button, dst: Button) {
        runCatching {
            dst.setTextColor(src.currentTextColor)
            dst.textSize = src.textSize
            dst.setPadding(
                src.paddingLeft, src.paddingTop,
                src.paddingRight, src.paddingBottom
            )
            src.background?.constantState?.newDrawable()?.let { dst.background = it }
            dst.compoundDrawablePadding = src.compoundDrawablePadding
            dst.minimumWidth = src.minimumWidth
            dst.minimumHeight = src.minimumHeight
            dst.gravity = src.gravity
            dst.typeface = src.typeface
        }
    }

    private fun applyLayoutParams(refBtn: View?, target: View) {
        if (refBtn == null) return
        val lp = refBtn.layoutParams ?: return
        val newLp = when (lp) {
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(lp)
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(lp)
            else -> ViewGroup.LayoutParams(lp)
        }
        target.layoutParams = newLp
    }

    // -----------------------------------------------------------------------
    // 工具方法
    // -----------------------------------------------------------------------
    private fun findActionButtonsContainer(view: View, res: Resources): ViewGroup? {
        val id = res.getIdentifier("action_buttons", "id", PKG)
        if (id != 0) {
            view.findViewById<ViewGroup>(id)?.let { return it }
        }
        // 降级：通过 screenshot 按钮的 parent 查找
        val screenshotId = res.getIdentifier("action_screenshot", "id", PKG)
        if (screenshotId != 0) {
            val screenshot = view.findViewById<View>(screenshotId)
            (screenshot?.parent as? ViewGroup)?.let { return it }
        }
        return null
    }

    private fun findViewByIdName(view: View, res: Resources, name: String): View? {
        val id = res.getIdentifier(name, "id", PKG)
        return if (id != 0) view.findViewById(id) else null
    }

    private fun loadClass(module: Main, cl: ClassLoader, name: String): Class<*>? {
        return runCatching { cl.loadClass(name) }.getOrElse {
            module.log(Log.WARN, TAG, "class not found: $name")
            null
        }
    }
}
