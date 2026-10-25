package com.wxmsglog

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 下载任务的全局状态。
 *
 * 单独抽出来是因为 1.3GB 要下好几分钟，用户一定会切到别的页面甚至杀掉界面。
 * 如果把下载放在 Activity 里，一退出就断了；放这里之后，界面只是「观察者」，
 * 进出都能接上当前进度，后台也照跑不误。
 */
object ModelTask {

    data class Snap(
        val specId: String?,
        val done: Long,
        val total: Long,
        val error: String?,
        val finished: Boolean,
    )

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "model-download").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var snap = Snap(null, 0L, 0L, null, true)

    private var observer: ((Snap) -> Unit)? = null

    /** 下载器返回 null 到底是「用户取消」还是「网络出错」，靠这个区分。 */
    private val wasCancelled = AtomicBoolean(false)

    fun current(): Snap = snap

    fun running(): Boolean = !snap.finished

    fun observe(cb: (Snap) -> Unit) {
        observer = cb
        main.post { cb(snap) }
    }

    fun clearObserver() {
        observer = null
    }

    @Synchronized
    fun start(context: Context, spec: ModelCatalog.Spec) {
        if (!snap.finished) return
        val app = context.applicationContext
        wasCancelled.set(false)

        snap = Snap(
            specId = spec.id,
            done = ModelDownloader.downloadedBytes(app, spec),
            total = spec.bytes,
            error = null,
            finished = false,
        )
        emit()

        io.execute {
            val file = ModelDownloader.download(app, spec) { done, total ->
                snap = snap.copy(done = done, total = total)
                emit()
            }
            snap = if (file != null) {
                Snap(spec.id, file.length(), file.length(), null, true)
            } else {
                Snap(
                    specId = spec.id,
                    done = snap.done,
                    total = snap.total,
                    error = if (wasCancelled.get()) null else "下载中断",
                    finished = true,
                )
            }
            emit()
        }
    }

    fun cancel() {
        wasCancelled.set(true)
        ModelDownloader.cancel()
    }

    private fun emit() {
        val s = snap
        main.post { observer?.invoke(s) }
    }
}
