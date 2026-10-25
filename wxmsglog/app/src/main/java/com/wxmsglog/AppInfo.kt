package com.wxmsglog

import android.content.Context
import android.graphics.drawable.Drawable
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

/** 应用包名 → 显示名 / 图标。通知里只带包名，界面和导出文本要用人看得懂的东西。 */
object AppInfo {

    private val cache = ConcurrentHashMap<String, String>()

    /** 图标可能是 null（应用已卸载），ConcurrentHashMap 不收 null，所以单独记一份「查过了」。 */
    private val icons = HashMap<String, Drawable?>()

    fun label(context: Context, pkg: String): String {
        // 分享进来的内容不对应任何真实应用，单独给个名字
        if (pkg == MessageDb.SHARE_PKG) return context.getString(R.string.src_share)
        cache[pkg]?.let { return it }
        val name = try {
            val pm = context.applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (t: Throwable) {
            pkg
        }
        cache[pkg] = name
        return name
    }

    fun icon(context: Context, pkg: String): Drawable? = synchronized(icons) {
        if (icons.containsKey(pkg)) return icons[pkg]
        val d = try {
            context.applicationContext.packageManager.getApplicationIcon(pkg)
        } catch (t: Throwable) {
            null
        }
        icons[pkg] = d
        return d
    }

    /** 应用是否还装着（用户可能卸载了）。 */
    fun installed(context: Context, pkg: String): Boolean = try {
        context.applicationContext.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
