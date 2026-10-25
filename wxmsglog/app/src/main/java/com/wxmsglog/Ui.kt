package com.wxmsglog

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 全面屏适配。
 *
 * targetSdk 35 起，Android 15 强制「边到边」布局——窗口内容会一直铺到状态栏和
 * 导航栏下面。如果不自己处理 insets，顶栏会被状态栏盖住、底部按钮会被导航栏压住。
 * 所以这里统一做两件事：让窗口延伸到全屏，再把系统栏占用的高度作为 padding
 * 补回给顶栏和底部容器。
 *
 * 界面固定为纯黑主题（见 themes.xml），所以系统栏图标永远用浅色，不再区分日夜。
 */
object Ui {

    /**
     * @param appBar 顶栏。上边距补为状态栏高度，背景色自然延伸到状态栏区域。
     * @param bottom 底部容器（可为空）。下边距补为导航栏高度。
     */
    @Suppress("DEPRECATION")
    fun edgeToEdge(activity: Activity, appBar: View, bottom: View?) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 状态栏、导航栏下面都是黑底，图标必须用浅色
        val controller = WindowCompat.getInsetsController(window, appBar)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        // 老系统上这两个属性还有效，设成透明才能看到下面的内容
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false

        // 布局里可能本来就留了内边距（比如 RecyclerView 的 paddingBottom=24dp，
        // 让最后一条不贴着屏幕边）。insets 回调会反复触发，如果每次都直接覆盖成
        // bars.bottom，原本那点留白就丢了——表现就是最后一条被导航栏压住。
        // 所以先记下原始值，每次都用「原始值 + insets」重新算。
        val appBarPadTop = appBar.paddingTop
        val bottomPadTop = bottom?.paddingTop ?: 0
        val bottomPadBottom = bottom?.paddingBottom ?: 0

        val content = activity.findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            appBar.setPadding(bars.left, appBarPadTop + bars.top, bars.right, appBar.paddingBottom)
            if (bottom != null) {
                bottom.setPadding(
                    bars.left,
                    bottomPadTop,
                    bars.right,
                    bottomPadBottom + bars.bottom,
                )
            }
            insets
        }
        appBar.post { ViewCompat.requestApplyInsets(content) }
    }

    /**
     * 按设置决定要不要给窗口加 FLAG_SECURE。
     * 加上之后系统截屏、录屏、最近任务缩略图拿到的都是黑屏——应用锁只能挡住
     * 「打开 App」，挡不住别人从后台缩略图或截图里看内容。
     * 可以在运行时随时加/去掉，不用重启 Activity。
     */
    fun applySecureFlag(activity: Activity) {
        if (Prefs.blockScreenshot(activity)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * 二级页面的锁检查。
     *
     * 主界面会把内容藏起来再弹验证，二级页面不重复这套——直接 finish() 退回主界面，
     * 由它统一验证。这样「离开太久」的判断只有一处，不会每个页面各写一套。
     *
     * @return true 表示已经退出，调用方应当立即 return。
     */
    fun closeIfLocked(activity: Activity): Boolean {
        if (!Prefs.lockEnabled(activity)) return false
        if (!Lock.shouldRelock(activity)) return false
        Lock.unlocked = false
        activity.finish()
        return true
    }

    /** 记录「这个页面退到后台了」，判断重新验证时要用。 */
    fun markLeftIfLocked(activity: Activity) {
        if (Prefs.lockEnabled(activity)) Lock.markLeft()
    }

    /** 读主题里的正文颜色。用代码给 TextView 换色时必须拿这个，否则会跟主题脱节。 */
    fun textColorPrimary(activity: Activity): Int {
        val attrs = activity.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        return try {
            attrs.getColor(0, Color.WHITE)
        } finally {
            attrs.recycle()
        }
    }
}
