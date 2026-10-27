package com.wxmsglog

import java.util.Locale

/**
 * 可下载的模型清单。
 *
 * 地址走 Ollama 的公开分发节点：同一份官方权重，匿名就能下，
 * 不需要注册、不需要同意许可、不需要 token。Qwen3 是 Apache 2.0，
 * BGE 是 MIT，都没有分发限制。
 *
 * 分两类：
 *  - **[Kind.GEN]** 生成模型，用来抽待办、写摘要
 *  - **[Kind.EMBED]** 向量模型，用来做语义搜索
 *
 * 两类不能互相顶替。向量模型输出的是 512 维向量，不是文字；生成模型
 * 反过来。界面上也分开选，避免用户把 bge 设成「当前模型」然后一脸茫然。
 */
object ModelCatalog {

    enum class Kind { GEN, EMBED }

    data class Spec(
        val id: String,
        val name: String,
        val fileName: String,
        val url: String,
        val bytes: Long,
        /** 向量维度，仅 EMBED 有意义。 */
        val dim: Int,
        val note: String,
        val recommended: Boolean = false,
    )

    private const val OLLAMA = "https://registry.ollama.ai/v2/"

    /** 生成模型：抽待办、写摘要。 */
    val GEN: List<Spec> = listOf(
        Spec(
            id = "qwen3-0.6b",
            name = "Qwen3 0.6B",
            fileName = "qwen3-0.6b-q4.gguf",
            url = OLLAMA + "library/qwen3/blobs/sha256:" +
                "7f4030143c1c477224c5434f8272c662a8b042079a0a584f0a27a1684fe2e1fa",
            bytes = 522_640_096L,
            dim = 0,
            note = "最省电，占内存约 0.7G。识别简单句够用，复杂表述容易走样。",
        ),
        Spec(
            id = "qwen3-1.7b",
            name = "Qwen3 1.7B",
            fileName = "qwen3-1.7b-q4.gguf",
            url = OLLAMA + "library/qwen3/blobs/sha256:" +
                "3d0b790534fe4b79525fc3692950408dca41171676ed7e21db57af5c65ef6ab6",
            bytes = 1_359_279_776L,
            dim = 0,
            note = "推荐。占内存约 1.5G，能听懂复杂指令，输出格式也稳。",
            recommended = true,
        ),
        Spec(
            id = "qwen3-4b",
            name = "Qwen3 4B",
            fileName = "qwen3-4b-q4.gguf",
            url = OLLAMA + "library/qwen3/blobs/sha256:" +
                "3e4cb14174460404e7a233e531675303b2fbf7749c02f91864fe311ab6344e4f",
            bytes = 2_497_280_480L,
            dim = 0,
            note = "识别最准，判断力和「懂没懂意思」明显强一档。代价是占内存约 3.5G、速度约为 1.7B 的一半，建议 8G 以上内存的机器用。",
        ),
        Spec(
            id = "qwen3-8b",
            name = "Qwen3 8B",
            fileName = "qwen3-8b-q4.gguf",
            url = OLLAMA + "library/qwen3/blobs/sha256:" +
                "a3de86cd1c132c822487ededd47a324c50491393e6565cd14bafa40d0b8e686f",
            bytes = 5_225_374_496L,
            dim = 0,
            note = "本地能跑的最强一档。占内存约 6G，要 12G 以上的机器；速度约为 1.7B 的四分之一，" +
                "每轮会自动少处理一些消息，别在急着用的时候点。",
        ),
    )

    /**
     * 向量模型：只服务语义搜索。
     *
     * bge-small-zh 是 BAAI 的中文检索模型，512 维，只有 46M。
     * 它比生成模型小两个数量级、快两个数量级，是这一层唯一值得内置的模型。
     */
    val EMBED: List<Spec> = listOf(
        Spec(
            id = "bge-small-zh",
            name = "BGE Small 中文",
            fileName = "bge-small-zh-v1.5.gguf",
            url = OLLAMA + "quentinz/bge-small-zh-v1.5/blobs/sha256:" +
                "26f293ad974e71827252926af8a5a22ef2bb076c284eaa5b2731924484ab5e52",
            bytes = 48_325_984L,
            dim = 512,
            note = "46M，专做「意思相近」的检索。搜「那个钱的事」也能找到「报销还没交」。",
            recommended = true,
        ),
    )

    /** 全部条目，界面遍历用。 */
    val ALL: List<Spec> = GEN + EMBED

    fun byId(id: String): Spec? = ALL.firstOrNull { it.id == id }

    fun kindOf(spec: Spec): Kind = if (spec.dim > 0) Kind.EMBED else Kind.GEN

    /** 把字节数说成人话。 */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
