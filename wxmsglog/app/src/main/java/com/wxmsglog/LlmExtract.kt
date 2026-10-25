package com.wxmsglog

import android.util.Log
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 用端侧模型从一句话里抽待办。
 *
 * 定位是「正则兜不住时的补充」，不是替代：[TodoExtract] 那套词表加时间正则
 * 对「周五前交材料」这类明确表述又快又准，而模型擅长的恰恰是反过来的那种——
 * 「这周得把那个报销的事办了」这种没有动作词、时间也含糊的话。
 *
 * 输出用 GBNF 语法硬约束成固定 JSON。这一步是关键：1.7B 的模型如果只在
 * 提示词里写「请输出 JSON」，十次里总有一两次会加解释、加代码块、或者少个引号。
 * 而在采样阶段就把非法 token 的概率压成零之后，同样的模型能次次都吐合法结构。
 */
object LlmExtract {

    private const val TAG = "WxLlmEx"

    /** 输出上限。一条待办的 JSON 也就三四十个 token，留一倍余量。 */
    private const val MAX_TOKENS = 80

    /** 模型给出来的正文上限，超了多半是在胡写。 */
    private const val MAX_TEXT = 40

    data class Hit(val text: String, val due: Long)

    /**
     * 只允许两种形状：有时间的、没时间的。
     * 不开放转义序列，因为中文待办里不会出现引号，开着反而给模型多一条出错的路。
     */
    private val GRAMMAR = """
        root  ::= "{" ws "\"text\"" ws ":" ws str ws "," ws "\"due\"" ws ":" ws (str | "null") ws "}"
        str   ::= "\"" [^"\\]* "\""
        ws    ::= [ \t\n\r]*
    """.trimIndent()

    private val TEXT_RE = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"")
    private val DUE_RE = Regex("\"due\"\\s*:\\s*(null|\"([^\"]*)\")")

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val HOUR_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun available(): Boolean = Llm.ready()

    /**
     * 判断一条消息里有没有待办。阻塞调用，几百毫秒量级，必须放工作线程。
     *
     * @return null 表示「没有待办」或「模型不可用」，两种情况调用方都当作不添加。
     */
    fun extract(body: String, sender: String, msgTs: Long): Hit? {
        if (!Llm.ready()) return null

        val text = body.trim()
        // 太短的话没有语境，喂进去只会让模型瞎猜
        if (text.length < 4) return null
        // 模型上下文只有 2048，单条消息裁到 300 字足够定位了
        val clipped = if (text.length > 300) text.substring(0, 300) else text

        val raw = try {
            Llm.generate(prompt(clipped, sender, stamp(msgTs)), GRAMMAR, MAX_TOKENS)
        } catch (t: Throwable) {
            Log.e(TAG, "推理失败", t)
            return null
        } ?: return null

        if (raw.isBlank()) return null

        val textMatch = TEXT_RE.find(raw) ?: return null
        val content = clean(textMatch.groupValues[1])
        if (content.isEmpty()) return null
        if (content.length > MAX_TEXT) return null

        return Hit(content, parseDue(raw, msgTs))
    }

    // ---------- 提示词 ----------

    private fun prompt(body: String, sender: String, now: String): String = buildString {
        append("<|im_start|>system\n")
        append("你是待办提取器。判断这句话里有没有交代「我」去做的事。")
        append("只输出 JSON，不要解释。")
        append("text 是待办本身，去掉「记得」「帮我」「麻烦」这类口头词，二十字以内；")
        append("due 是截止时间，格式 yyyy-MM-dd HH:mm，原文没提到时间就写 null；")
        append("如果这句话不是在交代事情，text 写空字符串。")
        append("<|im_end|>\n")

        append("<|im_start|>user\n")
        append("当前时间 ").append(now).append('\n')
        if (sender.isNotBlank()) append("说话人 ").append(sender).append('\n')
        append("原话：").append(body)
        append("<|im_end|>\n")

        // Qwen3 默认先想再说。这里在思考段开头就闭合，让它跳过推理链——
        // 抽一条待办不需要先想两百字，而那两百字在手机上要跑十几秒。
        // 空思考段是 Qwen3 官方的关思考写法，比在提示词里塞 /no_think 更可靠。
        append("<|im_start|>assistant\n")
        append("<")
        append("/think>\n\n")
    }

    // ---------- 结果处理 ----------

    private fun clean(s: String): String =
        s.replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('。', '，', ',', '.', '、', '；', ';', '：', ':')

    private fun stamp(ms: Long): String =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault())
            .format(STAMP)

    /**
     * 把模型给的时间串转成时间戳。解析不出来就当成「没有时间」，
     * 让用户在候选列表里自己点——宁可少给一个默认值，也不要给错一个。
     */
    private fun parseDue(raw: String, msgTs: Long): Long {
        val m = DUE_RE.find(raw) ?: return 0L
        if (m.groupValues[1] == "null") return 0L
        val s = m.groupValues[2].trim()
        if (s.isEmpty()) return 0L

        return try {
            val zone = ZoneId.systemDefault()
            when {
                // 只有日期：默认上午 9 点，比 0 点合理
                s.length <= 10 -> LocalDate.parse(s)
                    .atTime(LocalTime.of(9, 0))
                    .atZone(zone).toInstant().toEpochMilli()

                else -> LocalDateTime.parse(s.substring(0, 16), HOUR_MIN)
                    .atZone(zone).toInstant().toEpochMilli()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "时间解析失败: $s")
            0L
        }
    }
}
