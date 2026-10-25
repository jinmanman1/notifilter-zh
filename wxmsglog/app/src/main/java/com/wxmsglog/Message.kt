package com.wxmsglog

/** 一条被记录下来的通知消息。pkg 是来源应用的包名。 */
data class Message(
    val id: Long,
    val ts: Long,
    val sender: String,
    val body: String,
    val pkg: String,
)
