package com.wxmsglog

import android.os.Handler
import android.os.Looper

/**
 * 监听服务跑在自己的线程里，界面不能直接从那儿刷新。
 * 这里只做一个极简的「有新数据了」信号，切回主线程再回调。
 */
object Bus {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: (() -> Unit)? = null

    fun set(l: (() -> Unit)?) {
        listener = l
    }

    fun notifyChanged() {
        val l = listener ?: return
        handler.post { l.invoke() }
    }
}
