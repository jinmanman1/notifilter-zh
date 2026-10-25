package com.wxmsglog

import java.util.Locale

/**
 * 内置可下载的模型清单。
 *
 * 地址走的是 Ollama 的公开分发节点：它是同一份官方权重，匿名就能下载，
 * 不需要注册、不需要同意任何许可、不需要 token。这点比 Gemma 那套 gated
 * 流程省事太多——Qwen3 全系 Apache 2.0，本来就不限制分发。
 *
 * 链接里的 sha256 是 Ollama manifest 里模型层的 digest，指向的 blob 会
 * 先 307 跳到对象存储再出内容，[ModelDownloader] 会跟着跳。
 */
object ModelCatalog {

    data class Spec(
        val id: String,
        val name: String,
        val fileName: String,
        val url: String,
        val bytes: Long,
        val note: String,
        /** 推荐级别，界面上给个标记。 */
        val recommended: Boolean = false,
    )

    private const val BASE = "https://registry.ollama.ai/v2/library/qwen3/blobs/sha256:"

    val ALL: List<Spec> = listOf(
        Spec(
            id = "qwen3-0.6b",
            name = "Qwen3 0.6B",
            fileName = "qwen3-0.6b-q4.gguf",
            url = BASE + "7f4030143c1c477224c5434f8272c662a8b042079a0a584f0a27a1684fe2e1fa",
            bytes = 522_640_096L,
            note = "最省电，占内存约 0.7G。识别简单句够用，复杂表述容易走样。",
            recommended = false,
        ),
        Spec(
            id = "qwen3-1.7b",
            name = "Qwen3 1.7B",
            fileName = "qwen3-1.7b-q4.gguf",
            url = BASE + "3d0b790534fe4b79525fc3692950408dca41171676ed7e21db57af5c65ef6ab6",
            bytes = 1_359_279_776L,
            note = "推荐。占内存约 1.5G，能听懂复杂指令，输出格式也稳。",
            recommended = true,
        ),
    )

    fun byId(id: String): Spec? = ALL.firstOrNull { it.id == id }

    /** 把字节数说成人话。 */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
