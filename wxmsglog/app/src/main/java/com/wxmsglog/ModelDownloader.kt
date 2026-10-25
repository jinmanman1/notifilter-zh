package com.wxmsglog

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * 模型下载器。
 *
 * 直接对着 Ollama 的公开分发地址拉：那个地址会先返回 307 跳到对象存储的
 * 预签名链接，所以这里得手动跟跳转（HttpURLConnection 对跨主机的 307
 * 不一定会自己跟）。
 *
 * 支持断点续传：1.3GB 在手机上下一半被杀后台是常态，从头再来一次没人受得了。
 * 未完成的文件留着 .part 后缀，下次接着写。
 */
object ModelDownloader {

    private const val TAG = "WxDl"
    private const val UA = "WxMsgLog/3.3 (Android)"
    private const val CHUNK = 64 * 1024
    private const val REDIRECT_MAX = 6
    private const val REPORT_INTERVAL_MS = 200L

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /** 已经下到的字节数：.part 的长度，用来在界面上显示「已下多少」。 */
    fun downloadedBytes(context: Context, spec: ModelCatalog.Spec): Long = try {
        val done = File(ModelStore.dir(context), spec.fileName)
        val part = File(ModelStore.dir(context), spec.fileName + ".part")
        when {
            done.exists() && done.length() >= spec.bytes -> spec.bytes
            part.exists() -> part.length()
            else -> 0L
        }
    } catch (t: Throwable) {
        0L
    }

    /**
     * 下载并落盘。阻塞调用，必须放工作线程。
     *
     * @return 下好的文件；失败或被取消返回 null（.part 会保留，下次能续）。
     */
    fun download(
        context: Context,
        spec: ModelCatalog.Spec,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        attempt: Int = 0,
    ): File? {
        cancelled = false
        val dir = ModelStore.dir(context)
        val target = File(dir, spec.fileName)
        val part = File(dir, spec.fileName + ".part")

        // 已经完整下过，直接用
        if (target.exists() && (spec.bytes <= 0 || target.length() >= spec.bytes)) {
            return target
        }

        var have = if (part.exists()) part.length() else 0L
        if (spec.bytes > 0 && have > spec.bytes) {
            // 残留的 .part 比目标还大，说明是脏数据
            part.delete()
            have = 0L
        }
        if (spec.bytes > 0 && have == spec.bytes) {
            return if (part.renameTo(target)) target else null
        }

        var url = spec.url
        var conn = open(url, have)
        var hops = 0
        try {
            while (hops < REDIRECT_MAX) {
                val code = try {
                    conn.responseCode
                } catch (t: Throwable) {
                    Log.e(TAG, "连接失败", t)
                    return null
                }
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrBlank()) return null
                    url = resolve(url, loc)
                    conn.disconnect()
                    conn = open(url, have)
                    hops++
                    continue
                }
                break
            }

            val code = conn.responseCode

            // 416：要的区间超出了文件长度，说明 .part 是脏的，清掉重来。
            // 这个状态码在 HttpURLConnection 里没有对应常量，只能写数字。
            // 只重试一次：真要是服务端一直回 416，再递归下去就是死循环。
            if (code == 416) {
                conn.disconnect()
                part.delete()
                return if (attempt < 1) {
                    download(context, spec, onProgress, attempt + 1)
                } else {
                    null
                }
            }
            if (code !in 200..299) {
                Log.e(TAG, "HTTP $code")
                return null
            }

            // 服务器愿意续传才会回 206；回 200 就是从头给，本地得跟着清零
            val start = if (code == HttpURLConnection.HTTP_PARTIAL) have else 0L
            val total = when {
                spec.bytes > 0 -> spec.bytes
                code == HttpURLConnection.HTTP_PARTIAL -> start + conn.contentLengthLong
                else -> conn.contentLengthLong
            }

            Log.i(TAG, "开始下载 ${spec.fileName}，从 $start / $total")

            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(start)
                raf.seek(start)
                conn.inputStream.use { ins ->
                    val buf = ByteArray(CHUNK)
                    var done = start
                    var lastReport = 0L
                    while (true) {
                        if (cancelled) {
                            Log.i(TAG, "已取消，保留 $done 字节")
                            return null
                        }
                        val n = ins.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport >= REPORT_INTERVAL_MS) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "下载中断", t)
            return null
        } finally {
            try {
                conn.disconnect()
            } catch (t: Throwable) {
                // 断开失败无所谓
            }
        }

        if (cancelled) return null

        // 长度对不上就是没下全（连接被掐、磁盘满了），留着 .part 下次续
        val got = part.length()
        if (spec.bytes > 0 && got < spec.bytes) {
            Log.w(TAG, "下完但长度不足：$got / ${spec.bytes}")
            return null
        }
        if (!ModelStore.looksLikeGguf(part)) {
            Log.e(TAG, "下下来的东西不是 GGUF")
            part.delete()
            return null
        }

        return if (part.renameTo(target)) target else null
    }

    // ---------- 网络细节 ----------

    private fun open(url: String, from: Long): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        // 自己跟跳转，才控制得住每一跳都带上 Range 头
        c.instanceFollowRedirects = false
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "*/*")
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    private fun resolve(base: String, location: String): String = try {
        URI(base).resolve(location).toString()
    } catch (t: Throwable) {
        location
    }
}
