package com.wxmsglog

import android.content.Context
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
     * 单对象。多了一个 `todo` 布尔字段——这是这轮的关键改动。
     *
     * 原来让模型「不是待办就写空 text」，等于让它用「什么都不写」来表达否定。
     * 小模型不擅长这种间接表达，它倾向于**硬写点什么**，结果就是闲聊被抽成待办。
     * 给一个显式的 `"todo": false`，它才有正规的渠道说「这条不算」。
     *
     * 字段顺序也刻意排成 todo → text → due：自回归模型是先写 todo 再决定说什么，
     * 顺序和它做判断的自然顺序一致，比先写正文再补结论要稳。
     *
     * 不开放转义序列：中文待办里不会出现引号，开着反而给模型多一条出错的路。
     */
    private val GRAMMAR = """
        root ::= "{" ws "\"todo\"" ws ":" ws bool ws "," ws "\"text\"" ws ":" ws str ws "," ws "\"due\"" ws ":" ws (str | "null") ws "}"
        bool ::= "true" | "false"
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
     * 默认提示词模板。用户可以在 设置 → AI → 提示词 里改，改完不用重装。
     *
     * 三个占位符：`{{time}}`、`{{sender}}`、`{{body}}`。用 [render] 填。
     *
     * 注意末尾那两个空行——`</think>` 后面必须有换行，模型才会跳过思考段直接
     * 给答案。少了这两个换行，它会先自言自语几百字，手机上要多跑十几秒。
     *
     * 用 listOf + joinToString 而不是 `"""` 原始字符串，是因为 trimIndent 会把
     * 首尾空行吃掉，末尾那个换行就丢了，而且丢得不明显。
     */
    val DEFAULT_PROMPT: String = listOf(
        "<|im_start|>system",
        "你是待办提取器。读一条微信消息，判断里面有没有要求「我」去做的事。",
        "",
        "只有消息在要求我做某件具体的事时才抽：交东西、回消息、到场、办某个手续。",
        "这几种一律不抽：",
        "讲个情况（明天降温、周五团建、我明天出差）",
        "问问题（你们几点到、明天开会吗）",
        "评价和应答（这个不错、好的收到、哈哈哈哈）",
        "说别人要做的事（让他去交、小王会处理）",
        "广告、验证码、转账通知",
        "",
        "text 写成能直接执行的短句，二十字以内，保留对象和动作，" +
            "去掉「记得」「帮我」「麻烦」这类口头词。" +
            "due 只在原文有明确时间点或截止时间时才填，格式 yyyy-MM-dd HH:mm，其余写 null。",
        "",
        "示例一，有时间：当前时间 2026-10-27 09:00，消息「张三：明天下午三点前把材料发我」",
        "输出 {\"todo\":true,\"text\":\"三点前发材料\",\"due\":\"2026-10-28 15:00\"}",
        "",
        "示例二，没时间：消息「李四：这周得把那个报销的事办了」",
        "输出 {\"todo\":true,\"text\":\"办报销\",\"due\":null}",
        "",
        "示例三，带「记得」但只是关心：消息「妈：明天降温了记得穿厚点」",
        "输出 {\"todo\":false,\"text\":\"\",\"due\":null}",
        "",
        "示例四，讲自己在干嘛：消息「王五：我明天去上海出差」",
        "输出 {\"todo\":false,\"text\":\"\",\"due\":null}",
        "",
        "示例五，问句：消息「赵六：你们明天几点到」",
        "输出 {\"todo\":false,\"text\":\"\",\"due\":null}",
        "",
        "示例六，群里的通知：消息「公告：周五下午团建，地点在西湖」",
        "输出 {\"todo\":false,\"text\":\"\",\"due\":null}",
        "<|im_end|>",
        "<|im_start|>user",
        "当前时间 {{time}}",
        "{{sender}}{{body}}",
        "<|im_end|>",
        "<|im_start|>assistant",
        "</think>",
        "",
        // 生成起点后再留一个空行：`</think>\n\n` 是跳过思考链的标准写法
        "",
    ).joinToString("\n")

    /**
     * 判断一条消息里有没有待办。阻塞调用。
     *
     * @return null 表示「没有待办」「不值得问」或「模型不可用」。
     */
    fun extract(
        context: Context,
        body: String,
        sender: String,
        msgTs: Long,
        now: Long = System.currentTimeMillis(),
    ): Hit? {
        if (!Llm.ready()) return null
        val text = body.trim()
        if (!worthAsking(text)) return null

        val raw = try {
            Llm.generate(render(template(context), text, sender, now), GRAMMAR, MAX_TOKENS)
        } catch (t: Throwable) {
            Log.e(TAG, "推理失败", t)
            return null
        } ?: return null

        if (raw.isBlank()) return null
        return parse(raw)
    }

    // ---------- 模板 ----------

    /**
     * 一次试跑的结果，给设置页的「试一下」用。
     *
     * @param prompt 渲染后的完整提示词，用户能看到占位符被换成了什么
     * @param raw    模型原样吐出来的字符串
     * @param hit    按当前逻辑解析的结果，null 表示判定为「不是待办」
     */
    data class Trial(val prompt: String, val raw: String, val hit: Hit?)

    /** 当前生效的模板。用户改过就用改过的，没改过用默认。 */
    fun template(context: Context): String =
        Prefs.promptTemplate(context) ?: DEFAULT_PROMPT

    /** 是否被用户改过。界面上用来显示「已修改」。 */
    fun isCustomized(context: Context): Boolean = Prefs.promptTemplate(context) != null

    /**
     * 拿指定模板和当前模型真跑一条，看效果。
     *
     * 没有这个功能的话，用户改完提示词只能等下一次整理 40 条才知道好不好，
     * 试错成本太高。阻塞调用，必须在工作线程。
     *
     * 模板由调用方传入而不是从 Prefs 读——编辑页要能用**还没保存**的内容试跑，
     * 不然每次改一点就得先存一次。
     */
    fun trial(templateText: String, body: String, sender: String, now: Long): Trial {
        val prompt = render(templateText, body, sender, now)
        if (!Llm.ready()) return Trial(prompt, "", null)

        // 注意不能写成 `try { ... } ?: Trial(...)`：catch 分支是 return（类型 Nothing），
        // 整个 try 表达式的类型会退化成 Any，后面 raw.isBlank() 就编译不过了
        val raw = try {
            Llm.generate(prompt, GRAMMAR, MAX_TOKENS)
        } catch (t: Throwable) {
            Log.e(TAG, "试跑失败", t)
            return Trial(prompt, "（推理失败：" + (t.message ?: "未知") + "）", null)
        }
        if (raw == null) return Trial(prompt, "（模型没有输出）", null)

        return Trial(prompt, raw, if (raw.isBlank()) null else parse(raw))
    }

    /**
     * 把模板里的占位符换成实际内容。
     *
     * 三个占位符缺一不可：`{{body}}` 是消息正文，`{{sender}}` 会自带冒号
     * （空发信人替换成空串，不会留下孤零零一个冒号），`{{time}}` 是当前时间。
     *
     * 替换顺序是先 time、再 sender、最后 body。这样即使消息正文里恰好写了
     * `{{time}}` 这样的字样，也会因为那一步已经执行过而不会被二次替换。
     */
    fun render(template: String, body: String, sender: String, now: Long): String {
        val text = body.trim()
        val clipped = if (text.length > FEED_CLIP) text.substring(0, FEED_CLIP) + "…" else text
        return template
            .replace("{{time}}", stamp(now))
            .replace("{{sender}}", if (sender.isNotBlank()) sender.trim() + "：" else "")
            .replace("{{body}}", clipped)
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

    // ---------- 结果解析 ----------

    /**
     * 抽不出合法内容一律当成「没有待办」，不抛给调用方。
     *
     * `todo` 字段是硬闸门：模型说不算，就算 text 里写了东西也不收。
     * 小模型偶尔会「嘴上说不算、手上还是写了」，这种矛盾输出按不算处理。
     */
    private fun parse(raw: String): Hit? {
        val o = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "输出不是合法对象: " + raw.take(80))
            return null
        }

        if (!o.optBoolean("todo", false)) return null

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
