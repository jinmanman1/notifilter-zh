package com.wxmsglog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** 待办提醒的通知。独立渠道，用户可以单独关掉它而不影响应用本身。 */
object TodoNotify {

    private const val CHANNEL_ID = "todo_remind"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_todo),
            NotificationManager.IMPORTANCE_HIGH,
        )
        ch.description = context.getString(R.string.channel_todo_desc)
        ch.enableVibration(true)
        runCatching { nm.createNotificationChannel(ch) }
    }

    fun show(context: Context, item: Todo) {
        ensureChannel(context)
        // Android 13 起通知要用户授权，没授权直接发会被静默丢掉，先自己判一下
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return

        val open = Intent(context, TodoActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            100,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_todo)
            .setContentTitle(context.getString(R.string.todo_notify_title))
            .setContentText(item.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching { nm.notify(item.id.toInt(), n) }
    }
}
