package com.wxmsglog

import android.util.Log
import java.io.File

/**
 * 端侧推理的门面。
 *
 * 底下是 llama.cpp。这里只管三件事：把模型加载进来、给它一段提示词、
 * 拿到它吐出来的字符串。提示词怎么拼、JSON 怎么约束，都在 [LlmPrompt] 里。
 *
 * 一次只能加载一个模型。换模型要先 [unload] 再 [load]——同时开两个
 * 1.7B 模型会直接把手机内存吃穿。
 */
object Llm {

    private const val TAG = "WxLlm"

    /**
     * 上下文窗口。抽待办最多喂几百字，2048 绰绰有余。
     * 开大了没好处：KV cache 是按 n_ctx 分配的，2048 已经要几十兆。
     */
    private const val N_CTX = 2048

    /** 模型文件不可能小于这个数。选错文件时靠它挡一下，省得让 native 去读一张图片。 */
    private const val MIN_MODEL_BYTES = 20L * 1024 * 1024

    // 这几个字段必须写在 init 块之前：Kotlin 按源码顺序初始化，
    // 声明在 init 之后的属性会在 init 跑完之后再赋默认值，把 init 里设的结果盖掉。
    @Volatile private var handle: Long = 0L
    @Volatile private var loadedPath: String? = null
    @Volatile private var busy = false

    /** 各核是否同一档。算一次缓存住，null 表示还没算过。 */
    @Volatile private var uniformCores: Boolean? = null

    /** native 库是否可用。不可用时整个 AI 功能静默降级，不影响其他功能。 */
    @Volatile private var nativeOk = false

    /** 最后一次用模型的时间。空闲太久就把内存还回去。 */
    @Volatile private var lastUsedAt: Long = 0L

    /**
     * 空闲多久之后释放模型。
     *
     * 1.7B 加载完常驻要 1.5G。不释放的话，用户用过一次 AI，这块内存就一直
     * 占着，直到进程被杀——低内存机型会顺手把通知监听服务也干掉，消息就断了。
     *
     * 5 分钟是个折中：太短会导致连续操作时反复加载（每次几秒），
     * 太长等于没释放。
     */
    const val IDLE_UNLOAD_MS = 5 * 60_000L

    private fun touch() {
        lastUsedAt = System.currentTimeMillis()
    }

    /**
     * 空闲超时就释放模型。返回是否真的释放了。
     * 由界面在合适的时机（退到后台一段时间后）调，不做自动定时器。
     */
    @Synchronized
    fun sweepIdle(idleMs: Long = IDLE_UNLOAD_MS): Boolean {
        if (handle == 0L && embHandle == 0L) return false
        if (lastUsedAt == 0L) return false
        if (System.currentTimeMillis() - lastUsedAt < idleMs) return false
        Log.i(TAG, "空闲超时，释放模型内存")
        unload()
        unloadEmbed()
        lastUsedAt = 0L
        return true
    }

    init {
        // 加载失败不能让类初始化炸掉。没有这个 catch，只要 .so 因为任何原因没进包
        // （ABI 选错、构建漏编、架构不匹配），任何触碰 Llm 的代码都会在类初始化时
        // 抛 ExceptionInInitializerError，连「模型不可用」的提示都来不及给。
        nativeOk = try {
            System.loadLibrary("llmjni")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "native 库加载失败，AI 功能不可用", t)
            false
        }
    }

    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long

    /** 返回 UTF-8 原始字节，不是 String——四字节字符走 NewStringUTF 会被改坏。 */
    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        grammar: String?,
        maxTokens: Int,
    ): ByteArray

    private external fun nativeAbort(handle: Long)
    private external fun nativeFree(handle: Long)

    fun available(): Boolean = nativeOk

    fun ready(): Boolean = nativeOk && handle != 0L

    fun loadedPath(): String? = loadedPath

    /** 正在推理。界面据此禁用按钮，避免重复点。 */
    fun busy(): Boolean = busy

    /**
     * 推理线程数。
     *
     * 不是越多越好，取决于这台机器的核是不是「一样大」。
     *
     * 大小核机器（1+3+4 那种）：线程数超过大核数之后，多出来的线程会被调度到
     * 小核上。llama.cpp 是把矩阵按行切给各线程、每层结束要同步的，跑在小核上
     * 的那几条会拖住整轮，所以 6 条封顶反而更快。
     *
     * 全大核机器（比如骁龙 8 至尊版那种 2 个 4.32G + 6 个 3.53G，没有小核）：
     * 没有这个问题，开满 8 条就是实打实的加速。
     *
     * 判断依据是各核最大频率是否接近——频率差不到 35% 就当作同一档。
     */
    fun threads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        if (isUniformCores()) return cores.coerceIn(2, 8)
        return when {
            cores >= 8 -> 6
            cores >= 6 -> 5
            else -> (cores - 1).coerceIn(2, 4)
        }
    }

    /** 各核是不是同一档（全大核 / 全小核）。读不到频率就当不是，走保守分支。 */
    private fun isUniformCores(): Boolean {
        uniformCores?.let { return it }
        val f = maxFreqs()
        val hi = f.maxOrNull() ?: 0L
        val lo = f.minOrNull() ?: 0L
        val r = f.size >= 4 && hi > 0L && (hi - lo).toDouble() / hi < 0.35
        uniformCores = r
        if (f.isEmpty()) {
            Log.w(TAG, "读不到 CPU 频率，按大小核处理")
        } else {
            Log.i(TAG, "CPU ${f.size} 核，频率 ${lo / 1000}~${hi / 1000} MHz，${if (r) "全大核" else "大小核"}")
        }
        return r
    }

    /** 各 CPU 核的最大频率（kHz）。读不到的核直接跳过。 */
    private fun maxFreqs(): List<Long> = try {
        (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            val p = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (p.canRead()) p.readText().trim().toLongOrNull() else null
        }
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * 加载模型。耗时几秒（1.3GB 的 mmap 建立 + 张量布局），必须在工作线程调。
     * 返回失败原因，成功时 message 为空。
     */
    @Synchronized
    fun load(file: File): String? {
        if (!nativeOk) return "这个安装包不含推理引擎，AI 功能用不了"
        if (!file.exists()) return "模型文件不存在"
        if (file.length() < MIN_MODEL_BYTES) return "文件太小，不像模型"
        if (!ModelStore.looksLikeGguf(file)) return "不是 GGUF 格式"

        // 已经加载的就是它，不用重来
        if (handle != 0L && loadedPath == file.absolutePath) return null

        unload()

        val h = try {
            nativeLoad(file.absolutePath, N_CTX, threads())
        } catch (t: Throwable) {
            Log.e(TAG, "加载抛异常", t)
            0L
        }
        return if (h == 0L) {
            "加载失败，可能是内存不足或文件损坏"
        } else {
            handle = h
            loadedPath = file.absolutePath
            touch()
            Log.i(TAG, "已加载 ${file.name}，线程 ${threads()}")
            null
        }
    }

    @Synchronized
    fun unload() {
        val h = handle
        handle = 0L
        loadedPath = null
        if (h != 0L) {
            try {
                nativeFree(h)
            } catch (t: Throwable) {
                Log.e(TAG, "释放失败", t)
            }
        }
    }

    /** 让正在跑的那次生成尽快收手。界面退出时调一下，别让后台线程空转几十秒。 */
    fun cancel() {
        val h = handle
        if (h != 0L) {
            try {
                nativeAbort(h)
            } catch (t: Throwable) {
                Log.e(TAG, "取消失败", t)
            }
        }
    }

    /**
     * 跑一次生成，同步返回完整结果。
     *
     * @param grammar GBNF 语法。给了它，采样阶段就会掐掉不符合语法的 token，
     *                小模型也能稳定吐出合法 JSON。
     * @return 生成的文本；模型没加载好时返回 null。
     */
    fun generate(
        prompt: String,
        grammar: String? = null,
        maxTokens: Int = 96,
    ): String? {
        val h = handle
        if (h == 0L) return null
        if (busy) return null
        busy = true
        touch()
        return try {
            val raw = nativeGenerate(h, prompt, grammar, maxTokens)
            String(raw, Charsets.UTF_8).trim()
        } catch (t: Throwable) {
            Log.e(TAG, "生成失败", t)
            null
        } finally {
            busy = false
        }
    }

    // ---------- 向量模型（语义搜索用） ----------
    //
    // 和生成模型是两个独立的会话，可以同时活着：bge 只有 46M，
    // 跟 1.7B 的生成模型抢不了多少内存。互不干扰也意味着
    // 「卸载生成模型」不会顺手把语义搜索弄失效。

    private external fun nativeLoadEmbed(path: String): Long
    private external fun nativeEmbed(handle: Long, text: String): FloatArray

    @Volatile private var embHandle: Long = 0L
    @Volatile private var embPath: String? = null

    fun embedReady(): Boolean = nativeOk && embHandle != 0L

    fun loadedEmbedPath(): String? = embPath

    @Synchronized
    fun loadEmbed(file: File): String? {
        if (!nativeOk) return "这个安装包不含推理引擎"
        if (!file.exists()) return "向量模型不存在"
        if (file.length() < 1024 * 1024) return "文件太小，不像模型"
        if (embHandle != 0L && embPath == file.absolutePath) return null

        unloadEmbed()

        val h = try {
            nativeLoadEmbed(file.absolutePath)
        } catch (t: Throwable) {
            Log.e(TAG, "向量模型加载失败", t)
            0L
        }
        return if (h == 0L) {
            "向量模型加载失败"
        } else {
            embHandle = h
            embPath = file.absolutePath
            touch()
            null
        }
    }

    @Synchronized
    fun unloadEmbed() {
        val h = embHandle
        embHandle = 0L
        embPath = null
        if (h != 0L) {
            runCatching { nativeFree(h) }
        }
    }

    /**
     * 把文字变成单位向量。模型没加载或算不出来时返回 null。
     * 阻塞调用，一条几毫秒。
     */
    fun embed(text: String): FloatArray? {
        val h = embHandle
        if (h == 0L) return null
        touch()
        return try {
            val v = nativeEmbed(h, text)
            if (v.isEmpty()) null else v
        } catch (t: Throwable) {
            Log.e(TAG, "向量计算失败", t)
            null
        }
    }
}
