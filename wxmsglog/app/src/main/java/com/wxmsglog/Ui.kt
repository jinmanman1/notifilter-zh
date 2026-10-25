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

        val content = activity.findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // 每次都赋同样的值，不用累加，所以不会随回调次数膨胀
            appBar.setPadding(bars.left, bars.top, bars.right, 0)
            if (bottom != null) bottom.setPadding(bars.left, 0, bars.right, bars.bottom)
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
