package com.wxmsglog

import android.content.Context

/** 极简配置：目前只有「是否记录所有应用」。 */
object Prefs {

    const val WECHAT = "com.tencent.mm"

    private const val FILE = "cfg"
    private const val KEY_ALL = "record_all"

    fun recordAll(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ALL, false)

    fun setRecordAll(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALL, value)
            .apply()
    }
}
