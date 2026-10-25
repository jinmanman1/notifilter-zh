package com.wxmsglog

/** 一条待办。due = 0 表示没设截止时间；src = 0 表示不是从某条消息建的。 */
data class Todo(
    val id: Long,
    val text: String,
    val due: Long,
    val done: Boolean,
    val src: Long,
    val created: Long,
)
