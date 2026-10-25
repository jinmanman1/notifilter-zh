package com.wxmsglog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 定时任务的入口。
 *
 * 广播里什么都不算，只负责把活交给前台服务——onReceive 只有几秒钟，
 * 而跑一次模型要几十秒，在这里直接干会被系统判超时。
 *
 * 顺带把下一次排上：Service 是异步的，排程不依赖它跑完。
 * 万一 Service 因为权限被拒没起来，闹钟也已经排好了，下次还能再试。
 */
class AiAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AiSchedule.ACTION_SCAN -> {
                AiService.start(context, AiService.ACTION_SCAN)
                AiSchedule.afterScan(context)
            }

            AiSchedule.ACTION_DAY -> {
                AiService.start(context, AiService.ACTION_REPORT_DAY)
                AiSchedule.afterReport(context, AiSchedule.ACTION_DAY)
            }

            AiSchedule.ACTION_WEEK -> {
                AiService.start(context, AiService.ACTION_REPORT_WEEK)
                AiSchedule.afterReport(context, AiSchedule.ACTION_WEEK)
            }
        }
    }
}
