package com.wxmsglog

/** 一条被记录下来的通知消息。 */
data class Message(
    val id: Long,
    val ts: Long,
    val sender: String,
    val body: String,
)
