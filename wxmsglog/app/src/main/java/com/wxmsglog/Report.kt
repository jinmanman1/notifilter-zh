package com.wxmsglog

/**
 * 一份日报或周报。
 *
 * body 是纯文本，不存 Markdown —— 生成它的模型随时可能换，存结构化内容
 * 反而要跟着适配格式。正文里用换行和「·」这类简单符号就够看了。
 */
data class Report(
    val id: Long,
    /** "day" 或 "week" */
    val kind: String,
    val from: Long,
    val to: Long,
    val body: String,
    val created: Long,
) {
    val isWeek: Boolean get() = kind == "week"
}
