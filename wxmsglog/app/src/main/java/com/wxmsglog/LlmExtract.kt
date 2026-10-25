package com.wxmsglog

import android.util.Log
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 用端侧模型从消息里抽待办。
 *
 * 定位是「正则兜不住时的补充」，不是替代：[TodoExtract] 那套词表加时间正则
 * 对「周五前交材料」这类明确表述又快又准，而模型擅长的恰恰是反过来的那种——
 * 「这周得把那个报销的事办了」这种没有动作词、时间也含糊的话。
 *
 * 输出用 GBNF 语法硬约束成固定 JSON。这一步是关键：1.7B 的模型如果只在
 * 提示词里写「请输出 JSON」，十次里总有一两次会加解释、加代码块、少个引号。
 * 而在采样阶段就把非法 token 的概率压成零之后，同样的模型能次次都吐合法结构。
 *
 * ## 为什么坚持一条一跑
 *
 * 试过批量（一次喂五条、输出带编号数组），快是快三成，但小模型同时盯五件事
 * 时会**跳过**某些条——编号对账能挡住「错位」（把张三的事挂到李四头上），
 * 挡不住「漏项」。抽待办这事漏一条就是这个功能没做到，宁慢不错。
 *
 * 速度靠另外两条路解决，都不损准确率：
 *
 * 1. **KV 前缀复用**（在 native 层）。连续几条消息的提示词开头完全一样，
 *    那段 prefill 直接跳过。这是最大的一块。
 * 2. **不值得问的直接不问**（见 [worthAsking]）。纯数字、纯表情、「好的收到」
 *    这类消息里出现待办的概率是零，没必要为它们加载模型。
 */
object LlmExtract {

    private const val TAG = "WxLlmEx"

    /** 单条输出上限。一个 JSON 对象三十来个 token，96 是兜底。 */
    private const val MAX_TOKENS = 96

    /** 模型给出来的正文上限，超了多半是在胡写。 */
    private const val MAX_TEXT = 40

    /** 喂进去之前截断到多少字。单条不需要留太长，待办的线索都在前半句。 */
    private const val FEED_CLIP = 160

    data class Hit(val text: String, val due: Long)

    /**
     * 单对象，不带编号。
     *
     * 不开放转义序列：中文待办里不会出现引号，开着反而给模型多一条出错的路。
     */
    private val GRAMMAR = """
        root ::= "{" ws "\"text\"" ws ":" ws str ws "," ws "\"due\"" ws ":" ws (str | "null") ws "}"
        str  ::= "\"" [^"\\]* "\""
        ws   ::= [ \t\n\r]*
    """.trimIndent()

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val HOUR_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    // ---------- 不值得问模型的判据 ----------
    //
    // 这几条的共同点：命中即「这里面不可能有交代我做的事」，命中率极低但
    // 一旦命中就是百分之百肯定。所以跳过它们不会让召回率掉一点。

    /** 纯数字与分隔符：验证码、单号、金额、「1234」。 */
    private val PURE_CODE = Regex("^[\\d\\s\\-:：,，.。+*#/]+$")

    /** 既没有汉字也没有英文字母：纯表情、纯标点、纯符号。 */
    private val NO_LETTER = Regex("^[^A-Za-z\\u4e00-\\u9fff]+$")

    /** 整段就是一个应答词。去标点后完全相等才算，不做包含匹配。 */
    private val REPLY_ONLY = setOf(
        "好的", "好", "好的好的", "好滴", "好嘞", "行", "可以", "没问题", "行吧",
        "收到", "收到了", "收到啦", "收到谢谢", "好的谢谢", "好谢谢", "谢谢",
        "谢谢啦", "多谢", "感谢", "嗯", "嗯嗯", "嗯呢", "哦", "哦哦", "噢",
        "哈哈", "哈哈哈", "哈哈哈哈", "嘿嘿", "在吗", "在", "早", "晚安",
        "ok", "okay", "okey", "yes", "no", "1",
    )

    /** 系统噪声，和 [TodoExtract] 里那份保持一致的判断口径。 */
    private val NOISE_PARTS = arrayOf(
        "撤回了一条消息", "你已添加", "以上是打招呼", "消息已发出",
        "语音通话", "视频通话", "拍一拍", "开启了朋友验证",
    )

    fun available(): Boolean = Llm.ready()

    /**
     * 判断一条消息里有没有待办。阻塞调用。
     *
     * @return null 表示「没有待办」「不值得问」或「模型不可用」。
     */
    fun extract(
        body: String,
        sender: String,
        msgTs: Long,
        now: Long = System.currentTimeMillis(),
    ): Hit? {
        if (!Llm.ready()) return null
        val text = body.trim()
        if (!worthAsking(text)) return null

        val raw = try {
            Llm.generate(buildPrompt(text, sender, now), GRAMMAR, MAX_TOKENS)
        } catch (t: Throwable) {
            Log.e(TAG, "推理失败", t)
            return null
        } ?: return null

        if (raw.isBlank()) return null
        return parse(raw)
    }

    /**
     * 这条消息值不值得占用一次推理。
     *
     * 这是纯收益的过滤：命中的消息里出现待办的概率是零，跳过它们只是省时间，
     * 不会漏掉任何东西。判不准的一律放行——宁可多问一次，也不要自己吞掉。
     */
    fun worthAsking(body: String): Boolean {
        val t = body.trim()
        if (t.length < 4) return false

        for (p in NOISE_PARTS) if (t.contains(p)) return false
        if (PURE_CODE.matches(t)) return false
        if (NO_LETTER.matches(t)) return false

        // 应答词：去掉首尾标点后再比，避免「收到！」「好的~」漏网
        val bare = t.trim('。', '，', ',', '.', '、', '；', ';', '：', ':', '！', '!', '~', '～', ' ')
            .lowercase()
        if (bare in REPLY_ONLY) return false

        return true
    }

    // ---------- 提示词 ----------

    /**
     * 提示词的结构要**刻意固定**：system 段一字不差，user 段开头先写时间。
     *
     * 这样连续处理多条消息时，两条提示词的公共前缀能覆盖到「当前时间 …」之后，
     * native 层的 KV 复用才有一百多个 token 可以省。中间随便插一个变量（比如
     * 把发信人放前面），公共前缀立刻降到零，优化就废了。
     */
    private fun buildPrompt(body: String, sender: String, now: Long): String = buildString {
        append("<|im_start|>system\n")
        append("你是待办提取器。判断这条消息里有没有交代「我」去做的事。")
        append("只输出 JSON，不要解释。text 是待办本身，去掉「记得」「帮我」「麻烦」这类口头词，")
        append("二十字以内，不是待办就写空字符串；due 是截止时间，格式 yyyy-MM-dd HH:mm，")
        append("原文没提到时间就写 null。")
        append("<|im_end|>\n")

        append("<|im_start|>user\n")
        append("当前时间 ").append(stamp(now)).append('\n')
        if (sender.isNotBlank()) append(sender).append('：')
        append(if (body.length > FEED_CLIP) body.substring(0, FEED_CLIP) + "…" else body)
        append("<|im_end|>\n")

        // Qwen3 默认先想再说。这里在思考段开头就闭合，让它跳过推理链——
        // 抽待办不需要先想两百字，而那两百字在手机上要跑十几秒。
        append("<|im_start|>assistant\n")
        append("<")
        append("/think>\n\n")
    }

    // ---------- 结果解析 ----------

    /** 抽不出合法内容一律当成「没有待办」，不抛给调用方。 */
    private fun parse(raw: String): Hit? {
        val o = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "输出不是合法对象: " + raw.take(80))
            return null
        }

        val text = clean(o.optString("text"))
        if (text.isEmpty() || text.length > MAX_TEXT) return null
        return Hit(text, parseDue(o.optString("due")))
    }

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
    private fun parseDue(s: String): Long {
        val v = s.trim()
        if (v.isEmpty() || v == "null") return 0L

        return try {
            val zone = ZoneId.systemDefault()
            when {
                // 只有日期：默认上午 9 点，比 0 点合理
                v.length <= 10 -> LocalDate.parse(v)
                    .atTime(LocalTime.of(9, 0))
                    .atZone(zone).toInstant().toEpochMilli()

                else -> LocalDateTime.parse(v.substring(0, 16), HOUR_MIN)
                    .atZone(zone).toInstant().toEpochMilli()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "时间解析失败: " + v)
            0L
        }
    }
}
