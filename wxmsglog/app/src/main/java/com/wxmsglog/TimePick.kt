package com.wxmsglog

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import java.util.Calendar

/** 先选日期再选时刻。待办页和候选页共用这一份，免得两边行为不一致。 */
object TimePick {

    fun dateTime(activity: Activity, current: Long, onPicked: (Long) -> Unit) {
        val base = Calendar.getInstance()
        if (current > 0L) base.timeInMillis = current

        DatePickerDialog(
            activity,
            { _, year, month, day ->
                val time = Calendar.getInstance()
                if (current > 0L) time.timeInMillis = current
                TimePickerDialog(
                    activity,
                    { _, hour, minute ->
                        val out = Calendar.getInstance()
                        out.set(year, month, day, hour, minute, 0)
                        out.set(Calendar.MILLISECOND, 0)
                        onPicked(out.timeInMillis)
                    },
                    time.get(Calendar.HOUR_OF_DAY),
                    time.get(Calendar.MINUTE),
                    true,
                ).show()
            },
            base.get(Calendar.YEAR),
            base.get(Calendar.MONTH),
            base.get(Calendar.DAY_OF_MONTH),
        ).show()
    }
}
