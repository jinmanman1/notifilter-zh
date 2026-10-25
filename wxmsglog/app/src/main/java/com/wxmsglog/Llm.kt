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

    /** native 库是否可用。不可用时整个 AI 功能静默降级，不影响其他功能。 */
    @Volatile private var nativeOk = false

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

    /** 大核数量。开太多线程反而会因为抢 CPU 和降频变慢，6 个封顶。 */
    fun threads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

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
}
